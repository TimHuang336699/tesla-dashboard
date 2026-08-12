package com.tesla.dashboard.plugin

import com.tesla.dashboard.R

/**
 * 插件分类 (v0.5.2 插件系统)
 *
 * @param labelRes 分类显示名称资源 ID
 */
enum class PluginCategory(val labelRes: Int) {
    /** BLE 车辆控制命令拓展 (充电/空调/车门等) */
    BLE_COMMAND(R.string.plugin_category_ble_command),

    /** 工具类 (数据/日志/设置增强) */
    UTILITY(R.string.plugin_category_utility),
}
