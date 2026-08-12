package com.tesla.dashboard.data.source.ble

/**
 * Tesla BLE 协议常量定义
 *
 * 基于 Tesla 官方 vehicle-command SDK (github.com/teslamotors/vehicle-command)
 * 的 Go 源码 pkg/connector/ble/ble.go 和 pkg/protocol/ 协议定义。
 */
object TeslaBleConstants {

    // ===== BLE GATT UUID =====

    /** Tesla 车辆 BLE 服务 UUID */
    const val SERVICE_UUID = "00000211-b2d1-43f0-9b88-960cebf8b91e"

    /** 写入特征 (TX, 客户端→车辆) */
    const val TX_CHARACTERISTIC_UUID = "00000212-b2d1-43f0-9b88-960cebf8b91e"

    /** 读取特征 (RX, 车辆→客户端, Notify/Indicate) */
    const val RX_CHARACTERISTIC_UUID = "00000213-b2d1-43f0-9b88-960cebf8b91e"

    // ===== BLE 通信参数 =====

    /** 最大 BLE 消息大小 (字节) */
    const val MAX_BLE_MESSAGE_SIZE = 1024

    /** 接收块超时 (毫秒) */
    const val RX_TIMEOUT_MS = 1000L

    /**
     * 连接 / 扫描超时 (毫秒) — 车辆不在附近时,10s 内未扫描到车辆即快速失败,
     * 避免让用户长时间盯着"正在扫描..."界面。
     */
    const val CONNECT_TIMEOUT_MS = 10000L

    /**
     * 命令超时 (毫秒) — 单次 GATT 读写 / 加密命令的等待时间。
     */
    const val COMMAND_TIMEOUT_MS = 5000L

    /**
     * NFC 确认等待超时 (毫秒) — 发送 add-key-request 后,等用户在车机刷 NFC 卡片的最大等待时间。
     * 30s 平衡用户体验与网络抖动:太短会导致正常刷卡也被误判超时,太长会拖垮取消响应。
     */
    const val NFC_CONFIRM_TIMEOUT_MS = 30000L

    /** ATT 头部大小 (用于计算分块) */
    const val ATT_HEADER_SIZE = 3

    // ===== 消息长度前缀 =====

    /** 消息长度前缀大小 (2 字节大端) */
    const val LENGTH_PREFIX_SIZE = 2

    // ===== Domain 枚举 (universal_message.proto) =====

    /** Domain 广播 */
    const val DOMAIN_BROADCAST = 0

    /** VCSEC - 车辆安全域 (锁、后备箱、钥匙管理) */
    const val DOMAIN_VEHICLE_SECURITY = 2

    /** 信息娱乐域 (空调、充电、媒体等) */
    const val DOMAIN_INFOTAINMENT = 3

    // ===== 签名类型枚举 (signatures.proto) =====

    /** 标准 AES-GCM */
    const val SIGNATURE_TYPE_AES_GCM = 0

    /** 带元数据的 AES-GCM (最常用) */
    const val SIGNATURE_TYPE_AES_GCM_PERSONALIZED = 5

    /** HMAC */
    const val SIGNATURE_TYPE_HMAC = 6

    /** 带元数据的 HMAC */
    const val SIGNATURE_TYPE_HMAC_PERSONALIZED = 8

    /** 车辆响应加密 */
    const val SIGNATURE_TYPE_AES_GCM_RESPONSE = 9

    // ===== TLV Tag 枚举 (协议元数据) =====

    const val TAG_SIGNATURE_TYPE = 0
    const val TAG_DOMAIN = 1
    const val TAG_PERSONALIZATION = 2
    const val TAG_EPOCH = 3
    const val TAG_EXPIRES_AT = 4
    const val TAG_COUNTER = 5
    const val TAG_CHALLENGE = 6
    const val TAG_FLAGS = 7
    const val TAG_REQUEST_HASH = 8
    const val TAG_FAULT = 9
    const val TAG_END = 255

    // ===== VCSEC RKEAction 枚举 (vcsec.proto) =====

    const val RKE_ACTION_UNLOCK = 0
    const val RKE_ACTION_LOCK = 1
    const val RKE_ACTION_REMOTE_DRIVE = 20
    const val RKE_ACTION_AUTO_SECURE_VEHICLE = 29
    const val RKE_ACTION_WAKE_VEHICLE = 30

    // ===== 钥匙表单因子 (keys.proto) =====

