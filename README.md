# Tesla Dashboard Android

[中文文档](README_zh.md)

<p align="center">
  <strong>A modern car dashboard app for Tesla vehicles, built with Kotlin &amp; Jetpack</strong>
</p>

<p align="center">
  <a href="README_zh.md">中文文档</a> | English
</p>

<p align="center">
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License: GPL v3"></a>
</p>

---

## Overview

Tesla Dashboard is a native Android application that turns your device into a real-time vehicle dashboard for Tesla cars. It connects directly to the vehicle over Bluetooth Low Energy (BLE) — no cloud API, no subscription — and delivers an Apple-inspired minimalist dashboard with live driving data.

## Features

### Data Source — Tesla BLE Direct Connection
- **Direct BLE protocol** — Implements Tesla's vehicle-command BLE protocol (VCSEC + Infotainment dual-domain encrypted sessions, ECDH key agreement + AES-GCM)
- **5s polling** (v0.4, was 10s) — with cached device direct-connect to skip scanning on subsequent polls
- **Speed** — from vehicle CAN bus via Infotainment DriveState (mph → km/h)
- **Battery SOC / range / temperatures / gear (PRND) / odometer** — ChargeState, ClimateState, CarState
- **Position / heading / altitude** — vehicle GPS module
- **Derived values** — longitudinal/lateral acceleration (Δv/Δt, v×ω), combined G-force, trip distance (odometer delta), instant energy consumption (SOC delta × battery capacity / distance)
- **Door/frunk/trunk/lock states** — car silhouette warning indicators
- **Vehicle wake-up** — sends RKE wake command on every poll

### Pairing & Security
- **BLE pairing wizard** — VIN input → ECC key generation → NFC card confirmation on the center console → key save
- **Private key protection (v0.4)** — private key encrypted with an Android Keystore AES-256-GCM key before persisting; legacy plaintext keys auto-migrated and wiped
- **VIN decoder** — 17-digit VIN fully decoded (model / generation / battery / plant / year), NFC location illustration switches by generation
- **Test connection** — verify pairing without full polling

### UI / UX
- **Apple-style minimalist design** — pure dark background, rounded cards, System Blue accent
- **Large digit speedometer** — Pump gauge typeface, smooth 300ms value animation, READY state
- **11 theme options** — system / dark / light + 4 accent themes × dark/light, applied live without Activity restart
- **Multi-language** — 中文 / English / follow system
- **Unit system** — metric / imperial (speed, distance, temperature, energy consumption)
- **Full-screen landscape immersive mode** — optimized for in-car display

### Dashboard Details
- Top bar: gear (PRND) + SOC & range
- Center: vehicle silhouette with door/frunk/trunk warnings
- Right: vertical battery gauge
- Bottom: connection status, trip distance, G-force, coordinates, heading, History / Settings buttons
- Expandable detail panel (long-press Settings): inside/outside temperature, odometer, battery bar, **instant consumption kWh/100km (v0.4)**

### Settings (phone-style grouped menus, v0.4)
- **Vehicle** — Bluetooth & Vehicle (VIN, pairing, model selection, connection test, unpair)
- **Plugin Center** — installed plugins (on/off toggle), online marketplace (v0.5.2)
- **Display** — theme selection (11 options)
- **General** — units, language, **export diagnostic logs (direct action)**
- **About** — version info + log export

### Plugin System (v0.5.2)
- **Plugin framework**: `DashboardPlugin` interface, Hilt `Multibinding`, `PluginManager` with DataStore persistence
- **BLE Extension Plugin**: charge limit / start-stop / AC temp / charge port / low-power mode
- **Plugin Marketplace**: fetches `plugin-catalog.json` from GitHub, one-tap APK download, version compatibility check

### Vehicle Data (Modern carserver Protocol, v0.5.2)
- Read: charge state / rated & estimated range / charge current / in-outside temp / speed / power / gear / odometer / heading
- Fall back to "vehicle rejected" on old firmware without modern protocol support

