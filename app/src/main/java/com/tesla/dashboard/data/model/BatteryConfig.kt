package com.tesla.dashboard.data.model

import com.tesla.dashboard.util.VinDecoder

/**
 * Tesla 车型电池容量配置
 *
 * 根据车辆 VIN 或车型代码获取对应电池容量(kWh)，
 * 用于精确计算瞬时/区间电耗。
 *
 * ## 电池容量数据(2024-2025 最新,含各代际改款)
 *
 * ### Model S(四代演进)
 * | 车型                       | 容量(kWh) | 电池化学 | 代际              |
 * |----------------------------|-----------|----------|-------------------|
 * | Model S 60                 | 60        | NCA      | Nosecone 初代     |
 * | Model S 75                 | 75        | NCA      | Nosecone/Facelift |
 * | Model S 85                 | 85        | NCA      | Nosecone 初代     |
 * | Model S 90                 | 90        | NCA      | Nosecone 初代     |
 * | Model S 100                | 100       | NCA      | Facelift/Raven    |
 * | Model S Plaid              | 100       | NCA      | Palladium 焕新版  |
 *
 * ### Model 3(两代演进)
 * | 车型                       | 容量(kWh) | 电池化学 | 代际              |
 * |----------------------------|-----------|----------|-------------------|
 * | Model 3 标准续航 (LFP)     | 60        | LFP      | 旧版/Highland     |
 * | Model 3 标准续航 (NMC)     | 75        | NMC      | 旧版              |
 * | Model 3 长续航             | 78        | NMC      | 旧版/Highland     |
 * | Model 3 Performance (旧版) | 78        | NMC      | 旧版              |
 * | Model 3 Performance (焕新) | 82        | NMC      | Highland 焕新版   |
 *
 * ### Model X(三代演进)
 * | 车型                       | 容量(kWh) | 电池化学 | 代际              |
 * |----------------------------|-----------|----------|-------------------|
 * | Model X 75                 | 75        | NCA      | Original 初代     |
 * | Model X 90                 | 90        | NCA      | Original 初代     |
 * | Model X 100                | 100       | NCA      | Original/Raven    |
 * | Model X Plaid              | 100       | NCA      | Palladium 焕新版  |
 *
 * ### Model Y(两代演进)
 * | 车型                       | 容量(kWh) | 电池化学 | 代际              |
 * |----------------------------|-----------|----------|-------------------|
 * | Model Y 标准续航 (LFP)     | 60        | LFP      | 旧版/Juniper      |
 * | Model Y 标准续航 (NMC)     | 75        | NMC      | 旧版              |
 * | Model Y 长续航 (旧版)      | 78        | NMC      | 旧版              |
 * | Model Y 长续航 (Juniper)   | 81        | NMC      | Juniper 焕新版    |
 * | Model Y Performance        | 78        | NMC      | 旧版/Juniper      |
 *
 * ### Cybertruck
 * | 车型                       | 容量(kWh) | 电池化学 | 代际              |
 * |----------------------------|-----------|----------|-------------------|
 * | Cybertruck 双电机          | 123       | NMC      | 初代              |
 * | Cybertruck 三电机          | 123       | NMC      | 初代              |
 *
 * @see VinDecoder
 */
object BatteryConfig {

    /**
     * Tesla 车型电池容量映射 (kWh)
     *
     * 数据来源: Tesla 官方规格参数 + NHTSA 备案信息
     */
    private val capacityByModel = mapOf(
        // Model S — Nosecone 初代 / Facelift 改款 / Raven 更新 / Palladium 焕新版
        "model_s_60" to 60f,            // NCA 60 kWh (Nosecone 初代)
        "model_s_75" to 75f,            // NCA 75 kWh (Nosecone/Facelift)
        "model_s_85" to 85f,            // NCA 85 kWh (Nosecone 初代)
        "model_s_90" to 90f,            // NCA 90 kWh (Nosecone 初代)
        "model_s_100" to 100f,          // NCA 100 kWh (Facelift/Raven/Palladium)
        "model_s_plaid" to 100f,        // NCA 100 kWh (Palladium Plaid 三电机)
        // Model 3 — 旧版 / Highland 焕新版
        "model_3_standard" to 60f,       // LFP 60 kWh (2021+)
        "model_3_standard_nmc" to 75f,   // NMC 75 kWh (旧款)
        "model_3_long_range" to 78f,     // NMC 78 kWh
        "model_3_performance" to 78f,    // NMC 78 kWh (旧版)
        "model_3_performance_highland" to 82f, // NMC 82 kWh (Highland 焕新版)
        // Model X — Original 初代 / Raven 更新 / Palladium 焕新版
        "model_x_75" to 75f,
        "model_x_90" to 90f,
        "model_x_100" to 100f,
        "model_x_plaid" to 100f,        // NCA 100 kWh (Palladium Plaid 三电机)
        // Model Y — 旧版 / Juniper 焕新版
        "model_y_standard" to 60f,       // LFP 60 kWh
        "model_y_standard_nmc" to 75f,   // NMC 75 kWh (旧款)
        "model_y_long_range" to 78f,     // NMC 78 kWh (旧版)
        "model_y_long_range_juniper" to 81f, // NMC 81 kWh (Juniper 焕新版)
        "model_y_performance" to 78f,    // NMC 78 kWh
        // Cybertruck — 初代
        "cybertruck_dual" to 123f,
        "cybertruck_tri" to 123f,
        "cybertruck_single" to 123f,
    )

