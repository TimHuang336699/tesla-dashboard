# 架构设计

## 概述

Tesla Dashboard 采用 **MVVM（Model-View-ViewModel）** 架构，结合 **Repository 模式**实现职责分离。

## 架构图

```
┌─────────────────────────────────────────────┐
│                  UI 层                       │
│  DashboardActivity · SettingsActivity ·     │
│  PairingActivity · HistoryActivity ·        │
│  SplashActivity · PluginCenterActivity      │
│  BleExtensionActivity + 自定义 View         │
├─────────────────────────────────────────────┤
│               ViewModel 层                  │
│  DashboardViewModel · SettingsListViewModel │
│  BleSettingsViewModel · SettingsLightViewModel│
├─────────────────────────────────────────────┤
│              Repository 层                  │
│  VehicleDataRepository (BLE + GNSS 合并)    │
│  TripRepository · SettingsRepository        │
│  PluginCatalogRepository (GitHub JSON +    │
│  本地缓存)                                  │
├─────────────────────────────────────────────┤
│              Data Source 层                 │
│  TeslaBleProvider (轮询状态机)              │
│  PhoneGnssProvider (GPS 降级)               │
│  TeslaBleManager (GATT) · TeslaKeyManager   │
│  TeslaCrypto · TeslaProtobuf · TeslaMessages│
├─────────────────────────────────────────────┤
│              Plugin 层 (v0.5.2)             │
│  PluginManager (DataStore 持久化)           │
│  DashboardPlugin 接口 · Hilt Module         │
│  BleExtensionPlugin · MarketPluginInfo      │
├─────────────────────────────────────────────┤
│              基础设施                        │
│  Room DB · DataStore · Keystore · Hilt DI   │
│  ScreenStateTracker · VersionUtils          │
└─────────────────────────────────────────────┘
```

## 各层职责

### UI 层

| 组件 | 职责 |
|------|------|
| `DashboardActivity` | 主仪表盘显示、实时数据更新 |
| `SettingsActivity` | 设置菜单（含二级页面） |
| `PairingActivity` | BLE 配对向导 |
| `PluginCenterActivity` | 已安装插件 + 在线市场 (v0.5.2) |
| `BleExtensionActivity` | BLE 拓展命令页 |
| 自定义 View | `SpeedDisplayView`、`CarSilhouetteView`、`VerticalGaugeView`、`TurnSignalView` |

### ViewModel 层

| 组件 | 职责 |
|------|------|
| `DashboardViewModel` | 车辆数据流、单位转换、电耗计算 |
| `SettingsListViewModel` | 设置状态管理 |

### Repository 层

| 组件 | 职责 |
|------|------|
| `VehicleDataRepository` | 合并 BLE + GNSS 数据、降级逻辑管理 |
| `SettingsRepository` | DataStore  backed 设置持久化 |
| `PluginCatalogRepository` | 从 GitHub 拉取 plugin-catalog.json，本地缓存 |

### Data Source 层

| 组件 | 职责 |
|------|------|
| `TeslaBleProvider` | BLE 轮询状态机、车辆数据解析 |
| `PhoneGnssProvider` | BLE 断开时手机 GPS 降级 |
| `TeslaBleManager` | GATT 连接、消息定界、发送/接收 |
| `TeslaKeyManager` | 密钥存储、VIN 持久化 |
| `TeslaCrypto` | ECDH、AES-GCM、TLV 编码 |
| `TeslaProtobuf` | Wire 格式编解码 |
| `TeslaBleMessages` | 协议消息构建/解析 |

### Plugin 层 (v0.5.2)

| 组件 | 职责 |
|------|------|
| `PluginManager` | 插件生命周期、DataStore 持久化、启用/停用 |
| `DashboardPlugin` | 插件接口；通过 Hilt `@IntoMap` 注册 |
| `BleExtensionPlugin` | BLE 拓展命令（充电/空调/低功耗） |
| `MarketPluginInfo` | 市场插件元数据（Gson 序列化） |

## 数据流

```
┌─────────────┐     ┌─────────────────┐     ┌──────────────────┐
│   BLE/GNSS  │────▶│  Repository     │────▶│    ViewModel     │
│   Provider  │     │  (合并/过滤)     │     │  (状态/更新)      │
└─────────────┘     └─────────────────┘     └──────────────────┘
                                                       │
                                                       ▼
                                               ┌──────────────────┐
                                               │    UI 层         │
                                               │  (观察/响应)      │
                                               └──────────────────┘
```

### BLE 数据流（轮询）

