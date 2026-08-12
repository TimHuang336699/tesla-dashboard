package com.tesla.dashboard.data.source.ble

import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom

/**
 * Tesla BLE 协议消息构建与解析
 *
 * 基于 Tesla vehicle-command SDK 的 protobuf 定义，使用 [TeslaProtobuf] 编解码器
 * 构建和解析 BLE 通信所需的协议消息。
 *
 * 消息层次:
 * - RoutableMessage (顶层消息, universal_message.proto)
 *   ├── SessionInfoRequest (握手请求, signatures.proto)
 *   ├── SessionInfo (握手响应, signatures.proto)
 *   ├── SignatureData (签名数据, signatures.proto)
 *   └── protobuf_message_as_bytes (应用层 payload)
 *       ├── UnsignedMessage (VCSEC 命令, vcsec.proto)
 *       │   ├── RKEAction (解锁/锁定/唤醒)
 *       │   ├── WhitelistOperation (钥匙管理)
 *       │   └── InformationRequest (状态查询)
 *       └── carserver.Action (信息娱乐命令, car_server.proto)
 */
object TeslaBleMessages {

    /**
     * 会话信息 (握手后获得)
     *
     * @param counter 反重放计数器
     * @param vehiclePublicKey 车辆公钥 (65 字节未压缩格式)
     * @param epoch 16 字节 epoch ID
     * @param clockTime 车辆时钟 (秒)
     */
    data class SessionInfo(
        val counter: Int,
        val vehiclePublicKey: ByteArray,
        val epoch: ByteArray,
        val clockTime: Int,
    )

    /**
     * 现代协议 getVehicleData 响应快照 (v0.5.2)
     *
     * 字段号与单位来源: vehicle-command v0.4.1 vehicle.proto。
     * 所有字段可空 — 车辆可能按固件版本仅返回部分数据。
     *
     * @param chargingState 充电状态枚举 [TeslaBleConstants.CHARGING_STATE_*]
     * @param batteryRangeMi 额定续航 (mi)
     * @param estBatteryRangeMi 估算续航 (mi)
     * @param chargerActualCurrentA 实际充电电流 (A)
     * @param insideTempC 车内温度 (°C)
     * @param outsideTempC 车外温度 (°C)
     * @param driverTempC 驾驶员侧目标温度 (°C)
     * @param passengerTempC 副驾驶侧目标温度 (°C)
     * @param speedKmh 车速 (km/h)
     * @param powerKw 功率 (kW, 负值=动能回收)
     * @param shiftState 挡位枚举 [TeslaBleConstants.SHIFT_STATE_*]
     * @param odometerKm 里程表 (km)
     * @param headingDeg 航向 (°)
     */
    data class VehicleDataSnapshot(
        val chargingState: Int?,
        val batteryRangeMi: Float?,
        val estBatteryRangeMi: Float?,
        val chargerActualCurrentA: Int?,
        val insideTempC: Float?,
        val outsideTempC: Float?,
        val driverTempC: Float?,
        val passengerTempC: Float?,
        val speedKmh: Int?,
        val powerKw: Int?,
        val shiftState: Int?,
        val odometerKm: Float?,
        val headingDeg: Float?,
    ) {
        /** 是否正在充电 (含启动中) */
        val isCharging: Boolean
            get() = chargingState == TeslaBleConstants.CHARGING_STATE_CHARGING ||
                chargingState == TeslaBleConstants.CHARGING_STATE_STARTING
    }

    // ===== Destination 编码 =====

