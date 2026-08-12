# Tesla Dashboard 开发指南

[English](Home.md)

欢迎查阅 Tesla Dashboard 中文文档！本文档面向开发者和贡献者。

## 目录

- [快速开始](Getting-Started_zh.md)
- [架构设计](Architecture_zh.md)
- [BLE 协议](BLE-Protocol_zh.md)
- [安全](Security_zh.md)
- [自定义 View](Custom-Views_zh.md)
- [常见问题](FAQ_zh.md)
- [贡献指南](Contributing_zh.md)

## 项目简介

Tesla Dashboard 是一款原生 Android 应用，可将您的设备变成特斯拉车辆的实时仪表盘。应用通过**蓝牙低功耗（BLE）直连车辆**——无需云 API、无订阅费——以苹果式简约设计实时展示驾驶数据。

## 主要功能

- **BLE 直连** — 实现 Tesla vehicle-command 蓝牙协议
- **实时数据** — 车速、电量、温度、位置、G 力
- **车辆控制** — 通过 BLE 解锁/闭锁
- **GNSS 降级** — BLE 断开时自动切换手机 GPS
- **11 套主题** — 深色/浅色 + 强调色，实时切换
- **多语言** — 中文 / English
- **多单位** — 公制 / 英制

## 快速链接

- [GitHub 仓库](https://github.com/TimHuang336699/tesla-dashboard)
- [Releases](https://github.com/TimHuang336699/tesla-dashboard/releases)
- [Issues](https://github.com/TimHuang336699/tesla-dashboard/issues)

## 许可

本项目仅用于学习交流。Tesla 是 Tesla, Inc. 的商标。本应用与 Tesla 无任何关联或背书。
