package com.tesla.dashboard.util

/**
 * Tesla VIN(车辆识别号)解码器
 *
 * 解析 17 位 Tesla VIN，提取制造商、车型、电池配置、电池化学类型、
 * 车型年份、驱动类型、车身类型及工厂等信息。
 *
 * ## Tesla VIN 结构(NHTSA Part 565 标准)
 * ```
 * 位置:  1  2  3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 13 14 15 16 17
 * 分段: |--WMI--| |M| |B| |R| |C| |D| |校验| |年份| |工厂| |---序列号---|
 * ```
 * - **WMI**(1-3): 世界制造商识别码，标识国家与制造商类型
 * - **位置 4**: 车型代码(S=Model S, 3=Model 3, X=Model X, Y=Model Y, C=Cybertruck)
 * - **位置 5**: 车身类型(左舵/右舵, 轿车/SUV/掀背)
 * - **位置 6**: 安全约束系统(安全气囊/安全带配置)
 * - **位置 7**: 电池化学类型(E=NMC/NCA, F=LFP, H=NCA高容量, S=NCA标准, V=NCA超高容量)
 * - **位置 8**: 电机/驱动配置(单电机/双电机/三电机, 标准版/性能版)
 * - **校验位**(9): NHTSA 标准校验位
 * - **年份**(10): 车型年份代码
 * - **工厂**(11): 装配厂代码(F=Fremont, A=Austin, C=上海, B=柏林)
 * - **序列号**(12-17): 生产序列号
 *
 * ## 关键修正(相比旧版本)
 * - 旧版本错误地将**位置 5**(车身类型)用作电池代码
 * - 新版本正确使用**位置 7**(电池化学) + **位置 8**(电机配置)判断电池与驱动
 * - 新增位置 5(车身类型)、位置 7(电池化学)、位置 11(工厂)解码
 *
 * ## 使用示例
 * ```kotlin
 * // 5YJSA1E47HF000000 → Model S, 100kWh, AWD, 2017, Fremont
 * // LRW3E7EA9PE000000 → Model 3, Long Range 78kWh NMC, AWD, 2023, 上海
 * // 7G2CEHEDXRA000000 → Cybertruck, Dual Motor 123kWh, AWD, 2024, Austin
 * ```
 *
 * @see VinInfo
 */

/**
 * NFC 刷卡位置类型 — 用于配对流程 Step 3 根据车型代际选择不同的刷卡位置插图
 *
 * | 枚举值 | 适用车型代际 | 刷卡位置插图 |
 * |--------|--------------|--------------|
 * | [CENTER_CONSOLE] | Highland (Model 3 2024+), Juniper (Model Y 2025+), Palladium (S/X 2021+) | 中控台无线充电板上方 |
 * | [CUP_HOLDER] | 旧版 Model 3 (2017-2023), 旧版 Model Y (2020-2024), 旧版 S/X | 前排中央扶手杯架后方 |
 * | [UNKNOWN] | 无法识别 | 默认显示中控台 |
 */
enum class NfcLocationType {
    /** 中控台无线充电板 (焕新版 Model 3/Y/S/X) */
    CENTER_CONSOLE,

    /** 杯架后方 (老款 Model 3/Y) */
    CUP_HOLDER,

    /** 无法识别 — 降级为中控台 */
    UNKNOWN,
}

/**
 * 根据车型和代际判断 NFC 刷卡位置类型
 *
 * 判定规则:
 * - Model 3: generation 含 "Highland" → CENTER_CONSOLE，否则 CUP_HOLDER
 * - Model Y: generation 含 "Juniper" → CENTER_CONSOLE，否则 CUP_HOLDER
 * - Model S/X: generation 含 "Palladium" → CENTER_CONSOLE，否则 CUP_HOLDER
 * - Cybertruck/Semi/Roadster: 统一 CENTER_CONSOLE
 * - 其他: UNKNOWN
 *
 * @param info VIN 解码结果
 * @return NFC 位置类型
 */
fun nfcLocationType(info: VinDecoder.VinInfo?): NfcLocationType {
    if (info == null) return NfcLocationType.UNKNOWN
    return when {
        // Model 3: Highland 焕新版 (2024+) → 中控台
        info.model == "Model 3" && info.generation.contains("Highland") ->
            NfcLocationType.CENTER_CONSOLE

        // Model Y: Juniper 焕新版 (2025+) → 中控台
        info.model == "Model Y" && info.generation.contains("Juniper") ->
            NfcLocationType.CENTER_CONSOLE

        // Model S/X: Palladium 焕新版 (2021+) → 中控台
        (info.model == "Model S" || info.model == "Model X") &&
                info.generation.contains("Palladium") ->
            NfcLocationType.CENTER_CONSOLE

        // Cybertruck / Semi / Roadster 暂定中控台
        info.model in setOf("Cybertruck", "Semi", "Roadster") ->
            NfcLocationType.CENTER_CONSOLE

        // 旧版 Model 3/Y / 旧版 Model S/X → 杯架
        info.model in setOf("Model 3", "Model Y", "Model S", "Model X") ->
            NfcLocationType.CUP_HOLDER

        else -> NfcLocationType.UNKNOWN
    }
}

class VinDecoder private constructor() {

