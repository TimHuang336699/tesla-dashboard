# Tesla Dashboard 安卓车载仪表盘

[English](README.md)

<p align="center">
  <strong>面向特斯拉车辆的现代化车载仪表盘应用，基于 Kotlin &amp; Jetpack 开发</strong>
</p>

<p align="center">
  中文 | <a href="README.md">English</a>
</p>

---

## 项目简介

Tesla Dashboard 是一款原生 Android 应用，可将您的设备变成特斯拉车辆的实时仪表盘。应用通过**蓝牙低功耗（BLE）直连车辆**——无需云 API、无订阅费——以苹果式简约设计实时展示驾驶数据。

## 功能特性

### 数据源 — Tesla BLE 直连
- **自研 BLE 协议** — 实现 Tesla vehicle-command 蓝牙协议（VCSEC + Infotainment 双域加密会话，ECDH 密钥协商 + AES-GCM）
- **5 秒轮询（v0.4 由 10s 优化）** — 缓存设备地址直连，后续轮询免扫描
- **车速** — 车辆 CAN 总线实时车速（Infotainment 域 DriveState，mph→km/h）
- **电量 SOC / 续航 / 车内车外温度 / 档位 (PRND) / 里程表** — ChargeState / ClimateState / CarState
- **位置 / 航向 / 海拔** — 车辆 GPS 模块
- **导出数据** — 纵/横向加速度（Δv/Δt、v×ω）、合成 G 力、行程里程（里程表差值）、**瞬时电耗（SOC 差值 × 电池容量 / 里程）**
- **门/前备箱/后备箱/锁状态** — 车辆剪影警告指示
- **车辆唤醒** — 每次轮询发送 RKE 唤醒命令

### 配对与安全
- **BLE 配对向导** — 输入 VIN → 生成 ECC 密钥 → 车机中控刷 NFC 卡片确认 → 保存密钥
- **私钥保护（v0.4）** — 私钥经 Android Keystore AES-256-GCM 密钥加密后落盘；旧版明文密钥自动迁移并清除
- **VIN 解码器** — 17 位 VIN 完整解码（车型/代际/电池/工厂/年份），按代际切换 NFC 刷卡位置插图
- **连接测试** — 无需完整轮询即可验证配对有效性

### UI / 交互
- **苹果式简约设计** — 纯黑背景、圆角卡片、System Blue 强调色
- **大数字码表** — Pump 仪表字体、300ms 平滑动画、READY 状态
- **11 套主题** — 跟随系统 / 深色 / 浅色 + 4 款彩色主题 × 深浅，实时切换无需重建
- **多语言** — 中文 / English / 跟随系统
- **单位系统** — 公制 / 英制（速度、距离、温度、电耗）
- **横屏全屏沉浸式** — 针对车载显示优化

### 仪表盘布局
- 顶部栏：档位（PRND）+ 电量与续航
- 中部：车辆剪影（门/舱未关红色警告）
- 右侧：竖向电量仪表
- 底部：连接状态、行程里程、G 力、经纬度、航向、历史/设置按钮
- 可展开详情区（长按设置按钮）：温度、总里程、电量条、**瞬时电耗 kWh/100km（v0.4）**

### 设置（手机设置风格分组，v0.4）
- **车辆** — 蓝牙与车辆（VIN、配对、车型选择、连接测试、解除配对）
- **插件中心** — 已安装插件管理 + 在线市场 (v0.5.2)
- **显示** — 主题选择（11 项）
- **通用** — 单位、语言、**导出诊断日志（直达导出）**
- **关于** — 版本信息 + 日志导出

### 插件系统（v0.5.2）
- **插件框架**: `DashboardPlugin` 接口 + Hilt `Multibinding` 注册机制
- **PluginManager**: 启用状态持久化 (DataStore)、动态启用/停用、失败自动回滚
- **BLE 拓展命令插件**: 充电限值 / 开始·停止充电 / 空调开关与温度 / 充电口 / 低功耗模式

### 插件市场（v0.5.2）
- 从 GitHub 拉取 `plugin-catalog.json`，本地缓存，支持强制刷新
- 在线插件列表、APK 一键下载至私有目录、版本兼容性检查
- 规范文档: `docs/PLUGIN_CATALOG.md`

