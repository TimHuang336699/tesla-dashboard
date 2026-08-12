# 鏋舵瀯璁捐

[English](Architecture_en.md)

## 姒傝堪

Tesla Dashboard 閲囩敤 **MVVM锛圡odel-View-ViewModel锛?* 鏋舵瀯锛岀粨鍚?**Repository 妯″紡**瀹炵幇鑱岃矗鍒嗙銆?
## 鏋舵瀯鍥?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?                 UI 灞?                      鈹?鈹? DashboardActivity 路 SettingsActivity 路     鈹?鈹? PairingActivity 路 HistoryActivity 路        鈹?鈹? SplashActivity 路 PluginCenterActivity      鈹?鈹? BleExtensionActivity + 鑷畾涔?View         鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?              ViewModel 灞?                 鈹?鈹? DashboardViewModel 路 SettingsListViewModel 鈹?鈹? BleSettingsViewModel 路 SettingsLightViewModel鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?             Repository 灞?                 鈹?鈹? VehicleDataRepository (BLE + GNSS 鍚堝苟)    鈹?鈹? TripRepository 路 SettingsRepository        鈹?鈹? PluginCatalogRepository (GitHub JSON +    鈹?鈹? 鏈湴缂撳瓨)                                  鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?             Data Source 灞?                鈹?鈹? TeslaBleProvider (杞鐘舵€佹満)              鈹?鈹? PhoneGnssProvider (GPS 闄嶇骇)               鈹?鈹? TeslaBleManager (GATT) 路 TeslaKeyManager   鈹?鈹? TeslaCrypto 路 TeslaProtobuf 路 TeslaMessages鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?             Plugin 灞?(v0.5.2)             鈹?鈹? PluginManager (DataStore 鎸佷箙鍖?           鈹?鈹? DashboardPlugin 鎺ュ彛 路 Hilt Module         鈹?鈹? BleExtensionPlugin 路 MarketPluginInfo      鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?             鍩虹璁炬柦                        鈹?鈹? Room DB 路 DataStore 路 Keystore 路 Hilt DI   鈹?鈹? ScreenStateTracker 路 VersionUtils          鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

## 鍚勫眰鑱岃矗

