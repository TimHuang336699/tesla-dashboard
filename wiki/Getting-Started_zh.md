# 快速开始

[English](Getting-Started.md)

## 环境要求

- **Android Studio** Hedgehog（或更新版本）
- **JDK 17+**
- **Android SDK 34**
- **Android 设备** API 26+（Android 8.0+）且支持蓝牙

## 安装

### 方式一：下载 APK

1. 访问 [Releases](https://github.com/TimHuang336699/tesla-dashboard/releases)
2. 下载最新的 `TeslaDashboard-vX.X.X-release.apk`
3. 安装到 Android 设备（如需请开启"允许安装未知应用"）

### 方式二：从源码构建

```bash
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# 生成调试 APK
./gradlew assembleDebug

# 生成签名 Release APK
# 注意：keystore 不在仓库中，需自行生成
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias tesla-dashboard \
  -keypass tesla123 -storepass tesla123 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TeslaDashboard, OU=Dev, O=TeslaDashboard"
./gradlew assembleRelease
```

> **注意**：`gradle.properties` 包含本机路径配置。换机器请调整或删除 `org.gradle.java.home` 和 `android.aapt2FromMavenOverride`。

## 初次使用

### 1. 启动应用

在设备上打开 Tesla Dashboard，将看到狐狸 logo 眨眼动画的启动页。

### 2. BLE 配对（实时数据必需）

未完成配对时，仪表盘显示 `--` 占位符。

**步骤：**

1. 点击右下角**设置**图标
2. 进入 **车辆 → 蓝牙与车辆**
3. 输入特斯拉 **VIN**（17 位，可在行驶证或驾驶侧车门框找到）
4. 点击 **配对车辆**
5. 按提示将 **NFC 卡片** 刷在车辆中控台指定位置确认
6. 选择您的 **车型**（用于电池容量查询）
7. 点击 **测试连接** 验证
8. 点击 **保存**

### 3. 仪表盘使用

- **车速** — 大数字显示（左侧）
- **电量** — SOC 百分比与续航（右上角）
- **车辆状态** — 车辆剪影显示车门/前备箱/后备箱状态
- **G 力** — 纵向 + 横向加速度合成
- **行程里程** — 基于里程表的行程追踪

**长按设置**可展开详情面板，显示：
- 车内/外温度
- 总里程
- 电量进度条
- 瞬时电耗（kWh/100km）

## 设置页概览

| 菜单 | 选项 |
|------|------|
| **车辆** | VIN、配对、车型、连接测试、解除配对 |
| **插件中心** | 已安装插件管理 + 在线市场 (v0.5.2) |
| **显示** | 主题选择（11 项） |
| **通用** | 单位、语言、导出日志 |
| **关于** | 版本、日志导出 |

## 常见问题

### 应用各处都显示 "--"

- 确保蓝牙已开启
- 检查 BLE 配对是否完成
- 确认与车辆距离在约 10 米内
- 尝试在设置中点击"测试连接"

### 配对时找不到车辆

- 唤醒车辆（打开车门或踩刹车）
- 手机靠近中控台（NFC 有效范围）
- 确保车辆未处于深度睡眠状态

### 数据看起来过时

- 检查 BLE 连接状态（应显示"已连接"）
- 若显示"GNSS 降级"，说明 BLE 已断开 — 正在使用手机 GPS
- 尝试在设置中重新连接
