# Tesla Dashboard Android

<p align="center">
  <strong>A modern car dashboard app for Tesla vehicles, built with Kotlin &amp; Jetpack</strong>
</p>

<p align="center">
  <a href="README_zh.md">中文文档</a> | English
</p>

---

## Overview

Tesla Dashboard is a native Android application that turns your device into a real-time vehicle dashboard for Tesla cars. It combines GNSS positioning, accelerometer sensors, and the Tesla Fleet API to deliver a comprehensive driving experience with an Apple-inspired minimalist design.

## Features

### Data Sources
- **GNSS Provider** — High-precision GPS speed measurement (500ms interval), position, heading, altitude, and trip distance
- **Sensor Provider** — Accelerometer for longitudinal/lateral acceleration and G-force
- **Tesla Fleet API** — Battery SOC, range, inside/outside temperature, gear position (PRND), and odometer (15s polling)

### UI / UX
- **Apple-style minimalist design** — Pure black background, rounded cards, System Blue accent
- **Custom SpeedometerView** — 270° animated arc speedometer with smooth value transitions
- **Day/Night theme switching** — Dark, light, or follow system, powered by DataStore
- **Full-screen landscape immersive mode** — Optimized for in-car display

### Trip Recording
- **Room database** — Local trip history with start/end positions, distance, duration, max speed
- **GPS trajectory serialization** — Track points serialized as JSON for each trip
- **Foreground service** — Continuous recording with persistent notification

### Tesla API Integration
- **Settings page** — Input VIN, Access Token, and select region (CN/Global/EU)
- **Dynamic battery capacity** — Automatically fetch battery capacity based on vehicle model for accurate energy consumption calculation
- **Connection testing** — Verify Tesla API credentials before saving
- **Graceful degradation** — App fully functional with GNSS+Sensor only; Tesla API is optional

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 100% |
| Architecture | MVVM + Repository pattern |
| DI | Hilt (Dagger) |
| Database | Room |
| Async | Coroutines + Flow |
| Networking | Retrofit + OkHttp + Gson |
| Location | FusedLocationProvider (Google Play Services) |
| Settings | DataStore Preferences |
| UI | Material 3 (DayNight) + Custom Views |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

## Architecture

```
┌─────────────────────────────────────────────┐
│                  UI Layer                    │
│  DashboardActivity · SettingsActivity ·      │
│  HistoryActivity · SpeedometerView           │
├─────────────────────────────────────────────┤
│               ViewModel Layer                │
│  DashboardViewModel · SettingsViewModel      │
├─────────────────────────────────────────────┤
│              Repository Layer                │
│  VehicleDataRepository (data fusion) ·       │
│  TripRepository · SettingsRepository         │
├─────────────────────────────────────────────┤
│              Data Source Layer               │
│  GnssProvider · SensorProvider ·             │
│  TeslaBleProvider                            │
├─────────────────────────────────────────────┤
│              Infrastructure                  │
│  Room DB · DataStore · BLE · Hilt DI         │
└─────────────────────────────────────────────┘
```

### Data Fusion Strategy

The `VehicleDataRepository` merges three data sources using Kotlin Flow `combine`:

1. **GNSS** is the base layer (speed, position, heading, distance)
2. **Sensor** overlays acceleration and G-force data
3. **Tesla BLE** overlays battery, temperature, gear, and odometer — only when paired

Speed data always comes from GNSS (sub-second latency) rather than Tesla BLE (10s polling) to ensure minimal delay.

## Getting Started

### Prerequisites
- Android Studio Hedgehog (or newer)
- JDK 17
- Android SDK 34

### Build

```bash
# Clone the repository
git clone https://github.com/TimHuang336699/tesla-dashboard-android.git
cd tesla-dashboard-android

# Build debug APK
./gradlew assembleDebug
```

### Run

1. Open the project in Android Studio
2. Connect an Android device (API 26+) or start an emulator
3. Click **Run** or use `./gradlew installDebug`

### Tesla BLE Pairing (Optional)

1. Open the app and tap the **Settings** icon (top-right)
2. Enter your Tesla **VIN** (17 characters)
3. Tap **Pair Vehicle** to start BLE pairing
4. When prompted, tap your **NFC card** on the vehicle's center console to confirm
5. Select your **Vehicle Model** for battery capacity lookup
6. Tap **Test Connection** to verify, then **Save**

> The app works fully without BLE pairing — speed, G-force, GPS, and trip recording use local sensors only.
> BLE pairing requires proximity to the vehicle (~10m) and Bluetooth enabled.

## Project Structure

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application class, Hilt setup
├── data/
│   ├── local/              # Room DB, DAOs, SettingsRepository, TripRepository
│   ├── model/              # VehicleData, TrackPoint, BatteryConfig, Trip
│   ├── repository/         # VehicleDataRepository, TrackPointCollector
│   └── source/             # VehicleDataSource interface
│       ├── gnss/           # GnssProvider
│       ├── sensor/         # SensorProvider
│       └── ble/            # TeslaBleProvider (BLE direct connection)
├── di/                     # Hilt modules (DataSourceModule, DatabaseModule)
├── service/                # TripRecordingService (foreground)
├── ui/
│   ├── dashboard/          # DashboardActivity, DashboardViewModel, SpeedometerView
│   ├── history/            # HistoryActivity, trip list
│   └── settings/           # SettingsActivity, SettingsViewModel
└── util/                   # ThemeManager
```

## Preview

A browser-based HTML preview is available (`tesla-dashboard-preview.html`). Open it in any browser to see the dashboard UI with simulated real-time data.

## License

This project is for educational purposes. Tesla is a trademark of Tesla, Inc. This app is not affiliated with or endorsed by Tesla.

---

<p align="center">
  <a href="README_zh.md">中文文档</a>
</p>
