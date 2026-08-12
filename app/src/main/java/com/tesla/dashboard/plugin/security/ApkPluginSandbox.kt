package com.tesla.dashboard.plugin.security

import android.content.Context
import android.content.pm.PackageManager
import com.tesla.dashboard.plugin.DashboardPlugin
import com.tesla.dashboard.util.AppLog
import dalvik.system.DexClassLoader
import java.io.File
import java.security.cert.Certificate
import java.util.jar.JarFile

/**
 * APK 沙箱加载结果 (v0.6.0)
 */
sealed class SandboxLoadResult {
    /** 加载成功 */
    data class Success(val plugin: DashboardPlugin) : SandboxLoadResult()

    /** 签名验证失败 (白名单外自签名未确认 / 未信任 / 无法解析) */
    data class SignatureRejected(val reason: String) : SandboxLoadResult()

    /** 加载失败 (APK 损坏 / 无插件入口 / 版本不兼容) */
    data class LoadFailed(val reason: String) : SandboxLoadResult()
}

/**
 * APK 插件沙箱加载器 (v0.6.0)
 *
 * 同进程内使用 [DexClassLoader] + 独立 [ClassLoader] 加载外部插件 APK,
 * 实现逻辑隔离:
 *
 * 1. **独立 ClassLoader**: 插件类由专属 DexClassLoader 加载, 不继承
 *    应用 ClassLoader, 插件无法直接访问宿主内部类 (仅能访问
 *    [com.tesla.dashboard.plugin.DashboardPlugin] 接口与系统类),
 *    防止恶意插件窃取宿主数据 / 调用未授权 API
 * 2. **签名强制验证**: 加载前必须通过 [ApkSignatureVerifier] 验证,
 *    自签名证书插件需要用户确认信任 (由调用方持有信任记录)
 * 3. **入口发现**: 插件 APK 的 manifest meta-data `plugin_entry_class`
 *    声明插件类全名, 通过反射实例化
 *
 * @param trustChecker 信任判定 (白名单命中或用户已确认的自签名)
 */
class ApkPluginSandbox(
    private val context: Context,
    private val trustChecker: (ApkSignatureResult) -> Boolean,
) {

    private val TAG = "ApkPluginSandbox"

    /** 已加载插件的 ClassLoader 集合 (用于审计/卸载) */
    private val loadedClassLoaders = mutableSetOf<ClassLoader>()

    /**
     * 从插件 APK 加载插件
     *
     * @param apkFile 插件 APK 文件
     * @param pluginEntryClass 插件入口类全名 (manifest meta-data `plugin_entry_class`)
     * @return 加载结果
     */
    fun loadPlugin(apkFile: File, pluginEntryClass: String): SandboxLoadResult {
        // 1. 提取并验证签名证书
        val certificateDer = extractSigningCertificate(apkFile)
        if (certificateDer == null) {
            return SandboxLoadResult.SignatureRejected("无法提取 APK 签名证书")
        }
        val signatureResult = ApkSignatureVerifier.verify(certificateDer)
        if (signatureResult is ApkSignatureResult.Untrusted) {
            return SandboxLoadResult.SignatureRejected("证书不受信任: ${signatureResult.reason}")
        }
        if (!trustChecker(signatureResult)) {
            return SandboxLoadResult.SignatureRejected(
                "签名未确认: 自签名证书需用户确认信任 (SHA-256: " +
                    (signatureResult as? ApkSignatureResult.SelfSigned)?.fingerprintSha256?.take(16) + "…)",
            )
        }

        // 2. 独立 ClassLoader 加载
        val optimizedDir = context.codeCacheDir
        val loader = try {
            DexClassLoader(
                apkFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                // parent 传 null 级隔离: 仅系统类 + DexClassLoader 自带的
                // 引导加载器链可见, 宿主应用类不可见
                null,
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "DexClassLoader failed: ${e.message}", e)
            return SandboxLoadResult.LoadFailed("ClassLoader 创建失败: ${e.message}")
        }

        // 3. 反射实例化入口类
        val plugin = try {
            val clazz = Class.forName(pluginEntryClass, true, loader)
            if (!DashboardPlugin::class.java.isAssignableFrom(clazz)) {
                return SandboxLoadResult.LoadFailed("入口类未实现 DashboardPlugin 接口")
            }
            @Suppress("UNCHECKED_CAST")
            val instance = clazz.getDeclaredConstructor().newInstance() as DashboardPlugin
            loadedClassLoaders.add(loader)
            instance
        } catch (e: Exception) {
            AppLog.e(TAG, "Plugin instantiation failed: ${e.message}", e)
            return SandboxLoadResult.LoadFailed("插件实例化失败: ${e.message}")
        }
        return SandboxLoadResult.Success(plugin)
    }

    /**
     * 从 APK 提取签名证书 DER
     *
     * 优先使用 v2/v3 签名 (APK Signing Block), 回退到 v1 (JAR 签名)。
     * 注意: v2/v3 签名提取在 Android 平台使用 PackageManager;
     * 此处为纯 JVM 实现 (JAR 签名), 供单元测试与调试使用,
     * Android 平台上通过 [extractViaPackageManager] 获取。
     */
    fun extractSigningCertificate(apkFile: File): ByteArray? {
        // Android 平台: 优先走 PackageManager (v1/v2/v3 全部支持)
        extractViaPackageManager(apkFile)?.let { return it }
        // 纯 JVM 回退: v1 JAR 签名
        return extractV1Certificate(apkFile)
    }

    /**
     * 通过 PackageManager 获取签名证书 (Android 平台, 支持 v1/v2/v3)
     */
    private fun extractViaPackageManager(apkFile: File): ByteArray? {
        return try {
            val info = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNATURES,
            ) ?: return null
            @Suppress("DEPRECATION")
            val signatures = info.signatures ?: return null
            signatures.firstOrNull()?.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 提取 v1 (JAR) 签名证书 — 纯 JVM 实现 (测试友好)
     */
    fun extractV1Certificate(apkFile: File): ByteArray? {
        return try {
            JarFile(apkFile).use { jar ->
                val entry = jar.getJarEntry("META-INF/PLUGIN.SF")
                    ?: return null
                val manifestEntry = jar.getJarEntry("META-INF/MANIFEST.MF")
                val certificates: Array<Certificate>? = entry.certificates
                if (certificates.isNullOrEmpty() && manifestEntry != null) {
                    return null
                }
                certificates?.firstOrNull()?.encoded
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 已加载的 ClassLoader 数量 (审计) */
    val loadedCount: Int get() = loadedClassLoaders.size
}
