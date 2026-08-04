package com.tesla.dashboard.di

import android.content.Context
import com.tesla.dashboard.data.local.AppDatabase
import com.tesla.dashboard.data.local.dao.TripDao
import com.tesla.dashboard.data.source.VehicleDataSource
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
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
 * - DataSourceModule: 将 TeslaBleProvider 绑定到 VehicleDataSource 接口
 *   (GNSS/Sensor 已移除，BLE 为唯一数据源)
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
