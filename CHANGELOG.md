# 变更记录

[English](CHANGELOG_en.md)

## v0.6.0

### BLE 指令安全代理
- BleCommandProxy: 插件 BLE 指令强制入口, 抽象发送通道, 独立可测
- 指令白名单风险分级: 只读/低风险直接放行, 高风险 (开门/空调/充电等) 须用户确认
- 统一结果模型: Success / Rejected (永不发送) / VehicleError / Failed
- CommandScheduler: 冲突指令严格串行, 高优先级插队 (非抢占), 防指令互相覆盖

### APK 插件沙箱
- DexClassLoader + 独立 ClassLoader 同进程加载, 插件无法访问宿主内部
- manifest meta-data 声明插件入口类, 反射实例化

### 插件签名验证
- 证书 SHA-256 指纹: 白名单 → Trusted 放行; 自签名 → 用户确认后放行; 异常 → 拒绝
- 兼容签名方案 v1/v2/v3

### PluginContext API 增强
- PluginEventBus: 插件间类型化事件发布/订阅, 多订阅者/取消订阅/历史回放

### 安全
- 指令代理/调度/沙箱/签名全链路单元测试覆盖 (纯 JVM)
- AppLog 测试环境自动降级, 生产行为不变

### 工程化
- 版本号 0.6.0 (versionCode = 21)
- 90 个单元测试全绿

---

## v0.5.2

### 插件系统
- 插件框架: DashboardPlugin 接口 + Hilt Multibinding 注册
- PluginManager: 启用状态持久化(DataStore)、自动注册、动态启停、失败回滚
- 插件中心 UI: 分类分组展示、实验性标记、启用开关
- BLE 拓展命令插件: 充电限值、开始/停止充电、空调开关与温度、充电口、低功耗模式

### 插件市场
- 从 GitHub 拉取 plugin-catalog.json，本地缓存 + 强制刷新
- Gson 解析 MarketPluginInfo（纯 JVM，可单元测试）
- 在线插件列表、版本兼容性检查、APK 一键下载
- 外部插件规范文档: docs/PLUGIN_CATALOG.md

### 车辆数据读取
- 读取: 充电状态、额定/估算续航、充电电流、内外温度、车速、功率、挡位、里程表、航向
- 老固件不支持时提示"车辆拒绝执行"

### 省电优化
- 熄屏时 BLE 轮询降至 30s，亮屏立即恢复高频
- 连续失败退避上限 60s；≥6 次连续失败进入深度休眠慢轮询
- 数据刷新频率联动 GNSS 降级定位间隔

### 设置页
- 插件中心移至设置主页车辆分组下方（独立行，无分组标题）
- "导出原始数据"落地实现
- "重置所有设置"联动重置插件启用状态

### 安全
- 修复 keystore 误提交: 已从全部 git 历史清除，用新密钥重新签名

### 工程化
- 版本号 0.5.2 (versionCode = 20)
- 56 个单元测试全绿 (TeslaProtobuf / TeslaBleMessages / VersionUtils / PluginCatalogParser)
- GitHub Actions CI 工作流
- wiki/ 文档: Architecture、BLE Protocol、Getting Started

---

## v0.5.1
- 多车管理: 车辆列表、当前车辆切换、按 VIN 解绑、车型独立配置
- 车辆公钥固定校验 (按 VIN，防中继/伪装)

## v0.5.0
- 车辆控制: 解锁/锁定/前备箱/后备箱 (VCSEC 加密通道)
- GNSS 降级: BLE 断开时用手机 GPS 续接行程数据
- 设置页重组 (安卓分级结构)、主题色扩展 (森林绿/琥珀橙/午夜紫)
- 行程自动记录与历史

## v0.4.x
- BLE 轮询优化: 缓存直连、行驶中 2.5s 高频轮询、失败指数退避
- 双向温度显示、转向灯指示、语言切换 (中/英)、多主题

## v0.3.x
- 初始版本: BLE 配对 (NFC 确认)、VCSEC/Infotainment 握手、加密通信
- 仪表盘: 车速/电量/温度/里程/电耗/G 力/行程