    const val KEY_FORM_FACTOR_UNKNOWN = 0
    const val KEY_FORM_FACTOR_NFC_CARD = 1
    const val KEY_FORM_FACTOR_IOS_DEVICE = 6
    const val KEY_FORM_FACTOR_ANDROID_DEVICE = 7
    const val KEY_FORM_FACTOR_CLOUD_KEY = 9

    // ===== 钥匙角色 (keys.proto) =====

    const val ROLE_NONE = 0
    const val ROLE_SERVICE = 1
    const val ROLE_OWNER = 2
    const val ROLE_DRIVER = 3
    const val ROLE_FM = 4
    const val ROLE_VEHICLE_MONITOR = 5
    const val ROLE_CHARGING_MANAGER = 6
    const val ROLE_GUEST = 8

    // ===== ClosureMoveRequest (vcsec.proto, v0.5.0 前后备箱控制) =====

    /** ClosureMoveRequest.closure — 舱门类型 */
    const val CLOSURE_NONE = 0
    const val CLOSURE_FRUNK = 1
    const val CLOSURE_TRUNK = 2

    /** ClosureMoveRequest.action — 舱门动作 */
    const val CLOSURE_ACTION_NONE = 0
    const val CLOSURE_ACTION_MOVE = 1
    const val CLOSURE_ACTION_OPEN = 2
    const val CLOSURE_ACTION_CLOSE = 3

    // ===== CommandStatus (vcsec.proto, v0.5.0 命令执行状态) =====

    /** CommandStatus.operation_status — 命令执行结果 */
    const val OP_STATUS_PENDING = 1
    const val OP_STATUS_SUCCESS = 2
    const val OP_STATUS_FAILED = 3

    // ===== RoutableMessage Flag =====

    /** 请求车辆加密响应 */
    const val FLAG_ENCRYPT_RESPONSE = 1

    // ===== ECDH 参数 =====

    /** ECDH 曲线名 */
    const val ECDH_CURVE = "secp256r1" // = NIST P-256 = prime256v1

    /** 共享密钥大小 (字节) — AES-128 */
    const val SHARED_KEY_SIZE = 16

    /** AES-GCM Nonce 大小 (字节) */
    const val NONCE_SIZE = 12

    /** AES-GCM 认证标签大小 (字节) */
    const val GCM_TAG_SIZE = 16

    /** 公钥未压缩格式大小 (0x04 + 32x + 32y = 65 字节) */
    const val PUBLIC_KEY_SIZE = 65

    // ===== Protobuf 字段编号 =====

    // RoutableMessage (universal_message.proto)
    const val FIELD_RM_TO_DESTINATION = 6
    const val FIELD_RM_FROM_DESTINATION = 7
    const val FIELD_RM_SIGNED_MESSAGE_STATUS = 12
    const val FIELD_RM_SIGNATURE_DATA = 13
    const val FIELD_RM_SESSION_INFO_REQUEST = 14
    const val FIELD_RM_SESSION_INFO = 15
    const val FIELD_RM_PROTOBUF_MESSAGE_AS_BYTES = 10
    const val FIELD_RM_REQUEST_UUID = 50
    const val FIELD_RM_UUID = 51
    const val FIELD_RM_FLAGS = 52

    // Destination (universal_message.proto)
    const val FIELD_DEST_DOMAIN = 1
    const val FIELD_DEST_ROUTING_ADDRESS = 2

    // SessionInfoRequest (signatures.proto)
    const val FIELD_SIR_PUBLIC_KEY = 1
    const val FIELD_SIR_CHALLENGE = 2

    // SessionInfo (signatures.proto)
    const val FIELD_SI_COUNTER = 1
    const val FIELD_SI_PUBLIC_KEY = 2
    const val FIELD_SI_EPOCH = 3
    const val FIELD_SI_CLOCK_TIME = 4
    const val FIELD_SI_STATUS = 5
    const val FIELD_SI_HANDLE = 6

    // SignatureData (signatures.proto)
    const val FIELD_SD_AES_GCM_PERSONALIZED = 5
    const val FIELD_SD_SESSION_INFO_TAG = 8

    // AES_GCM_Personalized_Signature_Data (signatures.proto)
    const val FIELD_APS_EPOCH = 1
    const val FIELD_APS_NONCE = 2
    const val FIELD_APS_COUNTER = 3
    const val FIELD_APS_EXPIRES_AT = 4
    const val FIELD_APS_TAG = 5

