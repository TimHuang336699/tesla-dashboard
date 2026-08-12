package com.tesla.dashboard.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 顶层 DataStore 委托属性
 *
 * 使用 [preferencesDataStore] 扩展函数创建全局唯一的 DataStore<Preferences> 实例,
 * 名称 "tesla_settings" 对应磁盘上的 Preferences 文件。
 * 委托属性保证整个应用生命周期内只创建一个 DataStore 实例。
 */
private val Context.settingsDataStore by preferencesDataStore(name = "tesla_settings")

/**
 * 应用设置仓库 — 基于 DataStore Preferences
 *
 * 负责持久化用户在设置页面配置的各项参数,包括:
 * - [THEME_MODE] 主题模式(深色 / 浅色 / 跟随系统),默认 "system"
 * - [APP_LANGUAGE] 应用语言,默认 "system"
 * - [UNIT_SYSTEM] 单位系统,默认 "metric"
 * - [SHOW_TURN_SIGNALS] 转向灯显示开关,默认开启
 *
 * 注意: 已配对车辆信息 (VIN / 车型 / 车辆公钥) 由
 * [com.tesla.dashboard.data.local.VehicleRepository] 统一管理 (v0.5.1 多车支持),
 * 旧版存储的 `tesla_vin` / `battery_model` 通过 [consumeLegacyVinAndBatteryModel] 一次性迁移。
 *
 * ## 读写方式
 * - 读取: 每个设置项暴露一个 [Flow],数据变化时自动发射新值
 * - 写入: 提供对应的 suspend 方法,在协程中安全写入
 *
 * ## 依赖注入
 * 使用 Hilt @Singleton + @Inject constructor 自动注入,
 * 通过 @ApplicationContext 获取应用级 Context 以访问 DataStore。
 *
 * @param context 应用级 Context(由 Hilt 通过 @ApplicationContext 提供)
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    // ===== Preferences Keys =====

    /** 主题模式: "dark"(深色) / "light"(浅色) / "system"(跟随系统) */
    private val THEME_MODE = stringPreferencesKey("theme_mode")

    /** 应用语言: "system"(跟随系统) / "zh"(中文) / "en"(English) */
    private val APP_LANGUAGE = stringPreferencesKey("app_language")

    /** 单位系统: "metric"(公制) / "imperial"(英制) */
    private val UNIT_SYSTEM = stringPreferencesKey("unit_system")

    /** 是否显示转向灯指示 (v0.5.0, 默认开启) */
    private val SHOW_TURN_SIGNALS = booleanPreferencesKey("show_turn_signals")

    /** 旧版 VIN 键 (v0.5.1 迁移后不再写入) */
    private val TESLA_VIN = stringPreferencesKey("tesla_vin")

    /** 旧版车型键 (v0.5.1 迁移后不再写入) */
    private val BATTERY_MODEL = stringPreferencesKey("battery_model")

    // ===== Theme Mode =====

    /**
     * 观察主题模式设置流
     *
     * @return 主题模式 Flow("dark"/"light"/"system"),未设置时发射默认值 "system"
     */
    val themeModeFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[THEME_MODE] ?: DEFAULT_THEME_MODE
    }

    /**
     * 保存主题模式
     *
     * @param themeMode 主题模式:"dark"(深色) / "light"(浅色) / "system"(跟随系统)
     */
    suspend fun saveThemeMode(themeMode: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[THEME_MODE] = themeMode
        }
    }

    // ===== App Language =====

    /**
     * 观察应用语言设置流
     *
     * @return 语言代码 Flow("system"/"zh"/"en"),未设置时发射默认值 "system"
     */
    val appLanguageFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[APP_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    /**
     * 保存应用语言
     *
     * @param language 语言代码:"system"(跟随系统) / "zh"(中文) / "en"(English)
     */
    suspend fun saveAppLanguage(language: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[APP_LANGUAGE] = language
        }
    }

    // ===== Unit System =====

    /**
     * 观察单位系统设置流
     *
     * @return 单位系统代码 Flow("metric"/"imperial"),未设置时发射默认值 "metric"
     */
    val unitSystemFlow: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[UNIT_SYSTEM] ?: DEFAULT_UNIT_SYSTEM
    }

    /**
     * 保存单位系统
     *
     * @param unitSystem 单位系统代码:"metric"(公制) / "imperial"(英制)
     */
    suspend fun saveUnitSystem(unitSystem: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[UNIT_SYSTEM] = unitSystem
        }
    }

    // ===== Turn Signals (v0.5.0) =====

    /**
     * 观察转向灯显示开关流
     *
     * @return 是否显示转向灯指示, 未设置时默认 true
     */
    val showTurnSignalsFlow: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[SHOW_TURN_SIGNALS] ?: DEFAULT_SHOW_TURN_SIGNALS
    }

    /**
     * 保存转向灯显示开关
     *
     * @param show true=显示转向灯指示
     */
    suspend fun saveShowTurnSignals(show: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SHOW_TURN_SIGNALS] = show
        }
    }

    // ===== Legacy Migration (v0.5.1 多车迁移) =====

    /**
     * 读取并清除旧版 VIN / 车型设置 (v0.5.1 多车迁移用)
     *
     * v0.5.0 及之前, VIN 和车型代码直接存储在设置中; 多车支持后由
     * [com.tesla.dashboard.data.local.VehicleRepository] 按车辆管理。
     * 本方法读取旧值后立即删除, 供迁移流程写入对应车辆的 VehicleInfo。
     *
     * @return (vin, batteryModel) 旧版 VIN 与车型代码, 无旧数据时返回 null
     */
    suspend fun consumeLegacyVinAndBatteryModel(): Pair<String, String>? {
        val prefs = context.settingsDataStore.data.first()
        val vin = prefs[TESLA_VIN]?.takeIf { it.isNotBlank() } ?: return null
        val batteryModel = prefs[BATTERY_MODEL] ?: ""
        context.settingsDataStore.edit { p ->
            p.remove(TESLA_VIN)
            p.remove(BATTERY_MODEL)
        }
        return vin to batteryModel
    }

    companion object {
        /** 默认主题模式:跟随系统 */
        const val DEFAULT_THEME_MODE = "system"

        /** 默认应用语言:跟随系统 */
        const val DEFAULT_LANGUAGE = "system"

        /** 默认单位系统:公制 */
        const val DEFAULT_UNIT_SYSTEM = "metric"

        /** 默认显示转向灯指示 */
        const val DEFAULT_SHOW_TURN_SIGNALS = true
    }
}