    /**
     * 编码 Destination 消息
     *
     * @param domain 目标 Domain (可选)
     * @param routingAddress 路由地址 (可选, 随机 16 字节)
     * @return Destination protobuf 编码
     */
    fun encodeDestination(domain: Int? = null, routingAddress: ByteArray? = null): ByteArray {
        val buf = ByteArrayOutputStream()
        if (domain != null) {
            TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_DEST_DOMAIN, domain)
        }
        if (routingAddress != null) {
            TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_DEST_ROUTING_ADDRESS, routingAddress)
        }
        return buf.toByteArray()
    }

    // ===== SessionInfoRequest =====

    /**
     * 编码 SessionInfoRequest 消息
     *
     * @param publicKeyRaw 客户端公钥 (65 字节未压缩格式)
     * @return SessionInfoRequest protobuf 编码
     */
    fun encodeSessionInfoRequest(publicKeyRaw: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_SIR_PUBLIC_KEY, publicKeyRaw)
        return buf.toByteArray()
    }

    /**
     * 解析 SessionInfo 响应
     *
     * @param data session_info 字段的 bytes 值
     * @return 解析后的 SessionInfo
     */
    fun parseSessionInfo(data: ByteArray): SessionInfo {
        val fields = TeslaProtobuf.parseAllFields(data)
        return SessionInfo(
            counter = TeslaProtobuf.getUint32(fields, TeslaBleConstants.FIELD_SI_COUNTER) ?: 0,
            vehiclePublicKey = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_SI_PUBLIC_KEY)
                ?: ByteArray(0),
            epoch = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_SI_EPOCH)
                ?: ByteArray(0),
            clockTime = TeslaProtobuf.getFixed32(fields, TeslaBleConstants.FIELD_SI_CLOCK_TIME)
                ?: 0,
        )
    }

    // ===== RoutableMessage =====

    /**
     * 构建握手请求 RoutableMessage
     *
     * 包含 SessionInfoRequest, 发送到指定 Domain。
     * 不包含签名 (握手阶段未建立加密会话)。
     *
     * @param publicKeyRaw 客户端公钥 (65 字节)
     * @param domain 目标 Domain (默认 VCSEC)
     * @return 完整的 RoutableMessage protobuf 编码
     */
    fun buildHandshakeMessage(
        publicKeyRaw: ByteArray,
        domain: Int = TeslaBleConstants.DOMAIN_VEHICLE_SECURITY,
    ): ByteArray {
        val buf = ByteArrayOutputStream()

        // to_destination { domain }
        val toDest = encodeDestination(domain = domain)
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_TO_DESTINATION, toDest)

        // from_destination { routing_address: 随机 16 字节 }
        val routingAddress = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fromDest = encodeDestination(routingAddress = routingAddress)
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_FROM_DESTINATION, fromDest)

        // session_info_request { public_key }
        val sirBytes = encodeSessionInfoRequest(publicKeyRaw)
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_SESSION_INFO_REQUEST, sirBytes)

        // uuid (随机 16 字节)
        val uuid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_RM_UUID, uuid)

        return buf.toByteArray()
    }

    /**
     * 构建已签名命令 RoutableMessage
     *
     * 包含加密后的 payload 和 AES-GCM 签名数据。
     *
     * @param ciphertext 加密后的命令 payload
     * @param signatureData 签名数据 protobuf (AES_GCM_Personalized_Signature_Data)
     * @param domain 目标 Domain
     * @param flags 消息标志 (可选, FLAG_ENCRYPT_RESPONSE=1)
     * @return 完整的 RoutableMessage protobuf 编码
     */
    fun buildSignedCommandMessage(
        ciphertext: ByteArray,
        signatureData: ByteArray,
        domain: Int,
        flags: Int? = null,
    ): ByteArray {
        val buf = ByteArrayOutputStream()

        // to_destination { domain }
        val toDest = encodeDestination(domain = domain)
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_TO_DESTINATION, toDest)

        // from_destination { routing_address }
        val routingAddress = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fromDest = encodeDestination(routingAddress = routingAddress)
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_FROM_DESTINATION, fromDest)

        // protobuf_message_as_bytes (加密后的 payload)
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_RM_PROTOBUF_MESSAGE_AS_BYTES, ciphertext)

        // signature_data (包含 AES_GCM_Personalized_Signature_Data)
        val sigDataBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(sigDataBuf, TeslaBleConstants.FIELD_SD_AES_GCM_PERSONALIZED, signatureData)
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_RM_SIGNATURE_DATA, sigDataBuf.toByteArray())

        // flags (可选)
        if (flags != null) {
            TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_RM_FLAGS, flags)
        }

        // uuid
        val uuid = ByteArray(16).also { SecureRandom().nextBytes(it) }
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_RM_UUID, uuid)

        return buf.toByteArray()
    }

    // ===== AES_GCM_Personalized_Signature_Data =====

    /**
     * 编码 AES_GCM_Personalized_Signature_Data
     *
     * @param epoch 16 字节 epoch (来自 SessionInfo)
     * @param nonce 12 字节 nonce
     * @param counter 反重放计数器
     * @param expiresAt 过期时间戳
     * @param tag 16 字节 AES-GCM 认证标签
     * @return 签名数据 protobuf 编码
     */
    fun encodeSignatureData(
        epoch: ByteArray,
        nonce: ByteArray,
        counter: Int,
        expiresAt: Int,
        tag: ByteArray,
    ): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_APS_EPOCH, epoch)
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_APS_NONCE, nonce)
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_APS_COUNTER, counter)
        TeslaProtobuf.writeFixed32(buf, TeslaBleConstants.FIELD_APS_EXPIRES_AT, expiresAt)
        TeslaProtobuf.writeBytes(buf, TeslaBleConstants.FIELD_APS_TAG, tag)
        return buf.toByteArray()
    }

    // ===== VCSEC UnsignedMessage =====

    /**
     * 编码 RKEAction 命令 (解锁/锁定/唤醒)
     *
     * @param action RKE action 常量 (如 RKE_ACTION_UNLOCK)
     * @return UnsignedMessage protobuf 编码
     */
    fun encodeRkeAction(action: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_UM_RKE_ACTION, action)
        return buf.toByteArray()
    }

    /**
     * 编码 InformationRequest (查询车辆状态)
     *
     * @param infoRequestType 信息请求类型 (如 INFO_REQ_WHITELIST_INFO)
     * @return UnsignedMessage protobuf 编码
     */
    fun encodeInformationRequest(infoRequestType: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_UM_INFORMATION_REQUEST, infoRequestType)
        return buf.toByteArray()
    }

    /**
     * 编码 ClosureMoveRequest (打开/关闭前后备箱) (v0.5.0)
     *
     * @param closure 舱门类型 (如 CLOSURE_FRUNK / CLOSURE_TRUNK)
     * @param action 动作 (如 CLOSURE_ACTION_OPEN)
     * @return UnsignedMessage protobuf 编码
     */
    fun encodeClosureMoveRequest(closure: Int, action: Int): ByteArray {
        // ClosureMoveRequest { closure, action }
        val moveBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(moveBuf, TeslaBleConstants.FIELD_CM_CLOSURE, closure)
        TeslaProtobuf.writeUint32(moveBuf, TeslaBleConstants.FIELD_CM_ACTION, action)
        // UnsignedMessage { ClosureMoveRequest }
        val unsignedBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(unsignedBuf, TeslaBleConstants.FIELD_UM_CLOSURE_MOVE_REQUEST, moveBuf.toByteArray())
        return unsignedBuf.toByteArray()
    }

    /**
     * 编码 WhitelistOperation — 添加钥匙请求 (add-key-request)
     *
     * 构造 PermissionChange 消息，包含新公钥和角色，
     * 用于在配对流程中将应用公钥注册到车辆信任列表。
     *
     * @param publicKeyRaw 新公钥 (65 字节未压缩格式)
     * @param keyRole 钥匙角色 (如 ROLE_OWNER 或 ROLE_DRIVER)
     * @param keyFormFactor 钥匙表单因子 (如 KEY_FORM_FACTOR_CLOUD_KEY)
     * @return UnsignedMessage protobuf 编码
     */
    fun encodeAddKeyRequest(
        publicKeyRaw: ByteArray,
        keyRole: Int = TeslaBleConstants.ROLE_OWNER,
        keyFormFactor: Int = TeslaBleConstants.KEY_FORM_FACTOR_CLOUD_KEY,
    ): ByteArray {
        // PublicKey { PublicKeyRaw }
        val pubKeyBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeBytes(pubKeyBuf, TeslaBleConstants.FIELD_PK_PUBLIC_KEY_RAW, publicKeyRaw)
        val pubKeyBytes = pubKeyBuf.toByteArray()

        // PermissionChange { key, keyRole }
        val permChangeBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(permChangeBuf, TeslaBleConstants.FIELD_PC_KEY, pubKeyBytes)
        TeslaProtobuf.writeUint32(permChangeBuf, TeslaBleConstants.FIELD_PC_KEY_ROLE, keyRole)
        val permChangeBytes = permChangeBuf.toByteArray()

        // KeyMetadata { keyFormFactor }
        val metadataBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(metadataBuf, TeslaBleConstants.FIELD_KM_KEY_FORM_FACTOR, keyFormFactor)
        val metadataBytes = metadataBuf.toByteArray()

        // WhitelistOperation { addKeyToWhitelistAndAddPermissions, metadataForKey }
        val whitelistBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(whitelistBuf, TeslaBleConstants.FIELD_WO_ADD_KEY_AND_PERMISSIONS, permChangeBytes)
        TeslaProtobuf.writeMessage(whitelistBuf, TeslaBleConstants.FIELD_WO_METADATA, metadataBytes)
        val whitelistBytes = whitelistBuf.toByteArray()

        // UnsignedMessage { WhitelistOperation }
        val unsignedBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(unsignedBuf, TeslaBleConstants.FIELD_UM_WHITELIST_OPERATION, whitelistBytes)
        return unsignedBuf.toByteArray()
    }

    // ===== 响应解析 =====

    /**
     * 解析 VCSEC CommandStatus (v0.5.0)
     *
     * 从解密的 VCSEC 响应中提取命令执行状态。
     *
     * 解析层次:
     * ```
     * UnsignedMessage
     *   └── command_status (field 3)
     *         └── operation_status (field 1) → OP_STATUS_*
     * ```
     *
     * @param plaintext 解密后的 UnsignedMessage protobuf
     * @return operation_status 值 (OP_STATUS_SUCCESS=2 等), 无 CommandStatus 时返回 null
     */
    fun parseCommandStatus(plaintext: ByteArray): Int? {
        val unsignedFields = TeslaProtobuf.parseAllFields(plaintext)
        val csBytes = TeslaProtobuf.getBytes(unsignedFields, TeslaBleConstants.FIELD_UM_COMMAND_STATUS)
            ?: return null
        val csFields = TeslaProtobuf.parseAllFields(csBytes)
        return TeslaProtobuf.getUint32(csFields, TeslaBleConstants.FIELD_CS_OPERATION_STATUS)
    }

    // ===== carserver (Infotainment 域) 消息 =====

    /**
     * 编码 carserver.Action — GetVehicleState 请求
     *
     * 向 Infotainment 域发送此消息以获取完整车辆状态:
     * - DriveState: 车速 / 位置 / 航向 / 档位 / 海拔
     * - ChargeState: 电池 SOC / 续航里程
     * - ClimateState: 车内/车外温度
     * - CarState: 里程表 / 车型 / 距离单位
     *
     * @return Action protobuf 编码 (GetVehicleState 为空消息)
     */
    fun encodeGetVehicleState(): ByteArray {
        val buf = ByteArrayOutputStream()
        // Action { get_vehicle_state: {} }
        // field 2, wire type 2 (length-delimited), empty message
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_ACTION_GET_VEHICLE_STATE, ByteArray(0))
        return buf.toByteArray()
    }

    /**
     * 解析 carserver.Response — VehicleState 响应
     *
     * 从 Infotainment 域的加密响应中提取所有可用车辆数据字段。
     *
     * 解析层次:
     * ```
     * Response
     *   └── vehicle_state (field 3)
     *         ├── drive_state (field 3) → 车速/位置/航向/档位/海拔
     *         ├── charge_state (field 6) → 电池SOC/续航
     *         ├── vehicle_state (field 7) → 里程表/距离单位
     *         └── climate_state (field 8) → 车内/车外温度
     * ```
     *
     * @param plaintext 解密后的 Response protobuf
     * @return 包含所有已解析字段的 [VehicleStateData]
     */
    fun parseVehicleStateResponse(plaintext: ByteArray): VehicleStateData {
        val responseFields = TeslaProtobuf.parseAllFields(plaintext)

        // 提取 VehicleState (field 3)
        val vehicleStateBytes = TeslaProtobuf.getBytes(responseFields, TeslaBleConstants.FIELD_RESPONSE_VEHICLE_STATE)
            ?: return VehicleStateData() // 无车辆状态数据

        val vsFields = TeslaProtobuf.parseAllFields(vehicleStateBytes)

        // ===== DriveState (field 3) — 行驶状态 =====
        var speedMph: Int? = null
        var latitude: Double? = null
        var longitude: Double? = null
        var heading: Float? = null
        var gear: String? = null
        var altitude: Double? = null
        var powerKw: Float? = null

        TeslaProtobuf.getBytes(vsFields, TeslaBleConstants.FIELD_VS_DRIVE_STATE)?.let { driveBytes ->
            val dsFields = TeslaProtobuf.parseAllFields(driveBytes)
            speedMph = TeslaProtobuf.getUint32(dsFields, TeslaBleConstants.FIELD_DS_SPEED_LEGACY)
            // 瞬时功率 kW (int32, 正=驱动/负=动能回收)
            powerKw = TeslaProtobuf.getUint32(dsFields, TeslaBleConstants.FIELD_DS_POWER_LEGACY)?.toFloat()
            // 优先使用原生高精度坐标 (double)，否则降级使用 float 坐标
            latitude = TeslaProtobuf.getDouble(dsFields, TeslaBleConstants.FIELD_DS_NATIVE_LATITUDE)
                ?: TeslaProtobuf.getFloat(dsFields, TeslaBleConstants.FIELD_DS_LATITUDE)?.toDouble()
            longitude = TeslaProtobuf.getDouble(dsFields, TeslaBleConstants.FIELD_DS_NATIVE_LONGITUDE)
                ?: TeslaProtobuf.getFloat(dsFields, TeslaBleConstants.FIELD_DS_LONGITUDE)?.toDouble()
            heading = TeslaProtobuf.getFloat(dsFields, TeslaBleConstants.FIELD_DS_NATIVE_HEADING)
                ?: TeslaProtobuf.getUint32(dsFields, TeslaBleConstants.FIELD_DS_HEADING_LEGACY)?.toFloat()
            gear = TeslaProtobuf.getString(dsFields, TeslaBleConstants.FIELD_DS_SHIFT_STATE_LEGACY)
            // 海拔 — 尝试 elevation 字段
            altitude = TeslaProtobuf.getFloat(dsFields, TeslaBleConstants.FIELD_DS_ELEVATION)?.toDouble()
        }

        // ===== ChargeState (field 6) — 电池状态 =====
        var batterySOC: Int? = null
        var batteryRange: Float? = null

        TeslaProtobuf.getBytes(vsFields, TeslaBleConstants.FIELD_VS_CHARGE_STATE)?.let { chargeBytes ->
            val csFields = TeslaProtobuf.parseAllFields(chargeBytes)
            batterySOC = TeslaProtobuf.getUint32(csFields, TeslaBleConstants.FIELD_CS_BATTERY_LEVEL)
            batteryRange = TeslaProtobuf.getUint32(csFields, TeslaBleConstants.FIELD_CS_BATTERY_RANGE_LEGACY)?.toFloat()
                ?: TeslaProtobuf.getFloat(csFields, TeslaBleConstants.FIELD_CS_EST_BATTERY_RANGE_LEGACY)
        }

        // ===== CarState (field 7) — 车辆基本状态 =====
        var odometer: Float? = null
        var distanceUnits: String? = null
        var isLocked: Boolean? = null
        var df: Boolean? = null
        var dr: Boolean? = null
        var pf: Boolean? = null
        var pr: Boolean? = null
        var ft: Boolean? = null
        var rt: Boolean? = null

        TeslaProtobuf.getBytes(vsFields, TeslaBleConstants.FIELD_VS_CAR_STATE)?.let { carBytes ->
            val carFields = TeslaProtobuf.parseAllFields(carBytes)
            odometer = TeslaProtobuf.getFloat(carFields, TeslaBleConstants.FIELD_CAR_ODOMETER)
            distanceUnits = TeslaProtobuf.getString(carFields, TeslaBleConstants.FIELD_CAR_GUI_DISTANCE_UNITS)
            // 门/舱/锁状态 (bool 字段, varint 0=关闭 1=打开)
            isLocked = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_LOCKED)?.let { it != 0 }
            df = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_DF)?.let { it != 0 }
            dr = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_DR)?.let { it != 0 }
            pf = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_PF)?.let { it != 0 }
            pr = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_PR)?.let { it != 0 }
            ft = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_FT)?.let { it != 0 }
            rt = TeslaProtobuf.getUint32(carFields, TeslaBleConstants.FIELD_CAR_RT)?.let { it != 0 }
        }

        // ===== ClimateState (field 8) — 温度状态 =====
        var insideTemp: Float? = null
        var outsideTemp: Float? = null

        TeslaProtobuf.getBytes(vsFields, TeslaBleConstants.FIELD_VS_CLIMATE_STATE)?.let { climateBytes ->
            val clsFields = TeslaProtobuf.parseAllFields(climateBytes)
            insideTemp = TeslaProtobuf.getFloat(clsFields, TeslaBleConstants.FIELD_CLS_INSIDE_TEMP)
            outsideTemp = TeslaProtobuf.getFloat(clsFields, TeslaBleConstants.FIELD_CLS_OUTSIDE_TEMP)
        }

        return VehicleStateData(
            speedMph = speedMph,
            latitude = latitude,
            longitude = longitude,
            heading = heading,
            gear = gear,
            altitude = altitude,
            powerKw = powerKw,
            batterySOC = batterySOC,
            batteryRange = batteryRange,
            odometer = odometer,
            distanceUnits = distanceUnits,
            insideTemp = insideTemp,
            outsideTemp = outsideTemp,
            isLocked = isLocked,
            df = df,
            dr = dr,
            pf = pf,
            pr = pr,
            ft = ft,
            rt = rt,
        )
    }

    /**
     * carserver VehicleState 解析结果
     *
     * 包含从 Infotainment 域 GetVehicleState 响应中提取的所有可用字段。
     * 所有字段均可能为 null (取决于车辆状态和数据可用性)。
     *
     * @param speedMph 车速 (mph, 需转换为 km/h: ×1.609344)
     * @param latitude 纬度
     * @param longitude 经度
     * @param heading 航向角 0-360
     * @param gear 档位 P/R/N/D
     * @param altitude 海拔 (米)
     * @param batterySOC 电池电量百分比 0-100
     * @param batteryRange 电池续航里程 (单位取决于 distanceUnits)
     * @param odometer 总里程表 (单位取决于 distanceUnits)
     * @param distanceUnits 距离单位 "km" 或 "mi"
     * @param insideTemp 车内温度 °C
     * @param outsideTemp 车外温度 °C
     */
    data class VehicleStateData(
        val speedMph: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val heading: Float? = null,
        val gear: String? = null,
        val altitude: Double? = null,
        val powerKw: Float? = null,
        val batterySOC: Int? = null,
        val batteryRange: Float? = null,
        val odometer: Float? = null,
        val distanceUnits: String? = null,
        val insideTemp: Float? = null,
        val outsideTemp: Float? = null,
        val isLocked: Boolean? = null,
        val df: Boolean? = null,
        val dr: Boolean? = null,
        val pf: Boolean? = null,
        val pr: Boolean? = null,
        val ft: Boolean? = null,
        val rt: Boolean? = null,
    )

    // ===== 现代 carserver 协议 (v0.5.2 BLE 拓展插件) =====

    /**
     * 编码现代协议 Action 消息
     *
     * 现代协议结构 (teslamotors/vehicle-command):
     * ```
     * Action { vehicleAction { <具体动作> } }
     * ```
     * 其中 VehicleAction 位于 Action.field 2, 具体动作位于 VehicleAction 的 oneof。
     *
     * @param vehicleActionBytes VehicleAction 消息编码
     * @return Action protobuf 编码
     */
    private fun encodeModernAction(vehicleActionBytes: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_ACTION_VEHICLE_ACTION, vehicleActionBytes)
        return buf.toByteArray()
    }

    /**
     * 编码 VehicleAction 包装
     *
     * @param field VehicleAction 动作字段编号
     * @param actionBytes 动作消息编码
     * @return Action protobuf 编码
     */
    private fun encodeModernVehicleAction(field: Int, actionBytes: ByteArray): ByteArray {
        val vaBuf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(vaBuf, field, actionBytes)
        return encodeModernAction(vaBuf.toByteArray())
    }

    /** 编码空动作消息 (如 ChargePortDoorOpen/Close) */
    private fun encodeModernEmptyAction(field: Int): ByteArray =
        encodeModernVehicleAction(field, ByteArray(0))

    /**
     * 编码设置充电限值命令 (v0.5.2)
     *
     * @param percent 充电限值百分比 50-100 (ChargingSetLimitAction.percent)
     * @return Action protobuf 编码
     */
    fun encodeChargeLimit(percent: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_CSL_PERCENT, percent)
        return encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_CHARGING_SET_LIMIT, buf.toByteArray())
    }

    /**
     * 编码开始充电命令 (v0.5.2)
     *
     * @param maxRange 是否使用最大续航充电模式 (默认标准充电)
     * @return Action protobuf 编码
     */
    fun encodeChargeStart(maxRange: Boolean = false): ByteArray {
        val buf = ByteArrayOutputStream()
        val field = if (maxRange) TeslaBleConstants.FIELD_CSS_START_MAX_RANGE else TeslaBleConstants.FIELD_CSS_START
        TeslaProtobuf.writeMessage(buf, field, ByteArray(0))
        return encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_CHARGING_START_STOP, buf.toByteArray())
    }

    /**
     * 编码停止充电命令 (v0.5.2)
     *
     * @return Action protobuf 编码
     */
    fun encodeChargeStop(): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeMessage(buf, TeslaBleConstants.FIELD_CSS_STOP, ByteArray(0))
        return encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_CHARGING_START_STOP, buf.toByteArray())
    }

    /**
     * 编码空调开关命令 (v0.5.2, 自动模式)
     *
     * @param on true=开启空调, false=关闭
     * @return Action protobuf 编码
     */
    fun encodeHvacAuto(on: Boolean): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_HA_POWER_ON, if (on) 1 else 0)
        return encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_HVAC_AUTO, buf.toByteArray())
    }

    /**
     * 编码空调温度设置命令 (v0.5.2)
     *
     * @param driverCelsius 驾驶员侧目标温度 °C
     * @param passengerCelsius 副驾驶侧目标温度 °C
     * @return Action protobuf 编码
     */
    fun encodeHvacTemperature(driverCelsius: Float, passengerCelsius: Float): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeFloat(buf, TeslaBleConstants.FIELD_HTA_DRIVER_TEMP_CELSIUS, driverCelsius)
        TeslaProtobuf.writeFloat(buf, TeslaBleConstants.FIELD_HTA_PASSENGER_TEMP_CELSIUS, passengerCelsius)
        return encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_HVAC_TEMPERATURE_ADJUSTMENT, buf.toByteArray())
    }

    /**
     * 编码充电口开关命令 (v0.5.2)
     *
     * @param open true=打开充电口, false=关闭充电口
     * @return Action protobuf 编码
     */
    fun encodeChargePort(open: Boolean): ByteArray =
        if (open) {
            encodeModernEmptyAction(TeslaBleConstants.FIELD_VA_CHARGE_PORT_DOOR_OPEN)
        } else {
            encodeModernEmptyAction(TeslaBleConstants.FIELD_VA_CHARGE_PORT_DOOR_CLOSE)
        }

    /**
     * 编码车辆低功耗模式命令 (v0.5.2, 实验性)
     *
     * @param on true=进入低功耗(深度休眠), false=退出低功耗
     * @return Action protobuf 编码
     */
    fun encodeLowPowerMode(on: Boolean): ByteArray {
        val buf = ByteArrayOutputStream()
        TeslaProtobuf.writeUint32(buf, TeslaBleConstants.FIELD_SLPM_LOW_POWER_MODE, if (on) 1 else 0)
        return encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_SET_LOW_POWER_MODE, buf.toByteArray())
    }

    /**
     * 解析现代协议命令执行状态 (v0.5.2)
     *
     * 解析层次:
     * ```
     * Response
     *   └── actionStatus (field 1)
     *         └── result (field 1) → OP_STATUS_OK(0) / OP_STATUS_ERROR(1)
     * ```
     *
     * @param plaintext 解密后的 Response protobuf
     * @return result 值 (OP_STATUS_OK=0 等), 无 actionStatus 时返回 null
     */
    fun parseActionStatus(plaintext: ByteArray): Int? {
        val responseFields = TeslaProtobuf.parseAllFields(plaintext)
        val actionStatusBytes = TeslaProtobuf.getBytes(responseFields, TeslaBleConstants.FIELD_RESPONSE_ACTION_STATUS)
            ?: return null
        val asFields = TeslaProtobuf.parseAllFields(actionStatusBytes)
        return TeslaProtobuf.getUint32(asFields, TeslaBleConstants.FIELD_AS_RESULT)
    }

    /**
     * 编码 getVehicleData 请求 (v0.5.2)
     *
     * `VehicleAction { getVehicleData {} }` — 空消息请求全部车辆数据
     * (ChargeState/ClimateState/DriveState 等, 固件支持时返回)。
     *
     * @return Action protobuf 编码
     */
    fun encodeGetVehicleData(): ByteArray =
        encodeModernVehicleAction(TeslaBleConstants.FIELD_VA_GET_VEHICLE_DATA, ByteArray(0))

    /**
     * 解析现代协议 VehicleData 响应 (v0.5.2)
     *
     * 解析层次 (字段号来源: vehicle-command v0.4.1 vehicle.proto):
     * ```
     * Response
     *   └── vehicleData (field 2)
     *         ├── chargeState (field 3)
     *         │     ├── chargingState (field 1, 嵌套枚举消息: field N=空消息)
     *         │     ├── batteryRange / estBatteryRange (field 111/112, fixed32, 单位 mi)
     *         │     └── chargerActualCurrent (field 121, A)
     *         ├── climateState (field 4)
     *         │     ├── insideTemp (field 3) / outsideTemp (field 4) / °C
     *         │     └── driverTemp (field 5) / passengerTemp (field 6)
     *         └── driveState (field 5)
     *               ├── speed (field 1, km/h) / power (field 2, kW, 负值=回收)
     *               └── odometer (field 4, km) / heading (field 8, °)
     * ```
     *
     * @param plaintext 解密后的 Response protobuf
     * @return 解析快照, 响应中无 vehicleData 时返回 null
     */
    fun parseVehicleData(plaintext: ByteArray): VehicleDataSnapshot? {
        val responseFields = TeslaProtobuf.parseAllFields(plaintext)
        val vehicleDataBytes = TeslaProtobuf.getBytes(responseFields, TeslaBleConstants.FIELD_RESPONSE_VEHICLE_DATA)
            ?: return null
        val vdFields = TeslaProtobuf.parseAllFields(vehicleDataBytes)
        val c = TeslaBleConstants

        // ChargeState
        val chargeStateBytes = TeslaProtobuf.getBytes(vdFields, c.FIELD_VD_CHARGE_STATE)
        var chargingState: Int? = null
        var batteryRangeMi: Float? = null
        var estBatteryRangeMi: Float? = null
        var chargerActualCurrentA: Int? = null
        if (chargeStateBytes != null) {
            val csFields = TeslaProtobuf.parseAllFields(chargeStateBytes)
            chargingState = parseChargingStateEnum(
                TeslaProtobuf.getBytes(csFields, c.FIELD_CS_CHARGING_STATE),
            )
            batteryRangeMi = TeslaProtobuf.getFloat(csFields, c.FIELD_CS_BATTERY_RANGE)
            estBatteryRangeMi = TeslaProtobuf.getFloat(csFields, c.FIELD_CS_EST_BATTERY_RANGE)
            chargerActualCurrentA = TeslaProtobuf.getUint32(csFields, c.FIELD_CS_CHARGER_ACTUAL_CURRENT)
        }

        // ClimateState
        val climateBytes = TeslaProtobuf.getBytes(vdFields, c.FIELD_VD_CLIMATE_STATE)
        var insideTempC: Float? = null
        var outsideTempC: Float? = null
        var driverTempC: Float? = null
        var passengerTempC: Float? = null
        if (climateBytes != null) {
            val clFields = TeslaProtobuf.parseAllFields(climateBytes)
            insideTempC = TeslaProtobuf.getFloat(clFields, c.FIELD_CL_INSIDE_TEMP)
            outsideTempC = TeslaProtobuf.getFloat(clFields, c.FIELD_CL_OUTSIDE_TEMP)
            driverTempC = TeslaProtobuf.getFloat(clFields, c.FIELD_CL_DRIVER_TEMP)
            passengerTempC = TeslaProtobuf.getFloat(clFields, c.FIELD_CL_PASSENGER_TEMP)
        }

        // DriveState
        val driveBytes = TeslaProtobuf.getBytes(vdFields, c.FIELD_VD_DRIVE_STATE)
        var speedKmh: Int? = null
        var powerKw: Int? = null
        var shiftState: Int? = null
        var odometerKm: Float? = null
        var headingDeg: Float? = null
        if (driveBytes != null) {
            val dsFields = TeslaProtobuf.parseAllFields(driveBytes)
            speedKmh = TeslaProtobuf.getUint32(dsFields, c.FIELD_DS_SPEED)
            powerKw = TeslaProtobuf.getUint32(dsFields, c.FIELD_DS_POWER)?.toInt()
            shiftState = TeslaProtobuf.getUint32(dsFields, c.FIELD_DS_SHIFT_STATE)
            odometerKm = TeslaProtobuf.getFloat(dsFields, c.FIELD_DS_ODOMETER)
            headingDeg = TeslaProtobuf.getFloat(dsFields, c.FIELD_DS_HEADING)
        }

        return VehicleDataSnapshot(
            chargingState = chargingState,
            batteryRangeMi = batteryRangeMi,
            estBatteryRangeMi = estBatteryRangeMi,
            chargerActualCurrentA = chargerActualCurrentA,
            insideTempC = insideTempC,
            outsideTempC = outsideTempC,
            driverTempC = driverTempC,
            passengerTempC = passengerTempC,
            speedKmh = speedKmh,
            powerKw = powerKw,
            shiftState = shiftState,
            odometerKm = odometerKm,
            headingDeg = headingDeg,
        )
    }

    /**
     * 解析 ChargingState 嵌套枚举消息
     *
     * `ChargingState { oneof { Void charging = 5; ... } }` —
     * 枚举值 = 该字段号 (空消息标记存在)。
     *
     * @param chargingStateBytes 嵌套消息字节, null 时返回 null
     * @return 枚举值 ([TeslaBleConstants.CHARGING_STATE_*]), 无法识别返回 null
     */
    private fun parseChargingStateEnum(chargingStateBytes: ByteArray?): Int? {
        if (chargingStateBytes == null) return null
        // 枚举字段为长度分隔消息, 直接解析一层即可
        val fields = TeslaProtobuf.parseAllFields(chargingStateBytes)
        return fields.firstOrNull()?.fieldNumber
            ?.takeIf { it in TeslaBleConstants.CHARGING_STATE_UNKNOWN..TeslaBleConstants.CHARGING_STATE_CALIBRATING }
    }

    // ===== RoutableMessage 响应解析 =====

    /**
     * 解析 RoutableMessage 响应
     *
     * 提取关键字段用于后续处理。
     *
     * @param data RoutableMessage protobuf 编码
     * @return 解析后的响应数据
     */
    fun parseRoutableMessage(data: ByteArray): RoutableMessageResponse {
        val fields = TeslaProtobuf.parseAllFields(data)

        val sessionInfoBytes = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_SESSION_INFO)
        val payloadBytes = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_PROTOBUF_MESSAGE_AS_BYTES)
        val signatureDataBytes = TeslaProtobuf.getBytes(fields, TeslaBleConstants.FIELD_RM_SIGNATURE_DATA)
        val status = TeslaProtobuf.getUint32(fields, TeslaBleConstants.FIELD_RM_SIGNED_MESSAGE_STATUS)

        // 解析 SignatureData 中的 session_info_tag (HMAC 标签)
        var hmacTag: ByteArray? = null
        if (signatureDataBytes != null) {
            val sigFields = TeslaProtobuf.parseAllFields(signatureDataBytes)
            hmacTag = TeslaProtobuf.getBytes(sigFields, TeslaBleConstants.FIELD_SD_SESSION_INFO_TAG)
        }

        return RoutableMessageResponse(
            sessionInfo = sessionInfoBytes,
            payload = payloadBytes,
            signatureData = signatureDataBytes,
            hmacTag = hmacTag,
            status = status?.toInt() ?: 0,
        )
    }

    /**
     * RoutableMessage 响应解析结果
     */
    data class RoutableMessageResponse(
        /** SessionInfo 原始 bytes (握手响应时存在) */
        val sessionInfo: ByteArray?,
        /** 加密的 payload (命令响应时存在) */
        val payload: ByteArray?,
        /** 签名数据 */
        val signatureData: ByteArray?,
        /** HMAC 标签 (握手响应验证) */
        val hmacTag: ByteArray?,
        /** 协议状态码 */
        val status: Int,
    )
}