### Power Optimization (v0.5.2)
- **ScreenStateTracker**: BLE polling throttles to 30s when screen off, resumes immediately on wake
- Backoff cap raised to 60s; deep-sleep slow polling after ≥6 consecutive failures
- Data refresh interval synced with GNSS fallback rate

### Diagnostics
- **In-app log ring buffer** (500 entries) + one-tap export via FileProvider share (WeChat/email, no permission needed)

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 100% |
| Architecture | MVVM + Repository pattern |
| Dependency Injection | Hilt (Dagger) with plugin Multibinding |
| Database | Room (trip history, WIP) |
| Async | Coroutines + Flow |
| BLE | Native GATT + custom Tesla protocol (protobuf wire, ECDH, AES-GCM) |
| Plugin System | `DashboardPlugin` interface + Hilt `@IntoMap` registration |
| Security | Android Keystore (AES-256-GCM envelope for BLE private key) |
| Settings | DataStore Preferences |
| UI | Material 3 (DayNight) + Custom Views |
| CI | GitHub Actions (Kotlin lint + test + assemble) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

## Architecture

```
┌─────────────────────────────────────────────┐
│                  UI Layer                    │
│  DashboardActivity · SettingsActivity ·     │
│  PairingActivity · HistoryActivity ·        │
│  SplashActivity · PluginCenterActivity      │
│  BleExtensionActivity + custom Views        │
├─────────────────────────────────────────────┤
│               ViewModel Layer                │
│  DashboardViewModel · SettingsListViewModel  │
│  BleSettingsViewModel · SettingsLightViewModel│
├─────────────────────────────────────────────┤
│              Repository Layer                │
│  VehicleDataRepository (BLE passthrough +    │
│  consumption calc) · TripRepository          │
│  PluginCatalogRepository (GitHub JSON +     │
│  local cache)                              │
├─────────────────────────────────────────────┤
│              Data Source Layer               │
│  TeslaBleProvider (polling state machine)    │
│  TeslaBleManager (GATT) · TeslaKeyManager    │
│  TeslaCrypto · TeslaProtobuf · TeslaMessages │
├─────────────────────────────────────────────┤
│              Plugin Layer                    │
│  PluginManager (DataStore persistence)       │
│  DashboardPlugin interface · Hilt Module     │
│  BleExtensionPlugin · MarketPluginInfo       │
├─────────────────────────────────────────────┤
│              Infrastructure                  │
│  Room DB · DataStore · Keystore · Hilt DI   │
│  ScreenStateTracker · VersionUtils          │
└─────────────────────────────────────────────┘
```

## Getting Started

### Prerequisites
- Android Studio Hedgehog (or newer)
- JDK 17+ (note: `gradle.properties` contains machine-specific paths — adjust `org.gradle.java.home` / `android.aapt2FromMavenOverride` or remove them for your environment)
- Android SDK 34

### Build

```bash
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# Debug APK
./gradlew assembleDebug
```

### Build Release APK (Signed)

Release APK requires a signing keystore. The keystore is **not committed** (`*.jks` is in `.gitignore`).
Generate it after cloning:

