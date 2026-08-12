# 变更记录

[English](CHANGELOG_en.md)

## v0.5.2 (2026-08-11)

### 插件系统 (Plugin System)
- `DashboardPlugin` 接口 + Hilt `Multibinding` 注册机制 (`di/PluginModule`)
- `PluginManager`: 启用状态持久化 (DataStore)、启动时自动注册、动态启用/停用、
  `onRegister` 失败自动回滚、`resetAll()` 重置联动全部插件
- **插件中心 UI** (`PluginCenterActivity`): 分类分组展示插件、实验性标记、
  启用开关；BLE 拓展命令插件点击可进入命令页 (`BleExtensionActivity`)
- **BLE 拓展命令插件** (`ble-extension`): 充电限值 / 开始·停止充电 /
  空调开关与温度 / 充电口 / 低功耗模式；执行状态三态反馈 (成功/车辆拒绝/超时)

### 插件市场 (Plugin Marketplace, v0.5.3 在线化)
- `PluginCatalogRepository`: 从 GitHub 拉取 `plugin-catalog.json`，本地缓存 + 强制刷新
- `MarketPluginInfo` + `PluginCatalogParser` (Gson 解析, 纯 JVM 可单元测试)
- 市场 Tab: 在线插件列表、兼容性版本检查 (`VersionUtils`)、APK 下载至私有目录
  (`filesDir/plugins/<id>.apk`)、下载完成/失败 Toast 反馈
- 外部插件规范文档: `docs/PLUGIN_CATALOG.md`

### 车辆数据读取 (现代 carserver 协议)
- 读取车辆状态: 充电状态 / 额定·估算续航 / 充电电流 / 内外温度 /
  车速 / 功率 / 挡位 / 里程表 / 航向
- 老固件不支持现代协议时提示"车辆拒绝执行"

### 性能与耗电优化
- `ScreenStateTracker`: 熄屏时 BLE 轮询降频至 30s，亮屏立即恢复高频
- 连续失败退避上限提升至 60s；连续失败 ≥6 次视为深度休眠，固定慢轮询等待唤醒
- 数据刷新频率联动 GNSS 降级定位间隔

### 设置页
- 插件中心入口移至设置主页 **车辆分组下方** (独立行, 无分组标题)
- "导出原始数据" 落地实现 (导出诊断日志并分享)
- "重置所有设置" 联动重置插件启用状态

### 工程化
- 版本号 0.5.2 (`versionCode = 20`)
- 单元测试: `TeslaProtobuf` / `TeslaBleMessages` golden bytes、
  `VersionUtilsTest`、`PluginCatalogParserTest` (56 测试全绿)
- GitHub Actions CI 工作流 (`.github/workflows/ci.yml`)

---

## v0.5.1 (2026-08-09)
- 多车管理: 已配对车辆列表、当前车辆切换、按 VIN 解绑、车型独立配置
- 车辆公钥固定校验 (按 VIN, 防中继/伪装)

## v0.5.0 (2026-08-07)
- 车辆控制命令: 解锁 / 锁定 / 前备箱 / 后备箱 (VCSEC 加密通道)
- GNSS 降级数据源 (BLE 失效时手机定位续接行程)
- 设置页重组 (安卓分级结构)、主题色扩展 (森林绿/琥珀橙/午夜紫)
- 行程自动记录与历史

## v0.4.x
- BLE 轮询优化: 缓存直连、行驶中 2.5s 高频轮询、失败指数退避
- 双向温度显示、转向灯指示、语言切换 (中/英)、多主题

## v0.3.x
- 初始版本: BLE 配对 (NFC 确认)、VCSEC/Infotainment 握手、加密通信
- 仪表盘: 车速 / 电量 / 温度 / 里程 / 电耗 / G 力 / 行程
