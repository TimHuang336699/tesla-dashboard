# Tesla Dashboard 插件市场规范 (v0.5.2)

外部可选插件通过 GitHub 仓库 `tesla-dashboard-plugins` 分发。
仓库根目录放置 `plugin-catalog.json` 描述可用插件，本应用后续版本据此加载与展示。

## plugin-catalog.json 格式

```json
{
  "version": 1,
  "plugins": [
    {
      "id": "sentry-mode",
      "name": "Sentry Mode 快捷开关",
      "description": "一键开关哨兵模式（需车辆支持）",
      "version": "1.0.0",
      "category": "ble_command",
      "experimental": true,
      "minAppVersion": "0.5.2",
      "downloadUrl": "https://github.com/tesla-dashboard-plugins/sentry-mode/releases/download/v1.0.0/plugin.apk"
    }
  ]
}
```

## 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `version` | 是 | 目录格式版本号 (当前 1) |
| `plugins[].id` | 是 | 全局唯一插件 ID，与 `DashboardPlugin.id` 对应 |
| `plugins[].name` | 是 | 插件显示名称 |
| `plugins[].description` | 是 | 插件描述 |
| `plugins[].version` | 是 | 插件版本号 |
| `plugins[].category` | 是 | `ble_command` / `utility` |
| `plugins[].experimental` | 否 | 实验性插件标记 (默认 false) |
| `plugins[].minAppVersion` | 否 | 最低应用版本 (语义化版本比较) |
| `plugins[].downloadUrl` | 否 | 插件 APK 下载地址 |

## 插件生命周期

- **内置插件**：随 APK 分发，通过 `di/PluginModule` 的 `@Provides @IntoSet` 注册
- **外部插件**：应用从 `downloadUrl` 下载并校验签名后动态加载
  （`DexClassLoader` + `PluginContext` 沙箱，动态加载仍在安全审计中）

## 分类

- `ble_command`：BLE 车辆命令拓展（对应 `PluginCategory.BLE_COMMAND`）
- `utility`：工具类（对应 `PluginCategory.UTILITY`）

## 变更记录

- v0.5.2：创建目录规范；内置 `ble-extension` 插件（充电/空调/充电口/低功耗命令）
