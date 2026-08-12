package com.tesla.dashboard.plugin

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tesla.dashboard.data.local.SettingsRepository
import com.tesla.dashboard.data.local.VehicleRepository
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.plugin.security.BleCommandProxy
import com.tesla.dashboard.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 插件在 UI 层展示的状态 (v0.5.2)
 *
 * @param plugin 插件定义
 * @param enabled 是否启用
 */
data class PluginUiState(
    val plugin: DashboardPlugin,
    val enabled: Boolean,
)

/**
 * 插件管理器 (v0.5.2 插件系统)
 *
 * 职责:
 * 1. 收集 Hilt Multibinding 注册的所有 [DashboardPlugin]
 * 2. 持久化各插件的启用状态 (DataStore `tesla_plugins`)
 * 3. 应用启动时注册已启用插件 ([init] 由 [com.tesla.dashboard.app.DashboardApplication] 调用)
 * 4. 动态启用/停用插件 (启用→[DashboardPlugin.onRegister], 停用→[DashboardPlugin.onUnregister])
 * 5. 暴露 [pluginStates] 状态流供插件中心 UI 展示
 *
 * 懒加载: 插件仅在 [init] 时按启用状态注册, 停用插件不执行任何回调,
 * 保证禁用插件零运行时开销 (性能与耗电优化的一部分)。
 */
@Singleton
class PluginManager @Inject constructor(
    /** Hilt Multibinding: 所有内置插件 */
    private val plugins: Set<@JvmSuppressWildcards DashboardPlugin>,
    @ApplicationContext private val context: Context,
    private val vehicleRepository: VehicleRepository,
    private val settingsRepository: SettingsRepository,
    private val bleProvider: TeslaBleProvider,
    /** BLE 指令代理 (v0.6.0 安全核心) */
    private val commandProxy: BleCommandProxy,
    /** 插件事件总线 (v0.6.0) */
    private val eventBus: PluginEventBus,
) {
    private val TAG = "PluginManager"

    private val Context.pluginsDataStore by preferencesDataStore(name = "tesla_plugins")

    /** 已启用插件 ID 集合 */
    private val KEY_ENABLED = stringSetPreferencesKey("enabled_plugins")

    /** 应用级作用域 (单例生命周期) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 插件注册是否已完成 */
    @Volatile
    private var initialized = false

    /** 插件 UI 状态流 */
    private val _pluginStates = MutableStateFlow<List<PluginUiState>>(emptyList())
    val pluginStates: StateFlow<List<PluginUiState>> = _pluginStates.asStateFlow()

    /** 已启用插件 ID 缓存 */
    private val enabledIds: MutableSet<String> = mutableSetOf()

    /**
     * 应用启动时调用 — 注册所有已启用的插件
     */
    fun init() {
        if (initialized) return
        initialized = true
        scope.launch {
            val stored = context.pluginsDataStore.data.firstOrNull()?.get(KEY_ENABLED)
            // 默认全部启用 (首次安装无记录时)
            val defaultEnabled = plugins.map { it.id }
            enabledIds.addAll(stored ?: defaultEnabled)
            refreshState()

            plugins.forEach { plugin ->
                if (enabledIds.contains(plugin.id)) {
                    register(plugin)
                }
            }
            AppLog.d(TAG, "Plugin init complete: ${plugins.size} plugins, ${enabledIds.size} enabled")
        }
    }

    /**
     * 启用/停用插件
     *
     * 启用时若 [DashboardPlugin.onRegister] 失败, 自动回滚启用状态并撤销 DataStore 写入,
     * 保证 UI 开关与实际注册状态一致。
     *
     * @param id 插件 ID
     * @param enabled 是否启用
     */
    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch {
            val plugin = plugins.find { it.id == id } ?: return@launch
            if (enabled) {
                enabledIds.add(id)
                val registered = register(plugin)
                if (!registered) {
                    // 注册失败: 回滚启用状态, 通知 UI 刷新开关
                    enabledIds.remove(id)
                    refreshState()
                    AppLog.w(TAG, "Plugin '$id' enable rolled back: onRegister failed")
                    return@launch
                }
            } else {
                enabledIds.remove(id)
                plugin.onUnregister(buildContext())
            }
            context.pluginsDataStore.edit { prefs ->
                prefs[KEY_ENABLED] = enabledIds.toSet()
            }
            refreshState()
            AppLog.d(TAG, "Plugin '$id' ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /**
     * 查询插件是否启用
     *
     * @param id 插件 ID
     * @return 是否启用 (未知插件返回 false)
     */
    fun isEnabled(id: String): Boolean = enabledIds.contains(id)

    /**
     * 按 ID 获取插件实例
     *
     * @param id 插件 ID
     * @return 插件实例, 不存在时返回 null
     */
    fun getPlugin(id: String): DashboardPlugin? = plugins.find { it.id == id }

    /**
     * 重置插件系统 (v0.5.2)
     *
     * "重置所有设置"时调用: 停用全部插件回调、清空 DataStore,
     * 恢复默认 (全部启用) 并重新注册。
     */
    fun resetAll() {
        scope.launch {
            plugins.forEach { plugin ->
                if (enabledIds.contains(plugin.id)) {
                    runCatching { plugin.onUnregister(buildContext()) }
                }
            }
            enabledIds.clear()
            enabledIds.addAll(plugins.map { it.id })
            context.pluginsDataStore.edit { prefs ->
                prefs[KEY_ENABLED] = enabledIds.toSet()
            }
            refreshState()
            AppLog.d(TAG, "Plugin system reset: ${plugins.size} plugins re-enabled")
        }
    }

    private suspend fun register(plugin: DashboardPlugin): Boolean {
        return runCatching {
            plugin.onRegister(buildContext())
            AppLog.d(TAG, "Plugin registered: ${plugin.id} v${plugin.version}")
            true
        }.getOrElse { e ->
            AppLog.e(TAG, "Plugin register FAILED: ${plugin.id}", e)
            false
        }
    }

    /**
     * 构建插件上下文 (v0.5.2, v0.6.0 安全增强)
     *
     * 供插件中心 / 命令页面获取 [PluginContext] 以调用插件能力。
     * v0.6.0: 注入 [BleCommandProxy] (指令白名单+确认+调度) 与
     * [PluginEventBus] (插件间通信)。
     */
    fun buildContext(): PluginContext = PluginContext(
        vehicleRepository = vehicleRepository,
        settingsRepository = settingsRepository,
        bleProvider = bleProvider,
        commandProxy = commandProxy,
        eventBus = eventBus,
    )

    private fun refreshState() {
        _pluginStates.value = plugins
            .sortedWith(compareBy({ it.category.ordinal }, { it.id }))
            .map { PluginUiState(it, enabledIds.contains(it.id)) }
    }
}