```bash
# Create keystore directory and generate key (password can be customized; defaults in build.gradle.kts are tesla123)
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias tesla-dashboard \
  -keypass tesla123 \
  -storepass tesla123 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TeslaDashboard, OU=Dev, O=TeslaDashboard, L=Shenzhen, ST=Guangdong, C=CN"

# Build signed APK
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

> ⚠️ **Security Note**: The original v0.5.2 release accidentally committed the signing key to the repo.
> It has been purged from all git history using BFG Repo-Cleaner (including all prior commits).
> Never commit `release.jks` or any file containing signing keys.

### Run

1. Open the project in Android Studio
2. Connect an Android device (API 26+) or start an emulator
3. Click **Run** or use `./gradlew installDebug`

### Tesla BLE Pairing (Required for live data)

1. Open the app and tap the **Settings** icon (bottom-right)
2. **Vehicle → Bluetooth & Vehicle**, enter your Tesla **VIN** (17 characters)
3. Tap **Pair Vehicle** to start BLE pairing
4. When prompted, tap your **NFC card** on the vehicle's center console to confirm
5. Select your **Vehicle Model** for battery capacity lookup
6. Tap **Test Connection** to verify, then Save

> BLE pairing requires proximity to the vehicle (~10m) and Bluetooth enabled.
> Without a paired vehicle, the dashboard shows `--` placeholders.

## Project Structure

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application class, Hilt setup
├── data/
│   ├── local/              # Room DB, DAOs, SettingsRepository, TripRepository
│   ├── model/              # VehicleData, Trip, TrackPoint, BatteryConfig
│   ├── remote/             # PluginCatalogRepository (GitHub JSON + cache)
│   └── source/
│       └── ble/            # TeslaBleProvider, TeslaBleManager, TeslaKeyManager,
│                           # TeslaCrypto, TeslaProtobuf, TeslaBleMessages, TeslaBleConstants
├── di/                     # Hilt modules (DataSourceModule, DatabaseModule, PluginModule)
├── plugin/                 # Plugin framework
│   ├── DashboardPlugin.kt  # Plugin interface
│   ├── PluginManager.kt    # Lifecycle + DataStore persistence
│   ├── PluginCategory.kt   # Category enum
│   ├── ble/                # BleExtensionPlugin
│   └── market/             # MarketPluginInfo, PluginCatalogParser (Gson)
├── ui/
│   ├── dashboard/          # DashboardActivity, DashboardViewModel, custom Views
│   ├── settings/           # SettingsActivity + 7 sub-pages
│   ├── plugins/            # PluginCenterActivity, BleExtensionActivity
│   ├── pairing/            # Pairing wizard
│   └── splash/             # SplashActivity (fox logo animation)
└── util/                   # ThemeManager, LanguageManager, UnitSystem,
                             # VinDecoder, VinMasker, AppLog, LogExporter,
                             # ScreenStateTracker, VersionUtils
```

## Changelog

- **v0.5.2** — Plugin system (framework + BLE Extension plugin); Plugin Marketplace (online catalog, one-tap APK download, version compatibility); modern carserver vehicle data read (charge/range/temp/speed/power/gear/odometer); `ScreenStateTracker` power optimization; Plugin Center moved to main Settings page
- **v0.5.0** — GNSS fallback on BLE outage (speed/position/heading, uninterrupted trip distance); vehicle control panel (unlock/lock/frunk/trunk via BLE); turn signal indicators 2.0 (vector arrows, Tesla-style sweep animation, theme colors, settings toggle); persistent temperature/power display; data source status (GNSS fallback badge)
- **v0.4.2** — Stale data protection (keep values + dim on failure); adaptive polling 2.5s while driving; exponential backoff on repeated failures; instant power display
- **v0.4.1** — Scan robustness; GATT race fix; vehicle public key pinning (anti-relay/MITM)
- **v0.4.0** — Phone-style grouped settings (Vehicle/Display/General/About), direct log export from settings, instant consumption display (kWh/100km), BLE private key moved to Android Keystore (AES-256-GCM), BLE polling 10s→5s + cached device direct-connect, dead code & background resource cleanup, README rewrite
- **v0.3.5** — Fox logo redesign + 1s splash animation
- **v0.3.0** — Multi-language, multi-unit, hierarchical settings, VIN masking
- **v0.2.x** — Gauge → large digit speedometer, multi-theme support, theme dropdown with light variants
- **v0.1** — Initial release with BLE door/lock status

## License

This project is for educational purposes. Tesla is a trademark of Tesla, Inc. This app is not affiliated with or endorsed by Tesla.

---

<p align="center">
  <a href="README_zh.md">中文文档</a>
</p>
