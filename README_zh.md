# Tesla Dashboard 安卓车载仪表盘

<p align="center">
  <strong>面向特斯拉车辆的现代化车载仪表盘应用，基于 Kotlin &amp; Jetpack 开发</strong>
</p>

<p align="center">
  中文 | <a href="README.md">English</a>
</p>

---

## 项目简介

Tesla Dashboard 是一款原生 Android 应用，可将您的设备变成特斯拉车辆的实时仪表盘。结合 GNSS 定位、加速度传感器和 Tesla Fleet API，以苹果式简约设计提供全面的驾驶体验。

## 功能特性

### 数据源
- **GNSS 定位** — 高精度 GPS 测速（500ms 间隔），位置、航向、海拔、行驶里程
- **加速度传感器** — 纵向/横向加速度及 G 力值
- **Tesla Fleet API** — 电池电量 SOC、续航里程、车内/车外温度、档位（PRND）、里程表（15 秒轮询）

### UI / 交互
- **苹果式简约设计** — 纯黑背景、圆角卡片、System Blue 强调色
- **自定义 SpeedometerView** — 270° 圆弧速度表，带平滑动画过渡
- **日夜主题切换** — 深色、浅色、跟随系统，基于 DataStore 持久化
- **横屏全屏沉浸式** — 针对车载显示优化

### 行程记录
- **Room 数据库** — 本地行程历史，含起止位置、距离、时长、最高速度
- **GPS 轨迹序列化** — 行程中的轨迹点序列化为 JSON 存储
- **前台服务** — 持续记录，带常驻通知保活

### Tesla API 集成
- **设置页面** — 输入 VIN、Access Token，选择区域（中国区/全球/欧洲）
- **动态电池容量** — 根据车型自动获取电池容量，精确计算电耗
- **连接测试** — 保存前验证 Tesla API 凭据
- **优雅降级** — 仅用 GNSS + 传感器即可完整运行，Tesla API 为可选

## 技术栈

| 类别 | 技术 |
|------|------|
| 开发语言 | Kotlin 100% |
| 架构 | MVVM + Repository 模式 |
| 依赖注入 | Hilt (Dagger) |
| 数据库 | Room |
| 异步 | Coroutines + Flow |
| 网络 | Retrofit + OkHttp + Gson |
| 定位 | FusedLocationProvider (Google Play Services) |
| 设置存储 | DataStore Preferences |
| UI 框架 | Material 3 (DayNight) + 自定义 View |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |

## 架构设计

```
┌─────────────────────────────────────────────┐
│                  UI 层                       │
│  DashboardActivity · SettingsActivity ·      │
│  HistoryActivity · SpeedometerView           │
├─────────────────────────────────────────────┤
│               ViewModel 层                   │
│  DashboardViewModel · SettingsViewModel      │
├─────────────────────────────────────────────┤
│              Repository 层                   │
│  VehicleDataRepository（数据融合）·           │
│  TripRepository · SettingsRepository         │
├─────────────────────────────────────────────┤
│              数据源层                        │
│  GnssProvider · SensorProvider ·             │
│  TeslaBleProvider                            │
├─────────────────────────────────────────────┤
│              基础设施                        │
│  Room DB · DataStore · BLE · Hilt DI         │
└─────────────────────────────────────────────┘
```

### 数据融合策略

`VehicleDataRepository` 使用 Kotlin Flow `combine` 合并三个数据源：

1. **GNSS** 作为基底（车速、位置、航向、里程）
2. **传感器** 叠加加速度和 G 力数据
3. **Tesla BLE** 叠加电池、温度、档位、里程表 — 仅在配对成功时取用

车速始终来自 GNSS（亚秒级延迟），而非 Tesla BLE（10 秒轮询），确保最低延迟。

## 快速开始

### 环境要求
- Android Studio Hedgehog（或更新版本）
- JDK 17
- Android SDK 34

### 编译

```bash
# 克隆仓库
git clone https://github.com/TimHuang336699/tesla-dashboard-android.git
cd tesla-dashboard-android

# 编译 Debug APK
./gradlew assembleDebug
```

### 运行

1. 在 Android Studio 中打开项目
2. 连接 Android 设备（API 26+）或启动模拟器
3. 点击 **Run** 或执行 `./gradlew installDebug`

### Tesla BLE 配对（可选）

1. 打开应用，点击右上角 **设置** 图标
2. 输入特斯拉 **VIN**（17 位）
3. 点击 **配对车辆** 开始 BLE 蓝牙配对
4. 根据提示，在车机中控台上刷 **NFC 卡片** 确认配对
5. 选择 **车型**，用于电池容量查询
6. 点击 **测试连接** 验证，然后 **保存**

> 应用无需 BLE 配对即可完整运行 — 速度、G 力、GPS、行程记录均使用本地传感器。
> BLE 配对需要靠近车辆（约 10 米）并开启蓝牙。

## 项目结构

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application 类，Hilt 初始化
├── data/
│   ├── local/              # Room 数据库、DAO、SettingsRepository、TripRepository
│   ├── model/              # VehicleData、TrackPoint、BatteryConfig、Trip
│   ├── repository/         # VehicleDataRepository、TrackPointCollector
│   └── source/             # VehicleDataSource 接口
│       ├── gnss/           # GnssProvider
│       ├── sensor/         # SensorProvider
│       └── ble/            # TeslaBleProvider（BLE 蓝牙直连）
├── di/                     # Hilt 模块 (DataSourceModule, DatabaseModule)
├── service/                # TripRecordingService (前台服务)
├── ui/
│   ├── dashboard/          # DashboardActivity、DashboardViewModel、SpeedometerView
│   ├── history/            # HistoryActivity、行程列表
│   └── settings/           # SettingsActivity、SettingsViewModel
└── util/                   # ThemeManager
```

## 预览

浏览器预览页面已提供（`tesla-dashboard-preview.html`），用任意浏览器打开即可查看仪表盘 UI 及模拟实时数据效果。

## 许可声明

本项目仅供学习交流。Tesla 是 Tesla, Inc. 的商标。本应用与 Tesla 无关联，未获得 Tesla 认可。

---

<p align="center">
  <a href="README.md">English</a>
</p>