    /** 默认电池容量(kWh)，无法识别车型时使用 */
    private const val DEFAULT_CAPACITY_KWH = 75f

    /**
     * 根据车型代码获取电池容量
     *
     * @param modelCode 车型代码(如 "model_3_long_range")
     * @return 电池容量 kWh，未匹配时返回默认值
     */
    fun getCapacityKWh(modelCode: String?): Float {
        if (modelCode.isNullOrBlank()) return DEFAULT_CAPACITY_KWH
        return capacityByModel[modelCode.lowercase()] ?: DEFAULT_CAPACITY_KWH
    }

    /**
     * 根据 VIN 推断车型代码(回退方法)
     *
     * 当 [VinDecoder.decode] 失败时使用的粗略推断。
     * 基于 VIN 位置 4(车型)和位置 8(电机配置)进行判断。
     *
     * VIN 位置说明:
     * - 位置 4(索引 3): S=Model S, 3=Model 3, X=Model X, Y=Model Y, C=Cybertruck
     * - 位置 8(索引 7): 电机配置代码(因车型而异)
     *
     * @param vin 17位车辆识别号
     * @return 推断的车型代码
     */
    fun inferModelFromVin(vin: String?): String? {
        if (vin.isNullOrBlank() || vin.length < 8) return null
        val normalized = vin.uppercase()
        val modelChar = normalized.getOrNull(3) ?: return null
        val motorChar = normalized.getOrNull(7) ?: return null

        return when (modelChar) {
            'S' -> when (motorChar) {
                '6' -> "model_s_plaid"           // Palladium Plaid 三电机
                '5' -> "model_s_100"             // Palladium Dual Motor
                '4' -> "model_s_100"             // Performance AWD (Facelift)
                '2' -> "model_s_100"             // Dual Motor AWD (Facelift)
                '1', '3' -> "model_s_85"         // Single Motor (Nosecone/Facelift)
                else -> "model_s_100"
            }

            '3' -> when (motorChar) {
                'T' -> "model_3_performance_highland"  // Highland Performance 82kWh
                'C' -> "model_3_performance"           // 旧版 Performance 78kWh
                'B', 'K' -> "model_3_long_range"       // Long Range AWD
                'A', 'J', 'R', 'S' -> "model_3_standard" // Standard Range RWD
                else -> "model_3_long_range"
            }

            'X' -> when (motorChar) {
                '6' -> "model_x_plaid"           // Palladium Plaid 三电机
                '5' -> "model_x_100"             // Palladium Dual Motor
                '4' -> "model_x_100"             // Performance AWD (Original)
                '2' -> "model_x_100"             // Dual Motor AWD (Original)
                else -> "model_x_100"
            }

            'Y' -> when (motorChar) {
                'F', 'L' -> "model_y_performance"     // Performance (F=旧版, L=Juniper)
                'E', 'K', 'R' -> "model_y_long_range" // Long Range AWD
                'D', 'J', 'S' -> "model_y_standard"   // Standard Range RWD (S=Juniper DUB600A)
                else -> "model_y_long_range"
            }

            'C' -> when (motorChar) {
                'E' -> "cybertruck_tri"
                'C' -> "cybertruck_single"
                else -> "cybertruck_dual"
            }

            else -> null
        }
    }

    /**
     * 根据 [VinDecoder.VinInfo] 获取电池容量
     *
     * 利用 [VinDecoder] 解码后的结构化信息(车型 + 电池版本 + 电池化学)精确匹配电池容量。
     *
     * 匹配逻辑:
     * - 先通过 [inferModelCodeFromVinInfo] 将 VinInfo 映射为内部车型代码
     * - 再通过 [getCapacityKWh] 查询对应容量
     * - 无法匹配时返回 [DEFAULT_CAPACITY_KWH]
     *
     * @param vinInfo VIN 解码结果，为 null 时返回默认容量
     * @return 电池容量 kWh
     *
     * @see VinDecoder
     * @see inferModelCodeFromVinInfo
     */
    fun getCapacityByVinInfo(vinInfo: VinDecoder.VinInfo?): Float {
        if (vinInfo == null) return DEFAULT_CAPACITY_KWH
        val modelCode = inferModelCodeFromVinInfo(vinInfo) ?: return DEFAULT_CAPACITY_KWH
        return getCapacityKWh(modelCode)
    }

