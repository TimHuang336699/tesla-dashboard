# Tesla Dashboard — AI Memory

> 本文件记录项目架构、已知问题、开发规范，供 AI 助手参考。

---

## 项目概述

- **应用**: Tesla Dashboard — 安卓车载仪表盘，通过 BLE 直连特斯拉车辆
- **版本**: v0.5.2（versionCode = 20）
- **语言**: Kotlin 100%，无 Java
- **架构**: MVVM + Repository 模式
- **最低 SDK**: 26（Android 8.0）/ **目标 SDK**: 34（Android 14）

## 构建环境

```properties
# gradle.properties 仅保留跨平台配置
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
# JDK 和 SDK 由 GitHub Actions (setup-java) 和 runner 环境自动提供，无需手动指定
```

> **禁止在 `gradle.properties` 中写 Windows 绝对路径**（如 `org.gradle.java.home=C:\...`），CI Runner 是 Linux。

---

## 目录结构

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application 类, Hilt 入口
├── data/
│   ├── local/              # Room DB, DAO, SettingsRepository, TripRepository, VehicleRepository
│   ├── model/              # VehicleData, Trip, TrackPoint, BatteryConfig, VehicleInfo
│   ├── remote/             # PluginCatalogRepository (GitHub JSON + 本地缓存)
│   └── source/ble/         # TeslaBleProvider, Manager, KeyManager, Crypto, Protobuf, Messages, Constants
├── di/                     # Hilt 模块 (DataSourceModule, DatabaseModule, PluginModule)
├── plugin/                 # 插件框架 (v0.5.2)
│   ├── DashboardPlugin.kt  # 插件接口
│   ├── PluginManager.kt    # DataStore 持久化 + 生命周期管理
│   ├── PluginCategory.kt   # 分类枚举
│   ├── ble/                # BleExtensionPlugin
│   └── market/             # MarketPluginInfo, PluginCatalogParser (Gson)
├── ui/
│   ├── dashboard/          # DashboardActivity, DashboardViewModel, 自定义 View
│   ├── settings/           # 设置 + 7 个二级页
│   ├── plugins/            # PluginCenterActivity, BleExtensionActivity
│   ├── pairing/            # 配对向导
│   └── splash/             # 启动页
└── util/                   # ThemeManager, LanguageManager, UnitSystem,
                             # VinDecoder, VinMasker, AppLog, LogExporter,
                             # ScreenStateTracker, VersionUtils
```

---

## 关键架构决策

### 插件系统 (v0.5.2)
- 内置插件通过 Hilt `@IntoSet` 注册，`PluginManager` 启动时自动注册已启用插件
- `onRegister` 失败自动回滚（插件禁用）
- `resetAll()` 联动重置所有插件启用状态
- **动态加载外部 APK（DexClassLoader）仍在安全审计中，未启用**

### BLE 协议
- ECDH 密钥协商 + AES-GCM 加密，双域（VCSEC + Infotainment）
- 轮询状态机: Idle → Scanning → Connecting → Handshaking → Polling → Idle
- 熄屏降频至 30s（`ScreenStateTracker`），亮屏立即恢复高频
- 连续失败 ≥6 次进入深度休眠慢轮询

### 插件市场 (v0.5.3 规划)
- `PluginCatalogRepository`: GitHub `plugin-catalog.json` → 本地缓存
- `PluginCatalogParser`: Gson 解析（纯 JVM，可单元测试）
- APK 下载至 `filesDir/plugins/<id>.apk`
- 版本兼容性检查通过 `VersionUtils.meetsMinimum()`

---

## 已知问题与待办

### Bug / 历史遗留
| 位置 | 问题 | 状态 |
|------|------|------|
| `LanguageSettingsActivity.kt:84` | 历史 bug：切换语言后若未重启 App，设置值不生效 | 已记录注释，待修复 |
| `TeslaBleProvider.kt:1022` | TODO: 需实现 VCSEC 响应式编码解码字段 | 待开发 |

### 代码质量
| 位置 | 问题 | 建议 |
|------|------|------|
| `PluginCenterActivity.kt` | `AppLog` 使用全限定名 | 改为 `import com.tesla.dashboard.util.AppLog` |
| `TeslaBleProvider.kt` | 12 个 catch 块，部分吞异常 | 确认是否都有日志 |
| `DashboardViewModel.kt` | 4 个 catch 块 | 检查错误上报逻辑 |

### 安全
- **keystore/release.jks 已加入 .gitignore**，严禁入库
- 构建 Release APK 前需在本地生成新 keystore（见 README）
- 外部插件动态加载（DexClassLoader）尚未启用，处于安全审计阶段

---

## 文档布局

所有 `.md` 文件均在项目根目录，中文版本不带 `zh` 后缀——命名格式为 `文件名_zh.md`：

```
根目录:
  README.md / README_zh.md
  CHANGELOG.md / CHANGELOG_en.md
  CODE_OF_CONDUCT.md / CODE_OF_CONDUCT_zh.md
  CONTRIBUTING.md / CONTRIBUTING_zh.md
  SECURITY.md / SECURITY_zh.md

wiki/:
  Home.md / Home_zh.md
  Architecture.md / Architecture_en.md / Architecture_zh.md
  BLE-Protocol.md / BLE-Protocol_en.md / BLE-Protocol_zh.md
  Getting-Started.md / Getting-Started_en.md / Getting-Started_zh.md

docs/:
  PLUGIN_CATALOG.md / PLUGIN_CATALOG_en.md
```

> **规则**: 交叉链接使用相对路径（同目录用 `文件名_zh.md`，不同目录用 `../`）。

---

## GitHub Release

- **URL**: https://github.com/TimHuang336699/tesla-dashboard/releases/tag/v0.5.2
- **Title 仅版本号**: `v0.5.2`（不要描述性后缀）
- **Body**: 英文 changelog + `[中文版](CHANGELOG.md)` 链接
- **APK**: `TeslaDashboard-v0.5.2-release.apk`（2.1 MB）

---

## CI / 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| CI 构建失败: `org.gradle.java.home` 无效 | `gradle.properties` 含 Windows 路径 | 删除 `org.gradle.java.home` 和 `android.aapt2FromMavenOverride` |
| `fatal: pathspec 'keystore/release.jks' did not match` | keystore 被 gitignore 阻止入库 | 正常行为，构建时本地生成即可 |
| BFG 清理历史后 tag 丢失 | 删除旧 tag 后需重新推送 | `git tag -f v0.5.2 <commit>` 再 push |

---

## 开发规范

- 提交信息格式: `type(scope): description`（如 `feat: v0.5.2 插件系统`）
- 中文 commit message 可以，CI 和 Release 用英文
- `.md` 文件末尾不要有多余空行
- CHANGELOG 格式：无代码反引号、无 emoji、斜杠分隔改逗号分隔