    // UnsignedMessage (vcsec.proto)
    const val FIELD_UM_INFORMATION_REQUEST = 1
    const val FIELD_UM_RKE_ACTION = 2
    const val FIELD_UM_COMMAND_STATUS = 3
    const val FIELD_UM_CLOSURE_MOVE_REQUEST = 4
    const val FIELD_UM_WHITELIST_OPERATION = 16

    // ClosureMoveRequest (vcsec.proto)
    const val FIELD_CM_CLOSURE = 1
    const val FIELD_CM_ACTION = 2

    // CommandStatus (vcsec.proto)
    const val FIELD_CS_OPERATION_STATUS = 1

    // WhitelistOperation (vcsec.proto)
    const val FIELD_WO_ADD_KEY_AND_PERMISSIONS = 5
    const val FIELD_WO_METADATA = 6

    // PermissionChange (vcsec.proto)
    const val FIELD_PC_KEY = 1
    const val FIELD_PC_SECONDS_TO_BE_ACTIVE = 3
    const val FIELD_PC_KEY_ROLE = 4

    // PublicKey (vcsec.proto / keys.proto)
    const val FIELD_PK_PUBLIC_KEY_RAW = 1

    // KeyMetadata (vcsec.proto / keys.proto)
    const val FIELD_KM_KEY_FORM_FACTOR = 1

    // InformationRequest (vcsec.proto)
    const val FIELD_IR_INFORMATION_REQUEST_TYPE = 1

    // InformationRequestType enum
    const val INFO_REQ_NONE = 0
    const val INFO_REQ_ENTITY = 1
    const val INFO_REQ_PUBLIC_KEY_x509 = 2
    const val INFO_REQ_PUBLIC_KEY_SERIAL = 3
    const val INFO_REQ_WHITELIST = 4
    const val INFO_REQ_WHITELIST_INFO = 5

    // ===== carserver 协议字段编号 (car_server.proto) =====

    // carserver.Action
    /** Action.get_vehicle_state — 请求获取完整车辆状态 */
    const val FIELD_ACTION_GET_VEHICLE_STATE = 2

    // ===== 现代 carserver 协议 (v0.5.2 BLE 拓展插件, teslamotors/vehicle-command) =====

    /** Action.VehicleAction — 现代协议的动作包装 (field 2) */
    const val FIELD_ACTION_VEHICLE_ACTION = 2

    // VehicleAction (oneof vehicle_action_msg)
    /** VehicleAction.getVehicleData — 获取车辆数据 (空消息=全部) */
    const val FIELD_VA_GET_VEHICLE_DATA = 1
    /** VehicleAction.chargingSetLimitAction — 设置充电限值 */
    const val FIELD_VA_CHARGING_SET_LIMIT = 5
    /** VehicleAction.chargingStartStopAction — 开始/停止充电 */
    const val FIELD_VA_CHARGING_START_STOP = 6
    /** VehicleAction.hvacAutoAction — 空调开关 (自动模式) */
    const val FIELD_VA_HVAC_AUTO = 10
    /** VehicleAction.hvacTemperatureAdjustmentAction — 空调温度调整 */
    const val FIELD_VA_HVAC_TEMPERATURE_ADJUSTMENT = 14
    /** VehicleAction.chargePortDoorClose — 关闭充电口 */
    const val FIELD_VA_CHARGE_PORT_DOOR_CLOSE = 61
    /** VehicleAction.chargePortDoorOpen — 打开充电口 */
    const val FIELD_VA_CHARGE_PORT_DOOR_OPEN = 62
    /** VehicleAction.setLowPowerModeAction — 车辆低功耗模式 (休眠) */
    const val FIELD_VA_SET_LOW_POWER_MODE = 130

    // ChargingSetLimitAction
    const val FIELD_CSL_PERCENT = 1

    // ChargingStartStopAction (oneof charging_action)
    const val FIELD_CSS_START = 2
    const val FIELD_CSS_START_STANDARD = 3
    const val FIELD_CSS_START_MAX_RANGE = 4
    const val FIELD_CSS_STOP = 5

    // HvacAutoAction
    const val FIELD_HA_POWER_ON = 1
    const val FIELD_HA_MANUAL_OVERRIDE = 2

    // HvacTemperatureAdjustmentAction
    const val FIELD_HTA_DELTA_CELSIUS = 1
    const val FIELD_HTA_ABSOLUTE_CELSIUS = 3
    const val FIELD_HTA_DRIVER_TEMP_CELSIUS = 6
    const val FIELD_HTA_PASSENGER_TEMP_CELSIUS = 7

    // SetLowPowerModeAction
    const val FIELD_SLPM_LOW_POWER_MODE = 1

