# Plugin Catalog Specification (v0.5.2)

External optional plugins are distributed via the GitHub repository `tesla-dashboard-plugins`.
The repository root contains `plugin-catalog.json` describing available plugins;
the app loads and displays them in subsequent versions.

> **Note**: `DexClassLoader` + `PluginContext` sandbox; dynamic loading is under security review.

## Plugin Categories

- `ble_command`: BLE vehicle command extensions (maps to `PluginCategory.BLE_COMMAND`)
- `utility`: Utility plugins (maps to `PluginCategory.UTILITY`)

## plugin-catalog.json Format

```json
{
  "version": 1,
  "plugins": [
    {
      "id": "sentry-mode",
      "name": "Sentry Mode Quick Toggle",
      "description": "One-tap toggle for Sentry Mode (vehicle support required)",
      "version": "1.0.0",
      "category": "ble_command",
      "experimental": true,
      "minAppVersion": "0.5.2",
      "downloadUrl": "https://github.com/tesla-dashboard-plugins/sentry-mode/releases/download/v1.0.0/plugin.apk"
    }
  ]
}
```

## Field Descriptions

| Field | Required | Description |
|-------|----------|-------------|
| `version` | Yes | Catalog format version (currently 1) |
| `plugins[].id` | Yes | Globally unique plugin ID, corresponds to `DashboardPlugin.id` |
| `plugins[].name` | Yes | Plugin display name |
| `plugins[].description` | Yes | Plugin description |
| `plugins[].version` | Yes | Plugin version (semantic versioning) |
| `plugins[].category` | Yes | `ble_command` / `utility` |
| `plugins[].experimental` | No | Experimental plugin flag (default: false) |
| `plugins[].minAppVersion` | No | Minimum app version (semantic version comparison via `VersionUtils`) |
| `plugins[].downloadUrl` | No | Plugin APK download URL |

## Plugin Lifecycle

- **Built-in plugins**: Distributed with the APK, registered via `@Provides @IntoSet` in `di/PluginModule`
- **External plugins**: App downloads and verifies signature from `downloadUrl`, then dynamically loads via `DexClassLoader`
  (Dynamic loading is under security audit — not enabled by default)

## Changelog

- v0.5.2: Initial catalog specification; built-in `ble-extension` plugin (charge/AC/charge-port/low-power commands)
