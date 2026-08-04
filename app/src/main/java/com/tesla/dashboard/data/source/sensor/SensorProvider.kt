package com.tesla.dashboard.data.source.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.VehicleDataSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 加速度传感器数据源 Provider — 基于 SensorManager
 *
 * 负责填充 VehicleData 中的传感器相关字段:
 * accelLongitudinal, accelLateral, gForce
 *
 * 传感器选择策略:
 * - 优先使用 TYPE_LINEAR_ACCELERATION(已自动去除重力分量,数据更干净)
 * - 设备不支持时降级使用 TYPE_ACCELEROMETER(含重力,静止时 gForce ≈ 1.0)
 *
 * 坐标系说明(假设手机横屏放置于车内,屏幕朝上):
 * - X 轴 → 横向(左/右)  → accelLateral
 * - Y 轴 → 纵向(前/后)  → accelLongitudinal
 * - Z 轴 → 垂直(上/下)  → 参与 gForce 合成
 *
 * 注意: 实际安装方向不同时需根据设备旋转角度做坐标轴映射。
 * 本实现按需求假设 X=横向、Y=纵向,直接使用原始传感器值。
 *
 * Hilt 注入说明:
 * 本类通过 @Inject constructor 注入,供 Hilt 依赖图使用。
 * 实际接入 Hilt 时,需在构造参数上添加 @ApplicationContext 注解,
 * 并在提供此类的 Module 中添加 @Singleton 作用域。
 */
@Singleton
class SensorProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : VehicleDataSource {

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 传感器是否可用(设备支持加速度传感器) */
    private val _isAvailable = MutableStateFlow(false)
    override val isAvailable: Flow<Boolean> = _isAvailable.asStateFlow()

    /**
     * 观察加速度传感器数据流
     *
     * 使用 callbackFlow 将 SensorEventListener 转换为 Kotlin Flow。
     * 当 Flow 被收集时注册传感器监听,取消收集时自动注销。
     * 注册时使用 SENSOR_DELAY_GAME(约 20ms 间隔,适合流畅动画)。
     */
    override fun observeData(): Flow<VehicleData> = callbackFlow {
        // 优先使用线性加速度传感器(去除重力),降级使用普通加速度计
        val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (sensor == null) {
            // 设备不支持任何加速度传感器,关闭 Flow
            _isAvailable.value = false
            close()
            return@callbackFlow
        }

        _isAvailable.value = true

        // 传感器事件监听器 — 将 SensorEvent 转换为 VehicleData 并发送到 Flow
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // event.values: [X, Y, Z] 轴加速度(m/s²)
                val x = event.values[0] // 横向(左/右)
                val y = event.values[1] // 纵向(前/后)
                val z = event.values[2] // 垂直(上/下)

                // 合成 G 力: 三轴加速度模 / 标准重力加速度
                val gForce = sqrt(x * x + y * y + z * z) / GRAVITY_MS2

                val vehicleData = VehicleData(
                    accelLongitudinal = y,
                    accelLateral = x,
                    gForce = gForce
                )
                trySend(vehicleData)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // 精度变化时无需处理
            }
        }

        // 注册传感器监听,使用 GAME 延迟级别(约 20ms,适合流畅动画)
        sensorManager.registerListener(
            sensorListener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )

        // Flow 被取消时注销传感器监听,释放资源
        awaitClose {
            sensorManager.unregisterListener(sensorListener)
            _isAvailable.value = false
        }
    }

    override suspend fun start() {
        // 传感器监听的生命周期由 observeData() Flow 的收集管理
        // 此方法可预检查传感器可用性
        val hasSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
            || sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        _isAvailable.value = hasSensor
    }

    override suspend fun stop() {
        _isAvailable.value = false
    }

    companion object {
        /** 标准重力加速度 m/s² */
        private const val GRAVITY_MS2 = 9.81f
    }
}