    /**
     * 根据 VIN 字符串获取电池容量(便捷方法)
     *
     * 先调用 [VinDecoder.decode] 解码 VIN，再通过 [getCapacityByVinInfo] 获取容量。
     * 若 [VinDecoder.decode] 解码失败，则回退到 [inferModelFromVin] 粗略推断。
     *
     * @param vin 17 位车辆识别号，为空时返回默认容量
     * @return 电池容量 kWh
     *
     * @see VinDecoder.decode
     * @see getCapacityByVinInfo
     */
    fun getCapacityByVin(vin: String?): Float {
        if (vin.isNullOrBlank()) return DEFAULT_CAPACITY_KWH
        // 优先使用精确解码
        val vinInfo = VinDecoder.decode(vin)
        if (vinInfo != null) return getCapacityByVinInfo(vinInfo)
        // 解码失败时回退到粗略推断
        return getCapacityKWh(inferModelFromVin(vin))
    }

    /**
     * 将 [VinDecoder.VinInfo] 映射为内部车型代码
     *
     * 根据 VinInfo 中的车型名称、电池版本描述及电池化学类型，匹配 [capacityByModel] 中的 key。
     *
     * 匹配优先级:
     * 1. 性能版本(Plaid/Performance/P100D 等) — 优先判断避免被容量数值误匹配
     * 2. 长续航版本(Long Range)
     * 3. 标准版本(Standard Range) — 区分 LFP(60 kWh) 和 NMC(75 kWh)
     * 4. 仅容量数值(早期 Model S/X)
     *
     * @param vinInfo VIN 解码结果
     * @return 内部车型代码(如 "model_3_long_range")，无法匹配时返回 null
     */
    private fun inferModelCodeFromVinInfo(vinInfo: VinDecoder.VinInfo): String? {
        val battery = vinInfo.batteryType
        val chemistry = vinInfo.batteryChemistry
        val isLfp = chemistry.contains("LFP")
        val generation = vinInfo.generation

        return when (vinInfo.model) {
            "Model S" -> when {
                battery.contains("Plaid") || generation.contains("Palladium") && battery.contains("Plaid") -> "model_s_plaid"
                battery.contains("P100D") || battery.contains("Performance") -> "model_s_100"
                battery.contains("P85D") -> "model_s_85"
                battery.contains("P90D") -> "model_s_90"
                battery.contains("60") -> "model_s_60"
                battery.contains("75") -> "model_s_75"
                battery.contains("85") -> "model_s_85"
                battery.contains("90") -> "model_s_90"
                battery.contains("100") -> "model_s_100"
                // Palladium 焕新版默认 100 kWh
                generation.contains("Palladium") -> "model_s_100"
                else -> "model_s_100"
            }

            "Model 3" -> when {
                battery.contains("Performance") -> {
                    // Highland 焕新版 Performance 使用 82 kWh, 旧版使用 78 kWh
                    if (generation.contains("Highland")) "model_3_performance_highland"
                    else "model_3_performance"
                }
                battery.contains("Long Range") -> "model_3_long_range"
                battery.contains("Standard") -> if (isLfp) "model_3_standard" else "model_3_standard_nmc"
                else -> "model_3_long_range"
            }

            "Model X" -> when {
                battery.contains("Plaid") || generation.contains("Palladium") && battery.contains("Plaid") -> "model_x_plaid"
                battery.contains("P100D") || battery.contains("Performance") -> "model_x_100"
                battery.contains("P85D") -> "model_x_75"
                battery.contains("P90D") -> "model_x_90"
                battery.contains("75") -> "model_x_75"
                battery.contains("90") -> "model_x_90"
                battery.contains("100") -> "model_x_100"
                // Palladium 焕新版默认 100 kWh
                generation.contains("Palladium") -> "model_x_100"
                else -> "model_x_100"
            }

            "Model Y" -> when {
                battery.contains("Performance") -> "model_y_performance"
                battery.contains("Long Range") -> {
                    // Juniper 焕新版 Long Range 使用 81 kWh, 旧版使用 78 kWh
                    if (generation.contains("Juniper")) "model_y_long_range_juniper"
                    else "model_y_long_range"
                }
                battery.contains("Standard") -> if (isLfp) "model_y_standard" else "model_y_standard_nmc"
                else -> "model_y_long_range"
            }

            "Cybertruck" -> when {
                battery.contains("Cyberbeast") || battery.contains("三电机") -> "cybertruck_tri"
                battery.contains("Single") -> "cybertruck_single"
                else -> "cybertruck_dual"
            }

            else -> null
        }
    }
}
