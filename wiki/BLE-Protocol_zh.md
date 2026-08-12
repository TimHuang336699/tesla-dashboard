# BLE 协议

## 概述

Tesla Dashboard 实现了 Tesla 的 vehicle-command BLE 协议，用于与车辆直接通信。本文档描述协议的实现细节。

## 协议栈

```
┌─────────────────────────────────────┐
│        应用层 (Application Layer)     │
│  VehicleData / VehicleCommand       │
├─────────────────────────────────────┤
│        加密层 (Crypto Layer)         │
│  ECDH + AES-GCM + TLV              │
├─────────────────────────────────────┤
│        消息层 (Message Layer)        │
│  RoutableMessage / UnsignedMessage  │
├─────────────────────────────────────┤
│        传输层 (Transport Layer)      │
│  GATT 特征值 (TX/RX)                │
└─────────────────────────────────────┘
```

## BLE GATT 服务

| UUID | 名称 | 用途 |
|------|------|------|
| `00000211-b2d1-43f0-9b88-960cebf8b91e` | Tesla Service | 主 BLE 服务 |
| `00000212-b2d1-43f0-9b88-960cebf8b91e` | TX Characteristic | 客户端 → 车辆 |
| `00000213-b2d1-43f0-9b88-960cebf8b91e` | RX Characteristic | 车辆 → 客户端 (Notify) |

## 车辆发现

特斯拉车辆通过 VIN 的哈希值广播其 BLE 名称：

```
本地名称 = "S" + SHA1(VIN)[0:8].hex() + "C"
```

示例：VIN `5YJS0000000000000` → `S1a87a5a75f3df858C`

## 双域架构

协议在一个 GATT 连接上建立两个独立的加密会话：

### VCSEC 域（车辆安全）

| 特性 | 说明 |
|------|------|
| 域 ID | `2` |
| 用途 | 安全操作 |
| 命令 | 唤醒、锁定、解锁、前备箱/后备箱 |

### 信息娱乐域

| 特性 | 说明 |
|------|------|
| 域 ID | `3` |
| 用途 | 车辆数据 |
| 命令 | GetVehicleState |

## 消息格式

### RoutableMessage（顶层）

```protobuf
message RoutableMessage {
    Destination to_destination = 6;
    Destination from_destination = 7;
    bytes protobuf_message_as_bytes = 10;
    SignatureData signature_data = 13;
    SessionInfoRequest session_info_request = 14;
    SessionInfo session_info = 15;
    bytes uuid = 51;
    uint32 flags = 52;
}
```

### SessionInfoRequest（握手）

```protobuf
message SessionInfoRequest {
    bytes public_key = 1;  // 65 字节非压缩 EC 点
}
```

### SessionInfo（握手响应）

```protobuf
message SessionInfo {
    uint32 counter = 1;
    bytes public_key = 2;  // 车辆 65 字节公钥
    bytes epoch = 3;       // 16 字节 epoch ID
    uint32 clock_time = 4;
}
```

## 加密流程

### 1. ECDH 密钥协商

```
客户端                        车辆
  │                            │
  │  SessionInfoRequest        │
  │  { public_key: client_pub }│
  │──────────────────────────▶│
  │                            │
  │  SessionInfo               │
  │  { public_key: vehicle_pub,│
  │    epoch, counter }        │
  │◀──────────────────────────│
  │                            │
  shared_key = SHA1(ECDH(client_priv, vehicle_pub))[0:16]
```

### 2. 命令加密

```
1. 递增 counter
2. nonce = epoch[0:8] || counter (4 字节大端序)
3. 构建 TLV AAD:
   - signature_type: AES_GCM_PERSONALIZED (5)
   - domain: 目标域
   - personalization: VIN
   - epoch: 会话 epoch
   - expires_at: current_time + 10s
   - counter: 当前 counter
   - flags: 0
4. 加密: AES-GCM(key=shared_key, nonce, plaintext, aad)
5. 构建 SignatureData: { epoch, nonce, counter, expires_at, tag }
6. 发送 RoutableMessage { ciphertext, signature_data }
```

