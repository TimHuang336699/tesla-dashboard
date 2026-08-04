package com.tesla.dashboard.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.R
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.databinding.ActivityPairingBinding
import com.tesla.dashboard.ui.settings.SettingsViewModel
import com.tesla.dashboard.util.NfcLocationType
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.ThemeManager
import com.tesla.dashboard.util.VinDecoder
import com.tesla.dashboard.util.nfcLocationType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BLE 配对向导 Activity — 分步引导用户完成 Tesla 车辆 BLE 配对
 *
 * 采用 4 步向导式设计,苹果式简约风格:
 *
 * ## 配对流程 (状态机)
 * ```
 * Step 1 (VIN 输入)
 *   ↓  用户输入 17 位 VIN,点击"下一步"
 * Step 2 (密钥生成)
 *   ↓  自动进行: 生成密钥 → 扫描 → 连接 → 握手 → 发送配对请求
 * Step 3 (NFC 确认)
 *   ↓  用户在车机刷 NFC 卡片确认,自动保存密钥
 * Step 4 (配对成功)
 *   ↓  用户点击"完成"关闭页面
 * ```
 *
 * ## 状态驱动
 * 通过观察 [SettingsViewModel.pairingState] (即 [TeslaBleProvider.PairingState]) 驱动步骤切换:
 * - `WaitingForNfcConfirmation` → 切换到 Step 3
 * - `Completed` → 切换到 Step 4
 * - `Failed` → 显示错误并回退到 Step 1
 *
 * ## 主题
 * 通过 [ThemeManager.colors] 实时应用主题颜色到所有视图,无需重建 Activity。
 *
 * ## 沉浸式
 * 与 [com.tesla.dashboard.ui.dashboard.DashboardActivity] 一致的全屏沉浸式模式。
 *
 * @see SettingsViewModel
 * @see TeslaBleProvider.PairingState
 * @see VinDecoder
 * @see ThemeManager
 */
@AndroidEntryPoint
class PairingActivity : AppCompatActivity() {

    companion object {
        /** Intent extra key: 从 SettingsActivity 传入的车辆 VIN */
        const val EXTRA_VIN = "vin"

        /** 步骤总数 */
        private const val TOTAL_STEPS = 4
    }

    // ===== 步骤索引 (对应 ViewFlipper 子 View 的位置) =====

    /** Step 1: VIN 输入页 */
    private val STEP_VIN_INPUT = 0

    /** Step 2: 密钥生成页 */
    private val STEP_GENERATE_KEY = 1

    /** Step 3: NFC 确认页 */
    private val STEP_NFC_CONFIRM = 2

    /** Step 4: 配对成功页 */
    private val STEP_SUCCESS = 3

    // ===== ViewBinding / ViewModel / DI =====

    /** ViewBinding 实例 */
    private lateinit var binding: ActivityPairingBinding

    /** 设置页面 ViewModel,复用其 BLE 配对逻辑 */
    private val viewModel: SettingsViewModel by viewModels()

    /** 主题管理器,由 Hilt 注入 */
    @Inject
    lateinit var themeManager: ThemeManager

    // ===== 运行时状态 =====

    /** 当前步骤索引 */
    private var currentStep = STEP_VIN_INPUT

    /** 当前 VIN (用户输入或从 Intent 传入) */
    private var currentVin = ""

    /**
     * 当前主题颜色快照 (供 updateStepDots 等方法使用)
     */
    private var currentColors: ThemeColors = ThemeColors.Dark

    /** TextWatcher 递归保护标记 (防止大写转换时无限递归) */
    private var isFormattingVin = false

    /**
     * 当前 VIN 对应的车型代际 NFC 位置类型
     *
     * 缓存解码结果,避免每次进入 Step 3 都重新解码 VIN。
     * 默认值为 [NfcLocationType.UNKNOWN] (使用默认中控台插图)。
     */
    private var nfcLocation: NfcLocationType = NfcLocationType.UNKNOWN

    /** 当前车型的代际描述 (用于 tvNfcGenTag 显示) */
    private var nfcGenerationText: String = ""

