package com.tesla.dashboard.ui.plugins

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tesla.dashboard.R
import com.tesla.dashboard.data.source.ble.TeslaBleConstants
import com.tesla.dashboard.data.source.ble.TeslaBleMessages
import com.tesla.dashboard.databinding.ActivitySubSettingsBinding
import com.tesla.dashboard.databinding.ItemSettingsRowBinding
import com.tesla.dashboard.plugin.PluginManager
import com.tesla.dashboard.plugin.ble.BleExtensionPlugin
import com.tesla.dashboard.plugin.security.BleCommandProxy
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * BLE 拓展命令页面 (v0.5.2)
 *
 * 通过 [BleExtensionPlugin] 执行现代 carserver 协议命令:
 * 充电限值 / 开始·停止充电 / 空调开关·温度 / 充电口 / 低功耗模式。
 *
 * 需先在插件中心启用该插件, 且已设置当前车辆 (VIN)。
 * 命令在 IO 线程执行, 结果以 Toast 反馈 (实验性功能, 老车型可能不支持)。
 */
@AndroidEntryPoint
class BleExtensionActivity : BaseImmersiveActivity() {

    private lateinit var binding: ActivitySubSettingsBinding

    @Inject
    lateinit var pluginManager: PluginManager

    @Inject
    lateinit var blePlugin: BleExtensionPlugin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = getString(R.string.plugin_ble_extension_name)
        registerCommandConfirmation()
        populateCommands()
        observeThemeColors()
        applyThemeColors(themeManager.colors.value)
    }

    /**
     * 注册 BLE 指令用户确认回调 (v0.6.0 安全核心)
     *
     * 高风险指令 (充电/低功耗等) 发送前必须经用户确认:
     * - 对话框在 UI 线程展示, 说明指令名称与来源
     * - 用户拒绝 → 指令不发送, 返回 [CommandResult.Rejected]
     *
     * 页面销毁时自动注销, 避免对话框残留。
     */
    private fun registerCommandConfirmation() {
        pluginManager.buildContext().commandProxy.setConfirmationProvider { spec, requester ->
            val result = CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                val requesterLabel = if (requester == BleCommandProxy.REQUESTER_UI) {
                    getString(R.string.ble_ext_requester_ui)
                } else {
                    requester.removePrefix(BleCommandProxy.REQUESTER_PLUGIN_PREFIX)
                }
                MaterialAlertDialogBuilder(this@BleExtensionActivity)
                    .setTitle(R.string.ble_ext_confirm_title)
                    .setMessage(
                        getString(
                            R.string.ble_ext_confirm_message,
                            requesterLabel,
                            spec.name,
                        ),
                    )
                    .setPositiveButton(R.string.ble_ext_confirm_allow) { _, _ ->
                        result.complete(true)
                    }
                    .setNegativeButton(R.string.action_cancel) { _, _ ->
                        result.complete(false)
                    }
                    .setOnCancelListener { result.complete(false) }
                    .show()
            }
            result.await()
        }
    }

    override fun onDestroy() {
        pluginManager.buildContext().commandProxy.setConfirmationProvider(null)
        super.onDestroy()
    }

    private fun populateCommands() {
        val container = binding.rowsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)

        // 读取车辆数据
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_vehicle_data,
            summaryRes = R.string.ble_ext_vehicle_data_summary,
        ) { showVehicleData() }

        // 充电限值 (输入 50-100)
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_charge_limit,
            summaryRes = R.string.ble_ext_charge_limit_summary,
        ) { showChargeLimitDialog() }

        // 开始充电
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_charge_start,
            summaryRes = R.string.ble_ext_charge_start_summary,
        ) { confirmChargeStart() }

        // 停止充电
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_charge_stop,
            summaryRes = 0,
        ) { execute { blePlugin.stopCharging(pluginManager.buildContext()) } }

        // 空调开关
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_hvac,
            summaryRes = R.string.ble_ext_hvac_summary,
        ) { showHvacDialog() }

        // 空调温度
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_hvac_temp,
            summaryRes = R.string.ble_ext_hvac_temp_summary,
        ) { showTemperatureDialog() }

        // 充电口
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_charge_port,
            summaryRes = R.string.ble_ext_charge_port_summary,
        ) { showChargePortDialog() }

        // 低功耗模式 (实验性)
        addCommandRow(
            container, inflater,
            titleRes = R.string.ble_ext_low_power,
            summaryRes = R.string.ble_ext_low_power_summary,
        ) { showLowPowerDialog() }
    }

    private fun addCommandRow(
        container: android.widget.LinearLayout,
        inflater: LayoutInflater,
        titleRes: Int,
        summaryRes: Int,
        onClick: () -> Unit,
    ) {
        val rowBinding = ItemSettingsRowBinding.inflate(inflater, container, false)
        rowBinding.tvRowTitle.setText(titleRes)
        if (summaryRes != 0) {
            rowBinding.tvRowSummary.setText(summaryRes)
        } else {
            rowBinding.tvRowSummary.visibility = android.view.View.GONE
        }
        rowBinding.root.setOnClickListener { onClick() }
        container.addView(rowBinding.root)
    }

    // ===== 命令对话框 =====

    /**
     * 读取车辆数据 (getVehicleData, v0.5.2)
     *
     * 结果显示在对话框中: 充电状态 / 续航 / 充电电流 / 内外温度 / 车速功率。
     */
    private fun showVehicleData() {
        if (!pluginManager.isEnabled(blePlugin.id)) {
            Toast.makeText(this, R.string.ble_ext_not_enabled, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val context = withContext(Dispatchers.IO) { pluginManager.buildContext() }
            if (context.vehicleRepository.getCurrentVin().isBlank()) {
                Toast.makeText(this@BleExtensionActivity, R.string.ble_ext_no_vehicle, Toast.LENGTH_LONG).show()
                return@launch
            }
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { blePlugin.getVehicleData(context) }.getOrNull()
            }
            if (snapshot == null) {
                Toast.makeText(
                    this@BleExtensionActivity, R.string.ble_ext_failed, Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@BleExtensionActivity)
                .setTitle(R.string.ble_ext_vehicle_data)
                .setMessage(formatSnapshot(snapshot))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** 格式化车辆数据快照为多行文本 */
    private fun formatSnapshot(s: TeslaBleMessages.VehicleDataSnapshot): String = buildString {
        append(getString(R.string.ble_ext_data_charging)).append(": ")
        append(chargingStateName(s.chargingState))
        append('\n')
        append(getString(R.string.ble_ext_data_range)).append(": ")
        s.estBatteryRangeMi?.let { append(formatFloat(it)).append(" mi") } ?: append("--")
        append("  (")
        append(getString(R.string.ble_ext_data_range_rated))
        append(": ")
        s.batteryRangeMi?.let { append(formatFloat(it)).append(" mi") } ?: append("--")
        append(')')
        append('\n')
        append(getString(R.string.ble_ext_data_current)).append(": ")
        s.chargerActualCurrentA?.let { append(it).append(" A") } ?: append("--")
        append('\n')
        append(getString(R.string.ble_ext_data_temp)).append(": ")
        append(getString(R.string.ble_ext_data_temp_inside)).append(" ")
        s.insideTempC?.let { append(formatFloat(it)).append("°C") } ?: append("--")
        append("  ")
        append(getString(R.string.ble_ext_data_temp_outside)).append(" ")
        s.outsideTempC?.let { append(formatFloat(it)).append("°C") } ?: append("--")
        append('\n')
        append(getString(R.string.ble_ext_data_speed)).append(": ")
        s.speedKmh?.let { append(it).append(" km/h") } ?: append("--")
        append("  ")
        append(getString(R.string.ble_ext_data_power)).append(": ")
        s.powerKw?.let { append(it).append(" kW") } ?: append("--")
        append('\n')
        append(getString(R.string.ble_ext_data_gear)).append(": ")
        append(shiftStateName(s.shiftState))
        append('\n')
        append(getString(R.string.ble_ext_data_odometer)).append(": ")
        s.odometerKm?.let { append(formatFloat(it)).append(" km") } ?: append("--")
    }

    private fun formatFloat(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

    private fun chargingStateName(state: Int?): String = when (state) {
        TeslaBleConstants.CHARGING_STATE_DISCONNECTED -> getString(R.string.ble_ext_charging_disconnected)
        TeslaBleConstants.CHARGING_STATE_NO_POWER -> getString(R.string.ble_ext_charging_no_power)
        TeslaBleConstants.CHARGING_STATE_STARTING -> getString(R.string.ble_ext_charging_starting)
        TeslaBleConstants.CHARGING_STATE_CHARGING -> getString(R.string.ble_ext_charging_charging)
        TeslaBleConstants.CHARGING_STATE_COMPLETE -> getString(R.string.ble_ext_charging_complete)
        TeslaBleConstants.CHARGING_STATE_STOPPED -> getString(R.string.ble_ext_charging_stopped)
        TeslaBleConstants.CHARGING_STATE_CALIBRATING -> getString(R.string.ble_ext_charging_calibrating)
        TeslaBleConstants.CHARGING_STATE_UNKNOWN -> getString(R.string.ble_ext_charging_unknown)
        else -> "--"
    }

    private fun shiftStateName(state: Int?): String = when (state) {
        TeslaBleConstants.SHIFT_STATE_DRIVE -> "D"
        TeslaBleConstants.SHIFT_STATE_NEUTRAL -> "N"
        TeslaBleConstants.SHIFT_STATE_REVERSE -> "R"
        TeslaBleConstants.SHIFT_STATE_PARK -> "P"
        else -> "--"
    }

    private fun showChargeLimitDialog() {
        val input = EditText(this).apply {
            hint = "50 - 100"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_ext_charge_limit)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val percent = input.text.toString().trim().toIntOrNull()
                if (percent == null || percent !in 50..100) {
                    Toast.makeText(this, R.string.ble_ext_invalid_value, Toast.LENGTH_SHORT).show()
                } else {
                    execute { blePlugin.setChargeLimit(pluginManager.buildContext(), percent) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmChargeStart() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_ext_charge_start)
            .setMessage(R.string.ble_ext_charge_start_confirm)
            .setPositiveButton(R.string.ble_ext_charge_start) { _, _ ->
                execute { blePlugin.startCharging(pluginManager.buildContext()) }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showHvacDialog() {
        val options = arrayOf(getString(R.string.ble_ext_hvac_on), getString(R.string.ble_ext_hvac_off))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_ext_hvac)
            .setItems(options) { _, which ->
                execute { blePlugin.setHvacAuto(pluginManager.buildContext(), which == 0) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTemperatureDialog() {
        val input = EditText(this).apply {
            hint = "20.5"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_ext_hvac_temp)
            .setMessage(R.string.ble_ext_hvac_temp_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val celsius = input.text.toString().trim().toFloatOrNull()
                if (celsius == null) {
                    Toast.makeText(this, R.string.ble_ext_invalid_value, Toast.LENGTH_SHORT).show()
                } else {
                    execute {
                        blePlugin.setHvacTemperature(pluginManager.buildContext(), celsius, celsius)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChargePortDialog() {
        val options = arrayOf(getString(R.string.ble_ext_port_open), getString(R.string.ble_ext_port_close))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_ext_charge_port)
            .setItems(options) { _, which ->
                execute { blePlugin.setChargePort(pluginManager.buildContext(), which == 0) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showLowPowerDialog() {
        val options = arrayOf(getString(R.string.ble_ext_low_power_on), getString(R.string.ble_ext_low_power_off))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ble_ext_low_power)
            .setMessage(R.string.ble_ext_low_power_message)
            .setItems(options) { _, which ->
                execute { blePlugin.setLowPowerMode(pluginManager.buildContext(), which == 0) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ===== 命令执行 =====

    /**
     * 在 IO 线程执行命令并 Toast 结果
     *
     * 结果三态反馈 (v0.5.2):
     * - true → 命令执行成功 (OP_STATUS_OK)
     * - false → 车辆拒绝/固件不支持 (OP_STATUS_ERROR)
     * - null → 超时/连接失败
     *
     * 前置检查:
     * - 插件已启用 (用户可在插件中心开启)
     * - 已设置当前车辆
     */
    private fun execute(block: suspend () -> Boolean?) {
        if (!pluginManager.isEnabled(blePlugin.id)) {
            Toast.makeText(this, R.string.ble_ext_not_enabled, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val context = withContext(Dispatchers.IO) { pluginManager.buildContext() }
            if (context.vehicleRepository.getCurrentVin().isBlank()) {
                Toast.makeText(this@BleExtensionActivity, R.string.ble_ext_no_vehicle, Toast.LENGTH_LONG).show()
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }.getOrNull()
            }
            val messageRes = when (result) {
                true -> R.string.ble_ext_success
                false -> R.string.ble_ext_failed_unsupported
                else -> R.string.ble_ext_failed
            }
            Toast.makeText(this@BleExtensionActivity, messageRes, Toast.LENGTH_SHORT).show()
        }
    }

    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c
        binding.rootLayout.setBackgroundColor(c.background)
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)
        val container = binding.rowsContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            child.findViewById<TextView>(R.id.tvRowTitle)?.setTextColor(c.textPrimary)
            child.findViewById<TextView>(R.id.tvRowSummary)?.setTextColor(c.textSecondary)
            child.findViewById<TextView>(R.id.tvRowChevron)?.setTextColor(c.textSecondary)
        }
    }
}