### 3. 响应解密

```
1. 从响应中提取 SignatureData
2. 解析 AES_GCM_Personalized_Signature_Data:
   - epoch, nonce, counter, tag
3. 构建响应 AAD（与命令类似）
4. 解密: AES-GCM(key=shared_key, nonce, ciphertext, tag, aad)
5. 解析明文 protobuf
```

## 消息定界

BLE 消息使用 2 字节长度前缀进行定界：

```
┌──────────────┬────────────────────┐
│ 长度 (2B)    │ 载荷                │
│ 大端序       │ (protobuf 字节)      │
└──────────────┴────────────────────┘
```

超过 MTU 的消息会分块传输：

```
分块大小 = MTU - ATT_HEADER_SIZE (3)
```

## 轮询序列

```
┌─────────────────────────────────────────────┐
│ 1. 从 Keystore 加载私钥                      │
├─────────────────────────────────────────────┤
│ 2. 连接（缓存地址或扫描）                     │
├─────────────────────────────────────────────┤
│ 3. VCSEC 握手                               │
│    - 发送 SessionInfoRequest                 │
│    - 接收 SessionInfo                        │
│    - 计算共享密钥                             │
├─────────────────────────────────────────────┤
│ 4. 唤醒车辆                                  │
│    - RKE_ACTION_WAKE_VEHICLE (30)           │
├─────────────────────────────────────────────┤
│ 5. 信息娱乐握手                              │
│    - 新的 SessionInfoRequest                 │
│    - 新的共享密钥                             │
├─────────────────────────────────────────────┤
│ 6. GetVehicleState                          │
│    - 发送空 Action                           │
│    - 接收加密响应                            │
│    - 解密并解析                              │
├─────────────────────────────────────────────┤
│ 7. 断开连接                                  │
└─────────────────────────────────────────────┘
```

## 车辆控制命令

### 解锁

```kotlin
RKE_ACTION_UNLOCK = 0
// UnsignedMessage { rke_action: 0 }
```

### 锁定

```kotlin
RKE_ACTION_LOCK = 1
// UnsignedMessage { rke_action: 1 }
```

### 开启前备箱

```kotlin
CLOSURE_FRUNK = 1
CLOSURE_ACTION_OPEN = 2
// UnsignedMessage { closure_move_request: { closure: 1, action: 2 } }
```

### 开启后备箱

```kotlin
CLOSURE_TRUNK = 2
CLOSURE_ACTION_OPEN = 2
// UnsignedMessage { closure_move_request: { closure: 2, action: 2 } }
```

## 时序参数

| 参数 | 值 | 说明 |
|------|-----|------|
| 轮询间隔 | 5s | 正常轮询 |
| 行驶中轮询 | 2.5s | 车辆移动时 |
| 连接超时 | 10s | GATT 连接 |
| 扫描超时 | 15s | BLE 扫描 |
| 命令超时 | 5s | 单个命令 |
| NFC 超时 | 30s | 配对确认 |
| 最大退避 | 30s | 指数退避上限 |

## 错误处理

### 连接失败

- 缓存地址失败 → 全量扫描
- 扫描超时 → 发射过期数据
- GATT 断开 → 发射过期数据

### 加密失败

- 公钥不匹配 → 中止（可能 MITM 攻击）
- 解密失败 → 记录日志并跳过
- 计数器重放 → 车辆拒绝

### 指数退避

```
第 1 次尝试: 5s
第 2 次尝试: 10s
第 3 次尝试: 20s
第 4 次及以后: 30s（上限）
```

## 安全特性

1. **ECDH 密钥协商** — 共享密钥不会在网络中传输
2. **AES-GCM 加密** — 128 位密钥的认证加密
3. **计数器防重放** — 单调递增计数器
4. **公钥固定** — 车辆公钥本地存储
5. **私钥信封保护** — Android Keystore AES-256-GCM 保护

详见 [安全](Security_zh.md)。