    // Response / ActionStatus (现代协议)
    /** Response.actionStatus — 命令执行状态 (field 1) */
    const val FIELD_RESPONSE_ACTION_STATUS = 1
    /** Response.vehicleData — getVehicleData 响应数据 (field 2, v0.4.1 car_server.proto) */
    const val FIELD_RESPONSE_VEHICLE_DATA = 2
    /** ActionStatus.result — 执行结果 (OperationStatus_E) */
    const val FIELD_AS_RESULT = 1
    /** OperationStatus_E: OK */
    const val OP_STATUS_OK = 0
    /** OperationStatus_E: ERROR */
    const val OP_STATUS_ERROR = 1

    // ===== VehicleData / 子状态 (v0.5.2, 来源: vehicle-command v0.4.1 vehicle.proto) =====

    // VehicleData (field 2 内的消息)
    const val FIELD_VD_CHARGE_STATE = 3
    const val FIELD_VD_CLIMATE_STATE = 4
    const val FIELD_VD_DRIVE_STATE = 5

    // ChargeState (vehicle.proto, oneof optional 字段)
    /** ChargeState.charging_state — 充电状态枚举 (嵌套消息) */
    const val FIELD_CS_CHARGING_STATE = 1
    /** ChargeState.battery_range — 额定续航 (mi, fixed32) */
    const val FIELD_CS_BATTERY_RANGE = 111
    /** ChargeState.est_battery_range — 估算续航 (mi, fixed32) */
    const val FIELD_CS_EST_BATTERY_RANGE = 112
    /** ChargeState.charger_actual_current — 实际充电电流 (A, varint) */
    const val FIELD_CS_CHARGER_ACTUAL_CURRENT = 121

    // ChargingState 枚举 (ChargingState.charging_state 嵌套 oneof: 空消息标记)
    const val CHARGING_STATE_UNKNOWN = 1
    const val CHARGING_STATE_DISCONNECTED = 2
    const val CHARGING_STATE_NO_POWER = 3
    const val CHARGING_STATE_STARTING = 4
    const val CHARGING_STATE_CHARGING = 5
    const val CHARGING_STATE_COMPLETE = 6
    const val CHARGING_STATE_STOPPED = 7
    const val CHARGING_STATE_CALIBRATING = 8

    // ClimateState (vehicle.proto)
    const val FIELD_CL_FAN_STATUS = 1
    const val FIELD_CL_INSIDE_TEMP = 3
    const val FIELD_CL_OUTSIDE_TEMP = 4
    const val FIELD_CL_DRIVER_TEMP = 5
    const val FIELD_CL_PASSENGER_TEMP = 6

    // DriveState (vehicle.proto)
    const val FIELD_DS_SPEED = 1
    const val FIELD_DS_POWER = 2
    const val FIELD_DS_SHIFT_STATE = 3
    const val FIELD_DS_ODOMETER = 4
    const val FIELD_DS_HEADING = 8
    /** ShiftState 枚举 (DriveState.shift_state) */
    const val SHIFT_STATE_DRIVE = 1
    const val SHIFT_STATE_NEUTRAL = 2
    const val SHIFT_STATE_REVERSE = 3
    const val SHIFT_STATE_PARK = 4

    // carserver.Response
    /** Response.response_status — 响应状态 */
    const val FIELD_RESPONSE_STATUS = 1
    /** Response.vehicle_state — 车辆状态数据 (VehicleState 消息) */
    const val FIELD_RESPONSE_VEHICLE_STATE = 3

    // carserver.VehicleState (顶层响应)
    /** VehicleState.drive_state — 行驶状态(车速/位置/航向/档位) */
    const val FIELD_VS_DRIVE_STATE = 3
    /** VehicleState.charge_state — 充电/电池状态(SOC/续航) */
    const val FIELD_VS_CHARGE_STATE = 6
    /** VehicleState.vehicle_state — 车辆基本状态(里程表/车型) */
    const val FIELD_VS_CAR_STATE = 7
    /** VehicleState.climate_state — 空调/温度状态(车内/车外温度) */
    const val FIELD_VS_CLIMATE_STATE = 8