### UI 灞?
| 缁勪欢 | 鑱岃矗 |
|------|------|
| `DashboardActivity` | 涓讳华琛ㄧ洏鏄剧ず銆佸疄鏃舵暟鎹洿鏂?|
| `SettingsActivity` | 璁剧疆鑿滃崟锛堝惈浜岀骇椤甸潰锛?|
| `PairingActivity` | BLE 閰嶅鍚戝 |
| `PluginCenterActivity` | 宸插畨瑁呮彃浠?+ 鍦ㄧ嚎甯傚満 (v0.5.2) |
| `BleExtensionActivity` | BLE 鎷撳睍鍛戒护椤?|
| 鑷畾涔?View | `SpeedDisplayView`銆乣CarSilhouetteView`銆乣VerticalGaugeView`銆乣TurnSignalView` |

### ViewModel 灞?
| 缁勪欢 | 鑱岃矗 |
|------|------|
| `DashboardViewModel` | 杞﹁締鏁版嵁娴併€佸崟浣嶈浆鎹€佺數鑰楄绠?|
| `SettingsListViewModel` | 璁剧疆鐘舵€佺鐞?|

### Repository 灞?
| 缁勪欢 | 鑱岃矗 |
|------|------|
| `VehicleDataRepository` | 鍚堝苟 BLE + GNSS 鏁版嵁銆侀檷绾ч€昏緫绠＄悊 |
| `SettingsRepository` | DataStore  backed 璁剧疆鎸佷箙鍖?|
| `PluginCatalogRepository` | 浠?GitHub 鎷夊彇 plugin-catalog.json锛屾湰鍦扮紦瀛?|

### Data Source 灞?
| 缁勪欢 | 鑱岃矗 |
|------|------|
| `TeslaBleProvider` | BLE 杞鐘舵€佹満銆佽溅杈嗘暟鎹В鏋?|
| `PhoneGnssProvider` | BLE 鏂紑鏃舵墜鏈?GPS 闄嶇骇 |
| `TeslaBleManager` | GATT 杩炴帴銆佹秷鎭畾鐣屻€佸彂閫?鎺ユ敹 |
| `TeslaKeyManager` | 瀵嗛挜瀛樺偍銆乂IN 鎸佷箙鍖?|
| `TeslaCrypto` | ECDH銆丄ES-GCM銆乀LV 缂栫爜 |
| `TeslaProtobuf` | Wire 鏍煎紡缂栬В鐮?|
| `TeslaBleMessages` | 鍗忚娑堟伅鏋勫缓/瑙ｆ瀽 |

### Plugin 灞?(v0.5.2)

| 缁勪欢 | 鑱岃矗 |
|------|------|
| `PluginManager` | 鎻掍欢鐢熷懡鍛ㄦ湡銆丏ataStore 鎸佷箙鍖栥€佸惎鐢?鍋滅敤 |
| `DashboardPlugin` | 鎻掍欢鎺ュ彛锛涢€氳繃 Hilt `@IntoMap` 娉ㄥ唽 |
| `BleExtensionPlugin` | BLE 鎷撳睍鍛戒护锛堝厖鐢?绌鸿皟/浣庡姛鑰楋級 |
| `MarketPluginInfo` | 甯傚満鎻掍欢鍏冩暟鎹紙Gson 搴忓垪鍖栵級 |

## 鏁版嵁娴?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?  BLE/GNSS  鈹傗攢鈹€鈹€鈹€鈻垛攤  Repository     鈹傗攢鈹€鈹€鈹€鈻垛攤    ViewModel     鈹?鈹?  Provider  鈹?    鈹? (鍚堝苟/杩囨护)     鈹?    鈹? (鐘舵€?鏇存柊)      鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?    鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                                       鈹?                                                       鈻?                                               鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?                                               鈹?   UI 灞?        鈹?                                               鈹? (瑙傚療/鍝嶅簲)      鈹?                                               鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

### BLE 鏁版嵁娴侊紙杞锛?
```
1. TeslaBleProvider.observeData() 鈫?Flow<VehicleData>
2. 姣忔鍙戝皠:
   a. 鍔犺浇绉侀挜
   b. 鎵弿/杩炴帴锛堢紦瀛樺湴鍧€鎴栧叏閲忔壂鎻忥級
   c. VCSEC 鎻℃墜 鈫?鍞ら啋杞﹁締
   d. 淇℃伅濞变箰鎻℃墜 鈫?GetVehicleState
   e. 瑙ｅ瘑鍝嶅簲
   f. 瑙ｆ瀽 VehicleState锛堣溅閫熴€丼OC銆佹俯搴︾瓑锛?   g. 鍗曚綅杞崲锛坢ph鈫択m/h锛宮i鈫択m锛?   h. 璁＄畻琛嶇敓鏁版嵁锛堝姞閫熷害銆丟鍔涖€佽绋嬶級
   i. 鍙戝皠 VehicleData
```

### GNSS 闄嶇骇娴?
```
1. VehicleDataRepository 鐩戞帶 BLE 鍙敤鎬?2. BLE 涓嶅彲鐢?鈫?鍚敤 PhoneGnssProvider
3. PhoneGnssProvider 鍙戝皠鍩轰簬浣嶇疆鐨?VehicleData
4. Repository 鍚堝苟: BLE 鍩虹嚎 + GNSS 澧為噺
5. BLE 鎭㈠ 鈫?鍋滅敤 PhoneGnssProvider
```

### 鎻掍欢娉ㄥ唽娴?(v0.5.2)

```
1. 搴旂敤鍚姩 鈫?PluginManager.onStart()
2. Hilt 娉ㄥ叆鎵€鏈?@IntoSet DashboardPlugin 瀹炵幇
3. 瀵规瘡涓彃浠? 璋冪敤 plugin.onRegister(context)
4. 鑻?onRegister 鎶涘嚭寮傚父 鈫?鑷姩鍥炴粴锛堟彃浠剁鐢級
5. 鎻掍欢鐘舵€佷繚瀛樺埌 DataStore
6. PluginCenterActivity 瑙傚療 pluginStates Flow
```

## 鏍稿績璁捐妯″紡

### 1. 鐘舵€佹満锛圔LE 杞锛?
`TeslaBleProvider` 瀹炵幇杞鐘舵€佹満锛?- `Idle` 鈫?`Scanning` 鈫?`Connecting` 鈫?`Handshaking` 鈫?`Polling` 鈫?`Idle`
- 澶辫触鏃舵寚鏁伴€€閬?- 鑷€傚簲闂撮殧锛堣椹?2.5s銆侀潤姝?5s銆佺唲灞?30s锛?
### 2. 浜掓枼閿侊紙浼氳瘽瀹夊叏锛?
`sessionMutex` 纭繚鍚屼竴鏃堕棿鍙湁涓€涓?BLE 浼氳瘽锛?- 闃叉杞涓柇杞﹁締鎺у埗鍛戒护
- 闃叉骞跺彂 GATT 杩炴帴

### 3. Flow锛堝搷搴斿紡鏁版嵁锛?
鎵€鏈夋暟鎹祦浣跨敤 Kotlin `Flow`锛?- `StateFlow` 鐢ㄤ簬 UI 鐘舵€?- `callbackFlow` 鐢ㄤ簬 BLE 鍥炶皟
- `combine` 鐢ㄤ簬鍚堝苟澶氭暟鎹簮

### 4. Hilt锛堜緷璧栨敞鍏ワ級

```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val vehicleDataRepository: VehicleDataRepository,
    settingsRepository: SettingsRepository,
) : ViewModel()
```

### 5. 鎻掍欢 Multibinding (v0.5.2)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {
    @Binds
    @IntoSet
    abstract fun bindBleExtension(plugin: BleExtensionPlugin): DashboardPlugin
}
```