### 车辆数据读取 — 现代 carserver 协议（v0.5.2）
- 读取: 充电状态 / 额定·估算续航 / 充电电流 / 内外温度 /
  车速 / 功率 / 挡位 / 里程表 / 航向
- 老固件不支持时提示"车辆拒绝执行"

### 省电优化（v0.5.2）
- **ScreenStateTracker**: 熄屏时 BLE 轮询降至 30s，亮屏立即恢复
- 连续失败退避上限 60s；≥6 次连续失败进入深度休眠慢轮询
- 数据刷新频率联动 GNSS 降级定位间隔

### 诊断
- **应用内日志环形缓冲**（500 条）+ 一键导出（FileProvider 分享，微信/邮件均可，无需权限）

## 技术栈

| 类别 | 技术 |
|------|------|
| 开发语言 | Kotlin 100% |
| 架构 | MVVM + Repository 模式 |
| 依赖注入 | Hilt (Dagger) + 插件 Multibinding |
| 数据库 | Room（行程历史，规划中） |
| 异步 | Coroutines + Flow |
| 蓝牙 | 原生 GATT + 自研 Tesla 协议（protobuf wire、ECDH、AES-GCM） |
| 插件系统 | `DashboardPlugin` 接口 + Hilt `@IntoMap` 注册 |
| 安全 | Android Keystore（AES-256-GCM 封装 BLE 私钥） |
| 设置存储 | DataStore Preferences |
| UI 框架 | Material 3 (DayNight) + 自定义 View |
| CI | GitHub Actions（Kotlin lint + test + assemble） |
| 最低 SDK | 26 (Android 8.0) |
| 目标 SDK | 34 (Android 14) |

## 架构设计

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
│  VehicleDataRepository (BLE 透传 + 电耗计算)│
│  TripRepository                             │
│  PluginCatalogRepository (GitHub JSON +    │
│  本地缓存)                                  │
├─────────────────────────────────────────────┤
│              Data Source 层                 │
│  TeslaBleProvider (轮询状态机)              │
│  TeslaBleManager (GATT) · TeslaKeyManager   │
│  TeslaCrypto · TeslaProtobuf · TeslaMessages│
├─────────────────────────────────────────────┤
│              Plugin 层                      │
│  PluginManager (DataStore 持久化)           │
│  DashboardPlugin 接口 · Hilt Module         │
│  BleExtensionPlugin · MarketPluginInfo      │
├─────────────────────────────────────────────┤
│              基础设施                        │
│  Room DB · DataStore · Keystore · Hilt DI   │
│  ScreenStateTracker · VersionUtils          │
└─────────────────────────────────────────────┘
```

## 快速开始

### 环境要求
- Android Studio Hedgehog 或更新版本
- JDK 17+（注意：`gradle.properties` 含本机路径配置，换机器请调整或删除 `org.gradle.java.home` / `android.aapt2FromMavenOverride`）
- Android SDK 34

### 构建

```bash
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# 生成调试 APK
./gradlew assembleDebug
```

### 构建 Release APK（签名）

Release APK 需要签名密钥。密钥**不入库**（`.gitignore` 已排除 `*.jks`）。
克隆后请自行生成：

```bash
# 创建 keystore 目录并生成密钥（密码可自定义，build.gradle.kts 默认值为 tesla123）
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias tesla-dashboard \
  -keypass tesla123 \
  -storepass tesla123 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TeslaDashboard, OU=Dev, O=TeslaDashboard, L=Shenzhen, ST=Guangdong, C=CN"

# 构建签名 APK
./gradlew assembleRelease
# 输出: app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **安全须知**：原 v0.5.2 版本曾误将密钥提交至仓库，已通过 BFG Repo-Cleaner 从历史中彻底清除（含所有旧 commit）。
> 请勿将 `release.jks` 或任何包含密钥的文件提交到 git。

### 运行

1. 用 Android Studio 打开项目
2. 连接 Android 设备（API 26+）或启动模拟器
3. 点击 Run，或执行 `./gradlew installDebug`

