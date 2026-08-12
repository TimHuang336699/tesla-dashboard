# Getting Started

[中文](Getting-Started_zh.md)

## Prerequisites

- **Android Studio** Hedgehog (or newer)
- **JDK 17+**
- **Android SDK 34**
- **Android device** with API 26+ (Android 8.0+) and Bluetooth

## Installation

### Option 1: Download APK

1. Go to [Releases](https://github.com/TimHuang336699/tesla-dashboard/releases)
2. Download the latest `TeslaDashboard-vX.X.X-release.apk`
3. Install on your Android device (enable "Install from unknown sources" if needed)

### Option 2: Build from Source

```bash
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

> **Note**: `gradle.properties` contains machine-specific paths. Adjust or remove `org.gradle.java.home` and `android.aapt2FromMavenOverride` for your environment.

## Initial Setup

### 1. Launch the App

Open Tesla Dashboard on your device. You'll see the splash screen with the fox logo animation.

### 2. BLE Pairing (Required for Live Data)

Without pairing, the dashboard shows `--` placeholders.

**Steps:**

1. Tap the **Settings** icon (bottom-right corner)
2. Tap **Vehicle → Bluetooth & Vehicle**
3. Enter your Tesla **VIN** (17 characters, found on your registration or driver's side door)
4. Tap **Pair Vehicle**
5. When prompted, tap your **NFC card** on the vehicle's center console
6. Select your **Vehicle Model** (for battery capacity calculation)
7. Tap **Test Connection** to verify
8. Tap **Save**

### 3. Dashboard Usage

- **Speed** — Large digit display (left side)
- **Battery** — SOC percentage and range (top-right)
- **Vehicle Status** — Car silhouette shows door/frunk/trunk states
- **G-Force** — Combined longitudinal + lateral acceleration
- **Trip Distance** — Odometer-based trip tracking

**Long-press Settings** to expand the detail panel showing:
- Inside/outside temperature
- Odometer reading
- Battery progress bar
- Instant energy consumption (kWh/100km)

## Settings Overview

| Menu | Options |
|------|---------|
| **Vehicle** | VIN, Pairing, Model, Test Connection, Unpair |
| **Display** | Theme selection (11 options) |
| **General** | Units, Language, Export Logs |
| **About** | Version, Log Export |

## Troubleshooting

### App shows "--" everywhere

- Ensure Bluetooth is enabled
- Check if BLE pairing was completed
- Verify you're within ~10m of the vehicle
- Try "Test Connection" in settings

### Vehicle not found during pairing

- Wake the vehicle (open a door or press the brake)
- Keep phone close to center console (NFC range)
- Ensure vehicle is not in deep sleep

### Data seems stale

- Check BLE connection status (should show "Connected")
- If showing "GNSS Fallback", BLE is disconnected — using phone GPS
- Try reconnecting via settings
