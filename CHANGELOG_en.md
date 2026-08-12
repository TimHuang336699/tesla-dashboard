# CHANGELOG

## v0.5.2 (2026-08-12)

### Plugin System
- **Plugin framework**: `DashboardPlugin` interface + Hilt `Multibinding` registration (`di/PluginModule`)
- **PluginManager**: Enable/disable persistence (DataStore), auto-registration on startup, dynamic on/off toggle,
  `onRegister` failure rollback, `resetAll()` cascading reset
- **Plugin Center UI** (`PluginCenterActivity`): Grouped by category, experimental badge, enable/disable switch;
  BLE Extension command plugin opens `BleExtensionActivity` on tap
- **BLE Extension Plugin** (`ble-extension`): Charge limit / Start-Stop charge /
  AC on-off & temperature / charge port / low-power mode; three-state feedback (success/vehicle denied/timeout)

### Plugin Marketplace
- **`PluginCatalogRepository`**: Fetches `plugin-catalog.json` from GitHub, with local cache + force refresh
- **`MarketPluginInfo` + `PluginCatalogParser`**: Gson-based parsing (pure JVM, unit-testable)
- **Market Tab**: Online plugin list, compatibility version check (`VersionUtils`),
  APK download to private dir (`filesDir/plugins/<id>.apk`), download success/failure Toast
- **External Plugin Spec**: `docs/PLUGIN_CATALOG.md`

### Vehicle Data (Modern carserver Protocol)
- Read: charge state / rated & estimated range / charge current / in-outside temp /
  speed / power / gear / odometer / heading
- Fall back to "vehicle rejected" on old firmware without modern protocol support

### Power Optimization
- **`ScreenStateTracker`**: BLE polling throttles to 30s when screen off, resumes immediately on wake
- Backoff cap raised to 60s; deep-sleep slow polling after ≥6 consecutive failures
- Data refresh interval synced with GNSS fallback rate

### Settings Page
- Plugin Center entry moved to **main Settings page, below Vehicle group** (standalone row, no section header)
- "Export Raw Data" implemented (export diagnostic logs & share)
- "Reset All Settings" cascades to reset plugin enable states

### Security
- **Keystore rotation**: Original v0.5.2 release accidentally committed the signing key.
  Purged from all git history via BFG Repo-Cleaner + git-filter-repo.
  Release re-signed with a new key pair.

### Engineering
- Version 0.5.2 (`versionCode = 20`)
- Unit tests: `TeslaProtobuf` / `TeslaBleMessages` golden bytes,
  `VersionUtilsTest`, `PluginCatalogParserTest` (56 tests all green)
- GitHub Actions CI workflow (`.github/workflows/ci.yml`)
- wiki/ docs: Architecture, BLE Protocol, Getting Started

---

## v0.5.1 (2026-08-09)
- Multi-vehicle management: vehicle list, current vehicle switch, VIN-based unpair, per-vehicle config
- Vehicle public key pinning (by VIN, anti-relay/MITM)

## v0.5.0 (2026-08-07)
- Vehicle control commands: unlock / lock / frunk / trunk (VCSEC encrypted channel)
- GNSS fallback data source (phone GPS bridges trip distance when BLE drops)
- Settings page redesign (phone-style grouped menus), theme expansion (forest green / ember orange / midnight purple)
- Automatic trip recording & history

## v0.4.x
- BLE polling optimization: cached direct-connect, 2.5s high-frequency while driving, exponential backoff
- Two-way temperature display, turn signal indicators, language toggle (CN/EN), multi-theme

## v0.3.x
- Initial release: BLE pairing (NFC confirm), VCSEC/Infotainment handshake, encrypted communication
- Dashboard: speed / SOC / temperature / odometer / energy consumption / G-force / trips