### Tesla BLE 配对（实时数据必需）

1. 打开应用，点击右下角**设置**图标
2. **车辆 → 蓝牙与车辆**，输入特斯拉 **VIN**（17 位）
3. 点击**配对车辆**开始 BLE 配对
4. 提示时，将 **NFC 卡片**放在车辆中控台指定位置确认
5. 选择**车型**（用于电池容量查询）
6. 点击**测试连接**验证，然后保存

> BLE 配对需要在车辆附近（约 10 米）且蓝牙已开启。
> 未配对车辆时，仪表盘显示 `--` 占位符。

## 项目结构

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application 类, Hilt 入口
├── data/
│   ├── local/              # Room DB, DAO, SettingsRepository, TripRepository
│   ├── model/              # VehicleData, Trip, TrackPoint, BatteryConfig
│   ├── remote/             # PluginCatalogRepository (GitHub JSON + 本地缓存)
│   └── source/
│       └── ble/            # TeslaBleProvider, TeslaBleManager, TeslaKeyManager,
│                           # TeslaCrypto, TeslaProtobuf, TeslaBleMessages, TeslaBleConstants
├── di/                     # Hilt 模块 (DataSourceModule, DatabaseModule, PluginModule)
├── plugin/                 # 插件框架
│   ├── DashboardPlugin.kt  # 插件接口
│   ├── PluginManager.kt    # 生命周期 + DataStore 持久化
│   ├── PluginCategory.kt   # 分类枚举
│   ├── ble/                # BleExtensionPlugin
│   └── market/             # MarketPluginInfo, PluginCatalogParser (Gson)
├── ui/
│   ├── dashboard/          # DashboardActivity, DashboardViewModel, 自定义 View
│   ├── settings/           # 设置 + 7 个二级页
│   ├── plugins/            # PluginCenterActivity, BleExtensionActivity
│   ├── pairing/            # 配对向导
│   └── splash/             # 启动页 (狐狸 logo 动画)
└── util/                   # ThemeManager, LanguageManager, UnitSystem,
                             # VinDecoder, VinMasker, AppLog, LogExporter,
                             # ScreenStateTracker, VersionUtils
```

## 更新日志

- **v0.5.2** — 插件系统（框架 + BLE 拓展命令插件）；插件市场（在线目录、一键下载 APK、版本兼容性检查）；现代 carserver 协议读取车辆数据；`ScreenStateTracker` 省电优化；插件中心移至设置主页
- **v0.5.0** — BLE 断开时自动降级手机 GNSS（车速/位置/航向，行程里程不中断）；车辆控制面板（解锁/闭锁/前后备箱，BLE 直连命令）；转向灯指示 2.0（矢量箭头 + 特斯拉风格顺序扫描动画 + 主题色联动 + 设置开关）；温度/功率常驻主界面；数据来源状态显示（手机定位降级提示）
- **v0.4.2** — 数据失效保护（过期帧保留数值 + 降透明度提示）；行驶中自适应轮询 2.5s；连续失败指数退避；瞬时功率展示
- **v0.4.1** — 扫描健壮性优化；GATT 竞态修复；车辆公钥固定（防中继/伪装）
- **v0.4.0** — 设置改为手机风格分组（车辆/显示/通用/关于）；设置直达导出日志；仪表盘瞬时电耗展示（kWh/100km）；BLE 私钥迁至 Android Keystore（AES-256-GCM 封装）；BLE 轮询 10s→5s + 设备缓存直连；清理死代码与背景图片资源；README 重写
- **v0.3.5** — 狐狸 logo 重绘 + 1s 启动动画
- **v0.3.0** — 多语言、多单位、分级设置、VIN 遮罩
- **v0.2.x** — 圆环表改为大数字码表、多主题支持、主题下拉含浅色变体
- **v0.1** — 初版：BLE 门锁状态

## 许可

本项目仅用于学习交流。Tesla 是 Tesla, Inc. 的商标。本应用与 Tesla 无任何关联或背书。

---

<p align="center">
  <a href="README.md">English</a>
</p>
