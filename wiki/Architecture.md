# Architecture

## Overview

Tesla Dashboard follows **MVVM (Model-View-ViewModel)** architecture with **Repository pattern** for clean separation of concerns.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                      UI Layer                            │
│  DashboardActivity · SettingsActivity ·                 │
│  PairingActivity · HistoryActivity ·                    │
│  SplashActivity + Custom Views                          │
├─────────────────────────────────────────────────────────┤
│                   ViewModel Layer                        │
│  DashboardViewModel · SettingsListViewModel              │
│  BleSettingsViewModel · SettingsLightViewModel           │
├─────────────────────────────────────────────────────────┤
│                  Repository Layer                        │
│  VehicleDataRepository (BLE + GNSS merge)                │
│  TripRepository · SettingsRepository                     │
├─────────────────────────────────────────────────────────┤
│                  Data Source Layer                        │
│  TeslaBleProvider (Polling State Machine)                │
│  PhoneGnssProvider (GPS Fallback)                        │
│  TeslaBleManager (GATT) · TeslaKeyManager                │
│  TeslaCrypto · TeslaProtobuf · TeslaBleMessages          │
├─────────────────────────────────────────────────────────┤
│                   Infrastructure                         │
│  Room DB · DataStore · Keystore · Hilt DI                │
└─────────────────────────────────────────────────────────┘
```

## Layer Responsibilities

### UI Layer

| Component | Responsibility |
|-----------|----------------|
| `DashboardActivity` | Main dashboard display, real-time data updates |
| `SettingsActivity` | Settings menu with sub-pages |
| `PairingActivity` | BLE pairing wizard |
| `Custom Views` | `SpeedDisplayView`, `CarSilhouetteView`, `VerticalGaugeView`, `TurnSignalView` |

### ViewModel Layer

| Component | Responsibility |
|-----------|----------------|
| `DashboardViewModel` | Manages vehicle data flow, unit conversion, consumption calculation |
| `SettingsListViewModel` | Settings state management |

### Repository Layer

| Component | Responsibility |
|-----------|----------------|
| `VehicleDataRepository` | Merges BLE + GNSS data, manages fallback logic |
| `SettingsRepository` | DataStore-backed settings persistence |

### Data Source Layer

| Component | Responsibility |
|-----------|----------------|
| `TeslaBleProvider` | BLE polling state machine, vehicle data parsing |
| `PhoneGnssProvider` | Phone GPS fallback when BLE disconnects |
| `TeslaBleManager` | GATT connection, message framing, send/receive |
| `TeslaKeyManager` | Key storage, VIN persistence |
| `TeslaCrypto` | ECDH, AES-GCM, TLV encoding |
| `TeslaProtobuf` | Wire format encoding/decoding |
| `TeslaBleMessages` | Protocol message construction/parsing |

## Data Flow

```
┌─────────────┐     ┌─────────────────┐     ┌──────────────────┐
│   BLE/GNSS  │────▶│  Repository     │────▶│    ViewModel     │
│   Provider  │     │  (merge/filter) │     │  (state/update)  │
└─────────────┘     └─────────────────┘     └──────────────────┘
                                                      │
                                                      ▼
                                              ┌──────────────────┐
                                              │    UI Layer      │
                                              │  (observe/react) │
                                              └──────────────────┘
```

### BLE Data Flow (Polling)

```
1. TeslaBleProvider.observeData() → Flow<VehicleData>
2. Each emission:
   a. Load private key
   b. Scan/connect (cached address or full scan)
   c. VCSEC handshake → Wake vehicle
   d. Infotainment handshake → GetVehicleState
   e. Decrypt response
   f. Parse VehicleState (speed, SOC, temp, etc.)
   g. Unit conversion (mph → km/h, mi → km)
   h. Calculate derived data (acceleration, G-force, trip)
   i. Emit VehicleData
```

### GNSS Fallback Flow

```
1. VehicleDataRepository monitors BLE availability
2. BLE unavailable → Enable PhoneGnssProvider
3. PhoneGnssProvider emits Location-based VehicleData
4. Repository merges: BLE baseline + GNSS increment
5. BLE restored → Disable PhoneGnssProvider
```

## Key Design Patterns

### 1. State Machine (BLE Polling)

`TeslaBleProvider` implements a polling state machine:
- `Idle` → `Scanning` → `Connecting` → `Handshaking` → `Polling` → `Idle`
- Exponential backoff on failures
- Adaptive intervals (2.5s driving, 5s stationary)

### 2. Mutex (Session Safety)

`sessionMutex` ensures only one BLE session runs at a time:
- Prevents polling from interrupting vehicle commands
- Prevents concurrent GATT connections

### 3. Flow (Reactive Data)

All data streams use Kotlin `Flow`:
- `StateFlow` for UI state
- `callbackFlow` for BLE callbacks
- `combine` for merging data sources

### 4. Hilt (Dependency Injection)

```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vehicleDataRepository: VehicleDataRepository,
    settingsRepository: SettingsRepository,
) : ViewModel()
```

## Project Structure

```
app/src/main/java/com/tesla/dashboard/
├── app/                    # Application, Hilt setup
├── data/
│   ├── local/              # Room DB, DAOs, Repositories
│   ├── model/              # Data classes (VehicleData, Trip, etc.)
│   ├── repository/         # VehicleDataRepository
│   └── source/
│       ├── VehicleDataSource.kt
│       ├── ble/            # TeslaBleProvider, Manager, Crypto, etc.
│       └── gnss/           # PhoneGnssProvider
├── di/                     # Hilt modules
├── service/                # TripRecordingService (WIP)
├── ui/
│   ├── dashboard/          # DashboardActivity + Custom Views
│   ├── history/            # HistoryActivity
│   ├── settings/           # Settings + Sub-pages
│   ├── pairing/            # PairingActivity
│   └── splash/             # SplashActivity
└── util/                   # ThemeManager, LanguageManager, etc.
```

## Adding New Features

### New Data Field

1. Add field to `VehicleData` data class
2. Parse in `TeslaBleMessages.parseVehicleStateResponse()`
3. Map in `TeslaBleProvider.buildEnrichedVehicleData()`
4. Display in `DashboardActivity.updateUI()`

### New Setting

1. Add key to `SettingsRepository`
2. Create UI in appropriate Settings Activity
3. Observe in ViewModel via `stateIn()`

### New Custom View

1. Extend `View` class
2. Implement `onDraw()` with `Paint` objects
3. Add to layout XML
4. Expose setter methods for data binding