    /**
     * VIN 解码结果数据类
     *
     * @param manufacturer 制造商/地区信息(如 "美国 Tesla")
     * @param model 车型名称(如 "Model 3")
     * @param batteryType 电池/配置版本描述(如 "Long Range (78 kWh, NMC)")
     * @param batteryChemistry 电池化学类型(如 "NMC 三元锂", "LFP 磷酸铁锂")
     * @param modelYear 车型年份(如 2023)
     * @param driveType 驱动类型(如 "AWD", "RWD", "AWD Performance", "AWD Plaid (三电机)")
     * @param bodyType 车身类型(如 "左舵 5门 掀背/轿车", "左舵 5门 SUV 跨界")
     * @param plant 装配工厂(如 "Fremont, CA, 美国", "上海, 中国")
     * @param generation 车型代际(如 "Nosecone 初代 (2012-2016)", "Palladium 焕新版 (2021+)",
     *                   "Highland 焕新版 (2024+)", "Juniper 焕新版 (2025+)" 等)
     * @param rawVin 原始 17 位 VIN 字符串(已转为大写)
     */
    data class VinInfo(
        val manufacturer: String,
        val model: String,
        val batteryType: String,
        val batteryChemistry: String,
        val modelYear: Int,
        val driveType: String,
        val bodyType: String,
        val plant: String,
        val generation: String,
        val rawVin: String,
    )