    // carserver.DriveState — 行驶状态子消息 (传统 getVehicleState 协议)
    /** DriveState.latitude — 纬度 (float) */
    const val FIELD_DS_LATITUDE = 4
    /** DriveState.longitude — 经度 (float) */
    const val FIELD_DS_LONGITUDE = 5
    /** DriveState.heading — 航向角 0-360 (int32) */
    const val FIELD_DS_HEADING_LEGACY = 6
    /** DriveState.speed — 车速 mph (int32) */
    const val FIELD_DS_SPEED_LEGACY = 14
    /** DriveState.power — 功率 kW (int32, 正=驱动/负=动能回收) */
    const val FIELD_DS_POWER_LEGACY = 15
    /** DriveState.shift_state — 档位 P/R/N/D (string) */
    const val FIELD_DS_SHIFT_STATE_LEGACY = 17
    /** DriveState.gps_as_of — GPS 时间戳 (uint64) */
    const val FIELD_DS_GPS_AS_OF = 18
    /** DriveState.native_latitude — 原生纬度 (double, 高精度) */
    const val FIELD_DS_NATIVE_LATITUDE = 25
    /** DriveState.native_longitude — 原生经度 (double, 高精度) */
    const val FIELD_DS_NATIVE_LONGITUDE = 26
    /** DriveState.native_heading — 原生航向 (float) */
    const val FIELD_DS_NATIVE_HEADING = 27
    /** DriveState.native_type — GPS 定位类型 (int32) */
    const val FIELD_DS_NATIVE_TYPE = 28
    /** DriveState.elevation — 海拔 (float, 米) */
    const val FIELD_DS_ELEVATION = 35

    // carserver.ChargeState — 充电状态子消息 (传统 getVehicleState 协议)
    /** ChargeState.battery_level — 电池电量百分比 0-100 (int32) */
    const val FIELD_CS_BATTERY_LEVEL = 3
    /** ChargeState.battery_range — 电池续航里程 (int32) */
    const val FIELD_CS_BATTERY_RANGE_LEGACY = 5
    /** ChargeState.charge_energy_added — 已充入电量 kWh (float) */
    const val FIELD_CS_CHARGE_ENERGY_ADDED = 6
    /** ChargeState.charge_limit_soc — 充电上限百分比 (int32) */
    const val FIELD_CS_CHARGE_LIMIT_SOC = 9
    /** ChargeState.est_battery_range — 预估续航里程 (float) */
    const val FIELD_CS_EST_BATTERY_RANGE_LEGACY = 28

    // carserver.ClimateState — 温度状态子消息
    /** ClimateState.inside_temp — 车内温度 °C (float) */
    const val FIELD_CLS_INSIDE_TEMP = 1
    /** ClimateState.outside_temp — 车外温度 °C (float) */
    const val FIELD_CLS_OUTSIDE_TEMP = 7

    // carserver.VehicleState (车级子消息, 非顶层 VehicleState)
    /** CarState.odometer — 总里程表 (float, 英里或公里) */
    const val FIELD_CAR_ODOMETER = 10
    /** CarState.car_type — 车型代码 (string) */
    const val FIELD_CAR_TYPE = 14
    /** CarState.car_special_type — 特殊车型 (string) */
    const val FIELD_CAR_SPECIAL_TYPE = 15
    /** CarState.gui_distance_units — 里程单位 "km"/"mi" (string) */
    const val FIELD_CAR_GUI_DISTANCE_UNITS = 33

    // CarState 门/舱/锁状态字段 (bool, varint wire type 0, 0=关闭/1=打开)
    /** CarState.locked — 车辆锁定状态 (bool) */
    const val FIELD_CAR_LOCKED = 3
    /** CarState.ft — 前备箱打开 (bool) */
    const val FIELD_CAR_FT = 25
    /** CarState.rt — 后备箱打开 (bool) */
    const val FIELD_CAR_RT = 26
    /** CarState.df — 驾驶员侧前门打开 (bool) */
    const val FIELD_CAR_DF = 27
    /** CarState.dr — 驾驶员侧后门打开 (bool) */
    const val FIELD_CAR_DR = 28
    /** CarState.pf — 乘客侧前门打开 (bool) */
    const val FIELD_CAR_PF = 29
    /** CarState.pr — 乘客侧后门打开 (bool) */
    const val FIELD_CAR_PR = 30

    /**
     * 根据车辆 VIN 计算 BLE 广播名称 (Local Name)
     *
     * 算法: "S" + hex(SHA1(VIN)[:8]) + "C"
     * 例如: VIN="5YJS0000000000000" → "S1a87a5a75f3df858C"
     */
    fun vehicleLocalName(vin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(vin.toByteArray())
        val hex = digest.copyOf(8).joinToString("") { "%02x".format(it) }
        return "S${hex}C"
    }
}
