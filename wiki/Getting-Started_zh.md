# 蹇€熷紑濮?
[English](Getting-Started.md)

## 鐜瑕佹眰

- **Android Studio** Hedgehog锛堟垨鏇存柊鐗堟湰锛?- **JDK 17+**
- **Android SDK 34**
- **Android 璁惧** API 26+锛圓ndroid 8.0+锛変笖鏀寔钃濈墮

## 瀹夎

### 鏂瑰紡涓€锛氫笅杞?APK

1. 璁块棶 [Releases](https://github.com/TimHuang336699/tesla-dashboard/releases)
2. 涓嬭浇鏈€鏂扮殑 `TeslaDashboard-vX.X.X-release.apk`
3. 瀹夎鍒?Android 璁惧锛堝闇€璇峰紑鍚?鍏佽瀹夎鏈煡搴旂敤"锛?
### 鏂瑰紡浜岋細浠庢簮鐮佹瀯寤?
```bash
git clone https://github.com/TimHuang336699/tesla-dashboard.git
cd tesla-dashboard

# 鐢熸垚璋冭瘯 APK
./gradlew assembleDebug

# 鐢熸垚绛惧悕 Release APK
# 娉ㄦ剰锛歬eystore 涓嶅湪浠撳簱涓紝闇€鑷鐢熸垚
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias tesla-dashboard \
  -keypass tesla123 -storepass tesla123 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=TeslaDashboard, OU=Dev, O=TeslaDashboard"
./gradlew assembleRelease
```

> **娉ㄦ剰**锛歚gradle.properties` 鍖呭惈鏈満璺緞閰嶇疆銆傛崲鏈哄櫒璇疯皟鏁存垨鍒犻櫎 `org.gradle.java.home` 鍜?`android.aapt2FromMavenOverride`銆?
## 鍒濇浣跨敤

### 1. 鍚姩搴旂敤

鍦ㄨ澶囦笂鎵撳紑 Tesla Dashboard锛屽皢鐪嬪埌鐙愮嫺 logo 鐪ㄧ溂鍔ㄧ敾鐨勫惎鍔ㄩ〉銆?
### 2. BLE 閰嶅锛堝疄鏃舵暟鎹繀闇€锛?
鏈畬鎴愰厤瀵规椂锛屼华琛ㄧ洏鏄剧ず `--` 鍗犱綅绗︺€?
**姝ラ锛?*

1. 鐐瑰嚮鍙充笅瑙?*璁剧疆**鍥炬爣
2. 杩涘叆 **杞﹁締 鈫?钃濈墮涓庤溅杈?*
3. 杈撳叆鐗规柉鎷?**VIN**锛?7 浣嶏紝鍙湪琛岄┒璇佹垨椹鹃┒渚ц溅闂ㄦ鎵惧埌锛?4. 鐐瑰嚮 **閰嶅杞﹁締**
5. 鎸夋彁绀哄皢 **NFC 鍗＄墖** 鍒峰湪杞﹁締涓帶鍙版寚瀹氫綅缃‘璁?6. 閫夋嫨鎮ㄧ殑 **杞﹀瀷**锛堢敤浜庣數姹犲閲忔煡璇級
7. 鐐瑰嚮 **娴嬭瘯杩炴帴** 楠岃瘉
8. 鐐瑰嚮 **淇濆瓨**

### 3. 浠〃鐩樹娇鐢?
- **杞﹂€?* 鈥?澶ф暟瀛楁樉绀猴紙宸︿晶锛?- **鐢甸噺** 鈥?SOC 鐧惧垎姣斾笌缁埅锛堝彸涓婅锛?- **杞﹁締鐘舵€?* 鈥?杞﹁締鍓奖鏄剧ず杞﹂棬/鍓嶅绠?鍚庡绠辩姸鎬?- **G 鍔?* 鈥?绾靛悜 + 妯悜鍔犻€熷害鍚堟垚
- **琛岀▼閲岀▼** 鈥?鍩轰簬閲岀▼琛ㄧ殑琛岀▼杩借釜

**闀挎寜璁剧疆**鍙睍寮€璇︽儏闈㈡澘锛屾樉绀猴細
- 杞﹀唴/澶栨俯搴?- 鎬婚噷绋?- 鐢甸噺杩涘害鏉?- 鐬椂鐢佃€楋紙kWh/100km锛?
## 璁剧疆椤垫瑙?
| 鑿滃崟 | 閫夐」 |
|------|------|
| **杞﹁締** | VIN銆侀厤瀵广€佽溅鍨嬨€佽繛鎺ユ祴璇曘€佽В闄ら厤瀵?|
| **鎻掍欢涓績** | 宸插畨瑁呮彃浠剁鐞?+ 鍦ㄧ嚎甯傚満 (v0.5.2) |
| **鏄剧ず** | 涓婚閫夋嫨锛?1 椤癸級 |
| **閫氱敤** | 鍗曚綅銆佽瑷€銆佸鍑烘棩蹇?|
| **鍏充簬** | 鐗堟湰銆佹棩蹇楀鍑?|

## 甯歌闂

### 搴旂敤鍚勫閮芥樉绀?"--"

- 纭繚钃濈墮宸插紑鍚?- 妫€鏌?BLE 閰嶅鏄惁瀹屾垚
- 纭涓庤溅杈嗚窛绂诲湪绾?10 绫冲唴
- 灏濊瘯鍦ㄨ缃腑鐐瑰嚮"娴嬭瘯杩炴帴"

### 閰嶅鏃舵壘涓嶅埌杞﹁締

- 鍞ら啋杞﹁締锛堟墦寮€杞﹂棬鎴栬俯鍒硅溅锛?- 鎵嬫満闈犺繎涓帶鍙帮紙NFC 鏈夋晥鑼冨洿锛?- 纭繚杞﹁締鏈浜庢繁搴︾潯鐪犵姸鎬?
### 鏁版嵁鐪嬭捣鏉ヨ繃鏃?
- 妫€鏌?BLE 杩炴帴鐘舵€侊紙搴旀樉绀?宸茶繛鎺?锛?- 鑻ユ樉绀?GNSS 闄嶇骇"锛岃鏄?BLE 宸叉柇寮€ 鈥?姝ｅ湪浣跨敤鎵嬫満 GPS
- 灏濊瘯鍦ㄨ缃腑閲嶆柊杩炴帴