    companion object {

        /** 标准 VIN 长度 */
        private const val VIN_LENGTH = 17

        // ===== VIN 各字段位置索引(0-based) =====

        /** WMI 起始位置(位置 1-3) */
        private const val WMI_START = 0

        /** WMI 长度 */
        private const val WMI_LENGTH = 3

        /** 车型代码位置(位置 4) */
        private const val MODEL_INDEX = 3

        /** 车身类型位置(位置 5) */
        private const val BODY_TYPE_INDEX = 4

        /** 电池化学类型位置(位置 7) */
        private const val BATTERY_CHEMISTRY_INDEX = 6

        /** 电机/驱动配置位置(位置 8) */
        private const val MOTOR_INDEX = 7

        /** 校验位位置(位置 9) */
        private const val CHECK_DIGIT_INDEX = 8

        /** 年份代码位置(位置 10) */
        private const val YEAR_INDEX = 9

        /** 工厂代码位置(位置 11) */
        private const val PLANT_INDEX = 10

        /**
         * Tesla WMI 代码 → 制造商/地区映射
         *
         * | WMI  | 地区       | 说明                          |
         * |------|------------|-------------------------------|
         * | 5YJ  | 美国       | 轿车(Model S / Model 3)       |
         * | 7SA  | 美国       | MPV(Model X / Model Y)        |
         * | 7G2  | 美国       | 商用车(Cybertruck / Semi)     |
         * | LRW  | 中国       | 上海超级工厂(Model 3 / Model Y)|
         * | XP7  | 德国       | 柏林超级工厂(Model Y)          |
         * | SFZ  | 英国       | 初代 Roadster(Lotus 代工)     |
         */
        private val WMI_MAP: Map<String, String> = mapOf(
            "5YJ" to "美国 Tesla",
            "7SA" to "美国 Tesla",
            "7G2" to "美国 Tesla",
            "LRW" to "中国 Tesla",
            "XP7" to "德国 Tesla",
            "SFZ" to "英国 Tesla",
        )

        /**
         * 位置 5: 车身类型映射
         *
         * | 代码 | 含义                               |
         * |------|------------------------------------|
         * | A    | 5门 左舵 掀背/轿车                 |
         * | B    | 5门 右舵 掀背/轿车                 |
         * | C    | 5门 左舵 SUV                       |
         * | D    | 5门 右舵 SUV                       |
         * | E    | 左舵 4门 轿车 / 3门 敞篷(Roadster) |
         * | F    | 右舵 4门 轿车                      |
         * | G    | 5门 左舵 SUV 跨界(Model Y)         |
         * | H    | 5门 右舵 SUV 跨界(Model Y)         |
         */
        private val BODY_TYPE_MAP: Map<Char, String> = mapOf(
            'A' to "左舵 5门 掀背/轿车",
            'B' to "右舵 5门 掀背/轿车",
            'C' to "左舵 5门 SUV",
            'D' to "右舵 5门 SUV",
            'E' to "左舵 4门 轿车",
            'F' to "右舵 4门 轿车",
            'G' to "左舵 5门 SUV 跨界",
            'H' to "右舵 5门 SUV 跨界",
        )

        /**
         * 位置 7: 电池化学类型映射
         *
         * | 代码 | 化学类型           | 说明                           |
         * |------|--------------------|--------------------------------|
         * | E    | NMC/NCA 三元锂     | 标准三元锂电池(大多数 Tesla)   |
         * | F    | LFP 磷酸铁锂       | 磷酸铁锂电池(标准续航版常见)   |
         * | H    | NCA 高容量         | Model S 85 kWh 历史电池        |
         * | S    | NCA 标准容量       | Model S 60 kWh 历史电池        |
         * | V    | NCA 超高容量       | Model S 90 kWh 历史电池        |
         *
         * 注意: LFP 电池可安全充至 100%，NMC/NCA 建议日常充至 80-90%。
         */
        private val BATTERY_CHEMISTRY_MAP: Map<Char, String> = mapOf(
            'E' to "NMC/NCA 三元锂",
            'F' to "LFP 磷酸铁锂",
            'H' to "NCA 高容量",
            'S' to "NCA 标准容量",
            'V' to "NCA 超高容量",
        )

        /**
         * 位置 11: 装配工厂映射
         *
         * | 代码 | 工厂                        |
         * |------|-----------------------------|
         * | F    | Fremont, CA, 美国           |
         * | A    | Austin, TX, 美国            |
         * | C    | 上海, 中国                  |
         * | B    | 柏林, 德国                  |
         * | P    | Palo Alto, CA, 美国(Roadster)|
         */
        private val PLANT_MAP: Map<Char, String> = mapOf(
            'F' to "Fremont, CA, 美国",
            'A' to "Austin, TX, 美国",
            'C' to "上海, 中国",
            'B' to "柏林, 德国",
            'P' to "Palo Alto, CA, 美国",
        )

        /**
         * VIN 年份代码映射(2010-2039 周期)
         *
         * 标准年份代码每 30 年循环一次，跳过字母 I、O、Q、U、Z。
         * Tesla 车辆均生产于 2008 年之后，故此处采用 2010 起始周期。
         */
        private val YEAR_CODES: Map<Char, Int> = mapOf(
            'A' to 2010, 'B' to 2011, 'C' to 2012, 'D' to 2013, 'E' to 2014,
            'F' to 2015, 'G' to 2016, 'H' to 2017, 'J' to 2018, 'K' to 2019,
            'L' to 2020, 'M' to 2021, 'N' to 2022, 'P' to 2023, 'R' to 2024,
            'S' to 2025, 'T' to 2026, 'V' to 2027, 'W' to 2028, 'X' to 2029,
            'Y' to 2030,
            '1' to 2031, '2' to 2032, '3' to 2033, '4' to 2034, '5' to 2035,
            '6' to 2036, '7' to 2037, '8' to 2038, '9' to 2039,
        )

        /** VIN 校验位计算用的位置权重(第 9 位权重为 0，不参与计算) */
        private val CHECK_DIGIT_WEIGHTS = intArrayOf(
            8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2
        )

        /** VIN 校验位字母-数值转换映射(I、O、Q 不合法) */
        private val CHAR_VALUES: Map<Char, Int> = mapOf(
            'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
            'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
            'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9,
            '0' to 0, '1' to 1, '2' to 2, '3' to 3, '4' to 4,
            '5' to 5, '6' to 6, '7' to 7, '8' to 8, '9' to 9,
        )

        // ============================================================
        //  公共 API
        // ============================================================

        /**
         * 解码 Tesla VIN
         *
         * 解析 17 位 VIN，返回包含完整车辆信息的 [VinInfo]。
         *
         * 解码流程:
         * 1. 校验 VIN 长度为 17 位
         * 2. 解析 WMI(位置 1-3) → 制造商/地区
         * 3. 解析位置 4 → 车型(Model S/3/X/Y/Cybertruck)
         * 4. 解析位置 5 → 车身类型(左舵/右舵, 轿车/SUV)
         * 5. 解析位置 7 → 电池化学类型(NMC/LFP/NCA)
         * 6. 解析位置 8 → 电机配置(单电机/双电机/三电机, 标准/性能)
         * 7. 解析位置 10 → 车型年份
         * 8. 解析位置 11 → 装配工厂
         * 9. 综合位置 5 + 位置 8 + 年份 → 判断代际(全车型改款记录)
         *    - Model S: Nosecone 初代 / Facelift 改款 / Raven 更新 / Palladium 焕新版
         *    - Model X: Original 初代 / Raven 更新 / Palladium 焕新版
         *    - Model 3: 旧版 / Highland 焕新版
         *    - Model Y: 旧版 / Juniper 焕新版
         *
         * @param vin 17 位车辆识别号(大小写不敏感)
         * @return 解码结果 [VinInfo]，无法识别时返回 null
         */
        fun decode(vin: String): VinInfo? {
            if (vin.length != VIN_LENGTH) return null

            val normalized = vin.uppercase()

            // 1. WMI(位置 1-3) → 制造商/地区
            val wmi = normalized.substring(WMI_START, WMI_START + WMI_LENGTH)
            val manufacturer = WMI_MAP[wmi] ?: return null

            // 2. 位置 4 → 车型
            val model = decodeModel(normalized[MODEL_INDEX]) ?: return null

            // 3. 位置 5 → 车身类型
            val bodyType = BODY_TYPE_MAP[normalized[BODY_TYPE_INDEX]] ?: "未知"

            // 4. 位置 7 → 电池化学类型
            val batteryChemistry = BATTERY_CHEMISTRY_MAP[normalized[BATTERY_CHEMISTRY_INDEX]]
                ?: "未知"

            // 5. 位置 8 → 电机/驱动配置 + 电池容量
            val motorChar = normalized[MOTOR_INDEX]
            val batteryChar = normalized[BATTERY_CHEMISTRY_INDEX]
            val motorConfig = decodeMotorConfig(
                model = model,
                motorChar = motorChar,
                batteryChar = batteryChar,
            ) ?: return null

            // 6. 位置 10 → 车型年份
            val modelYear = YEAR_CODES[normalized[YEAR_INDEX]] ?: return null

            // 7. 位置 11 → 装配工厂
            val plant = PLANT_MAP[normalized[PLANT_INDEX]] ?: "未知"

            // 8. 综合判断代际(Highland 焕新版 / 旧版)
            val generation = detectGeneration(
                model = model,
                bodyChar = normalized[BODY_TYPE_INDEX],
                motorChar = motorChar,
                modelYear = modelYear,
            )

            return VinInfo(
                manufacturer = manufacturer,
                model = model,
                batteryType = motorConfig.batteryType,
                batteryChemistry = batteryChemistry,
                modelYear = modelYear,
                driveType = motorConfig.driveType,
                bodyType = bodyType,
                plant = plant,
                generation = generation,
                rawVin = normalized,
            )
        }

        /**
         * 验证 VIN 校验位(第 9 位)
         *
         * 采用 NHTSA(美国国家公路交通安全管理局)标准算法:
         * 1. 将 VIN 各位字符转换为数值(字母 I/O/Q 不合法)
         * 2. 各位数值 × 对应位置权重后求和
         * 3. 总和 mod 11 得到校验值
         * 4. 校验值为 10 时，第 9 位应为 "X"；否则为对应数字
         *
         * @param vin 17 位车辆识别号
         * @return 校验位是否正确；VIN 格式或字符非法时返回 false
         */
        fun validateCheckDigit(vin: String): Boolean {
            if (vin.length != VIN_LENGTH) return false
            val normalized = vin.uppercase()

            var sum = 0
            for (i in normalized.indices) {
                val charValue = CHAR_VALUES[normalized[i]] ?: return false
                sum += charValue * CHECK_DIGIT_WEIGHTS[i]
            }

            val remainder = sum % 11
            val expectedCheck = if (remainder == 10) 'X' else remainder.digitToChar()
            return normalized[CHECK_DIGIT_INDEX] == expectedCheck
        }

        /**
         * 提取 VIN 序列号(位置 12-17)
         *
         * @param vin 17 位车辆识别号
         * @return 6 位序列号字符串，VIN 长度不足时返回 null
         */
        fun extractSerialNumber(vin: String): String? {
            if (vin.length != VIN_LENGTH) return null
            return vin.substring(11, VIN_LENGTH).uppercase()
        }

        // ============================================================
        //  位置 4: 车型解码
        // ============================================================

        /**
         * 解码位置 4 → 车型名称
         *
         * | 代码 | 车型       |
         * |------|------------|
         * | S    | Model S    |
         * | 3    | Model 3    |
         * | X    | Model X    |
         * | Y    | Model Y    |
         * | C    | Cybertruck |
         * | R    | Roadster   |
         * | T    | Semi       |
         */
        private fun decodeModel(char: Char): String? = when (char) {
            'S' -> "Model S"
            '3' -> "Model 3"
            'X' -> "Model X"
            'Y' -> "Model Y"
            'C' -> "Cybertruck"
            'R' -> "Roadster"
            'T' -> "Semi"
            else -> null
        }

        // ============================================================
        //  位置 8: 电机/驱动配置解码
        // ============================================================

        /**
         * 根据车型分发到对应的电机配置解码方法
         *
         * @param model 车型名称(如 "Model 3")
         * @param motorChar 位置 8 的字符(电机配置代码)
         * @param batteryChar 位置 7 的字符(电池化学代码)
         * @return 电机配置解码结果，无法识别时返回 null
         */
        private fun decodeMotorConfig(
            model: String,
            motorChar: Char,
            batteryChar: Char,
        ): MotorConfig? = when (model) {
            "Model S", "Model X" -> decodeModelSXMotor(motorChar, batteryChar)
            "Model 3" -> decodeModel3Motor(motorChar, batteryChar)
            "Model Y" -> decodeModelYMotor(motorChar, batteryChar)
            "Cybertruck" -> decodeCybertruckMotor(motorChar)
            "Roadster" -> MotorConfig("Roadster 电池", "RWD")
            "Semi" -> MotorConfig("商用电池", "AWD")
            else -> null
        }

        /**
         * 解码 Model S / Model X 电机配置(位置 8)
         *
         * Model S 和 Model X 共用同一套电机代码:
         * - '1' = 单电机 标准版 (RWD)
         * - '2' = 双电机 标准版 (AWD)
         * - '3' = 单电机 性能版 (RWD Performance)
         * - '4' = 双电机 性能版 (AWD Performance, P85D/P90D/P100D)
         * - '5' = 双电机 长续航版 (2021+ 改款, AWD Long Range)
         * - '6' = 三电机 Plaid (AWD Plaid)
         *
         * 电池容量由位置 7(电池化学)辅助判断:
         * - 化学代码 'S' → 60 kWh(早期 Model S)
         * - 化学代码 'H' → 85 kWh(Model S 85)
         * - 化学代码 'V' → 90 kWh(Model S 90)
         * - 化学代码 'E' → 100 kWh(新款) / 75 kWh(单电机旧款)
         *
         * @param motorChar 位置 8 的字符
         * @param batteryChar 位置 7 的字符(电池化学代码)
         * @return Model S/X 电机配置信息
         */
        private fun decodeModelSXMotor(
            motorChar: Char,
            batteryChar: Char,
        ): MotorConfig {
            return when (motorChar) {
                '1' -> { // 单电机 标准版 (RWD)
                    val battery = when (batteryChar) {
                        'S' -> "60 kWh"
                        'H' -> "85 kWh"
                        'V' -> "90 kWh"
                        'E' -> "75 kWh"
                        else -> "标准版"
                    }
                    MotorConfig(battery, "RWD")
                }

                '2' -> { // 双电机 标准版 (AWD)
                    val battery = when (batteryChar) {
                        'H' -> "85 kWh"
                        'V' -> "90 kWh"
                        'E' -> "100 kWh"
                        else -> "100 kWh"
                    }
                    MotorConfig(battery, "AWD")
                }

                '3' -> { // 单电机 性能版 (RWD Performance)
                    val battery = when (batteryChar) {
                        'H' -> "P85 (85 kWh)"
                        'V' -> "P90 (90 kWh)"
                        else -> "Performance"
                    }
                    MotorConfig(battery, "RWD Performance")
                }

                '4' -> { // 双电机 性能版 (AWD Performance: P85D/P90D/P100D)
                    val battery = when (batteryChar) {
                        'H' -> "P85D (85 kWh)"
                        'V' -> "P90D (90 kWh)"
                        'E' -> "P100D (100 kWh)"
                        else -> "Performance (100 kWh)"
                    }
                    MotorConfig(battery, "AWD Performance")
                }

                '5' -> // 双电机 长续航版 (2021+ 改款)
                    MotorConfig("Long Range (100 kWh)", "AWD Long Range")

                '6' -> // 三电机 Plaid
                    MotorConfig("Plaid (100 kWh)", "AWD Plaid (三电机)")

                else -> MotorConfig("未知", "未知")
            }
        }

        /**
         * 解码 Model 3 电机配置(位置 8)
         *
         * ## 旧版 Model 3(2017-2023)
         * | 代码 | 配置                    | 电池                |
         * |------|-------------------------|---------------------|
         * | A    | 单电机 标准版 (RWD)     | LFP 60 kWh 或 NMC 75 kWh |
         * | B    | 双电机 长续航版 (AWD)   | NMC 78 kWh          |
         * | C    | 双电机 性能版 (AWD Perf)| NMC 78 kWh          |
         *
         * ## Highland 焕新版 Model 3(2024+)
         * | 代码 | 配置                    | 电池                | 说明              |
         * |------|-------------------------|---------------------|-------------------|
         * | J    | 单电机 标准版 (RWD)     | LFP 60 kWh          | Hairpin 绕组      |
         * | K    | 双电机 标准版 (AWD)     | NMC 78 kWh          | Hairpin 绕组      |
         * | R    | 单电机 标准版 (RWD)     | LFP 60 kWh          | 焕新版变体        |
         * | S    | 单电机 标准版 (RWD)     | LFP 60 kWh          | 焕新版变体        |
         * | T    | 双电机 性能版 (AWD Perf)| NMC 82 kWh          | 焕新版 Performance|
         *
         * Highland 焕新版 Performance 电池容量为 82 kWh(旧版为 78 kWh)。
         *
         * @param motorChar 位置 8 的字符
         * @param batteryChar 位置 7 的字符(电池化学代码)
         * @return Model 3 电机配置信息
         */
        private fun decodeModel3Motor(
            motorChar: Char,
            batteryChar: Char,
        ): MotorConfig {
            return when (motorChar) {
                // ===== 旧版电机代码 =====
                'A' -> { // 单电机 标准版 (RWD) — 旧版
                    val battery = when (batteryChar) {
                        'F' -> "Standard Range (60 kWh, LFP)"
                        'E' -> "Standard Range (75 kWh)"
                        else -> "Standard Range"
                    }
                    MotorConfig(battery, "RWD")
                }

                'B' -> { // 双电机 长续航版 (AWD) — 旧版
                    val battery = when (batteryChar) {
                        'E' -> "Long Range (78 kWh, NMC)"
                        'F' -> "Long Range (60 kWh, LFP)"
                        else -> "Long Range"
                    }
                    MotorConfig(battery, "AWD")
                }

                'C' -> // 双电机 性能版 (AWD Performance) — 旧版
                    MotorConfig("Performance (78 kWh, NMC)", "AWD Performance")

                // ===== Highland 焕新版电机代码 =====
                'J' -> { // 单电机 标准版 (RWD) — Highland, Hairpin 绕组
                    val battery = when (batteryChar) {
                        'F' -> "Standard Range (60 kWh, LFP)"
                        'E' -> "Standard Range (75 kWh)"
                        else -> "Standard Range"
                    }
                    MotorConfig(battery, "RWD")
                }

                'K' -> { // 双电机 长续航版 (AWD) — Highland, Hairpin 绕组
                    val battery = when (batteryChar) {
                        'E' -> "Long Range (78 kWh, NMC)"
                        else -> "Long Range"
                    }
                    MotorConfig(battery, "AWD")
                }

                'R', 'S' -> { // 单电机 标准版 (RWD) — Highland 变体
                    val battery = when (batteryChar) {
                        'F' -> "Standard Range (60 kWh, LFP)"
                        'E' -> "Standard Range (75 kWh)"
                        else -> "Standard Range"
                    }
                    MotorConfig(battery, "RWD")
                }

                'T' -> // 双电机 性能版 (AWD Performance) — Highland
                    MotorConfig("Performance (82 kWh, NMC)", "AWD Performance")

                else -> MotorConfig("未知", "未知")
            }
        }

        /**
         * 解码 Model Y 电机配置(位置 8)
         *
         * ## 旧版 Model Y(2020-2024)
         * | 代码 | 配置                    | 电机类型        | 电池                |
         * |------|-------------------------|-----------------|---------------------|
         * | D    | 单电机 标准版 (RWD)     | 标准绕组        | LFP 60 kWh          |
         * | E    | 双电机 长续航版 (AWD)   | 标准绕组        | NMC 78 kWh          |
         * | F    | 双电机 性能版 (AWD Perf)| 标准绕组        | NMC 78 kWh          |
         * | J    | 单电机 标准版 (RWD)     | Hairpin 绕组(新)| LFP 60 kWh          |
         * | K    | 双电机 长续航版 (AWD)   | Hairpin 绕组(新)| NMC 78 kWh          |
         * | R    | 双电机 (中国产)         | —               | NMC 78 kWh          |
         *
         * ## Juniper 焕新版 Model Y(2025+)
         * | 代码 | 配置                    | 电机类型        | 电池                |
         * |------|-------------------------|-----------------|---------------------|
         * | L    | 双电机 性能版 (AWD Perf)| Juniper 专属    | NMC 78 kWh          |
         * | S    | 单电机 标准版 (RWD)     | DUB 600A        | LFP 60 kWh          |
         *
         * Hairpin 绕组电机: 采用矩形铜导体的新型电机设计,
         * 具有更高槽满率、更低电阻和更优热性能。
         *
         * @param motorChar 位置 8 的字符
         * @param batteryChar 位置 7 的字符(电池化学代码)
         * @return Model Y 电机配置信息
         */
        private fun decodeModelYMotor(
            motorChar: Char,
            batteryChar: Char,
        ): MotorConfig {
            return when (motorChar) {
                'D', 'J', 'S' -> { // 单电机 标准版 (RWD), D=标准/J=Hairpin/S=Juniper DUB600A
                    val battery = when (batteryChar) {
                        'F' -> "Standard Range (60 kWh, LFP)"
                        'E' -> "Standard Range (75 kWh)"
                        else -> "Standard Range"
                    }
                    MotorConfig(battery, "RWD")
                }

                'E', 'K' -> { // 双电机 长续航版 (AWD), E=标准绕组, K=Hairpin绕组
                    val battery = when (batteryChar) {
                        'E' -> "Long Range (78 kWh, NMC)"
                        else -> "Long Range"
                    }
                    MotorConfig(battery, "AWD")
                }

                'F', 'L' -> { // 双电机 性能版 (AWD Performance), F=旧版, L=Juniper专属
                    val battery = when (batteryChar) {
                        'E' -> "Performance (78 kWh, NMC)"
                        else -> "Performance (78 kWh)"
                    }
                    MotorConfig(battery, "AWD Performance")
                }

                'R' -> // 双电机 (部分中国产车型)
                    MotorConfig("Long Range (78 kWh)", "AWD")

                else -> MotorConfig("未知", "未知")
            }
        }

        /**
         * 解码 Cybertruck 电机配置(位置 8)
         *
         * | 代码 | 配置                        | 电池       |
         * |------|-----------------------------|------------|
         * | D    | 双电机 AWD                  | 123 kWh    |
         * | E    | 三电机 Cyberbeast (AWD)     | 123 kWh    |
         * | C    | 单电机 RWD(未来版本)       | 123 kWh    |
         *
         * @param motorChar 位置 8 的字符
         * @return Cybertruck 电机配置信息
         */
        private fun decodeCybertruckMotor(motorChar: Char): MotorConfig {
            return when (motorChar) {
                'D' -> MotorConfig("Dual Motor (123 kWh)", "AWD")
                'E' -> MotorConfig("Cyberbeast (123 kWh)", "AWD 三电机")
                'C' -> MotorConfig("Single Motor (123 kWh)", "RWD")
                else -> MotorConfig("Dual Motor (123 kWh)", "AWD")
            }
        }

        // ============================================================
        //  代际检测(全车型改款记录)
        // ============================================================

        /**
         * 检测车型代际 — 覆盖全部 Tesla 车型的历年改款
         *
         * Tesla 不使用底盘代号,而是通过 "Refresh(改款)" 持续迭代。
         * 同一车型名称(如 "Model 3")在不同年份可能代表完全不同的外观和内饰。
         *
         * ## 各车型改款历史总览
         *
         * ### Model S 四代演进
         * | 代际              | 代号        | 时间       | VIN 区分规则                          |
         * |-------------------|-------------|------------|---------------------------------------|
         * | Nosecone 初代     | —           | 2012-2016  | 位置7: H/S/V, 位置8: 1-4, 年份: A-G   |
         * | Facelift 改款     | Facelift    | 2016-2020  | 位置7: E, 位置8: 1-4, 年份: H-L       |
         * | Raven 更新        | Raven       | 2019-2020  | 年份: K-L (Facelift 子代,永磁前电机)  |
         * | Palladium 焕新版  | Palladium   | 2021+      | 位置8: 5(双电机)/6(三电机Plaid)      |
         *
         * ### Model X 三代演进
         * | 代际              | 代号        | 时间       | VIN 区分规则                          |
         * |-------------------|-------------|------------|---------------------------------------|
         * | Original 初代     | —           | 2015-2020  | 位置8: 2/4, 年份: G-L                |
         * | Raven 更新        | Raven       | 2019-2020  | 年份: K-L (子代,永磁前电机+自适应空悬)|
         * | Palladium 焕新版  | Palladium   | 2021+      | 位置8: 5(双电机)/6(三电机Plaid)      |
         *
         * ### Model 3 两代演进
         * | 代际              | 代号        | 时间       | VIN 区分规则                          |
         * |-------------------|-------------|------------|---------------------------------------|
         * | 旧版              | —           | 2017-2023  | 位置8: A/B/C, 位置5: A/B             |
         * | Highland 焕新版   | Highland    | 2024+      | 位置8: J/K/R/S/T, 位置5: E/F         |
         *
         * ### Model Y 两代演进
         * | 代际              | 代号        | 时间       | VIN 区分规则                          |
         * |-------------------|-------------|------------|---------------------------------------|
         * | 旧版              | —           | 2020-2024  | 位置8: D/E/F/J/K/R, 年份: H-R        |
         * | Juniper 焕新版    | Juniper     | 2025+      | 位置8: L/S (新代码), 年份: S+         |
         *
         * ### Cybertruck / Semi / Roadster
         * | 车型       | 代际  | 时间    |
         * |------------|-------|---------|
         * | Cybertruck | 初代  | 2024+   |
         * | Semi       | 初代  | 2023+   |
         * | Roadster   | 初代  | 2008-2012 (新一代未上市) |
         *
         * @param model 车型名称
         * @param bodyChar 位置 5 的字符(车身类型代码)
         * @param motorChar 位置 8 的字符(电机配置代码)
         * @param modelYear 车型年份
         * @return 代际描述(如 "Palladium 焕新版", "Highland 焕新版", "Juniper 焕新版" 等)
         */
        private fun detectGeneration(
            model: String,
            bodyChar: Char,
            motorChar: Char,
            modelYear: Int,
        ): String {
            return when (model) {
                "Model S" -> detectModelSGeneration(motorChar, modelYear)
                "Model X" -> detectModelXGeneration(motorChar, modelYear)
                "Model 3" -> detectModel3Generation(bodyChar, motorChar, modelYear)
                "Model Y" -> detectModelYGeneration(motorChar, modelYear)
                "Cybertruck" -> "初代 (2024+)"
                "Roadster" -> if (modelYear <= 2012) "初代 (2008-2012)" else "新一代 (未上市)"
                "Semi" -> "初代 (2023+)"
                else -> "未知"
            }
        }

        /**
         * 检测 Model S 代际
         *
         * Model S 经历了四代演进:
         *
         * 1. **Nosecone 初代 (2012-2016)**
         *    - 标志性黑色 "鼻锥" 前脸设计
         *    - 电池: 40/60/70/75/85/90 kWh
         *    - VIN 位置 7: H(85kWh)/S(60kWh)/V(90kWh) — 2015年前专用电池代码
         *    - VIN 位置 8: 1/2/3/4 — 旧版电机代码
         *
         * 2. **Facelift 改款 (2016-2020)**
         *    - 取消鼻锥,采用类似 Model X 的新前脸
         *    - 引入 HEPA 过滤器("生物武器防御模式")
         *    - 电池: 75/100 kWh
         *    - VIN 位置 7: E(Electric,不再使用电池容量代码)
         *    - VIN 位置 8: 1/2/3/4 — 同旧版电机代码
         *
         * 3. **Raven 更新 (2019-2020)** — Facelift 子代
         *    - 前电机换装永磁同步电机(3D1),效率大幅提升
         *    - 全新自适应空气悬架
         *    - 取消数字命名(75D/100D → Long Range/Performance)
         *    - VIN 无专用代码,仅通过年份区分(2019-2020)
         *
         * 4. **Palladium 焕新版 (2021+)**
         *    - 全新内饰: 17寸横屏 + Yoke 方向盘 + 后排娱乐屏
         *    - 取消怀挡,屏幕换挡
         *    - Plaid 三电机版本: 1020马力,0-60mph <2s
         *    - 热泵系统
         *    - VIN 位置 8: 5(双电机)/6(三电机Plaid) — 全新电机代码
         *    - WMI: 5YJ(2021) → 7SA(2022+)
         *
         * @param motorChar 位置 8 的字符
         * @param modelYear 车型年份
         * @return 代际描述
         */
        private fun detectModelSGeneration(motorChar: Char, modelYear: Int): String {
            // Palladium 焕新版: 位置 8 为 5(双电机) 或 6(三电机Plaid)
            val palladiumMotorCodes = setOf('5', '6')
            if (motorChar in palladiumMotorCodes) return "Palladium 焕新版 (2021+)"

            // 旧版电机代码 1-4
            val legacyMotorCodes = setOf('1', '2', '3', '4')
            if (motorChar in legacyMotorCodes) {
                return when {
                    // Raven 更新: 2019-2020 年间, Facelift 子代
                    modelYear in 2019..2020 -> "Facelift Raven 更新 (2019-2020)"
                    // Facelift 改款: 2017-2020
                    modelYear in 2017..2020 -> "Facelift 改款 (2016-2020)"
                    // Nosecone 初代: 2012-2016
                    modelYear <= 2016 -> "Nosecone 初代 (2012-2016)"
                    // 2021+ 但仍使用旧电机代码(边界情况)
                    modelYear >= 2021 -> "Palladium 焕新版 (2021+)"
                    else -> "Facelift 改款 (2016-2020)"
                }
            }

            // 仅按年份推断(电机代码未识别时)
            return when {
                modelYear >= 2021 -> "Palladium 焕新版 (2021+)"
                modelYear in 2019..2020 -> "Facelift Raven 更新 (2019-2020)"
                modelYear in 2017..2018 -> "Facelift 改款 (2016-2020)"
                modelYear <= 2016 -> "Nosecone 初代 (2012-2016)"
                else -> "未知"
            }
        }

        /**
         * 检测 Model X 代际
         *
         * Model X 经历了三代演进:
         *
         * 1. **Original 初代 (2015-2020)**
         *    - 标志性鹰翼门(Falcon Wing Door)
         *    - 17寸竖置中控屏
         *    - 电池: 60/75/90/100 kWh
         *    - VIN 位置 8: 2(双电机标准)/4(双电机性能)
         *
         * 2. **Raven 更新 (2019-2020)** — 初代子代
         *    - 前电机换装永磁同步电机
         *    - 全新自适应空气悬架
         *    - 取消数字命名
         *    - VIN 无专用代码,仅通过年份区分
         *
         * 3. **Palladium 焕新版 (2021+)**
         *    - 全新内饰: 17寸横屏 + Yoke 方向盘 + 后排8寸屏
         *    - 取消怀挡,屏幕换挡
         *    - Plaid 三电机: 1020马力,0-60mph 2.5s
         *    - 热泵系统
         *    - VIN 位置 8: 5(双电机)/6(三电机Plaid) — 全新电机代码
         *    - WMI: 5YJ(2021) → 7SA(2022+)
         *
         * @param motorChar 位置 8 的字符
         * @param modelYear 车型年份
         * @return 代际描述
         */
        private fun detectModelXGeneration(motorChar: Char, modelYear: Int): String {
            // Palladium 焕新版: 位置 8 为 5(双电机) 或 6(三电机Plaid)
            val palladiumMotorCodes = setOf('5', '6')
            if (motorChar in palladiumMotorCodes) return "Palladium 焕新版 (2021+)"

            // 旧版电机代码 2/4
            val legacyMotorCodes = setOf('2', '4')
            if (motorChar in legacyMotorCodes) {
                return when {
                    // Raven 更新: 2019-2020
                    modelYear in 2019..2020 -> "Raven 更新 (2019-2020)"
                    // Original 初代: 2015-2018
                    modelYear <= 2018 -> "Original 初代 (2015-2020)"
                    // 2021+ 但仍使用旧电机代码(边界情况)
                    modelYear >= 2021 -> "Palladium 焕新版 (2021+)"
                    else -> "Original 初代 (2015-2020)"
                }
            }

            // 仅按年份推断
            return when {
                modelYear >= 2021 -> "Palladium 焕新版 (2021+)"
                modelYear in 2019..2020 -> "Raven 更新 (2019-2020)"
                modelYear <= 2018 -> "Original 初代 (2015-2020)"
                else -> "未知"
            }
        }

        /**
         * 检测 Model 3 代际
         *
         * Model 3 经历了两代演进:
         *
         * 1. **旧版 (2017-2023)**
         *    - 初代 Model 3 设计
         *    - VIN 位置 5: A(左舵)/B(右舵) — 5门掀背/轿车
         *    - VIN 位置 8: A(单电机)/B(双电机)/C(双电机性能)
         *
         * 2. **Highland 焕新版 (2024+)**
         *    - 全新前后保险杠、大灯、尾灯设计
         *    - 全宽 LED 灯带
         *    - 取消转向灯拨杆,按钮移至方向盘
         *    - 屏幕换挡
         *    - VIN 位置 5: E(左舵)/F(右舵) — 4门轿车
         *    - VIN 位置 8: J/K(Hairpin绕组)/R/S(焕新变体)/T(焕新性能版)
         *    - Performance 版电池升级至 82 kWh(旧版 78 kWh)
         *
         * @param bodyChar 位置 5 的字符
         * @param motorChar 位置 8 的字符
         * @param modelYear 车型年份
         * @return 代际描述
         */
        private fun detectModel3Generation(
            bodyChar: Char,
            motorChar: Char,
            modelYear: Int,
        ): String {
            // Highland 专属电机代码: J/K/R/S/T
            val highlandMotorCodes = setOf('J', 'K', 'R', 'S', 'T')
            if (motorChar in highlandMotorCodes) return "Highland 焕新版 (2024+)"

            // Highland 车身代码: E/F(4门轿车) 且年份 >= 2024
            val highlandBodyCodes = setOf('E', 'F')
            if (bodyChar in highlandBodyCodes && modelYear >= 2024) return "Highland 焕新版 (2024+)"

            // 旧版电机代码 + 旧版车身类型
            val oldMotorCodes = setOf('A', 'B', 'C')
            val oldBodyCodes = setOf('A', 'B')
            if (motorChar in oldMotorCodes && bodyChar in oldBodyCodes) return "旧版 (2017-2023)"

            // 辅助判断: 2024+ 年份 + 旧版电机代码 → 可能是 Highland
            if (modelYear >= 2024 && motorChar in oldMotorCodes) return "Highland 焕新版 (2024+)"

            return "未知"
        }

        /**
         * 检测 Model Y 代际
         *
         * Model Y 经历了两代演进:
         *
         * 1. **旧版 (2020-2024)**
         *    - 初代 Model Y 设计
         *    - VIN 位置 5: G(左舵)/H(右舵) — MPV 5门
         *    - VIN 位置 8: D/E/F(标准绕组)/J/K(Hairpin绕组)/R(上海产)
         *    - 电池: 60 LFP / 75 NMC / 78 kWh
         *
         * 2. **Juniper 焕新版 (2025+)**
         *    - 全宽 LED 灯带(类似 Highland)
         *    - 全新内饰: 8寸后排屏幕 + 环境氛围灯
         *    - 频率选择悬挂(Frequency-Selective Damper)
         *    - 全车隔音玻璃
         *    - 取消怀挡,屏幕换挡
         *    - VIN 位置 8: L(双电机性能,Juniper专属)/S(单电机标准,DUB 600A,Juniper专属)
         *    - 年份代码 S(2025)+
         *    - 电池: 60 LFP / 78 kWh / 81 kWh (Long Range NMC)
         *
         * 注意: 部分 2024 年生产的旧版 Model Y 可能使用 2025(VIN位置10=S)年份代码,
         * 但 Juniper 专属电机代码 L/S 可作为确定性判断依据。
         *
         * @param motorChar 位置 8 的字符
         * @param modelYear 车型年份
         * @return 代际描述
         */
        private fun detectModelYGeneration(motorChar: Char, modelYear: Int): String {
            // Juniper 专属电机代码: L(双电机性能) / S(单电机标准 DUB 600A)
            val juniperMotorCodes = setOf('L', 'S')
            if (motorChar in juniperMotorCodes) return "Juniper 焕新版 (2025+)"

            // 年份 >= 2026 (T+) → 确定 Juniper
            if (modelYear >= 2026) return "Juniper 焕新版 (2025+)"

            // 年份 2025 (S) → 大概率 Juniper (2025年1月首发)
            if (modelYear == 2025) return "Juniper 焕新版 (2025+)"

            // 年份 <= 2024 → 旧版
            if (modelYear <= 2024) return "旧版 (2020-2024)"

            return "未知"
        }
    }

    /**
     * 内部电机配置解码中间结果
     *
     * @param batteryType 电池/配置版本描述(如 "Long Range (78 kWh, NMC)")
     * @param driveType 驱动类型(如 "AWD", "RWD", "AWD Performance")
     */
    private data class MotorConfig(
        val batteryType: String,
        val driveType: String,
    )
}
