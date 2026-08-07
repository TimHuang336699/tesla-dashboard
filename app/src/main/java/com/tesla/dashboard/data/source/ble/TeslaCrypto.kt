package com.tesla.dashboard.data.source.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tesla BLE 协议加密工具
 *
 * 实现 Tesla vehicle-command 协议所需的加密操作:
 * - ECDH 密钥协商 (NIST P-256 / secp256r1)
 * - AES-GCM 加密/解密 (128-bit key)
 * - SHA-1 哈希 (用于共享密钥派生)
 * - TLV 元数据编码 (Tag-Length-Value)
 * - 公钥编解码 (未压缩点格式 0x04|x|y)
 *
 * 基于 Tesla 官方 vehicle-command SDK 的 internal/authentication/ 和 pkg/protocol/ 源码。
 */
object TeslaCrypto {

    // ===== ECDH 密钥协商 =====

    /**
     * 生成新的 ECC 密钥对 (NIST P-256)
     *
     * @return Pair(privateKey, publicKey) 均为 EC 类型
     */
    fun generateKeyPair(): Pair<PrivateKey, PublicKey> {
        val keyPairGen = java.security.KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec(TeslaBleConstants.ECDH_CURVE))
        val keyPair = keyPairGen.generateKeyPair()
        return Pair(keyPair.private, keyPair.public)
    }

    /**
     * 将公钥编码为未压缩点格式
     *
     * 格式: 0x04 || BIG_ENDIAN(x, 32) || BIG_ENDIAN(y, 32) = 65 字节
     *
     * @param publicKey EC 公钥
     * @return 65 字节未压缩点编码
     */
    fun encodePublicKey(publicKey: PublicKey): ByteArray {
        val ecPubKey = publicKey as ECPublicKey
        val w = ecPubKey.w
        val out = ByteArrayOutputStream()
        out.write(0x04)
        out.write(encodeUnsignedBigInt(w.affineX, 32))
        out.write(encodeUnsignedBigInt(w.affineY, 32))
        return out.toByteArray()
    }

    /**
     * 从未压缩点格式解码公钥
     *
     * @param encoded 65 字节未压缩点编码 (0x04 || x || y)
     * @return EC 公钥
     */
    fun decodePublicKey(encoded: ByteArray): PublicKey {
        require(encoded.size == TeslaBleConstants.PUBLIC_KEY_SIZE && encoded[0] == 0x04.toByte()) {
            "Invalid public key format: expected 65 bytes starting with 0x04"
        }
        val x = java.math.BigInteger(1, encoded.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, encoded.copyOfRange(33, 65))
        val point = ECPoint(x, y)

        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(ECPublicKeySpec(point, ecSpec()))
    }

    /**
     * 将 PKCS8 编码的私钥转换为 PrivateKey 对象
     */
    fun decodePrivateKey(pkcs8Encoded: ByteArray): PrivateKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePrivate(PKCS8EncodedKeySpec(pkcs8Encoded))
    }

    /**
     * 将 PrivateKey 编码为 PKCS8 字节数组
     */
    fun encodePrivateKey(privateKey: PrivateKey): ByteArray = privateKey.encoded

    /**
     * 从 X509 编码字节解码公钥
     */
    fun decodePublicKeyX509(x509Encoded: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        return keyFactory.generatePublic(X509EncodedKeySpec(x509Encoded))
    }

    /**
     * ECDH 密钥协商 — 计算共享密钥
     *
     * K = SHA1(BIG_ENDIAN(Sx, 32))[:16]
     *
     * 其中 S = ECDH(privateKey, otherPublicKey), Sx 是共享点的 X 坐标
     *
     * @param privateKey 本端 EC 私钥
     * @param otherPublicKey 对端 EC 公钥 (车辆公钥)
     * @return 16 字节 AES-128 共享密钥
     */
    fun computeSharedKey(privateKey: PrivateKey, otherPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(otherPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // K = SHA1(Sx)[:16]
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest(sharedSecret)
        return digest.copyOf(TeslaBleConstants.SHARED_KEY_SIZE)
    }

    // ===== AES-GCM 加密/解密 =====

    /**
     * AES-GCM 加密
     *
     * @param key 16 字节 AES-128 密钥
     * @param nonce 12 字节 nonce
     * @param plaintext 明文
     * @param aad 关联数据 (Associated Authenticated Data)
     * @return Pair(ciphertext, tag) — tag 为 16 字节认证标签
     */
    fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        // 安全加固: GCM nonce 必须为 12 字节, 尺寸错误立即失败 (v0.4.1)
        require(nonce.size == TeslaBleConstants.NONCE_SIZE) {
            "Invalid nonce size: ${nonce.size} (expected ${TeslaBleConstants.NONCE_SIZE})"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TeslaBleConstants.GCM_TAG_SIZE * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)
        val cipherOutput = cipher.doFinal(plaintext)

        // GCM 输出 = ciphertext || tag
        val tagSize = TeslaBleConstants.GCM_TAG_SIZE
        val ciphertext = cipherOutput.copyOfRange(0, cipherOutput.size - tagSize)
        val tag = cipherOutput.copyOfRange(cipherOutput.size - tagSize, cipherOutput.size)
        return Pair(ciphertext, tag)
    }

    /**
     * AES-GCM 解密
     *
     * @param key 16 字节 AES-128 密钥
     * @param nonce 12 字节 nonce
     * @param ciphertext 密文
     * @param tag 16 字节认证标签
     * @param aad 关联数据
     * @return 明文，若认证失败则抛出异常
     */
    fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        // 安全加固: GCM nonce/tag 尺寸校验, 防止尺寸错误的输入进入解密 (v0.4.1)
        require(nonce.size == TeslaBleConstants.NONCE_SIZE) {
            "Invalid nonce size: ${nonce.size} (expected ${TeslaBleConstants.NONCE_SIZE})"
        }
        require(tag.size == TeslaBleConstants.GCM_TAG_SIZE) {
            "Invalid GCM tag size: ${tag.size} (expected ${TeslaBleConstants.GCM_TAG_SIZE})"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TeslaBleConstants.GCM_TAG_SIZE * 8, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)

        // 合并 ciphertext + tag (Java GCM 要求输入为 ciphertext||tag)
        val combined = ByteBuffer.allocate(ciphertext.size + tag.size)
            .put(ciphertext)
            .put(tag)
            .array()
        return cipher.doFinal(combined)
    }

    // ===== TLV 元数据编码 =====

    /**
     * 编码 TLV (Tag-Length-Value) 元数据
     *
     * Tesla 协议使用 TLV 编码命令元数据作为 AES-GCM 的 AAD。
     * 字段按 Tag 升序排列，末尾追加 0xFF (TAG_END)。
     *
     * Tag 类型和值长度:
     * - TAG_SIGNATURE_TYPE (0): uint32, 4 字节大端
     * - TAG_DOMAIN (1): uint32, 4 字节大端
     * - TAG_PERSONALIZATION (2): bytes (VIN 字符串)
     * - TAG_EPOCH (3): bytes (16 字节)
     * - TAG_EXPIRES_AT (4): fixed32, 4 字节大端
     * - TAG_COUNTER (5): uint32, 4 字节大端
     * - TAG_FLAGS (7): uint32, 4 字节大端
     * - TAG_END (255): 无值
     *
     * @param entries 按 tag 排序的 (tag, value) 对
     * @return TLV 编码的字节数组 (含末尾 0xFF)
     */
    fun encodeTlv(entries: List<Pair<Int, ByteArray>>): ByteArray {
        // 按 tag 升序排序
        val sorted = entries.sortedBy { it.first }
        val out = ByteArrayOutputStream()
        for ((tag, value) in sorted) {
            if (tag == TeslaBleConstants.TAG_END) continue
            // 安全加固: TLV 值长度必须 ≤255 字节, 否则 write() 会静默截断 (v0.4.1)
            require(value.size <= 255) { "TLV value too long: ${value.size} bytes (max 255)" }
            out.write(tag)
            out.write(value.size)
            out.write(value)
        }
        // 追加结束标记
        out.write(TeslaBleConstants.TAG_END)
        return out.toByteArray()
    }

    /**
     * 构造 uint32 大端字节数组
     */
    fun uint32ToBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()

    /**
     * 构造 nonce (12 字节)
     *
     * nonce = epoch[:8] || counter (4 字节大端)
     * (基于协议分析: epoch 为 16 字节，取前 8 字节 + 4 字节 counter)
     */
    fun buildNonce(epoch: ByteArray, counter: Int): ByteArray {
        // 安全加固: epoch 必须 ≥8 字节, 否则越界拷贝 (v0.4.1)
        require(epoch.size >= 8) { "Epoch too short: ${epoch.size} bytes (min 8)" }
        val nonce = ByteArray(TeslaBleConstants.NONCE_SIZE)
        // 取 epoch 前 8 字节
        System.arraycopy(epoch, 0, nonce, 0, 8)
        // counter 4 字节大端
        val counterBytes = uint32ToBytes(counter)
        System.arraycopy(counterBytes, 0, nonce, 8, 4)
        return nonce
    }

    /**
     * 计算命令过期时间戳 (当前时间 + 10 秒)
     */
    fun computeExpiresAt(): Int =
        (System.currentTimeMillis() / 1000 + 10).toInt()

    // ===== 辅助方法 =====

    /**
     * 将 BigInteger 编码为固定长度的无符号大端字节数组
     */
    private fun encodeUnsignedBigInt(value: java.math.BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size == length + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
            bytes.size < length -> {
                val padded = ByteArray(length)
                System.arraycopy(bytes, 0, padded, length - bytes.size, bytes.size)
                padded
            }
            else -> bytes.copyOfRange(bytes.size - length, bytes.size)
        }
    }

    /**
     * 获取 EC 曲线参数规范
     */
    private fun ecSpec(): java.security.spec.ECParameterSpec {
        val keyPairGen = java.security.KeyPairGenerator.getInstance("EC")
        keyPairGen.initialize(ECGenParameterSpec(TeslaBleConstants.ECDH_CURVE))
        return (keyPairGen.generateKeyPair().public as ECPublicKey).params
    }

    /**
     * 将私钥和公钥序列化为可存储的 ByteArray 格式
     *
     * @return Pair(privateKeyBytes, publicKeyBytes)
     */
    fun serializeKeyPair(privateKey: PrivateKey, publicKey: PublicKey): Pair<ByteArray, ByteArray> {
        return Pair(encodePrivateKey(privateKey), publicKey.encoded)
    }
}
