package com.tesla.dashboard.plugin

/**
 * 仪表盘插件接口 (v0.5.2 插件系统)
 *
 * 插件通过 Hilt Multibinding 注册到 [PluginManager]:
 * - 在 [di.PluginModule] 中用 `@Provides @IntoSet fun bind(plugin: XxxPlugin): DashboardPlugin` 注册
 * - 应用启动时 [PluginManager.init] 对每个"已启用"的插件调用 [onRegister]
 * - 用户可在插件中心停用/启用插件, 停用后调用 [onUnregister]
 *
 * 内置插件随 APK 分发; 外部插件库 (GitHub `tesla-dashboard-plugins` 仓库的
 * `plugin-catalog.json`) 描述可选插件, 供后续版本动态加载。
 */
interface DashboardPlugin {

    /** 全局唯一插件 ID (与插件库 catalog 中的 id 对应) */
    val id: String

    /** 插件名称资源 ID */
    val nameRes: Int

    /** 插件描述资源 ID */
    val descriptionRes: Int

    /** 插件版本号 */
    val version: String

    /** 插件分类 */
    val category: PluginCategory

    /** 是否实验性功能 (UI 显示"实验"标记, 需真车验证) */
    val isExperimental: Boolean
        get() = false

    /**
     * 插件注册回调 — 应用启动且插件启用时调用
     *
     * @param context 插件上下文 (核心依赖)
     */
    suspend fun onRegister(context: PluginContext)

    /**
     * 插件注销回调 — 用户停用插件时调用, 用于释放资源
     *
     * @param context 插件上下文
     */
    suspend fun onUnregister(context: PluginContext) {}
}
