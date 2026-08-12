# BLE 鍗忚

[English](BLE-Protocol.md)

## 姒傝堪

Tesla Dashboard 瀹炵幇浜?Tesla 鐨?vehicle-command BLE 鍗忚锛岀敤浜庝笌杞﹁締鐩存帴閫氫俊銆傛湰鏂囨。鎻忚堪鍗忚鐨勫疄鐜扮粏鑺傘€?
## 鍗忚鏍?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?       搴旂敤灞?(Application Layer)     鈹?鈹? VehicleData / VehicleCommand       鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?       鍔犲瘑灞?(Crypto Layer)         鈹?鈹? ECDH + AES-GCM + TLV              鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?       娑堟伅灞?(Message Layer)        鈹?鈹? RoutableMessage / UnsignedMessage  鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?       浼犺緭灞?(Transport Layer)      鈹?鈹? GATT 鐗瑰緛鍊?(TX/RX)                鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

## BLE GATT 鏈嶅姟

| UUID | 鍚嶇О | 鐢ㄩ€?|
|------|------|------|
| `00000211-b2d1-43f0-9b88-960cebf8b91e` | Tesla Service | 涓?BLE 鏈嶅姟 |
| `00000212-b2d1-43f0-9b88-960cebf8b91e` | TX Characteristic | 瀹㈡埛绔?鈫?杞﹁締 |
| `00000213-b2d1-43f0-9b88-960cebf8b91e` | RX Characteristic | 杞﹁締 鈫?瀹㈡埛绔?(Notify) |

## 杞﹁締鍙戠幇

鐗规柉鎷夎溅杈嗛€氳繃 VIN 鐨勫搱甯屽€煎箍鎾叾 BLE 鍚嶇О锛?
```
鏈湴鍚嶇О = "S" + SHA1(VIN)[0:8].hex() + "C"
```

绀轰緥锛歏IN `5YJS0000000000000` 鈫?`S1a87a5a75f3df858C`

## 鍙屽煙鏋舵瀯

鍗忚鍦ㄤ竴涓?GATT 杩炴帴涓婂缓绔嬩袱涓嫭绔嬬殑鍔犲瘑浼氳瘽锛?
### VCSEC 鍩燂紙杞﹁締瀹夊叏锛?
| 鐗规€?| 璇存槑 |
|------|------|
| 鍩?ID | `2` |
| 鐢ㄩ€?| 瀹夊叏鎿嶄綔 |
| 鍛戒护 | 鍞ら啋銆侀攣瀹氥€佽В閿併€佸墠澶囩/鍚庡绠?|

### 淇℃伅濞变箰鍩?
| 鐗规€?| 璇存槑 |
|------|------|
| 鍩?ID | `3` |
| 鐢ㄩ€?| 杞﹁締鏁版嵁 |
| 鍛戒护 | GetVehicleState |

## 娑堟伅鏍煎紡

### RoutableMessage锛堥《灞傦級

```protobuf
message RoutableMessage {
    Destination to_destination = 6;
    Destination from_destination = 7;
    bytes protobuf_message_as_bytes = 10;
    SignatureData signature_data = 13;
    SessionInfoRequest session_info_request = 14;
    SessionInfo session_info = 15;
    bytes uuid = 51;
    uint32 flags = 52;
}
```

### SessionInfoRequest锛堟彙鎵嬶級

```protobuf
message SessionInfoRequest {
    bytes public_key = 1;  // 65 瀛楄妭闈炲帇缂?EC 鐐?}
```

### SessionInfo锛堟彙鎵嬪搷搴旓級

```protobuf
message SessionInfo {
    uint32 counter = 1;
    bytes public_key = 2;  // 杞﹁締 65 瀛楄妭鍏挜
    bytes epoch = 3;       // 16 瀛楄妭 epoch ID
    uint32 clock_time = 4;
}
```

## 鍔犲瘑娴佺▼

### 1. ECDH 瀵嗛挜鍗忓晢

```
瀹㈡埛绔?                       杞﹁締
  鈹?                           鈹?  鈹? SessionInfoRequest        鈹?  鈹? { public_key: client_pub }鈹?  鈹傗攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈻垛攤
  鈹?                           鈹?  鈹? SessionInfo               鈹?  鈹? { public_key: vehicle_pub,鈹?  鈹?   epoch, counter }        鈹?  鈹傗梹鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?  鈹?                           鈹?  shared_key = SHA1(ECDH(client_priv, vehicle_pub))[0:16]
```

### 2. 鍛戒护鍔犲瘑

```
1. 閫掑 counter
2. nonce = epoch[0:8] || counter (4 瀛楄妭澶х搴?
3. 鏋勫缓 TLV AAD:
   - signature_type: AES_GCM_PERSONALIZED (5)
   - domain: 鐩爣鍩?   - personalization: VIN
   - epoch: 浼氳瘽 epoch
   - expires_at: current_time + 10s
   - counter: 褰撳墠 counter
   - flags: 0
4. 鍔犲瘑: AES-GCM(key=shared_key, nonce, plaintext, aad)
5. 鏋勫缓 SignatureData: { epoch, nonce, counter, expires_at, tag }
6. 鍙戦€?RoutableMessage { ciphertext, signature_data }
```

### 3. 鍝嶅簲瑙ｅ瘑

