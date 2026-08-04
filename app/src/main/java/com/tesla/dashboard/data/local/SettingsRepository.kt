package com.tesla.dashboard.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 顶层 DataStore 委托属性
 *
 * 使用 [preferencesDataStore] 扩展函数创建全局唯一的 DataStore<Preferences> 实例,
 * 名称 "tesla_settings" 对应磁盘上的 Preferences 文件。
 * 委托属性保证整个应用生命周期内只创建一个 DataStore 实例。
 */
private val Context.settingsDataStore by preferencesDataStore(name = "tesla_settings")

/**
 * 应用设置仓库 — 基于 DataStore Preferences
 *
 * 负责持久化用户在设置页面配置的各项参数,包括:
 * - [TESLA_VIN] Tesla 车辆识别号(VIN),用于 BLE 扫描和配对
 * - [THEME_MODE] 主题模式(深色 / 浅色 / 跟随系统),默认 "system"
 * - [BATTERY_MODEL] 车型代码(用于查询电池容量,如 "model_3_long_range")
 *
 * 注意: BLE 密钥对和配对信息由 [com.tesla.dashboard.data.source.ble.TeslaKeyManager] 单独管理,
 * 不在此仓库中存储。
 *
 * ## 读写方式
 * - 读取: 每个设置项暴露一个 [Flow],数据变化时自动发射新值
 * - 写入: 提供对应的 suspend 方法,在协程中安全写入
 *
 * ## 依赖注入
 * 使用 Hilt @Singleton + @Inject constructor 自动注入,
 * 通过 @ApplicationContext 获取应用级 Context 以访问 DataStore。
 *
 * @param context 应用级 Context(由 Hilt 通过 @ApplicationContext 提供)
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ===== Preferences Keys =====

    /** Tesla 车辆识别号(VIN),17 位字母数字 */
    private val TESLA_VIN = stringPreferencesKey("tesla_vin")

    /** 主题模式: "dark"(深色) / "light"(浅色) / "system"(跟随系统) */
    private val THEME_MODE = stringPreferencesKey("theme_mode")

    /** 车型代码,用于查询电池容量(如 "model_3_long_range") */
    private val BATTERY_MODEL = stringPreferencesKey("battery_model")

    /** 仪表背景: "default"(默认氛围) / "stealth"(黑武士) / "ocean"(深蓝) / "nebula"(深紫) / "crimson"(深红) / "wine"(酒红) */
    private val DASH_BACKGROUND = stringPreferencesKey("dash_background")

    // ===== VIN =====

    /**
     * 观察 VIN 设置流
     *
     * @return VIN 字符串 Flow,未设置时发射空字符串
     */
    val vinFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[TESLA_VIN] ?: ""
    }

    /**
     * 保存 Tesla VIN
     *
     * @param vin 17 位车辆识别号
     */
    suspend fun saveVin(vin: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[TESLA_VIN] = vin.trim()
        }
    }

    // ===== Theme Mode =====

    /**
     * 观察主题模式设置流
     *
     * @return 主题模式 Flow("dark"/"light"/"system"),未设置时发射默认值 "system"
     */
    val themeModeFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: DEFAULT_THEME_MODE
    }

    /**
     * 保存主题模式
     *
     * @param themeMode 主题模式:"dark"(深色) / "light"(浅色) / "system"(跟随系统)
     */
    suspend fun saveThemeMode(themeMode: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[THEME_MODE] = themeMode
        }
    }

    // ===== Battery Model =====

    /**
     * 观察车型代码设置流
     *
     * @return 车型代码 Flow(如 "model_3_long_range"),未设置时发射空字符串
     */
    val batteryModelFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[BATTERY_MODEL] ?: ""
    }

    /**
     * 保存车型代码
     *
     * @param batteryModel 车型代码,对应 [com.tesla.dashboard.data.model.BatteryConfig] 中的 key
     */
    suspend fun saveBatteryModel(batteryModel: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[BATTERY_MODEL] = batteryModel
        }
    }

    // ===== Dashboard Background =====

    /**
     * 观察仪表背景设置流
     *
     * @return 背景代码 Flow,未设置时发射默认值 "default"
     */
    val dashBackgroundFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[DASH_BACKGROUND] ?: DEFAULT_DASH_BACKGROUND
    }

    /**
     * 保存仪表背景
     *
     * @param background 背景代码 ("default"/"stealth"/"ocean"/"nebula"/"crimson"/"wine")
     */
    suspend fun saveDashBackground(background: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[DASH_BACKGROUND] = background
        }
    }

    companion object {
        /** 默认主题模式:跟随系统 */
        const val DEFAULT_THEME_MODE = "system"

        /** 默认仪表背景:默认氛围 */
        const val DEFAULT_DASH_BACKGROUND = "default"
    }
}
