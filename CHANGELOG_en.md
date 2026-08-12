# CHANGELOG

[中文](CHANGELOG.md)

## v0.6.0

### BLE Command Proxy
- BleCommandProxy: mandatory gateway for plugin BLE commands, testable send abstraction
- Risk-classified whitelist: read-only/low-risk pass through; high-risk (unlock/AC/charging) need user confirmation
- Unified result model: Success / Rejected (never sent) / VehicleError / Failed
- CommandScheduler: strictly serial execution of conflicting commands, high-priority queue-jump (non-preemptive)

### APK Plugin Sandbox
- DexClassLoader with isolated ClassLoader; plugins cannot reach host internals
- Plugin entry class declared via manifest meta-data, instantiated via reflection

### Plugin Signature Verification
- SHA-256 cert fingerprint: whitelisted → Trusted; self-signed → after user confirmation; abnormal → refused
- Works across signing schemes v1/v2/v3

### PluginContext API Enhancements
- PluginEventBus: typed event pub/sub between plugins, multi-subscriber/unsubscribe/history replay

### Security
- Full unit-test coverage of proxy/scheduler/sandbox/signature chain (pure JVM)
- AppLog degrades gracefully in test env; production behavior unchanged

### Engineering
- Version 0.6.0 (versionCode = 21)
- 90 unit tests green

---

## v0.5.2

### Plugin System
- Plugin framework: DashboardPlugin interface + Hilt Multibinding registration
- PluginManager: DataStore persistence, auto-registration, dynamic on/off, failure rollback
- Plugin Center UI: grouped by category, experimental badge, enable/disable switch
- BLE Extension Plugin: charge limit, start/stop charge, AC temp, charge port, low-power mode

### Plugin Marketplace
- Fetch plugin-catalog.json from GitHub with local cache + force refresh
- Gson-based MarketPluginInfo parsing (pure JVM, unit-testable)
- Online plugin list, version compatibility check, one-tap APK download
- External plugin spec: docs/PLUGIN_CATALOG.md

### Vehicle Data
- Read: charge state, rated/estimated range, charge current, in/outside temp, speed, power, gear, odometer, heading
- Shows "vehicle rejected" on old firmware without modern protocol support

### Power Optimization
- BLE polling throttles to 30s when screen off, resumes immediately on wake
- Backoff cap raised to 60s; deep-sleep slow polling after 6+ consecutive failures
- Data refresh interval synced with GNSS fallback rate

### Settings Page
- Plugin Center moved to main Settings page, below Vehicle group (standalone row)
- "Export Raw Data" implemented (export diagnostic logs & share)
- "Reset All Settings" cascades to reset plugin enable states

### Security
- Fixed keystore leak: removed from all git history via BFG + git-filter-repo, re-signed with new key pair

### Engineering
- Version 0.5.2 (versionCode = 20)
- 56 unit tests passing (TeslaProtobuf, TeslaBleMessages, VersionUtils, PluginCatalogParser)
- GitHub Actions CI workflow
- wiki/ docs: Architecture, BLE Protocol, Getting Started

---

## v0.5.1
- Multi-vehicle management: vehicle list, current vehicle switch, VIN-based unpair, per-vehicle config
- Vehicle public key pinning (by VIN, anti-relay/MITM)

## v0.5.0
- Vehicle control: unlock/lock/frunk/trunk via VCSEC encrypted channel
- GNSS fallback: phone GPS bridges trip data when BLE disconnects
- Settings redesign (phone-style grouped menus), new themes (forest green / ember orange / midnight purple)
- Automatic trip recording & history

## v0.4.x
- BLE polling optimization: cached direct-connect, 2.5s high-frequency while driving, exponential backoff
- Two-way temperature display, turn signal indicators, language toggle (CN/EN), multi-theme

## v0.3.x
- Initial release: BLE pairing (NFC confirm), VCSEC/Infotainment handshake, encrypted communication
- Dashboard: speed, SOC, temperature, odometer, energy consumption, G-force, trips