```
1. 浠庡搷搴斾腑鎻愬彇 SignatureData
2. 瑙ｆ瀽 AES_GCM_Personalized_Signature_Data:
   - epoch, nonce, counter, tag
3. 鏋勫缓鍝嶅簲 AAD锛堜笌鍛戒护绫讳技锛?4. 瑙ｅ瘑: AES-GCM(key=shared_key, nonce, ciphertext, tag, aad)
5. 瑙ｆ瀽鏄庢枃 protobuf
```

## 娑堟伅瀹氱晫

BLE 娑堟伅浣跨敤 2 瀛楄妭闀垮害鍓嶇紑杩涜瀹氱晫锛?
```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?闀垮害 (2B)    鈹?杞借嵎                鈹?鈹?澶х搴?      鈹?(protobuf 瀛楄妭)      鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹粹攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

瓒呰繃 MTU 鐨勬秷鎭細鍒嗗潡浼犺緭锛?
```
鍒嗗潡澶у皬 = MTU - ATT_HEADER_SIZE (3)
```

## 杞搴忓垪

```
鈹屸攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?1. 浠?Keystore 鍔犺浇绉侀挜                      鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?2. 杩炴帴锛堢紦瀛樺湴鍧€鎴栨壂鎻忥級                     鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?3. VCSEC 鎻℃墜                               鈹?鈹?   - 鍙戦€?SessionInfoRequest                 鈹?鈹?   - 鎺ユ敹 SessionInfo                        鈹?鈹?   - 璁＄畻鍏变韩瀵嗛挜                             鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?4. 鍞ら啋杞﹁締                                  鈹?鈹?   - RKE_ACTION_WAKE_VEHICLE (30)           鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?5. 淇℃伅濞变箰鎻℃墜                              鈹?鈹?   - 鏂扮殑 SessionInfoRequest                 鈹?鈹?   - 鏂扮殑鍏变韩瀵嗛挜                             鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?6. GetVehicleState                          鈹?鈹?   - 鍙戦€佺┖ Action                           鈹?鈹?   - 鎺ユ敹鍔犲瘑鍝嶅簲                            鈹?鈹?   - 瑙ｅ瘑骞惰В鏋?                             鈹?鈹溾攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?鈹?7. 鏂紑杩炴帴                                  鈹?鈹斺攢鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹?```

## 杞﹁締鎺у埗鍛戒护

### 瑙ｉ攣

```kotlin
RKE_ACTION_UNLOCK = 0
// UnsignedMessage { rke_action: 0 }
```

### 閿佸畾

```kotlin
RKE_ACTION_LOCK = 1
// UnsignedMessage { rke_action: 1 }
```

### 寮€鍚墠澶囩

```kotlin
CLOSURE_FRUNK = 1
CLOSURE_ACTION_OPEN = 2
// UnsignedMessage { closure_move_request: { closure: 1, action: 2 } }
```

### 寮€鍚悗澶囩

```kotlin
CLOSURE_TRUNK = 2
CLOSURE_ACTION_OPEN = 2
// UnsignedMessage { closure_move_request: { closure: 2, action: 2 } }
```

## 鏃跺簭鍙傛暟

| 鍙傛暟 | 鍊?| 璇存槑 |
|------|-----|------|
| 杞闂撮殧 | 5s | 姝ｅ父杞 |
| 琛岄┒涓疆璇?| 2.5s | 杞﹁締绉诲姩鏃?|
| 杩炴帴瓒呮椂 | 10s | GATT 杩炴帴 |
| 鎵弿瓒呮椂 | 15s | BLE 鎵弿 |
| 鍛戒护瓒呮椂 | 5s | 鍗曚釜鍛戒护 |
| NFC 瓒呮椂 | 30s | 閰嶅纭 |
| 鏈€澶ч€€閬?| 30s | 鎸囨暟閫€閬夸笂闄?|

## 閿欒澶勭悊

### 杩炴帴澶辫触

- 缂撳瓨鍦板潃澶辫触 鈫?鍏ㄩ噺鎵弿
- 鎵弿瓒呮椂 鈫?鍙戝皠杩囨湡鏁版嵁
- GATT 鏂紑 鈫?鍙戝皠杩囨湡鏁版嵁

### 鍔犲瘑澶辫触

- 鍏挜涓嶅尮閰?鈫?涓锛堝彲鑳?MITM 鏀诲嚮锛?- 瑙ｅ瘑澶辫触 鈫?璁板綍鏃ュ織骞惰烦杩?- 璁℃暟鍣ㄩ噸鏀?鈫?杞﹁締鎷掔粷

### 鎸囨暟閫€閬?
```
绗?1 娆″皾璇? 5s
绗?2 娆″皾璇? 10s
绗?3 娆″皾璇? 20s
绗?4 娆″強浠ュ悗: 30s锛堜笂闄愶級
```

## 瀹夊叏鐗规€?
1. **ECDH 瀵嗛挜鍗忓晢** 鈥?鍏变韩瀵嗛挜涓嶄細鍦ㄧ綉缁滀腑浼犺緭
2. **AES-GCM 鍔犲瘑** 鈥?128 浣嶅瘑閽ョ殑璁よ瘉鍔犲瘑
3. **璁℃暟鍣ㄩ槻閲嶆斁** 鈥?鍗曡皟閫掑璁℃暟鍣?4. **鍏挜鍥哄畾** 鈥?杞﹁締鍏挜鏈湴瀛樺偍
5. **绉侀挜淇″皝淇濇姢** 鈥?Android Keystore AES-256-GCM 淇濇姢

璇﹁ [瀹夊叏](Security_zh.md)銆?
