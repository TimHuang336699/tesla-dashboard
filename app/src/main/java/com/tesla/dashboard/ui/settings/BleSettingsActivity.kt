package com.tesla.dashboard.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tesla.dashboard.R
import com.tesla.dashboard.data.model.VehicleInfo
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.databinding.ActivityBleSettingsBinding
import com.tesla.dashboard.ui.dashboard.IndicatorStripView
import com.tesla.dashboard.ui.pairing.PairingActivity
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.VinMasker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 电池车型选项数据类
 *
 * @property nameRes 用户可见的车型显示名称资源 ID (随语言切换)
 * @property code 车型代码,对应 [com.tesla.dashboard.data.model.BatteryConfig] 中的 key
 */
private data class BatteryModelOption(val nameRes: Int, val code: String)

/**
 * 蓝牙与车辆设置二级页 — 多车管理 (v0.5.1)
 *
 * 承载原设置页的 Tesla BLE 配置(VIN + 配对状态 + 指示灯 + 按钮)与车辆信息(车辆选择 + 车型)。
 *
 * ## 多车流程 (v0.5.1)
 * 1. 车辆选择器: 选择已配对车辆作为"当前车辆" (仪表盘读取该车数据)
 * 2. 输入新 VIN + "配对车辆"按钮 → 添加新车 (配对向导)
 * 3. "添加车辆"按钮: 取消当前选择, 解锁 VIN 输入框以便录入新车
 * 4. 已配对车辆列表: 每辆车可单独删除 (解绑)
 * 5. 车型下拉: 为当前选中的车辆单独设置车型 (电耗计算用)
 *
 * @see BleSettingsViewModel
 */
