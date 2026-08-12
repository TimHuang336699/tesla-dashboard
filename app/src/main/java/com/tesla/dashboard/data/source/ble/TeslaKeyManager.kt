package com.tesla.dashboard.data.source.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tesla.dashboard.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesla BLE 密钥管理器
 *
 * 负责:
 * - 生成 ECC 密钥对 (NIST P-256)
 * - 安全存储/加载密钥对
 * - 迁移旧版单车配对数据到 [com.tesla.dashboard.data.local.VehicleRepository]
 *
 * ## 私钥安全存储 (v0.4 升级)
 *
 * 私钥不再以明文 Base64 存入 DataStore，而是使用 **Android Keystore 封装的 AES-256-GCM
 * 密钥** 进行加密后落盘:
 * - Keystore 中的 AES 密钥不可导出（硬件级保护，root 也无法提取）
 * - 落盘内容 = Base64(nonce(12B) || ciphertext)，无密钥无法解密
 * - 旧版本明文私钥 (private_key_b64) 首次加载时自动迁移并清除明文
 *
 * 密钥对用于 BLE 认证握手和命令签名。
 *
 * @param context 应用级 Context
 */
@Singleton
class TeslaKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val TAG = "TeslaKeyManager"

    /** 密钥存储 DataStore */
    private val Context.bleKeyStore by preferencesDataStore(name = "tesla_ble_keys")

    /** 私钥 (旧版明文 PKCS8 Base64, 仅用于一次性迁移) */
    private val KEY_PRIVATE_LEGACY = stringPreferencesKey("private_key_b64")

    /** 私钥 (AES-256-GCM 加密: Base64(nonce(12B) || ciphertext)) */
    private val KEY_PRIVATE_ENC = stringPreferencesKey("private_key_enc_b64")

    /** 公钥 (X509 Base64) */
    private val KEY_PUBLIC = stringPreferencesKey("public_key_b64")

    /** 公钥未压缩格式 (Base64, 用于协议通信) */
    private val KEY_PUBLIC_RAW = stringPreferencesKey("public_key_raw_b64")

    /** 已配对车辆的 VIN */
    private val KEY_PAIRED_VIN = stringPreferencesKey("paired_vin")

    /**
     * 车辆 VCSEC 公钥 (65 字节未压缩格式, Base64)
     *
     * 配对成功时固定, 之后每次 BLE 握手校验车辆出示的公钥必须一致,
     * 防止中继/伪装设备 (MITM) 冒充车辆 (v0.4.1 安全加固)。
     */
    private val KEY_VEHICLE_PUBLIC_RAW = stringPreferencesKey("vehicle_public_key_raw_b64")

    /** Keystore 中 AES 封装密钥的别名 (不可导出) */
    private val KEYSTORE_ALIAS = "tesla_ble_keystore_aes"

    /** 缓存的密钥对 (避免频繁读取 DataStore/Keystore) */
    @Volatile
    private var cachedPrivateKey: PrivateKey? = null

    @Volatile
    private var cachedPublicKey: PublicKey? = null

    @Volatile
    private var cachedPublicKeyRaw: ByteArray? = null

    /**
     * 获取 Keystore 中的 AES-256-GCM 封装密钥, 不存在时创建。
     */
    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 安全加固: 强制随机 IV, 避免 IV 复用导致 GCM 密钥损坏 (v0.4.1)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * 用 Keystore AES 密钥加密私钥 (PKCS8)
     *
     * @return Base64(nonce(12B) || ciphertext), 无换行
     */
    private fun encryptPrivateKey(pkcs8Bytes: ByteArray): String {
        val key = getOrCreateAesKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(pkcs8Bytes)
        val out = ByteBuffer.allocate(cipher.iv.size + ciphertext.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * 用 Keystore AES 密钥解密私钥
     *
     * @param b64 [encryptPrivateKey] 生成的密文
     * @return PKCS8 私钥字节
     */
    private fun decryptPrivateKey(b64: String): ByteArray {
        val key = getOrCreateAesKey()
        val data = Base64.decode(b64, Base64.NO_WRAP)
        val ivSize = 12 // GCM 默认 nonce 大小
        require(data.size > ivSize) { "Encrypted key data is corrupted" }
        val iv = data.copyOfRange(0, ivSize)
        val ciphertext = data.copyOfRange(ivSize, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * 生成新的 ECC 密钥对
     *
     * 生成后不会自动保存，需调用 [saveKeyPair] 持久化。
     *
     * @return Triple(privateKey, publicKey, publicKeyRaw)
     */
    fun generateKeyPair(): Triple<PrivateKey, PublicKey, ByteArray> {
        val (privateKey, publicKey) = TeslaCrypto.generateKeyPair()
        val publicKeyRaw = TeslaCrypto.encodePublicKey(publicKey)
        return Triple(privateKey, publicKey, publicKeyRaw)
    }

    /**
     * 保存密钥对 (私钥经 Keystore AES-256-GCM 加密后落盘)
     *
     * @param privateKey EC 私钥
     * @param publicKey EC 公钥
     */
    suspend fun saveKeyPair(privateKey: PrivateKey, publicKey: PublicKey) {
        val privateKeyEnc = encryptPrivateKey(TeslaCrypto.encodePrivateKey(privateKey))
        val publicKeyBytes = publicKey.encoded
        val publicKeyRaw = TeslaCrypto.encodePublicKey(publicKey)

        context.bleKeyStore.edit { prefs ->
            prefs[KEY_PRIVATE_ENC] = privateKeyEnc
            prefs.remove(KEY_PRIVATE_LEGACY)
            prefs[KEY_PUBLIC] = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
            prefs[KEY_PUBLIC_RAW] = Base64.encodeToString(publicKeyRaw, Base64.NO_WRAP)
        }

        // 更新缓存
        cachedPrivateKey = privateKey
        cachedPublicKey = publicKey
        cachedPublicKeyRaw = publicKeyRaw
    }

    /**
     * 加载已保存的私钥 (自动迁移旧版明文密钥)
     *
     * @return 私钥对象，未保存时返回 null
     */
    suspend fun loadPrivateKey(): PrivateKey? {
        cachedPrivateKey?.let { return it }

        val prefs = context.bleKeyStore.data.first()

        // 1. 新格式: Keystore 加密的私钥
        prefs[KEY_PRIVATE_ENC]?.let { encB64 ->
            val privateKeyBytes = runCatching { decryptPrivateKey(encB64) }.getOrNull()
            if (privateKeyBytes != null) {
                val privateKey = TeslaCrypto.decodePrivateKey(privateKeyBytes)
                cachedPrivateKey = privateKey
                return privateKey
            }
            // 解密失败(如 Keystore 被清除): 视为无密钥, 由用户重新配对
            AppLog.e(TAG, "Keystore private key decryption FAILED - key unusable, need re-pair")
            return null
        }

        // 2. 旧格式: 明文私钥 → 迁移为加密存储并删除明文
        val legacyB64 = prefs[KEY_PRIVATE_LEGACY] ?: run {
            AppLog.w(TAG, "No private key found (no encrypted, no legacy)")
            return null
        }
        val privateKeyBytes = Base64.decode(legacyB64, Base64.NO_WRAP)
        val privateKey = TeslaCrypto.decodePrivateKey(privateKeyBytes)

        AppLog.d(TAG, "Migrating legacy plaintext private key to Keystore-encrypted storage")
        val encB64 = encryptPrivateKey(privateKeyBytes)
        context.bleKeyStore.edit { p ->
            p[KEY_PRIVATE_ENC] = encB64
            p.remove(KEY_PRIVATE_LEGACY)
        }

        cachedPrivateKey = privateKey
        return privateKey
    }

    /**
     * 加载已保存的公钥 (X509 格式)
     *
     * @return 公钥对象，未保存时返回 null
     */
    suspend fun loadPublicKey(): PublicKey? {
        cachedPublicKey?.let { return it }

        val prefs = context.bleKeyStore.data.first()
        val publicKeyB64 = prefs[KEY_PUBLIC] ?: return null

        val publicKeyBytes = Base64.decode(publicKeyB64, Base64.NO_WRAP)
        val publicKey = TeslaCrypto.decodePublicKeyX509(publicKeyBytes)

        cachedPublicKey = publicKey
        return publicKey
    }

    /**
     * 加载公钥的未压缩点格式 (65 字节, 0x04|x|y)
     *
     * 用于 BLE 握手时发送给车辆。
     *
     * @return 65 字节公钥编码，未保存时返回 null
     */
    suspend fun loadPublicKeyRaw(): ByteArray? {
        cachedPublicKeyRaw?.let { return it }

        val prefs = context.bleKeyStore.data.first()
        val publicKeyRawB64 = prefs[KEY_PUBLIC_RAW] ?: return null

        val publicKeyRaw = Base64.decode(publicKeyRawB64, Base64.NO_WRAP)
        cachedPublicKeyRaw = publicKeyRaw
        return publicKeyRaw
    }

    /**
     * 检查是否已有密钥对
     */
    suspend fun hasKeyPair(): Boolean = loadPrivateKey() != null

    /**
     * 旧版单车配对数据 (v0.5.0 之前)
     *
     * @param vin 已配对的车辆 VIN
     * @param vehiclePublicKeyRaw 固定过的车辆公钥 (65 字节未压缩格式), 旧版本可能缺失
     */
    data class LegacyPairing(
        val vin: String,
        val vehiclePublicKeyRaw: ByteArray?,
    )

    /**
     * 读取并清除旧版单车配对数据 (v0.5.0 多车迁移用)
     *
     * 多车支持 (v0.5.1) 之后, 车辆列表及车辆公钥由
     * [com.tesla.dashboard.data.local.VehicleRepository] 统一管理,
     * 本方法读取旧版 `paired_vin` / `vehicle_public_key_raw_b64` 后立即删除,
     * 供 [com.tesla.dashboard.data.source.ble.TeslaBleProvider.start] 一次性迁移。
     *
     * @return 旧版配对信息, 无旧数据时返回 null
     */
    suspend fun consumeLegacyPairing(): LegacyPairing? {
        val prefs = context.bleKeyStore.data.first()
        val vin = prefs[KEY_PAIRED_VIN]?.takeIf { it.isNotBlank() } ?: return null
        val publicKeyRaw = prefs[KEY_VEHICLE_PUBLIC_RAW]
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
        context.bleKeyStore.edit { p ->
            p.remove(KEY_PAIRED_VIN)
            p.remove(KEY_VEHICLE_PUBLIC_RAW)
        }
        AppLog.d(TAG, "Legacy pairing consumed for migration: VIN=$vin")
        return LegacyPairing(vin, publicKeyRaw)
    }
}
