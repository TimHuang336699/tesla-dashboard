package com.tesla.dashboard.di

import android.content.Context
import com.tesla.dashboard.data.local.AppDatabase
import com.tesla.dashboard.data.local.dao.TripDao
import com.tesla.dashboard.data.source.VehicleDataSource
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.data.source.gnss.PhoneGnssProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