@AndroidEntryPoint
class BleSettingsActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例,在 onCreate 中初始化 */
    private lateinit var binding: ActivityBleSettingsBinding

    /** BLE 设置 ViewModel,由 Hilt 自动提供 */
    private val viewModel: BleSettingsViewModel by viewModels()

    /**
     * 表单是否已填充标记
     *
     * 首次从 DataStore 加载到已保存的设置值时填充表单并置为 true,
     * 防止后续 Flow 发射覆盖用户正在编辑的内容。
     */
    private var isFormPopulated = false

    /** 当前车辆列表快照 (车辆选择器/列表渲染用) */
    private var currentVehicles: List<VehicleInfo> = emptyList()

    /** 当前选中的车型代码 */
    private var selectedBatteryModel: String = ""

    /** VIN 是否已锁定 (当前车辆选中后锁定输入框并遮罩后 6 位) */
    private var isVinLocked: Boolean = false

    /** 待执行的 BLE 操作 (权限授予后执行) */
    private var pendingBleAction: BleAction? = null

    /** BLE 权限请求启动器 */
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            pendingBleAction?.let { executeBleAction(it) }
        } else {
            Toast.makeText(this, R.string.settings_ble_permissions_needed, Toast.LENGTH_SHORT).show()
        }
        pendingBleAction = null
    }

    /**
     * 可选车型列表(名称资源 ID ↔ 车型代码)
     *
     * 显示名称包含电池容量和化学类型，便于用户精确选择。
     * 车型代码对应 [com.tesla.dashboard.data.model.BatteryConfig.capacityByModel] 中的 key。
     */
    private val batteryModelOptions = listOf(
        // Model S — Nosecone 初代 / Facelift 改款 / Raven 更新 / Palladium 焕新版
        BatteryModelOption(R.string.battery_model_s_60, "model_s_60"),
        BatteryModelOption(R.string.battery_model_s_75, "model_s_75"),
        BatteryModelOption(R.string.battery_model_s_85, "model_s_85"),
        BatteryModelOption(R.string.battery_model_s_90, "model_s_90"),
        BatteryModelOption(R.string.battery_model_s_100, "model_s_100"),
        BatteryModelOption(R.string.battery_model_s_plaid, "model_s_plaid"),
        // Model 3 — 旧版 / Highland 焕新版
        BatteryModelOption(R.string.battery_model_3_standard, "model_3_standard"),
        BatteryModelOption(R.string.battery_model_3_standard_nmc, "model_3_standard_nmc"),
        BatteryModelOption(R.string.battery_model_3_long_range, "model_3_long_range"),
        BatteryModelOption(R.string.battery_model_3_performance, "model_3_performance"),
        BatteryModelOption(R.string.battery_model_3_performance_highland, "model_3_performance_highland"),
        // Model X — Original 初代 / Raven 更新 / Palladium 焕新版
        BatteryModelOption(R.string.battery_model_x_75, "model_x_75"),
        BatteryModelOption(R.string.battery_model_x_90, "model_x_90"),
        BatteryModelOption(R.string.battery_model_x_100, "model_x_100"),
        BatteryModelOption(R.string.battery_model_x_plaid, "model_x_plaid"),
        // Model Y — 旧版 / Juniper 焕新版
        BatteryModelOption(R.string.battery_model_y_standard, "model_y_standard"),
        BatteryModelOption(R.string.battery_model_y_standard_nmc, "model_y_standard_nmc"),
        BatteryModelOption(R.string.battery_model_y_long_range, "model_y_long_range"),
        BatteryModelOption(R.string.battery_model_y_long_range_juniper, "model_y_long_range_juniper"),
        BatteryModelOption(R.string.battery_model_y_performance, "model_y_performance"),
        // Cybertruck — 初代
        BatteryModelOption(R.string.battery_model_cybertruck_dual, "cybertruck_dual"),
        BatteryModelOption(R.string.battery_model_cybertruck_tri, "cybertruck_tri"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. 初始化 ViewBinding
        binding = ActivityBleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 设置车型下拉菜单
        setupBatteryModelDropdown()

        // 4. 设置车辆选择器
        setupVehicleSelector()

        // 5. 初始化状态指示灯条
        setupIndicatorStrip()

        // 6. 设置按钮监听
        setupClickListeners()

        // 7. 设置即时自动保存
        setupAutoSave()

        // 8. 立即应用当前主题配色 (避免初始回退色闪现)
        applyThemeColors(themeManager.colors.value)

        // 9. 观察数据
        observeViewModel()
    }

    /**
     * 设置车型下拉菜单 (作用于当前选中的车辆)
     */
    private fun setupBatteryModelDropdown() {
        val displayNames = batteryModelOptions.map { getString(it.nameRes) }
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            displayNames,
        )
        binding.actvBatteryModel.setAdapter(adapter)

        binding.actvBatteryModel.setOnItemClickListener { _, _, position, _ ->
            selectedBatteryModel = batteryModelOptions[position].code
            // 车型选择后即时自动保存到当前车辆
            if (isFormPopulated && isVinLocked) {
                val currentVin = viewModel.uiState.value.currentVin
                if (currentVin.isNotBlank()) {
                    viewModel.saveBatteryModel(currentVin, selectedBatteryModel)
                }
            }
        }
    }

    /**
     * 设置车辆选择器 (选择当前车辆)
     */
    private fun setupVehicleSelector() {
        binding.actvCurrentVehicle.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, emptyList<String>()))
        binding.actvCurrentVehicle.setOnItemClickListener { _, _, position, _ ->
            currentVehicles.getOrNull(position)?.let { vehicle ->
                viewModel.switchVehicle(vehicle.vin)
            }
        }
    }

    /**
     * 初始化状态指示灯条 (BLE/GPS/罗盘)
     *
     * BLE 状态由配对状态驱动, GPS/罗盘在设置页无数据源, 默认非激活。
     */
    private fun setupIndicatorStrip() {
        val bleIcon: Drawable = ResourcesCompat.getDrawable(
            resources, android.R.drawable.stat_sys_data_bluetooth, theme
        ) ?: return
        val gpsIcon: Drawable = ResourcesCompat.getDrawable(
            resources, android.R.drawable.ic_menu_mylocation, theme
        ) ?: return
        val compassIcon: Drawable = ResourcesCompat.getDrawable(
            resources, android.R.drawable.ic_menu_compass, theme
        ) ?: return

        binding.indicatorStrip.setIndicators(
            listOf(
                IndicatorStripView.Indicator("ble", bleIcon, active = false),
                IndicatorStripView.Indicator("gps", gpsIcon, active = false),
                IndicatorStripView.Indicator("compass", compassIcon, active = false),
            )
        )
    }

    /**
     * 设置按钮点击监听
     */
    private fun setupClickListeners() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 配对车辆按钮 — 用输入框中的 VIN 启动配对向导 (添加新车)
        binding.btnPair.setOnClickListener {
            val vin = binding.etVin.text.toString().trim()
            if (vin.isBlank()) {
                Toast.makeText(this, R.string.error_vin_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (vin.length != 17) {
                Toast.makeText(this, R.string.error_vin_length, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startPairingWithPermissionCheck(vin)
        }

        // 测试连接按钮 — 测试当前选中的车辆
        binding.btnTestConnection.setOnClickListener {
            val currentVin = viewModel.uiState.value.currentVin
            if (currentVin.isBlank()) return@setOnClickListener
            testConnectionWithPermissionCheck(currentVin)
        }

        // 解除配对按钮 — 解绑当前选中的车辆
        binding.btnUnpair.setOnClickListener {
            val currentVin = viewModel.uiState.value.currentVin
            if (currentVin.isBlank()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_remove_vehicle_title)
                .setMessage(R.string.settings_remove_vehicle_confirm)
                .setPositiveButton(R.string.settings_remove_vehicle) { _, _ ->
                    viewModel.unpair(currentVin)
                    Toast.makeText(this, R.string.success_unpaired, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        // 添加车辆按钮 — 取消当前选择, 解锁 VIN 输入框
        binding.btnAddVehicle.setOnClickListener {
            viewModel.clearCurrentSelection()
        }
    }

    /**
     * 观察 ViewModel 的数据流
     */
    private fun observeViewModel() {
        // 观察 UI 状态
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state.isLoaded && !isFormPopulated) {
                        populateForm(state)
                        isFormPopulated = true
                    }
                    updateVehicleSelector(state)
                    updateVehicleList(state)
                    updatePairingStatus(state)
                }
            }
        }

        // 观察配对进度
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pairingState.collect { state ->
                    updatePairingProgress(state)
                }
            }
        }

        // 观察主题颜色 — 实时应用配色
        observeThemeColors()
    }

    /**
     * 应用主题颜色到设置页所有 UI 元素
     *
     * @param c 当前主题颜色集合
     */
    override fun applyThemeColors(c: ThemeColors) {
        currentColors = c

        // ===== 根布局背景 =====
        binding.rootScroll.setBackgroundColor(c.background)

        // ===== 顶部标题栏 =====
        binding.btnBack.imageTintList = ColorStateList.valueOf(c.accentCyan)
        binding.tvTitle.setTextColor(c.textPrimary)

        // ===== 卡片背景与描边 =====
        binding.cardTeslaBle.strokeColor = c.divider
        binding.cardTeslaBle.setCardBackgroundColor(c.cardBackground)
        binding.cardVehicle.strokeColor = c.divider
        binding.cardVehicle.setCardBackgroundColor(c.cardBackground)

        // ===== 分区标题 =====
        binding.tvSectionBle.setTextColor(c.accentCyan)
        binding.tvSectionVehicle.setTextColor(c.accentCyan)

        // ===== VIN 输入框 =====
        binding.tilVin.boxStrokeColor = c.accentCyan
        binding.etVin.setTextColor(c.textPrimary)

        // ===== 配对状态 =====
        binding.tvPairingStatus.setTextColor(
            if (viewModel.uiState.value.isPaired) c.accentGreen else c.textSecondary,
        )
        binding.tvPairingProgress.setTextColor(c.accentCyan)

        // ===== BLE 按钮 =====
        binding.btnPair.backgroundTintList = ColorStateList.valueOf(c.accentCyan)
        binding.btnTestConnection.setTextColor(c.accentGreen)
        binding.btnTestConnection.strokeColor = ColorStateList.valueOf(c.accentGreen)
        binding.btnUnpair.setTextColor(c.textSecondary)
        binding.btnUnpair.strokeColor = ColorStateList.valueOf(c.divider)
        binding.btnAddVehicle.setTextColor(c.accentCyan)
        binding.btnAddVehicle.strokeColor = ColorStateList.valueOf(c.accentCyan)

        // ===== 车辆选择器 =====
        binding.tilCurrentVehicle.boxStrokeColor = c.accentCyan
        binding.actvCurrentVehicle.setTextColor(c.textPrimary)

        // ===== 车型下拉框 =====
        binding.tilBatteryModel.boxStrokeColor = c.accentCyan
        binding.actvBatteryModel.setTextColor(c.textPrimary)
        binding.tvVehicleListEmpty.setTextColor(c.textSecondary)

        // ===== 状态指示灯条配色 =====
        binding.indicatorStrip.setActiveColor(c.accentGreen)
        binding.indicatorStrip.setInactiveColor(c.textSecondary)
    }

    /**
     * 用已保存的设置值填充表单
     *
     * VIN 填充规则: 有当前车辆 → 显示遮罩并锁定输入框; 无 → 明文可编辑。
     */
    private fun populateForm(state: BleSettingsUiState) {
        // VIN (根据当前车辆存在与否决定明文/遮罩)
        applyVinLockState(state.isPaired, state.currentVehicle?.vin.orEmpty())

        // 车型 (当前车辆的车型代码)
        selectedBatteryModel = state.currentVehicle?.batteryModel.orEmpty()
        val option = batteryModelOptions.find { it.code == selectedBatteryModel }
        if (option != null) {
            binding.actvBatteryModel.setText(getString(option.nameRes), false)
        } else {
            binding.actvBatteryModel.setText("", false)
        }
    }

    /**
     * 更新车辆选择器
     *
     * @param state 当前 UI 状态
     */
    private fun updateVehicleSelector(state: BleSettingsUiState) {
        currentVehicles = state.vehicles
        val displayNames = state.vehicles.map { VinMasker.mask(it.vin) }
        val adapter = ArrayAdapter(this, R.layout.item_dropdown, displayNames)
        binding.actvCurrentVehicle.setAdapter(adapter)

        val currentVehicle = state.currentVehicle
        if (currentVehicle != null) {
            binding.actvCurrentVehicle.setText(VinMasker.mask(currentVehicle.vin), false)
        } else {
            binding.actvCurrentVehicle.setText("", false)
        }
        binding.actvCurrentVehicle.isEnabled = state.vehicles.isNotEmpty()
    }

    /**
     * 更新已配对车辆列表
     *
     * @param state 当前 UI 状态
     */
    private fun updateVehicleList(state: BleSettingsUiState) {
        binding.llVehicleList.removeAllViews()
        state.vehicles.forEach { vehicle ->
            binding.llVehicleList.addView(buildVehicleRow(vehicle))
        }
        binding.tvVehicleListEmpty.visibility = if (state.vehicles.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 构建车辆列表行 (遮罩 VIN + 车型 + 删除按钮)
     *
     * @param vehicle 车辆信息
     * @return 行布局
     */
    private fun buildVehicleRow(vehicle: VehicleInfo): View {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        val infoText = buildString {
            append(VinMasker.mask(vehicle.vin))
            val option = batteryModelOptions.find { it.code == vehicle.batteryModel }
            if (option != null) {
                append(" · ")
                append(getString(option.nameRes))
            }
        }
        val tvInfo = TextView(this).apply {
            text = infoText
            setTextColor(currentColors.textPrimary)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnRemove = MaterialButton(this).apply {
            text = getString(R.string.settings_remove_vehicle)
            textSize = 12f
            isAllCaps = false
            setTextColor(currentColors.textSecondary)
            val paddingH = (12 * density).toInt()
            setPadding(paddingH, 0, paddingH, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt(),
            )
            setOnClickListener {
                MaterialAlertDialogBuilder(this@BleSettingsActivity)
                    .setTitle(R.string.settings_remove_vehicle_title)
                    .setMessage(getString(R.string.settings_remove_vehicle_confirm_with_vin, VinMasker.mask(vehicle.vin)))
                    .setPositiveButton(R.string.settings_remove_vehicle) { _, _ ->
                        viewModel.unpair(vehicle.vin)
                        Toast.makeText(this@BleSettingsActivity, R.string.success_unpaired, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }

        row.addView(tvInfo)
        row.addView(btnRemove)
        return row
    }

    /**
     * 应用 VIN 锁定/遮罩状态
     *
     * - 有当前车辆: 输入框禁用, 显示遮罩 (后 6 位 `*`), 提示"已配对车辆(尾号隐藏)"
     * - 无当前车辆: 输入框启用, 显示明文, 清除提示
     *
     * @param isLocked 是否锁定
     * @param vin 明文 VIN
     */
    private fun applyVinLockState(isLocked: Boolean, vin: String) {
        isVinLocked = isLocked
        if (isLocked) {
            binding.etVin.setText(VinMasker.mask(vin))
            binding.etVin.isEnabled = false
            binding.tilVin.hint = getString(R.string.settings_vin_masked_hint)
        } else {
            binding.etVin.setText(vin)
            binding.etVin.isEnabled = true
            binding.tilVin.hint = getString(R.string.settings_vin)
        }
    }

    /**
     * 更新配对状态 UI
     *
     * @param state 当前 UI 状态
     */
    private fun updatePairingStatus(state: BleSettingsUiState) {
        val isPaired = state.isPaired

        // 同步 BLE 状态指示灯
        binding.indicatorStrip.updateIndicator("ble", isPaired)

        // 同步 VIN 锁定/遮罩状态 (使用明文 VIN)
        applyVinLockState(isPaired, state.currentVehicle?.vin.orEmpty())

        // 车型下拉框: 有当前车辆才可编辑
        binding.actvBatteryModel.isEnabled = isPaired
        binding.tilBatteryModel.isEnabled = isPaired

        if (isPaired) {
            binding.tvPairingStatus.text = getString(R.string.settings_paired)
            binding.tvPairingStatus.setTextColor(currentColors.accentGreen)
            binding.btnPair.isEnabled = false
            binding.btnTestConnection.isEnabled = true
            binding.btnUnpair.isEnabled = true
            binding.btnAddVehicle.visibility = View.VISIBLE
        } else {
            binding.tvPairingStatus.text = getString(R.string.settings_not_paired)
            binding.tvPairingStatus.setTextColor(currentColors.textSecondary)
            binding.btnPair.isEnabled = !isVinLocked && binding.etVin.text?.length == 17
            binding.btnTestConnection.isEnabled = false
            binding.btnUnpair.isEnabled = false
            binding.btnAddVehicle.visibility = View.GONE
        }
    }

    /**
     * 更新配对进度显示
     *
     * @param state 当前配对状态
     */
    private fun updatePairingProgress(state: TeslaBleProvider.PairingState) {
        val (text, visible) = when (state) {
            is TeslaBleProvider.PairingState.Idle -> "" to false
            is TeslaBleProvider.PairingState.GeneratingKey ->
                getString(R.string.pairing_progress_generating) to true
            is TeslaBleProvider.PairingState.Scanning ->
                getString(R.string.pairing_progress_scanning) to true
            is TeslaBleProvider.PairingState.Connecting ->
                getString(R.string.pairing_progress_connecting) to true
            is TeslaBleProvider.PairingState.Handshaking ->
                getString(R.string.pairing_progress_handshaking) to true
            is TeslaBleProvider.PairingState.SendingPairRequest ->
                getString(R.string.pairing_progress_sending) to true
            is TeslaBleProvider.PairingState.WaitingForNfcConfirmation ->
                getString(R.string.settings_pairing_nfc_prompt) to true
            is TeslaBleProvider.PairingState.SavingKey ->
                getString(R.string.pairing_progress_saving) to true
            is TeslaBleProvider.PairingState.Completed -> "" to false
            is TeslaBleProvider.PairingState.Failed ->
                getString(R.string.pairing_progress_failed, state.message) to true
        }

        binding.tvPairingProgress.text = text
        binding.tvPairingProgress.visibility = if (visible) View.VISIBLE else View.GONE

        // 配对进行中禁用所有 BLE 按钮
        val inProgress = state !is TeslaBleProvider.PairingState.Idle &&
                state !is TeslaBleProvider.PairingState.Completed &&
                state !is TeslaBleProvider.PairingState.Failed

        if (inProgress) {
            binding.btnPair.isEnabled = false
            binding.btnTestConnection.isEnabled = false
            binding.btnUnpair.isEnabled = false
            binding.btnAddVehicle.isEnabled = false
        } else {
            binding.btnAddVehicle.isEnabled = true
        }

        // 配对完成或失败后恢复按钮状态
        if (state is TeslaBleProvider.PairingState.Completed) {
            Toast.makeText(this, R.string.settings_pairing_success, Toast.LENGTH_SHORT).show()
        } else if (state is TeslaBleProvider.PairingState.Failed) {
            Toast.makeText(this, R.string.settings_pairing_failed, Toast.LENGTH_SHORT).show()
            // 恢复按钮状态(基于当前配对状态)
            viewModel.uiState.value.let { updatePairingStatus(it) }
        }
    }

    // ===== BLE 权限处理 =====

    /**
     * 检查是否拥有 BLE 所需权限
     */
    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求 BLE 权限
     */
    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        blePermissionLauncher.launch(permissions)
    }

    /**
     * 带权限检查的配对操作
     */
    private fun startPairingWithPermissionCheck(vin: String) {
        if (hasBlePermissions()) {
            executeBleAction(BleAction.Pair(vin))
        } else {
            pendingBleAction = BleAction.Pair(vin)
            requestBlePermissions()
        }
    }

    /**
     * 带权限检查的测试连接操作
     */
    private fun testConnectionWithPermissionCheck(vin: String) {
        if (hasBlePermissions()) {
            executeBleAction(BleAction.Test(vin))
        } else {
            pendingBleAction = BleAction.Test(vin)
            requestBlePermissions()
        }
    }

    /**
     * 执行 BLE 操作
     */
    private fun executeBleAction(action: BleAction) {
        when (action) {
            is BleAction.Pair -> startPairing(action.vin)
            is BleAction.Test -> testConnection(action.vin)
        }
    }

    // ===== BLE 操作 =====

    /**
     * 开始 BLE 配对
     */
    private fun startPairing(vin: String) {
        binding.btnPair.isEnabled = false
        binding.btnTestConnection.isEnabled = false
        binding.btnUnpair.isEnabled = false

        viewModel.startPairing(vin) { success ->
            runOnUiThread {
                if (!success) {
                    // 配对失败,恢复按钮状态
                    viewModel.uiState.value.let { updatePairingStatus(it) }
                }
            }
        }
    }

    /**
     * 测试 BLE 连接
     */
    private fun testConnection(vin: String) {
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "…"

        viewModel.testConnection(vin) { success ->
            runOnUiThread {
                binding.btnTestConnection.isEnabled = true
                binding.btnTestConnection.setText(R.string.settings_test_connection)

                val messageId = if (success) {
                    R.string.settings_connection_success
                } else {
                    R.string.settings_connection_failed
                }
                Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 即时自动保存 =====

    /**
     * 设置即时自动保存
     *
     * - VIN: TextWatcher + 500ms debounce + 实时更新配对按钮可用性
     * - 车型: 下拉选择时立即保存到当前车辆 (在 [setupBatteryModelDropdown] 中注册)
     *
     * 注意: 通过 [isFormPopulated] 守卫, 避免 [populateForm] 回填表单时触发误保存。
     */
    private fun setupAutoSave() {
        binding.etVin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 锁定状态下输入框禁用, 跳过
                if (isVinLocked) return
                // 输入满 17 位时允许配对
                val length = s?.toString()?.trim()?.length ?: 0
                binding.btnPair.isEnabled = length == 17
            }
        })
    }

    companion object {
        /** VIN 自动保存 debounce 延迟 (毫秒) */
        private const val VIN_DEBOUNCE_MS = 500L
    }

    // ===== 内部类 =====

    /**
     * BLE 操作类型 (用于权限授予后的延迟执行)
     */
    private sealed class BleAction {
        data class Pair(val vin: String) : BleAction()
        data class Test(val vin: String) : BleAction()
    }
}
