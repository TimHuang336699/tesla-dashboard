package com.tesla.dashboard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tesla.dashboard.data.local.dao.TripDao
import com.tesla.dashboard.data.local.entity.TripEntity
import javax.inject.Singleton

/**
 * Room 数据库
 *
 * 当前包含 trips 表(行程记录),version=1。
 *
 * ## 单例构建
 * 使用 companion object 中的 [getInstance] 提供线程安全的双重检查锁单例。
 * 在 Hilt 环境中,可通过 DatabaseModule 的 @Provides 方法调用 [getInstance] 进行注入,
 * 也可在非 Hilt 场景下直接调用。
 *
 * @Singleton 注解为作用域标记,实际单例管理由 companion object 的双重检查锁保证。
 */
@Singleton
@Database(
    entities = [TripEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * 获取行程 DAO
     * @return [TripDao] 实例
     */
    abstract fun tripDao(): TripDao

    companion object {

        /** 数据库文件名 */
        private const val DATABASE_NAME = "tesla_dashboard.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例(双重检查锁)
         *
         * 线程安全,首次调用时创建数据库实例,后续直接返回缓存。
         *
         * @param context 上下文(内部使用 applicationContext,避免 Activity 泄漏)
         * @return [AppDatabase] 单例
         */
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { INSTANCE = it }
            }
        }
    }
}
