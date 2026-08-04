# ============================================================================
# Tesla Dashboard - ProGuard / R8 规则
# ============================================================================

# ----------------------------------------------------------------------------
# 通用 Android 规则
# ----------------------------------------------------------------------------

# 保留注解属性,避免反射相关功能失效
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留泛型签名(用于 Retrofit / Room 等反射场景)
-keepattributes RuntimeVisibleAnnotations

# ----------------------------------------------------------------------------
# 数据模型 — 保留所有字段,避免序列化/反序列化失败
# ----------------------------------------------------------------------------

# VehicleData 数据模型
-keep class com.tesla.dashboard.data.model.VehicleData { *; }

# Trip 数据模型
-keep class com.tesla.dashboard.data.model.Trip { *; }

# ----------------------------------------------------------------------------
# Room 数据库 — 保留实体和 DAO
# ----------------------------------------------------------------------------

# Room 实体
-keep class com.tesla.dashboard.data.local.entity.** { *; }

# Room DAO 接口
-keep class com.tesla.dashboard.data.local.dao.** { *; }

# Room 数据库
-keep class com.tesla.dashboard.data.local.AppDatabase { *; }
-keep class * extends androidx.room.RoomDatabase

# Room 自动生成的实现类
-keep class * extends androidx.room.RoomDatabase_Impl { *; }

# ----------------------------------------------------------------------------
# Hilt / Dagger — 编译期生成,通常不需要额外规则,但保留安全
# ----------------------------------------------------------------------------

# Hilt 生成的类
-keep class **_HiltModules { *; }
-keep class **_HiltComponents { *; }
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class *

# Dagger 生成的工厂类
-keep,allowobfuscation @javax.inject.Inject class *

# ----------------------------------------------------------------------------
# Gson — TrackPoint 序列化
# ----------------------------------------------------------------------------

# Gson 类型适配器
-keep class com.google.gson.** { *; }
-keepattributes RuntimeVisibleAnnotations

# 保留被 Gson 序列化的类
-keep class com.tesla.dashboard.data.model.** { *; }

# ----------------------------------------------------------------------------
# Kotlin Coroutines
# ----------------------------------------------------------------------------

# 保留协程相关类
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ----------------------------------------------------------------------------
# ViewBinding — 自动生成,无需额外规则
# ----------------------------------------------------------------------------

# ViewBinding 生成的绑定类
-keep class com.tesla.dashboard.databinding.** { *; }
