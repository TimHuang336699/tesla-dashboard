package com.tesla.dashboard.data.source.ble

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.PrivateKey
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesla BLE 密钥管理器
 *
 * 负责:
 * - 生成 ECC 密钥对 (NIST P-256)
 * - 安全存储/加载密钥对 (DataStore + Base64)
 * - 管理已配对车辆信息 (VIN, 会话信息)
 *
 * 密钥对用于 BLE 认证握手和命令签名。
 *
 * @param context 应用级 Context
 */
@Singleton
class TeslaKeyManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 密钥存储 DataStore */
    private val Context.bleKeyStore by preferencesDataStore(name = "tesla_ble_keys")

    /** 私钥 (PKCS8 Base64) */
    private val KEY_PRIVATE = stringPreferencesKey("private_key_b64")

    /** 公钥 (X509 Base64) */
    private val KEY_PUBLIC = stringPreferencesKey("public_key_b64")

    /** 公钥未压缩格式 (Base64, 用于协议通信) */
    private val KEY_PUBLIC_RAW = stringPreferencesKey("public_key_raw_b64")

    /** 已配对车辆的 VIN */
    private val KEY_PAIRED_VIN = stringPreferencesKey("paired_vin")

    /** 缓存的密钥对 (避免频繁读取 DataStore) */
    @Volatile
    private var cachedPrivateKey: PrivateKey? = null

    @Volatile
    private var cachedPublicKey: PublicKey? = null

    @Volatile
    private var cachedPublicKeyRaw: ByteArray? = null

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
     * 保存密钥对到 DataStore
     *
     * @param privateKey EC 私钥
     * @param publicKey EC 公钥
     */
    suspend fun saveKeyPair(privateKey: PrivateKey, publicKey: PublicKey) {
        val privateKeyBytes = TeslaCrypto.encodePrivateKey(privateKey)
        val publicKeyBytes = publicKey.encoded
        val publicKeyRaw = TeslaCrypto.encodePublicKey(publicKey)

        context.bleKeyStore.edit { prefs ->
            prefs[KEY_PRIVATE] = Base64.encodeToString(privateKeyBytes, Base64.NO_WRAP)
            prefs[KEY_PUBLIC] = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP)
            prefs[KEY_PUBLIC_RAW] = Base64.encodeToString(publicKeyRaw, Base64.NO_WRAP)
        }

        // 更新缓存
        cachedPrivateKey = privateKey
        cachedPublicKey = publicKey
        cachedPublicKeyRaw = publicKeyRaw
    }

    /**
     * 加载已保存的私钥
     *
     * @return 私钥对象，未保存时返回 null
     */
    suspend fun loadPrivateKey(): PrivateKey? {
        cachedPrivateKey?.let { return it }

        val prefs = context.bleKeyStore.data.first()
        val privateKeyB64 = prefs[KEY_PRIVATE] ?: return null

        val privateKeyBytes = Base64.decode(privateKeyB64, Base64.NO_WRAP)
        val privateKey = TeslaCrypto.decodePrivateKey(privateKeyBytes)

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
     * 保存已配对的车辆 VIN
     */
    suspend fun savePairedVin(vin: String) {
        context.bleKeyStore.edit { prefs ->
            prefs[KEY_PAIRED_VIN] = vin.trim()
        }
    }

    /**
     * 加载已配对的车辆 VIN
     *
     * @return VIN 字符串，未配对时返回 null
     */
    suspend fun loadPairedVin(): String? {
        val prefs = context.bleKeyStore.data.first()
        return prefs[KEY_PAIRED_VIN]?.takeIf { it.isNotBlank() }
    }

    /**
     * 检查是否已有密钥对
     */
    suspend fun hasKeyPair(): Boolean = loadPrivateKey() != null

    /**
     * 检查是否已配对车辆
     */
    suspend fun isPaired(): Boolean = !loadPairedVin().isNullOrBlank()

    /**
     * 清除所有密钥和配对信息
     *
     * 用于解绑车辆或重置配对。
     */
    suspend fun clearAll() {
        context.bleKeyStore.edit { it.clear() }
        cachedPrivateKey = null
        cachedPublicKey = null
        cachedPublicKeyRaw = null
    }
}
