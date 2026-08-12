package com.tesla.dashboard.data.local

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tesla.dashboard.data.model.VehicleInfo
import com.tesla.dashboard.data.model.VehicleListSerializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 顶层 DataStore 委托属性
 */
private val Context.vehiclesDataStore by preferencesDataStore(name = "tesla_vehicles")

/**
 * 车辆管理仓库 — 支持多车绑定
 */
@Singleton
class VehicleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val KEY_VEHICLE_LIST = stringPreferencesKey("vehicle_list")
    private val KEY_CURRENT_VIN = stringPreferencesKey("current_vin")

    // ===== 车辆列表 =====

    val vehiclesFlow: Flow<List<VehicleInfo>> = context.vehiclesDataStore.data.map { prefs ->
        val json = prefs[KEY_VEHICLE_LIST] ?: return@map emptyList()
        VehicleListSerializer.fromJson(json)
    }

    suspend fun getVehicles(): List<VehicleInfo> {
        val prefs = context.vehiclesDataStore.data.first()
        val json = prefs[KEY_VEHICLE_LIST] ?: return emptyList()
        return VehicleListSerializer.fromJson(json)
    }

    suspend fun addVehicle(vin: String, vehiclePublicKeyRaw: ByteArray, batteryModel: String) {
        val existing = getVehicles()
        if (existing.any { it.vin == vin }) return

        val now = System.currentTimeMillis()
        val vehicle = VehicleInfo(
            vin = vin.trim(),
            batteryModel = batteryModel,
            vehiclePublicKeyRaw = android.util.Base64.encodeToString(vehiclePublicKeyRaw, android.util.Base64.NO_WRAP),
            pairedAt = now,
        )
        val updated = existing.toMutableList().apply { add(vehicle) }
        context.vehiclesDataStore.edit { prefs ->
            prefs[KEY_VEHICLE_LIST] = VehicleListSerializer.toJson(updated)
            if (existing.isEmpty()) {
                prefs[KEY_CURRENT_VIN] = vin.trim()
            }
        }
    }

    suspend fun removeVehicle(vin: String) {
        val existing = getVehicles()
        val updated = existing.filter { it.vin != vin }.toMutableList()
        context.vehiclesDataStore.edit { prefs ->
            prefs[KEY_VEHICLE_LIST] = VehicleListSerializer.toJson(updated)
            val currentVin = prefs[KEY_CURRENT_VIN] ?: ""
            if (currentVin == vin) {
                prefs[KEY_CURRENT_VIN] = updated.firstOrNull()?.vin ?: ""
            }
        }
    }

    suspend fun updateBatteryModel(vin: String, batteryModel: String) {
        val existing = getVehicles()
        val updated = existing.map { if (it.vin == vin) it.copy(batteryModel = batteryModel) else it }
        context.vehiclesDataStore.edit { prefs ->
            prefs[KEY_VEHICLE_LIST] = VehicleListSerializer.toJson(updated)
        }
    }

    // ===== 当前车辆 =====

    val currentVinFlow: Flow<String> = context.vehiclesDataStore.data.map { prefs ->
        prefs[KEY_CURRENT_VIN] ?: ""
    }

    suspend fun getCurrentVin(): String {
        val prefs = context.vehiclesDataStore.data.first()
        return prefs[KEY_CURRENT_VIN] ?: ""
    }

    suspend fun setCurrentVin(vin: String) {
        context.vehiclesDataStore.edit { prefs ->
            prefs[KEY_CURRENT_VIN] = vin.trim()
        }
    }

    suspend fun getCurrentBatteryModel(): String {
        val currentVin = getCurrentVin()
        return getVehicles().find { it.vin == currentVin }?.batteryModel ?: ""
    }

    suspend fun getCurrentVehicle(): VehicleInfo? {
        val currentVin = getCurrentVin()
        return getVehicles().find { it.vin == currentVin }
    }

    suspend fun getVehiclePublicKeyRaw(vin: String): ByteArray? {
        return getVehicles()
            .find { it.vin == vin }
            ?.vehiclePublicKeyRaw
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
    }

    suspend fun hasPairedVehicles(): Boolean = getVehicles().isNotEmpty()

    suspend fun isVehiclePaired(vin: String): Boolean = getVehicles().any { it.vin == vin }

    suspend fun clearAll() {
        context.vehiclesDataStore.edit { prefs ->
            prefs.remove(KEY_VEHICLE_LIST)
            prefs.remove(KEY_CURRENT_VIN)
        }
    }

    suspend fun getVehicleCount(): Int = getVehicles().size
}
