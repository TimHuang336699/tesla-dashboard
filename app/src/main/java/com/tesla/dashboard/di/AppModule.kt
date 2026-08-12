package com.tesla.dashboard.di

import android.content.Context
import com.tesla.dashboard.data.local.AppDatabase
import com.tesla.dashboard.data.local.dao.TripDao
import com.tesla.dashboard.data.source.VehicleDataSource
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.data.source.gnss.PhoneGnssProvider
import com.tesla.dashboard.plugin.security.ApkPluginSandbox
import com.tesla.dashboard.plugin.security.ApkSignatureResult
import com.tesla.dashboard.plugin.security.BleCommandProxy
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块
 *
 * - DataSourceModule: 将 TeslaBleProvider 绑定到 VehicleDataSource 接口 (@Named("tesla") 主数据源),
 *   PhoneGnssProvider 绑定为降级数据源 (@Named("gnss"), v0.5.0 BLE 失效时降级)
 * - DatabaseModule:   提供 Room 数据库和 DAO
 */

// ===== 数据源绑定 =====

@Module
@InstallIn(SingletonComponent::class)
abstract class DataSourceModule {

    @Binds
    @Named("tesla")
    @Singleton
    abstract fun bindTeslaBleProvider(impl: TeslaBleProvider): VehicleDataSource

    @Binds
    @Named("gnss")
    @Singleton
    abstract fun bindPhoneGnssProvider(impl: PhoneGnssProvider): VehicleDataSource
}

// ===== Room 数据库 =====

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides
    fun provideTripDao(db: AppDatabase): TripDao = db.tripDao()
}

// ===== 插件注册 (v0.5.2 插件系统) =====

/**
 * 插件 Multibinding 注册模块
 *
 * 每个内置插件在此用 `@Provides @IntoSet` 注册到 [com.tesla.dashboard.plugin.PluginManager]。
 * 外部插件库 (GitHub `tesla-dashboard-plugins` 的 plugin-catalog.json) 中的
 * 可选插件未来可通过动态加载在此追加绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginModule {

    @Provides
    @IntoSet
    fun bindBleExtensionPlugin(plugin: com.tesla.dashboard.plugin.ble.BleExtensionPlugin): com.tesla.dashboard.plugin.DashboardPlugin = plugin
}

// ===== 插件安全 (v0.6.0) =====

/**
 * 插件安全模块
 *
 * - [BleCommandProxy]: BLE 指令代理 (白名单 + 用户确认 + 优先级调度)
 * - [ApkPluginSandbox]: APK 沙箱加载器 (DexClassLoader 隔离 + 签名验证)
 *
 * 沙箱信任策略: 证书在白名单内直接放行, 自签名证书需用户确认
 * (信任记录由 SettingsRepository 持久化)。
 */
@Module
@InstallIn(SingletonComponent::class)
object PluginSecurityModule {

    @Provides
    @Singleton
    fun provideBleCommandProxy(bleProvider: TeslaBleProvider): BleCommandProxy =
        BleCommandProxy(bleProvider)
    @Provides
    @Singleton
    fun provideApkPluginSandbox(
        @ApplicationContext context: Context,
        settingsRepository: com.tesla.dashboard.data.local.SettingsRepository,
    ): ApkPluginSandbox = ApkPluginSandbox(
        context = context,
        trustChecker = { result ->
            // 白名单直接放行
            if (result is ApkSignatureResult.Trusted) return@ApkPluginSandbox true
            // 自签名: 查询用户已确认的信任记录 (协程包装为同步查询,
            // 沙箱加载在 IO 线程执行, 此处为快速路径)
            if (result is ApkSignatureResult.SelfSigned) {
                return@ApkPluginSandbox kotlinx.coroutines.runBlocking {
                    settingsRepository.getTrustedPluginFingerprints()
                        .contains(result.fingerprintSha256)
                }
            }
            false
        },
    )
}
