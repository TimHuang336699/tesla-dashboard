package com.tesla.dashboard.plugin.security

import java.security.MessageDigest

/**
 * APK 签名验证结果 (v0.6.0)
 *
 * - [Trusted]: 签名证书 SHA-256 指纹命中证书白名单 (可信开发者)
 * - [SelfSigned]: 自签名证书, 不在白名单内 — 需用户确认信任后放行
 * - [Untrusted]: 签名无法解析 / 证书异常 — 直接拒绝加载
 */
sealed class ApkSignatureResult {
    data class Trusted(val fingerprintSha256: String) : ApkSignatureResult()
    data class SelfSigned(val fingerprintSha256: String) : ApkSignatureResult()
    data class Untrusted(val reason: String) : ApkSignatureResult()
}

/**
 * APK 签名验证器 (v0.6.0 插件安全)
 *
 * 职责: 计算 APK 签名证书的 SHA-256 指纹, 并与证书白名单比对。
 *
 * 安全策略:
 * 1. **证书白名单**: 官方 / 受信任第三方开发者证书指纹 → [ApkSignatureResult.Trusted],
 *    直接放行 (签名不可伪造, 指纹唯一标识开发者身份)
 * 2. **自签名**: 不在白名单但结构合法 → [ApkSignatureResult.SelfSigned],
 *    需用户明确确认信任 (UI 弹窗 + 持久化信任记录)
 * 3. **异常**: 证书提取失败 / 非预期格式 → [ApkSignatureResult.Untrusted], 拒绝
 *
 * 指纹算法: SHA-256 (Android 签名方案 v1/v2/v3 证书均为 X.509 证书)
 */
object ApkSignatureVerifier {

    private val TAG = "ApkSignatureVerifier"

    /**
     * 证书白名单: 受信任开发者证书 SHA-256 指纹 (大写十六进制)
     *
     * 内置插件签名者 (与主应用签名证书一致)。第三方开发者申请加入
     * 白名单需通过项目安全流程提交证书指纹。
     */
    val TRUSTED_CERTIFICATE_FINGERPRINTS: Set<String> = setOf(
        // 占位: 发布版签名证书指纹。DEBUG 构建使用 Android Studio 调试证书,
        // 不在此白名单, 以自签名流程由用户确认。
    )

    /**
     * 计算 X.509 证书的 SHA-256 指纹 (RFC 7638 风格: 大写十六进制, 冒号分隔)
     *
     * @param certificateDer 证书 DER 编码字节
     * @return SHA-256 指纹字符串 (如 "AB:CD:...:12"), 或 null 如果证书无效
     */
    fun computeFingerprint(certificateDer: ByteArray): String? {
        if (certificateDer.isEmpty()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(certificateDer)
            digest.joinToString(":") { byte ->
                String.format("%02X", byte)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 验证签名证书指纹
     *
     * @param certificateDer 从 APK 提取的签名证书 DER 字节
     * @return 验证结果
     */
    fun verify(certificateDer: ByteArray): ApkSignatureResult {
        val fingerprint = computeFingerprint(certificateDer)
            ?: return ApkSignatureResult.Untrusted("无法计算证书指纹 (证书为空或格式异常)")
        return when {
            fingerprint in TRUSTED_CERTIFICATE_FINGERPRINTS -> ApkSignatureResult.Trusted(fingerprint)
            // 自签名判定: 证书 Subject 与 Issuer 相同 (IETF X.509 自签名定义)
            isSelfSignedCertificate(certificateDer) -> ApkSignatureResult.SelfSigned(fingerprint)
            else -> ApkSignatureResult.Untrusted("非自签名证书且不在白名单")
        }
    }

    /**
     * 判断证书是否为自签名
     *
     * 轻量判定: 解析 X.509 的 Subject / Issuer 可分辨名称 (RDN) 序列是否一致。
     * 完全解析 X.509 ASN.1 需引入 BouncyCastle, 这里按 RDN 字节序列比较,
     * 足够区分自签名 (Subject==Issuer) 与 CA 签发证书。
     *
     * @param certificateDer 证书 DER 编码字节
     * @return true=自签名
     */
    fun isSelfSignedCertificate(certificateDer: ByteArray): Boolean {
        return try {
            val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(certificateDer)) as java.security.cert.X509Certificate
            cert.subjectX500Principal.name == cert.issuerX500Principal.name
        } catch (e: Exception) {
            false
        }
    }
}