    // ===== BLE 权限请求器 =====

    /** BLE 权限请求启动器,授权后自动开始配对 */
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted) {
            // 权限已授予,开始配对
            startPairing()
        } else {
            // 权限被拒绝,提示并回退到 Step 1
            Toast.makeText(this, "需要蓝牙权限,请授予后重试", Toast.LENGTH_SHORT).show()
            goToStep(STEP_VIN_INPUT)
        }
    }

    // ================================================================
    //  生命周期
    // ================================================================

    /**
     * Activity 创建入口
     *
     * 初始化顺序:
     * 1. 全屏沉浸式配置
     * 2. ViewBinding
     * 3. 点击监听
     * 4. VIN 输入监听
     * 5. 预填充 VIN (从 Intent extra)
     * 6. 观察 ViewModel (配对状态 + 主题颜色)
     * 7. 应用初始主题
     * 8. 进入 Step 1
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 全屏沉浸式配置
        setupImmersiveMode()

        // 2. 初始化 ViewBinding
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 设置点击监听
        setupClickListeners()

        // 4. 设置 VIN 输入监听 (自动解码)
        setupVinInput()

        // 5. 预填充 VIN (从 SettingsActivity 传入)
        prefillVinFromIntent()

        // 6. 观察 ViewModel
        observeViewModel()

        // 7. 应用初始主题颜色
        currentColors = themeManager.colors.value
        applyTheme(currentColors)

        // 8. 进入 Step 1
        goToStep(STEP_VIN_INPUT)
    }

    /**
     * Activity 暂停时停止 NFC 涟漪动画
     *
     * 避免在后台继续运行动画浪费资源。
     * 重新进入前台 (onResume) 后,若当前处于 Step 3 会自动重新启动涟漪。
     */
    override fun onPause() {
        super.onPause()
        binding.nfcRipple.stopRipple()
    }

    /**
     * Activity 销毁时主动取消配对协程
     *
     * 关键修复: 解决"配对中途按返回键退出页面 → 下次进入配对页卡死"问题。
     *
     * 原因:
     * - `startPairing` 在 `SettingsViewModel.viewModelScope` 中运行, 即使用户退出 PairingActivity,
     *   协程仍在后台等待 `receiveMessage(30000ms)` 的 NFC 确认, GATT 连接未释放。
     * - 下次进入 PairingActivity 时, GATT 资源被旧连接占用, 新配对会立即超时失败。
     * - pairingState 仍停留在 WaitingForNfcConfirmation, UI 显示卡死无法点击"下一步"。
     *
     * 解决: 在 onDestroy 中调用 [SettingsViewModel.cancelPairing],
     * 主动取消协程 + 释放 GATT + 重置状态为 Idle。
     */
    override fun onDestroy() {
        super.onDestroy()
        viewModel.cancelPairing()
        // 兜底: 即使协程因异常未捕获没走 cancelPairing 路径, 也停掉涟漪
        binding.nfcRipple.stopRipple()
    }

    // ================================================================
    //  沉浸式模式
    // ================================================================

    /**
     * 配置全屏沉浸式模式
     *
     * 与 DashboardActivity 一同:
     * - 内容延伸到系统栏区域
     * - 屏幕常亮
     * - 隐藏状态栏和导航栏
     * - 滑动边缘时短暂显示系统栏后自动隐藏
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

    // ================================================================
    //  点击监听
    // ================================================================

    /**
     * 设置所有按钮的点击监听
     *
     * - [binding.btnBack]: 顶部返回按钮,关闭页面
     * - [binding.btnPrev]: 底部"上一步"按钮
     * - [binding.btnNext]: 底部"下一步"按钮 (Step 1 时触发配对)
     * - [binding.btnFinish]: Step 4"完成"按钮,关闭页面
     */
    private fun setupClickListeners() {
        // 顶部返回按钮 — 直接关闭页面
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 底部"上一步"按钮
        binding.btnPrev.setOnClickListener {
            if (currentStep > STEP_VIN_INPUT) {
                goToStep(currentStep - 1)
            }
        }

        // 底部"下一步"按钮
        binding.btnNext.setOnClickListener {
            onNextStepClicked()
        }

        // Step 4"完成"按钮 — 关闭页面
        binding.btnFinish.setOnClickListener {
            finish()
        }
    }

    /**
     * "下一步"按钮点击处理
     *
     * 根据 [currentStep] 执行不同操作:
     * - Step 1: 检查 BLE 权限后开始配对,切换到 Step 2
     * - 其他步骤: 由配对状态自动驱动,不响应手动点击
     */
    private fun onNextStepClicked() {
        when (currentStep) {
            STEP_VIN_INPUT -> {
                // 校验 VIN
                val vin = binding.etVinInput.text.toString().trim().uppercase()
                if (vin.length != 17) {
                    Toast.makeText(this, "VIN 应为 17 位", Toast.LENGTH_SHORT).show()
                    return
                }
                currentVin = vin
                // 检查 BLE 权限后开始配对
                startPairingWithPermissionCheck()
            }
            // Step 2/3 由配对状态自动驱动,不响应手动点击
        }
    }

    // ================================================================
    //  VIN 输入与自动解码
    // ================================================================

    /**
     * 设置 VIN 输入框的文本变化监听
     *
     * - 自动将输入转为大写
     * - 输入满 17 位时调用 [VinDecoder.decode] 解码并展示车辆信息
     * - 输入不足 17 位时隐藏车辆信息卡片并禁用"下一步"按钮
     */
    private fun setupVinInput() {
        binding.etVinInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (isFormattingVin) return

                val text = s?.toString() ?: ""
                val upper = text.uppercase()

                // 自动大写转换 (部分键盘不遵守 inputType=textCapCharacters)
                if (text != upper) {
                    isFormattingVin = true
                    s?.replace(0, s.length, upper, 0, upper.length)
                    isFormattingVin = false
                    return
                }

                // 根据输入长度更新 UI
                if (upper.length == 17) {
                    // 满 17 位,尝试解码
                    decodeAndShowVinInfo(upper)
                } else {
                    // 不足 17 位,隐藏信息卡片和错误提示,禁用下一步
                    binding.layoutVinInfo.visibility = View.GONE
                    binding.tvVinError.visibility = View.GONE
                    binding.btnNext.isEnabled = false
                }
            }
        })
    }

    /**
     * 从 Intent extra 预填充 VIN
     *
     * 如果 SettingsActivity 传入了 VIN,自动填入输入框并触发解码。
     */
    private fun prefillVinFromIntent() {
        intent.getStringExtra(EXTRA_VIN)?.let { vin ->
            if (vin.isNotBlank()) {
                binding.etVinInput.setText(vin.uppercase())
                // 将光标移到末尾
                binding.etVinInput.setSelection(vin.length)
            }
        }
    }

    /**
     * 解码 VIN 并展示车辆信息
     *
     * 调用 [VinDecoder.decode] 解析 17 位 VIN:
     * - 解码成功: 显示车型、年份、电池、驱动类型、制造商信息,启用"下一步"按钮
     * - 解码失败: 显示错误提示,禁用"下一步"按钮
     *
     * 同时缓存 [nfcLocation] 和 [nfcGenerationText] 以供 Step 3 切换 NFC 位置插图使用。
     *
     * @param vin 17 位车辆识别号 (已大写)
     */
    private fun decodeAndShowVinInfo(vin: String) {
        val info = VinDecoder.decode(vin)

        if (info != null) {
            // 解码成功 — 展示车辆信息
            binding.tvVinError.visibility = View.GONE
            binding.layoutVinInfo.visibility = View.VISIBLE

            binding.tvVehicleModel.text = info.model
            binding.tvVehicleGeneration.text = info.generation
            binding.tvVehicleYear.text = info.modelYear.toString()
            binding.tvVehicleBattery.text = info.batteryType
            binding.tvVehicleChemistry.text = info.batteryChemistry
            binding.tvVehicleDrive.text = info.driveType
            binding.tvVehicleBodyType.text = info.bodyType
            binding.tvVehicleManufacturer.text = info.plant

            // 缓存 NFC 位置类型与代际文本 (供 Step 3 切换插图与说明)
            nfcLocation = nfcLocationType(info)
            nfcGenerationText = info.generation

            // 启用"下一步"按钮
            binding.btnNext.isEnabled = true
        } else {
            // 解码失败 — 显示错误提示
            binding.layoutVinInfo.visibility = View.GONE
            binding.tvVinError.text = "无法识别该 VIN,请确认是否为 Tesla 车辆"
            binding.tvVinError.visibility = View.VISIBLE
            binding.btnNext.isEnabled = false
            // 重置 NFC 位置信息,使用默认插图
            nfcLocation = NfcLocationType.UNKNOWN
            nfcGenerationText = ""
        }
    }

    // ================================================================
    //  ViewModel 观察
    // ================================================================

    /**
     * 观察 ViewModel 的数据流
     *
     * - [observePairingState]: 根据 BLE 配对状态自动切换步骤
     * - [observeThemeColors]: 主题颜色变化时实时刷新所有视图
     */
    private fun observeViewModel() {
        // 观察配对状态 — 驱动步骤切换
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pairingState.collect { state ->
                    handlePairingState(state)
                }
            }
        }

        // 观察主题颜色 — 实时应用主题
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                themeManager.colors.collect { colors ->
                    currentColors = colors
                    applyTheme(colors)
                }
            }
        }
    }

    /**
     * 处理配对状态变化,驱动步骤切换
     *
     * 状态与步骤的映射关系:
     * ```
     * GeneratingKey / Scanning / Connecting / Handshaking / SendingPairRequest
     *   → 保持 Step 2,更新进度文字
     * WaitingForNfcConfirmation
     *   → 切换到 Step 3
     * Completed
     *   → 切换到 Step 4
     * Failed
     *   → 显示错误,回退到 Step 1
     * ```
     *
     * 注意: 仅在配对进行中 (Step 2/3) 时响应状态变化,
     * 避免历史状态 (如上一次配对的 Failed/Completed) 误触发。
     *
     * @param state 当前 BLE 配对状态
     */
    private fun handlePairingState(state: TeslaBleProvider.PairingState) {
        when (state) {
            is TeslaBleProvider.PairingState.WaitingForNfcConfirmation -> {
                // 等待 NFC 确认 → 切换到 Step 3
                if (currentStep == STEP_GENERATE_KEY) {
                    goToStep(STEP_NFC_CONFIRM)
                }
            }

            is TeslaBleProvider.PairingState.Completed -> {
                // 配对完成 → 切换到 Step 4
                if (currentStep == STEP_GENERATE_KEY || currentStep == STEP_NFC_CONFIRM) {
                    goToStep(STEP_SUCCESS)
                }
            }

            is TeslaBleProvider.PairingState.Failed -> {
                // 配对失败 → 显示错误,回退到 Step 1
                if (currentStep == STEP_GENERATE_KEY || currentStep == STEP_NFC_CONFIRM) {
                    Toast.makeText(
                        this,
                        "配对失败: ${state.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    goToStep(STEP_VIN_INPUT)
                }
            }

            is TeslaBleProvider.PairingState.SavingKey -> {
                // 正在保存密钥 — 如果在 NFC 确认页,更新提示文字
                if (currentStep == STEP_NFC_CONFIRM) {
                    binding.tvNfcInstruction.text = "正在保存密钥..."
                }
            }

            else -> {
                // 其他进行中状态 (GeneratingKey / Scanning / Connecting / Handshaking / SendingPairRequest)
                // 仅在 Step 2 时更新进度文字
                if (currentStep == STEP_GENERATE_KEY) {
                    updateKeyGenText(state)
                }
            }
        }
    }

    /**
     * 更新密钥生成页 (Step 2) 的进度文字
     *
     * 根据当前配对状态显示对应的进度描述。
     *
     * @param state 当前配对状态 (非终态)
     */
    private fun updateKeyGenText(state: TeslaBleProvider.PairingState) {
        val text = when (state) {
            is TeslaBleProvider.PairingState.GeneratingKey -> "正在生成密钥..."
            is TeslaBleProvider.PairingState.Scanning -> "正在扫描车辆..."
            is TeslaBleProvider.PairingState.Connecting -> "正在连接车辆..."
            is TeslaBleProvider.PairingState.Handshaking -> "正在建立加密通道..."
            is TeslaBleProvider.PairingState.SendingPairRequest -> "正在发送配对请求..."
            else -> "正在生成密钥..."
        }
        binding.tvKeyGenText.text = text
    }

    // ================================================================
    //  步骤切换 (状态机)
    // ================================================================

    /**
     * 切换到指定步骤
     *
     * 更新以下 UI 元素:
     * - ViewFlipper 显示对应步骤的子页面
     * - 步骤指示文字 (如 "Step 2/4")
     * - 步骤进度点高亮
     * - 底部按钮栏的显隐与启用状态
     * - Step 3 的 NFC 位置插图 (根据车型代际切换)
     *
     * @param step 目标步骤索引 [STEP_VIN_INPUT] … [STEP_SUCCESS]
     */
    private fun goToStep(step: Int) {
        // 离开 Step 3 时停止 NFC 涟漪动画
        if (currentStep == STEP_NFC_CONFIRM && step != STEP_NFC_CONFIRM) {
            binding.nfcRipple.stopRipple()
        }

        currentStep = step

        // 切换 ViewFlipper 显示的子页面
        binding.stepContainer.displayedChild = step

        // 进入 Step 3 时启动 NFC 涟漪动画,并应用车型代际插图
        if (step == STEP_NFC_CONFIRM) {
            applyNfcLocationForCurrentVin()
            binding.nfcRipple.startRipple()
        }

        // 更新步骤指示文字和进度点
        updateStepIndicator()
        updateStepDots()

        // 更新底部按钮栏
        updateBottomBar()
    }

    /**
     * 根据当前 [nfcLocation] 切换 Step 3 的 NFC 位置插图与说明文字
     *
     * 切换规则:
     * - [VinDecoder.NfcLocationType.CENTER_CONSOLE] (焕新版 Model 3/Y/S/X) →
     *   使用 `nfc_location_highland.png` (中控台无线充电板插图)
     *   提示文字: "请在中控台无线充电板上方刷 NFC 卡片"
     * - [VinDecoder.NfcLocationType.CUP_HOLDER] (老款 Model 3/Y/S/X) →
     *   使用 `nfc_location_legacy.gif` (杯架后方插图)
     *   提示文字: "请在前排中央扶手杯架后方刷 NFC 卡片"
     * - [VinDecoder.NfcLocationType.UNKNOWN] (降级默认) →
     *   使用 `nfc_location_highland.png`,提示文字保持中控台默认
     *
     * 同时在 tvNfcGenTag 上显示车型代际文本 (如 "Highland 焕新版" / "旧版 Model 3")。
     */
    private fun applyNfcLocationForCurrentVin() {
        when (nfcLocation) {
            NfcLocationType.CENTER_CONSOLE -> {
                // 焕新版: 中控台无线充电板
                binding.ivNfcLocation.setImageResource(R.drawable.nfc_location_highland)
                binding.tvNfcInstruction.text = getString(R.string.pairing_nfc_instruction_highland)
                // 副标题显示代际 (例如 "Highland 焕新版 Model 3")
                if (nfcGenerationText.isNotBlank()) {
                    binding.tvNfcGenTag.text = nfcGenerationText
                    binding.tvNfcGenTag.visibility = View.VISIBLE
                } else {
                    binding.tvNfcGenTag.visibility = View.GONE
                }
            }
            NfcLocationType.CUP_HOLDER -> {
                // 老款: 杯架后方
                binding.ivNfcLocation.setImageResource(R.drawable.nfc_location_legacy)
                binding.tvNfcInstruction.text = getString(R.string.pairing_nfc_instruction_legacy)
                // 副标题显示代际
                if (nfcGenerationText.isNotBlank()) {
                    binding.tvNfcGenTag.text = nfcGenerationText
                    binding.tvNfcGenTag.visibility = View.VISIBLE
                } else {
                    binding.tvNfcGenTag.visibility = View.GONE
                }
            }
            NfcLocationType.UNKNOWN -> {
                // 未识别: 默认中控台插图 + 中性提示
                binding.ivNfcLocation.setImageResource(R.drawable.nfc_location_highland)
                binding.tvNfcInstruction.text = getString(R.string.pairing_nfc_instruction_default)
                binding.tvNfcGenTag.visibility = View.GONE
            }
        }
    }

    /**
     * 更新步骤指示文字
     *
     * 显示格式: "Step 1/4" … "Step 4/4"
     */
    private fun updateStepIndicator() {
        binding.tvStepIndicator.text = "Step ${currentStep + 1}/$TOTAL_STEPS"
    }

    /**
     * 更新步骤进度点颜色
     *
     * 当前及之前步骤的点为强调色 (accentBlue),之后的点为分割线色 (divider)。
     */
    private fun updateStepDots() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3, binding.dot4)
        dots.forEachIndexed { index, dot ->
            val isActive = index <= currentStep
            val color = if (isActive) currentColors.accentBlue else currentColors.divider
            dot.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    /**
     * 更新底部按钮栏的显隐与启用状态
     *
     * - Step 1: 显示底部栏,"上一步"隐藏,"下一步"可见 (VIN 有效时启用)
     * - Step 2/3: 隐藏底部栏 (由配对状态自动驱动)
     * - Step 4: 隐藏底部栏 (显示"完成"按钮)
     */
    private fun updateBottomBar() {
        when (currentStep) {
            STEP_VIN_INPUT -> {
                // Step 1: 显示底部栏,隐藏"上一步"
                binding.bottomBar.visibility = View.VISIBLE
                binding.btnPrev.visibility = View.GONE
                binding.btnNext.visibility = View.VISIBLE
                // "下一步"启用状态由 VIN 解码结果决定
                val vin = binding.etVinInput.text.toString().trim()
                binding.btnNext.isEnabled = vin.length == 17
            }

            STEP_GENERATE_KEY, STEP_NFC_CONFIRM -> {
                // Step 2/3: 隐藏底部栏 (自动驱动)
                binding.bottomBar.visibility = View.GONE
            }

            STEP_SUCCESS -> {
                // Step 4: 隐藏底部栏 (使用"完成"按钮)
                binding.bottomBar.visibility = View.GONE
            }
        }
    }

    // ================================================================
    //  BLE 权限处理
    // ================================================================

    /**
     * 检查是否拥有 BLE 所需权限
     *
     * - Android 12+ (API 31+): 需要 BLUETOOTH_SCAN 和 BLUETOOTH_CONNECT
     * - Android 6-11 (API 23-30): 需要 ACCESS_FINE_LOCATION
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
     * 先检查 BLE 权限:
     * - 已授予: 直接开始配对
     * - 未授予: 请求权限,授权后自动开始配对
     */
    private fun startPairingWithPermissionCheck() {
        if (hasBlePermissions()) {
            startPairing()
        } else {
            requestBlePermissions()
        }
    }

    // ================================================================
    //  BLE 配对
    // ================================================================

    /**
     * 开始 BLE 配对
     *
     * 调用 [SettingsViewModel.startPairing] 发起配对流程,并切换到 Step 2 (密钥生成页)。
     * 后续步骤切换由 [handlePairingState] 根据 [TeslaBleProvider.PairingState] 自动驱动。
     */
    private fun startPairing() {
        // 切换到 Step 2 (密钥生成页)
        goToStep(STEP_GENERATE_KEY)

        // 调用 ViewModel 开始配对
        // 步骤切换由 pairingState 观察驱动,此回调仅用于日志
        viewModel.startPairing(currentVin) { _ ->
            // 配对结果已通过 pairingState (Completed/Failed) 处理
            // 此处无需额外操作
        }
    }

    // ================================================================
    //  主题应用
    // ================================================================

    /**
     * 将主题颜色应用到所有视图
     *
     * 收集 [ThemeManager.colors] 变化时调用,实现无 Activity 重建的实时主题切换。
     * 遍历布局中所有需要着色的视图,逐一设置背景色、文字色、强调色等。
     *
     * @param colors 当前主题颜色集合
     */
    private fun applyTheme(colors: ThemeColors) {
        val density = resources.displayMetrics.density

        // --- 根布局背景 ---
        binding.rootLayout.setBackgroundColor(colors.background)

        // --- 顶部栏 ---
        binding.btnBack.backgroundTintList = ColorStateList.valueOf(colors.textSecondary)
        binding.tvStepIndicator.setTextColor(colors.textPrimary)

        // --- 步骤进度点 ---
        updateStepDots()

        // --- Step 1: VIN 输入页 ---
        binding.tvVinTitle.setTextColor(colors.textPrimary)
        binding.tvVinSubtitle.setTextColor(colors.textSecondary)
        binding.etVinInput.setTextColor(colors.textPrimary)
        binding.etVinInput.setHintTextColor(colors.textSecondary)
        // VIN 输入框背景 (圆角矩形 + 描边)
        binding.etVinInput.background = createRoundedBackground(
            fillColor = colors.surface,
            strokeColor = colors.divider,
            cornerRadius = 12f * density
        )
        binding.tvVinError.setTextColor(colors.accentRed)
        // 车辆信息卡片背景
        binding.layoutVinInfo.background = createRoundedBackground(
            fillColor = colors.surface,
            strokeColor = colors.divider,
            cornerRadius = 16f * density
        )
        // 信息卡片标题 ("车辆信息")
        val cardTitle = binding.layoutVinInfo.getChildAt(0) as? TextView
        cardTitle?.setTextColor(colors.accentBlue)
        // 信息卡片各行标签和值
        for (i in 1 until binding.layoutVinInfo.childCount) {
            val row = binding.layoutVinInfo.getChildAt(i)
            if (row is LinearLayout) {
                val label = row.getChildAt(0) as? TextView
                val value = row.getChildAt(1) as? TextView
                label?.setTextColor(colors.textSecondary)
                value?.setTextColor(colors.textPrimary)
            }
        }

        // --- Step 2: 密钥生成页 ---
        binding.progressBarKeyGen.indeterminateTintList = ColorStateList.valueOf(colors.accentBlue)
        binding.tvKeyGenText.setTextColor(colors.textPrimary)
        binding.tvKeyGenSubtitle.setTextColor(colors.textSecondary)

        // --- Step 3: NFC 确认页 ---
        binding.tvNfcTitle.setTextColor(colors.textPrimary)
        binding.tvNfcInstruction.setTextColor(colors.textSecondary)
        binding.tvNfcGenTag.setTextColor(colors.accentCyan)
        binding.nfcRipple.rippleColor = colors.accentCyan

        // --- Step 4: 成功页 ---
        binding.tvSuccessTitle.setTextColor(colors.textPrimary)
        binding.tvSuccessSubtitle.setTextColor(colors.textSecondary)
        binding.btnFinish.backgroundTintList = ColorStateList.valueOf(colors.accentBlue)

        // --- 底部按钮栏 ---
        binding.btnPrev.setTextColor(colors.textSecondary)
        binding.btnPrev.strokeColor = ColorStateList.valueOf(colors.divider)
        binding.btnNext.backgroundTintList = ColorStateList.valueOf(colors.accentBlue)
    }

    /**
     * 创建圆角矩形背景 drawable
     *
     * 用于 VIN 输入框和车辆信息卡片,支持自定义填充色和描边色。
     *
     * @param fillColor 填充颜色
     * @param strokeColor 描边颜色
     * @param cornerRadius 圆角半径 (px)
     * @return 配置好的 [GradientDrawable]
     */
    private fun createRoundedBackground(
        fillColor: Int,
        strokeColor: Int,
        cornerRadius: Float,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke(2, strokeColor)
            this.cornerRadius = cornerRadius
        }
    }
}