## 椤圭洰缁撴瀯

```
app/src/main/java/com/tesla/dashboard/
鈹溾攢鈹€ app/                    # Application 绫? Hilt 鍏ュ彛
鈹溾攢鈹€ data/
鈹?  鈹溾攢鈹€ local/              # Room DB, DAO, Repositories
鈹?  鈹溾攢鈹€ model/              # 鏁版嵁绫?(VehicleData, Trip 绛?
鈹?  鈹溾攢鈹€ remote/             # PluginCatalogRepository (GitHub JSON + 缂撳瓨)
鈹?  鈹斺攢鈹€ source/
鈹?      鈹斺攢鈹€ ble/            # TeslaBleProvider, Manager, Crypto 绛?鈹溾攢鈹€ di/                     # Hilt 妯″潡 (DataSourceModule, DatabaseModule, PluginModule)
鈹溾攢鈹€ plugin/                 # 鎻掍欢妗嗘灦
鈹?  鈹溾攢鈹€ DashboardPlugin.kt  # 鎻掍欢鎺ュ彛
鈹?  鈹溾攢鈹€ PluginManager.kt    # 鐢熷懡鍛ㄦ湡 + DataStore 鎸佷箙鍖?鈹?  鈹溾攢鈹€ PluginCategory.kt   # 鍒嗙被鏋氫妇
鈹?  鈹溾攢鈹€ ble/                # BleExtensionPlugin
鈹?  鈹斺攢鈹€ market/             # MarketPluginInfo, PluginCatalogParser (Gson)
鈹溾攢鈹€ ui/
鈹?  鈹溾攢鈹€ dashboard/          # DashboardActivity + 鑷畾涔?View
鈹?  鈹溾攢鈹€ settings/           # 璁剧疆 + 浜岀骇椤?鈹?  鈹溾攢鈹€ plugins/            # PluginCenterActivity, BleExtensionActivity
鈹?  鈹溾攢鈹€ pairing/            # 閰嶅鍚戝
鈹?  鈹斺攢鈹€ splash/             # 鍚姩椤?鈹斺攢鈹€ util/                   # ThemeManager, LanguageManager 绛?                             # ScreenStateTracker, VersionUtils
```

## 鏂板鍔熻兘鎸囧崡

### 鏂板鏁版嵁瀛楁

1. 鍦?`VehicleData` 鏁版嵁绫讳腑娣诲姞瀛楁
2. 鍦?`TeslaBleMessages.parseVehicleStateResponse()` 涓В鏋?3. 鍦?`TeslaBleProvider.buildEnrichedVehicleData()` 涓槧灏?4. 鍦?`DashboardActivity.updateUI()` 涓樉绀?
### 鏂板鎻掍欢 (v0.5.2)

1. 鍒涘缓瀹炵幇 `DashboardPlugin` 鐨勭被
2. 鍦?`PluginModule` 涓坊鍔?`@Binds @IntoSet` 缁戝畾
3. 瀹炵幇 `onRegister(context)`銆乣onEnable()`銆乣onDisable()`
4. 鍦?`di/PluginModule` 涓敞鍐?
### 鏂板璁剧疆椤?
1. 鍦?`SettingsRepository` 涓坊鍔?key
2. 鍦ㄥ搴?Settings Activity 涓垱寤?UI
3. 鍦?ViewModel 涓€氳繃 `stateIn()` 瑙傚療

### 鏂板鑷畾涔?View

1. 缁ф壙 `View` 绫?2. 鍦?`onDraw()` 涓娇鐢?`Paint` 瀵硅薄缁樺埗
3. 娣诲姞鍒板竷灞€ XML
4. 鏆撮湶 setter 鏂规硶渚涙暟鎹粦瀹?
