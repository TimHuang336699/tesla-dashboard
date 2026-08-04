package com.tesla.dashboard.util

/**
 * 单位系统枚举
 *
 * @property code DataStore 持久化代码
 */
enum class UnitSystem(val code: String) {
    /** 公制: km/h, km, °C */
    METRIC("metric"),

    /** 英制: mph, mi, °F */
    IMPERIAL("imperial");

    companion object {
        /**
         * 从持久化代码解析单位系统
         *
         * @param code 单位系统代码, 未知时回退公制
         */
        fun fromCode(code: String?): UnitSystem =
            entries.firstOrNull { it.code == code } ?: METRIC
    }
}