```
1. TeslaBleProvider.observeData() → Flow<VehicleData>
2. 每次发射:
   a. 加载私钥
   b. 扫描/连接（缓存地址或全量扫描）
   c. VCSEC 握手 → 唤醒车辆
   d. 信息娱乐握手 → GetVehicleState
   e. 解密响应
   f. 解析 VehicleState（车速、SOC、温度等）
   g. 单位转换（mph→km/h，mi→km）
   h. 计算衍生数据（加速度、G力、行程）
   i. 发射 VehicleData
```

### GNSS 降级流

```
1. VehicleDataRepository 监控 BLE 可用性
2. BLE 不可用 → 启用 PhoneGnssProvider
3. PhoneGnssProvider 发射基于位置的 VehicleData
4. Repository 合并: BLE 基线 + GNSS 增量
5. BLE 恢复 → 停用 PhoneGnssProvider
```

### 插件注册流 (v0.5.2)

```
1. 应用启动 → PluginManager.onStart()
2. Hilt 注入所有 @IntoSet DashboardPlugin 实现
3. 对每个插件: 调用 plugin.onRegister(context)
4. 若 onRegister 抛出异常 → 自动回滚（插件禁用）
5. 插件状态保存到 DataStore
6. PluginCenterActivity 观察 pluginStates Flow
```

## 核心设计模式

### 1. 状态机（BLE 轮询）

`TeslaBleProvider` 实现轮询状态机：
- `Idle` → `Scanning` → `Connecting` → `Handshaking` → `Polling` → `Idle`
- 失败时指数退避
- 自适应间隔（行驶 2.5s、静止 5s、熄屏 30s）

### 2. 互斥锁（会话安全）

`sessionMutex` 确保同一时间只有一个 BLE 会话：
- 防止轮询中断车辆控制命令
- 防止并发 GATT 连接

### 3. Flow（响应式数据）

所有数据流使用 Kotlin `Flow`：
- `StateFlow` 用于 UI 状态
- `callbackFlow` 用于 BLE 回调
- `combine` 用于合并多数据源

### 4. Hilt（依赖注入）

```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vehicleDataRepository: VehicleDataRepository,
    settingsRepository: SettingsRepository,
) : ViewModel()
```

### 5. 插件 Multibinding (v0.5.2)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {
    @Binds
    @IntoSet
    abstract fun bindBleExtension(plugin: BleExtensionPlugin): DashboardPlugin
}
```

## 项目结构

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application 类, Hilt 入口
├── data/
│   ├── local/              # Room DB, DAO, Repositories
│   ├── model/              # 数据类 (VehicleData, Trip 等)
│   ├── remote/             # PluginCatalogRepository (GitHub JSON + 缓存)
│   └── source/
│       └── ble/            # TeslaBleProvider, Manager, Crypto 等
├── di/                     # Hilt 模块 (DataSourceModule, DatabaseModule, PluginModule)
├── plugin/                 # 插件框架
│   ├── DashboardPlugin.kt  # 插件接口
│   ├── PluginManager.kt    # 生命周期 + DataStore 持久化
│   ├── PluginCategory.kt   # 分类枚举
│   ├── ble/                # BleExtensionPlugin
│   └── market/             # MarketPluginInfo, PluginCatalogParser (Gson)
├── ui/
│   ├── dashboard/          # DashboardActivity + 自定义 View
│   ├── settings/           # 设置 + 二级页
│   ├── plugins/            # PluginCenterActivity, BleExtensionActivity
│   ├── pairing/            # 配对向导
│   └── splash/             # 启动页
└── util/                   # ThemeManager, LanguageManager 等
                             # ScreenStateTracker, VersionUtils
```

## 新增功能指南

### 新增数据字段

1. 在 `VehicleData` 数据类中添加字段
2. 在 `TeslaBleMessages.parseVehicleStateResponse()` 中解析
3. 在 `TeslaBleProvider.buildEnrichedVehicleData()` 中映射
4. 在 `DashboardActivity.updateUI()` 中显示

### 新增插件 (v0.5.2)

1. 创建实现 `DashboardPlugin` 的类
2. 在 `PluginModule` 中添加 `@Binds @IntoSet` 绑定
3. 实现 `onRegister(context)`、`onEnable()`、`onDisable()`
4. 在 `di/PluginModule` 中注册

### 新增设置项

1. 在 `SettingsRepository` 中添加 key
2. 在对应 Settings Activity 中创建 UI
3. 在 ViewModel 中通过 `stateIn()` 观察

### 新增自定义 View

1. 继承 `View` 类
2. 在 `onDraw()` 中使用 `Paint` 对象绘制
3. 添加到布局 XML
4. 暴露 setter 方法供数据绑定
