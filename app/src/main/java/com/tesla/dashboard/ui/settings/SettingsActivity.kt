package com.tesla.dashboard.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.BuildConfig
import com.tesla.dashboard.R
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.databinding.ActivitySettingsBinding
import com.tesla.dashboard.ui.dashboard.IndicatorStripView
import com.tesla.dashboard.ui.pairing.PairingActivity
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 电池车型选项数据类
 *
 * @property displayName 用户可见的车型显示名称
 * @property code 车型代码,对应 [com.tesla.dashboard.data.model.BatteryConfig] 中的 key
 */
private data class BatteryModelOption(val displayName: String, val code: String)

/**
 * 设置页面 Activity — BLE 蓝牙直连版,苹果式简约设计
 *
 * 以横屏全屏沉浸式模式展示 Tesla Dashboard 的配置项,包括:
 * - Tesla BLE 配置(VIN + 配对状态 + 配对/测试/解绑按钮)
 * - 车辆信息(车型选择,用于电池容量查询)
 * - 外观(主题模式选择)
 *
 * ## BLE 配对流程
 * 1. 用户输入车辆 VIN
 * 2. 点击"配对车辆"按钮
 * 3. 应用生成 ECC 密钥对,扫描并连接车辆 BLE
 * 4. ECDH 握手建立加密会话
 * 5. 发送 add-key-request 到车辆
 * 6. 用户在车机上用 NFC 卡片确认配对
 * 7. 配对成功后,密钥保存到本地,可开始获取车辆数据
 *
 * ## 权限处理
 * - Android 12+: 运行时请求 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT 权限
 * - Android 11-: 使用已授予的 ACCESS_FINE_LOCATION 权限进行 BLE 扫描
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    /** ViewBinding 实例,在 onCreate 中初始化 */
    private lateinit var binding: ActivitySettingsBinding

    /** 设置页面 ViewModel,由 Hilt 自动提供 */
    private val viewModel: SettingsViewModel by viewModels()

    /** 主题管理器,由 Hilt 自动注入 — 用于设置页实时应用主题配色 */
    @Inject
    lateinit var themeManager: ThemeManager

    /** 当前主题颜色(由 colors 流更新,供 UI 实时应用) */
    private var currentColors: ThemeColors = ThemeColors.Dark

    /**
     * 表单是否已填充标记
     *
     * 首次从 DataStore 加载到已保存的设置值时填充表单并置为 true,
     * 防止后续 Flow 发射覆盖用户正在编辑的内容。
     */
    private var isFormPopulated = false

    /** 当前选中的车型代码 */
    private var selectedBatteryModel: String = ""

    /** 最近一次已保存的 VIN (避免重复写入 DataStore) */
    private var lastSavedVin: String = ""

    /** VIN 自动保存 debounce 处理器 (主线程) */
    private val vinDebounceHandler = Handler(Looper.getMainLooper())

    /** 待执行的 VIN 保存任务 */
    private var vinSaveRunnable: Runnable? = null

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
     * 主题选项数据类
     *
     * @property displayName 用户可见的主题名称
     * @property code 主题代码,对应 [com.tesla.dashboard.util.ThemeManager] 的 applyTheme 分支
     */
    private data class ThemeOption(val displayName: String, val code: String)

    /**
     * 可选主题列表(显示名称 ↔ 主题代码)
     *
     * 包含经典深/浅 + 4 种彩色主题(深/浅各一) + 跟随系统。
     */
    private val themeOptions = listOf(
        ThemeOption("跟随系统", "system"),
        ThemeOption("深色", "dark"),
        ThemeOption("浅色", "light"),
        ThemeOption("特斯拉蓝（深色）", "tesla_blue"),
        ThemeOption("特斯拉蓝（浅色）", "tesla_blue_light"),
        ThemeOption("森林绿（深色）", "forest_green"),
        ThemeOption("森林绿（浅色）", "forest_green_light"),
        ThemeOption("琥珀橙（深色）", "ember_orange"),
        ThemeOption("琥珀橙（浅色）", "ember_orange_light"),
        ThemeOption("午夜紫（深色）", "midnight_purple"),
        ThemeOption("午夜紫（浅色）", "midnight_purple_light"),
    )

    /**
     * 可选车型列表(显示名称 ↔ 车型代码)
     *
     * 显示名称包含电池容量和化学类型，便于用户精确选择。
     * 车型代码对应 [com.tesla.dashboard.data.model.BatteryConfig.capacityByModel] 中的 key。
     */
    private val batteryModelOptions = listOf(
        // Model S — Nosecone 初代 / Facelift 改款 / Raven 更新 / Palladium 焕新版
        BatteryModelOption("Model S 60 kWh (Nosecone 初代)", "model_s_60"),
        BatteryModelOption("Model S 75 kWh (Nosecone/Facelift)", "model_s_75"),
        BatteryModelOption("Model S 85 kWh (Nosecone 初代)", "model_s_85"),
        BatteryModelOption("Model S 90 kWh (Nosecone 初代)", "model_s_90"),
        BatteryModelOption("Model S 100 kWh (Facelift/Raven)", "model_s_100"),
        BatteryModelOption("Model S Plaid (100 kWh Palladium)", "model_s_plaid"),
        // Model 3 — 旧版 / Highland 焕新版
        BatteryModelOption("Model 3 标准版 (60 kWh LFP)", "model_3_standard"),
        BatteryModelOption("Model 3 标准版 (75 kWh NMC)", "model_3_standard_nmc"),
        BatteryModelOption("Model 3 长续航 (78 kWh)", "model_3_long_range"),
        BatteryModelOption("Model 3 Performance (78 kWh 旧版)", "model_3_performance"),
        BatteryModelOption("Model 3 Performance (82 kWh Highland)", "model_3_performance_highland"),
        // Model X — Original 初代 / Raven 更新 / Palladium 焕新版
        BatteryModelOption("Model X 75 kWh (Original 初代)", "model_x_75"),
        BatteryModelOption("Model X 90 kWh (Original 初代)", "model_x_90"),
        BatteryModelOption("Model X 100 kWh (Original/Raven)", "model_x_100"),
        BatteryModelOption("Model X Plaid (100 kWh Palladium)", "model_x_plaid"),
        // Model Y — 旧版 / Juniper 焕新版
        BatteryModelOption("Model Y 标准版 (60 kWh LFP)", "model_y_standard"),
        BatteryModelOption("Model Y 标准版 (75 kWh NMC)", "model_y_standard_nmc"),
        BatteryModelOption("Model Y 长续航 (78 kWh 旧版)", "model_y_long_range"),
        BatteryModelOption("Model Y 长续航 (81 kWh Juniper)", "model_y_long_range_juniper"),
        BatteryModelOption("Model Y Performance (78 kWh)", "model_y_performance"),
        // Cybertruck — 初代
        BatteryModelOption("Cybertruck 双电机 (123 kWh)", "cybertruck_dual"),
        BatteryModelOption("Cybertruck 三电机 (123 kWh)", "cybertruck_tri"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 全屏沉浸式配置
        setupImmersiveMode()

        // 2. 初始化 ViewBinding
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 设置车型下拉菜单
        setupBatteryModelDropdown()

        // 3b. 设置主题下拉菜单
        setupThemeDropdown()

        // 3c. 初始化状态指示灯条
        setupIndicatorStrip()

        // 4. 设置按钮监听
        setupClickListeners()

        // 5. 设置即时自动保存
        setupAutoSave()

        // 6. 立即应用当前主题配色 (避免初始回退色闪现)
        applyThemeColors(themeManager.colors.value)

        // 7. 观察数据
        observeViewModel()

        // 8. 显示应用版本号
        setupVersionLabel()
    }

    /**
     * 在设置页底部显示应用版本号
     *
     * 从 BuildConfig 读取 versionName,格式: "Tesla Dashboard v0.2.1.2"
     */
    private fun setupVersionLabel() {
        binding.tvVersion.text = getString(R.string.settings_version_format, BuildConfig.VERSION_NAME)
    }

    /**
     * 配置全屏沉浸式模式
     */
    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 设置车型下拉菜单
     */
    private fun setupBatteryModelDropdown() {
        val displayNames = batteryModelOptions.map { it.displayName }
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            displayNames,
        )
        binding.actvBatteryModel.setAdapter(adapter)

        binding.actvBatteryModel.setOnItemClickListener { _, _, position, _ ->
            selectedBatteryModel = batteryModelOptions[position].code
            // 车型选择后即时自动保存
            if (isFormPopulated) {
                viewModel.saveBatteryModel(selectedBatteryModel)
            }
        }
    }

    /**
     * 设置主题下拉菜单
     *
     * 主题选项列表: 跟随系统 / 深色 / 浅色 + 4 种彩色主题(深浅各一)
     */
    private fun setupThemeDropdown() {
        val adapter = ArrayAdapter(
            this,
            R.layout.item_dropdown,
            themeOptions.map { it.displayName },
        )
        binding.actvTheme.setAdapter(adapter)

        binding.actvTheme.setOnItemClickListener { _, _, position, _ ->
            val mode = themeOptions[position].code
            // 主题选择后即时自动保存并实时应用
            if (isFormPopulated) {
                viewModel.saveThemeMode(mode)
                themeManager.setThemeMode(mode)
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

        // 配对车辆按钮 — 启动配对向导 Activity
        binding.btnPair.setOnClickListener {
            val vin = binding.etVin.text.toString().trim()
            if (vin.isBlank()) {
                Toast.makeText(this, "请先输入 VIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (vin.length != 17) {
                Toast.makeText(this, "VIN 应为 17 位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, PairingActivity::class.java)
            intent.putExtra(PairingActivity.EXTRA_VIN, vin)
            startActivity(intent)
        }

        // 测试连接按钮
        binding.btnTestConnection.setOnClickListener {
            val vin = binding.etVin.text.toString().trim()
            if (vin.isBlank()) {
                Toast.makeText(this, "请先输入 VIN", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            testConnectionWithPermissionCheck(vin)
        }

        // 解除配对按钮
        binding.btnUnpair.setOnClickListener {
            viewModel.unpair()
            Toast.makeText(this, "已解除配对", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 观察 ViewModel 的数据流
     *
     * - [observeUiState]: 首次收到已持久化的设置值时填充表单,并更新配对状态 UI
     * - [observePairingState]: 实时显示 BLE 配对进度
     */
    private fun observeViewModel() {
        // 观察 UI 状态
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 仅当 DataStore 数据已加载时填充表单。
                    // 修复竞态: stateIn 的 initialValue(全默认空值) 会先于真实数据发射,
                    // 若不加 isLoaded 守卫, 空值会先填充表单并将 isFormPopulated 置 true,
                    // 导致真实已保存数据被跳过, 界面始终显示默认值(看起来"恢复原设置")。
                    if (state.isLoaded && !isFormPopulated) {
                        populateForm(state)
                        isFormPopulated = true
                    }
                    updatePairingStatus(state.isPaired)
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

        // 观察主题颜色 — 实时应用配色(切换主题后设置页即时刷新)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                themeManager.colors.collect { colors ->
                    applyThemeColors(colors)
                }
            }
        }
    }

    /**
     * 应用主题颜色到设置页所有 UI 元素
     *
     * 在 [ThemeManager.colors] 发射新值时调用,统一刷新:
     * - 根布局背景
     * - 标题与返回按钮
     * - 三张卡片的背景/描边
     * - 分区标题、输入框、下拉框
     * - 配对状态与三个 BLE 按钮
     * - 主题选项 RadioButton
     *
     * @param c 当前主题颜色集合
     */
    private fun applyThemeColors(c: ThemeColors) {
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
        binding.cardAppearance.strokeColor = c.divider
        binding.cardAppearance.setCardBackgroundColor(c.cardBackground)

        // ===== 分区标题 (强调色) =====
        binding.tvSectionBle.setTextColor(c.accentCyan)
        binding.tvSectionVehicle.setTextColor(c.accentCyan)
        binding.tvSectionAppearance.setTextColor(c.accentCyan)

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

        // ===== 车型下拉框 =====
        binding.tilBatteryModel.boxStrokeColor = c.accentCyan
        binding.actvBatteryModel.setTextColor(c.textPrimary)

        // ===== 外观卡: 主题标签与主题下拉框 =====
        binding.tvThemeLabel.setTextColor(c.textSecondary)
        binding.tilTheme.boxStrokeColor = c.accentCyan
        binding.actvTheme.setTextColor(c.textPrimary)

        // ===== 状态指示灯条配色 =====
        binding.indicatorStrip.setActiveColor(c.accentGreen)
        binding.indicatorStrip.setInactiveColor(c.textSecondary)

        // ===== 版本号文字 =====
        binding.tvVersion.setTextColor(c.textSecondary)
    }

    /**
     * 用已保存的设置值填充表单
     */
    private fun populateForm(state: SettingsUiState) {
        // VIN
        binding.etVin.setText(state.vin)

        // 主题 (下拉菜单)
        val themeOption = themeOptions.find { it.code == state.themeMode }
        if (themeOption != null) {
            binding.actvTheme.setText(themeOption.displayName, false)
        }

        // 车型
        selectedBatteryModel = state.batteryModel
        val option = batteryModelOptions.find { it.code == state.batteryModel }
        if (option != null) {
            binding.actvBatteryModel.setText(option.displayName, false)
        }
    }

    /**
     * 更新配对状态 UI
     *
     * 根据配对状态更新:
     * - 配对状态文字(已配对/未配对)和颜色
     * - 按钮启用/禁用状态
     *
     * @param isPaired 是否已配对
     */
    private fun updatePairingStatus(isPaired: Boolean) {
        // 同步 BLE 状态指示灯
        binding.indicatorStrip.updateIndicator("ble", isPaired)
        if (isPaired) {
            binding.tvPairingStatus.text = getString(R.string.settings_paired)
            binding.tvPairingStatus.setTextColor(currentColors.accentGreen)
            binding.btnPair.isEnabled = false
            binding.btnTestConnection.isEnabled = true
            binding.btnUnpair.isEnabled = true
        } else {
            binding.tvPairingStatus.text = getString(R.string.settings_not_paired)
            binding.tvPairingStatus.setTextColor(currentColors.textSecondary)
            binding.btnPair.isEnabled = true
            binding.btnTestConnection.isEnabled = false
            binding.btnUnpair.isEnabled = false
        }
    }

    /**
     * 更新配对进度显示
     *
     * 根据 [TeslaBleProvider.PairingState] 显示对应的进度文字。
     * Idle 和 Completed 状态隐藏进度文字。
     *
     * @param state 当前配对状态
     */
    private fun updatePairingProgress(state: TeslaBleProvider.PairingState) {
        val (text, visible) = when (state) {
            is TeslaBleProvider.PairingState.Idle -> "" to false
            is TeslaBleProvider.PairingState.GeneratingKey -> "正在生成密钥…" to true
            is TeslaBleProvider.PairingState.Scanning -> "正在扫描车辆…" to true
            is TeslaBleProvider.PairingState.Connecting -> "正在连接车辆…" to true
            is TeslaBleProvider.PairingState.Handshaking -> "正在建立加密通道…" to true
            is TeslaBleProvider.PairingState.SendingPairRequest -> "正在发送配对请求…" to true
            is TeslaBleProvider.PairingState.WaitingForNfcConfirmation ->
                getString(R.string.settings_pairing_nfc_prompt) to true
            is TeslaBleProvider.PairingState.SavingKey -> "正在保存密钥…" to true
            is TeslaBleProvider.PairingState.Completed -> "" to false
            is TeslaBleProvider.PairingState.Failed -> "配对失败: ${state.message}" to true
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
        }

        // 配对完成或失败后恢复按钮状态
        if (state is TeslaBleProvider.PairingState.Completed) {
            Toast.makeText(this, R.string.settings_pairing_success, Toast.LENGTH_SHORT).show()
        } else if (state is TeslaBleProvider.PairingState.Failed) {
            Toast.makeText(this, R.string.settings_pairing_failed, Toast.LENGTH_SHORT).show()
            // 恢复按钮状态(基于当前配对状态)
            viewModel.uiState.value.let { updatePairingStatus(it.isPaired) }
        }
    }

    // ===== BLE 权限处理 =====

    /**
     * 检查是否拥有 BLE 所需权限
     *
     * - Android 12+ (API 31+): 需要 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT
     * - Android 6-11 (API 23-30): 需要 ACCESS_FINE_LOCATION (BLE 扫描需要位置权限)
     *
     * @return true 如果所有必要权限已授予
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
     *
     * 先检查 BLE 权限,若缺少则请求权限,授予后自动执行配对。
     *
     * @param vin 车辆识别号
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
     *
     * @param vin 车辆识别号
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
     *
     * @param action 待执行的 BLE 操作
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
     *
     * 调用 ViewModel.startPairing,配对期间禁用按钮。
     * 结果通过 Toast 反馈。
     *
     * @param vin 车辆识别号
     */
    private fun startPairing(vin: String) {
        binding.btnPair.isEnabled = false
        binding.btnTestConnection.isEnabled = false
        binding.btnUnpair.isEnabled = false

        viewModel.startPairing(vin) { success ->
            runOnUiThread {
                if (!success) {
                    // 配对失败,恢复按钮状态
                    viewModel.uiState.value.let { updatePairingStatus(it.isPaired) }
                }
                // 成功时 updatePairingProgress(Completed) 会显示 Toast
                // 失败时 updatePairingProgress(Failed) 会显示 Toast
            }
        }
    }

    /**
     * 测试 BLE 连接
     *
     * 调用 ViewModel.testConnection,测试期间禁用按钮并显示进度。
     *
     * @param vin 车辆识别号
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
     * 所有设置项修改后立即持久化到 DataStore,无需点击保存按钮:
     * - VIN: TextWatcher + 500ms debounce,防止每次按键都写磁盘
     * - 主题: 下拉菜单选择时立即保存 (在 [setupThemeDropdown] 中注册)
     * - 车型: 下拉选择时立即保存 (在 [setupBatteryModelDropdown] 中注册)
     *
     * 注意: 通过 [isFormPopulated] 守卫, 避免 [populateForm] 回填表单时触发误保存。
     * 自动保存使 DataStore 写入发生在页面存活期间, 解决原"保存后立即 finish()
     * 导致写入协程被取消、再次进入时设置清空"的问题。
     */
    private fun setupAutoSave() {
        // VIN 即时保存 (带 debounce)
        binding.etVin.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 表单回填也会触发此回调, 必须跳过
                if (!isFormPopulated) return
                vinSaveRunnable?.let { vinDebounceHandler.removeCallbacks(it) }
                val value = s?.toString()?.trim().orEmpty()
                vinSaveRunnable = Runnable {
                    if (value != lastSavedVin) {
                        lastSavedVin = value
                        viewModel.saveVin(value)
                    }
                }
                vinDebounceHandler.postDelayed(vinSaveRunnable!!, VIN_DEBOUNCE_MS)
            }
        })
    }

    /**
     * 销毁时清理并立即执行未触发的 VIN 保存任务
     *
     * VIN 自动保存采用 500ms debounce, 若用户在 debounce 窗口内退出,
     * 未执行的保存任务会被清除导致丢失。此处先移除回调再同步执行,
     * 确保退出前最后一次输入的 VIN 也能写入。
     */
    override fun onDestroy() {
        super.onDestroy()
        vinSaveRunnable?.let { runnable ->
            vinDebounceHandler.removeCallbacks(runnable)
            runnable.run()
        }
        vinSaveRunnable = null
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
