package com.tesla.dashboard.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.R
import com.tesla.dashboard.data.model.DataSource
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.data.source.ble.TeslaBleProvider
import com.tesla.dashboard.databinding.ActivityDashboardBinding
import com.tesla.dashboard.ui.history.HistoryActivity
import com.tesla.dashboard.ui.settings.SettingsActivity
import com.tesla.dashboard.util.BaseImmersiveActivity
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.UnitFormatter
import com.tesla.dashboard.util.UnitSystem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Dashboard 主界面 Activity
 *
 * 以横屏全屏沉浸式模式展示车辆实时数据仪表盘,采用 Apple CarPlay 极简风格:
 * - 顶部:档位(左)+ 电量/续航(右)
 * - 中部:大数字码表 + 车辆剪影 + 竖向电量仪表
 * - 底部:Tesla 连接状态、行程里程、G力、经纬度、航向、历史/设置按钮
 * - 详情区(可展开):温度、总里程、电量进度条、瞬时电耗
 *
 * ## 沉浸式全屏
 * - 使用 [WindowInsetsControllerCompat] 隐藏状态栏和导航栏
 * - 设置 [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] 保持屏幕常亮
 * - 配合 themes.xml 中的全屏主题实现完全沉浸式体验
 *
 * ## 实时主题切换
 * 收集 [ThemeManager.colors] ([ThemeColors]),在主题变化时立即应用配色到
 * 所有文字、背景、分割线及速度表 —— 无需重建 Activity,不产生闪烁。
 *
 * ## ViewBinding
 * 通过 build.gradle.kts 中启用的 viewBinding = true,
 * 自动生成 [ActivityDashboardBinding] 供类型安全地访问布局视图。
 *
 * ## Hilt
 * @AndroidEntryPoint 使 Hilt 能在此 Activity 中进行依赖注入,
 * ViewModel 通过 by viewModels() 委托自动获取。
 *
 * ## 数据观察
 * 使用 [repeatOnLifecycle] 在 STARTED 状态下安全收集 StateFlow,
 * 避免 Activity 不可见时浪费资源。
 */
@AndroidEntryPoint
class DashboardActivity : BaseImmersiveActivity() {

    /** ViewBinding 实例,在 onCreate 中初始化 */
    private lateinit var binding: ActivityDashboardBinding

    /** Dashboard ViewModel,由 Hilt 自动提供 */
    private val viewModel: DashboardViewModel by viewModels()

    /** 最近一次 Tesla 连接状态(由 vehicleData 流更新,供主题应用时复用) */
    private var isTeslaConnected: Boolean = false

    /** 当前单位系统(由 uiState 流更新, 供 updateUI 换算) */
    private var currentUnitSystem: UnitSystem = UnitSystem.METRIC

    /** 详情区(detailSection)是否已展开 */
    private var detailExpanded = false

    // ===== 缓存的字符串资源 (避免 updateUI 中重复调用 getString) =====
    private lateinit var defaultStr: String
    private lateinit var connectedStr: String
    private lateinit var gnssFallbackStr: String
    private lateinit var staleStr: String
    private lateinit var disconnectedStr: String

    // ===== 复用的 StringBuilder (避免 G-force 文本每次分配) =====
    private val gForceStringBuilder = StringBuilder(16)

    /**
     * Activity 创建入口
     *
     * 执行顺序:
     * 1. 初始化 ViewBinding (沉浸式由基类配置)
     * 2. 设置按钮点击/长按监听
     * 3. 立即应用当前主题颜色(避免初始 XML 回退色闪现)
     * 4. 开始观察 ViewModel 数据
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化缓存字符串 (必须在 super.onCreate 之后)
        defaultStr = getString(R.string.default_value)
        connectedStr = getString(R.string.tesla_connected)
        gnssFallbackStr = getString(R.string.tesla_gnss_fallback)
        staleStr = getString(R.string.tesla_stale)
        disconnectedStr = getString(R.string.tesla_disconnected)

        // 1. 初始化 ViewBinding
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. 设置按钮
        setupButtons()

        // 4. 立即应用当前主题颜色(StateFlow 当前值),避免初始回退色闪现
        applyThemeColors(themeManager.colors.value)

        // 5. 观察数据
        observeViewModel()
    }

    /**
     * 设置按钮点击与长按监听
     *
     * - historyButton:跳转到历史行程页面
     * - controlButton (v0.5.0):直接切换解锁/闭锁
     * - settingsButton(点击):跳转到设置页面
     * - settingsButton(长按):切换详情区展开/收起,带高度动画
     */
    private fun setupButtons() {
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // v0.5.0: 直接切换解锁/闭锁
        binding.controlButton.setOnClickListener {
            toggleLockState()
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 长按设置按钮:展开/收起详情区(温度、总里程、电量进度条)
        binding.settingsButton.setOnLongClickListener {
            toggleDetailSection()
            true
        }
    }

    /**
     * 切换车辆解锁/闭锁状态 (v0.5.0)
     *
     * 根据当前车辆锁定状态自动发送解锁或闭锁命令。
     * 通过 VCSEC 域 BLE 加密通道发送, 结果以 Toast 提示。
     */
    private fun toggleLockState() {
        val isLocked = viewModel.vehicleData.value.isLocked
        val command = if (isLocked == true) {
            TeslaBleProvider.VehicleCommand.Unlock
        } else {
            TeslaBleProvider.VehicleCommand.Lock
        }
        viewModel.sendVehicleCommand(command)
    }

    /**
     * 观察 ViewModel 的 StateFlow
     *
     * 使用 [repeatOnLifecycle] 在 Activity STARTED 时开始收集,
     * 在 STOPPED 时自动取消,避免后台浪费资源。
     *
     * 收集:
     * - [DashboardViewModel.uiState]: 车辆数据 + 单位系统,更新仪表盘数值
     * - 主题颜色 (基类 [BaseImmersiveActivity.observeThemeColors])
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 收集车辆数据 + 单位系统 — 更新仪表盘数值 (实时换算)
                launch {
                    viewModel.uiState.collect { state ->
                        currentUnitSystem = state.unitSystem
                        updateUI(state)
                    }
                }

                // v0.5.0: 转向灯显示开关
                launch {
                    viewModel.showTurnSignals.collect { show ->
                        binding.turnSignalView.visibility =
                            if (show) View.VISIBLE else View.GONE
                    }
                }

                // v0.5.0: 车辆控制命令结果提示
                launch {
                    viewModel.controlState.collect { controlState ->
                        when (controlState) {
                            ControlUiState.Idle -> Unit
                            ControlUiState.Sending ->
                                Toast.makeText(
                                    this@DashboardActivity,
                                    R.string.control_sending,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            is ControlUiState.Done ->
                                Toast.makeText(
                                    this@DashboardActivity,
                                    if (controlState.success) R.string.control_success
                                    else R.string.control_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                }
            }
        }

        // 收集主题颜色 — 实时应用配色(无需重建 Activity, 由基类提供)
        observeThemeColors()
    }

    /**
     * 应用主题颜色到所有 UI 元素
     *
     * 在 [ThemeManager.colors] 发射新值时调用,统一刷新:
     * - 根布局背景
     * - 顶部档位/电量/续航文字
     * - 顶部分割线
     * - 底部各项数据文字
     * - Tesla 连接状态(颜色依赖连接状态,使用最近一次 [isTeslaConnected])
     * - 历史/设置按钮
     * - 大数字码表/竖向仪表配色 (经 [SpeedDisplayView.setThemeColors] 等传入)
     *
     * @param c 当前主题颜色集合
     */
    override fun applyThemeColors(c: ThemeColors) {
        // 缓存当前主题,供 updateUI 在数据变化时复用
        currentColors = c

        // ===== 根布局背景 =====
        binding.root.setBackgroundColor(c.background)

        // ===== 顶部栏 =====
        binding.gearText.setTextColor(c.accentBlue)
        binding.tempTopText.setTextColor(c.textSecondary)
        binding.socText.setTextColor(c.textPrimary)
        binding.rangeText.setTextColor(c.textSecondary)
        binding.topDivider.setBackgroundColor(c.divider)

        // ===== 中列: 常驻功率 (v0.5.0) =====
        binding.powerTopText.setTextColor(c.accentGreen)

        // ===== 转向灯指示 (v0.5.0, 主题色联动) =====
        binding.turnSignalView.setColors(active = c.accentGreen, idle = c.divider)

        // ===== 底部数据 =====
        binding.tripDistText.setTextColor(c.accentBlue)
        binding.gForceText.setTextColor(c.accentBlue)
        binding.latText.setTextColor(c.textPrimary)
        binding.lonText.setTextColor(c.textPrimary)
        binding.headingText.setTextColor(c.accentBlue)

        // ===== Tesla 连接状态(颜色依赖连接状态) =====
        binding.teslaStatusText.setTextColor(
            if (isTeslaConnected) c.accentGreen else c.accentOrange,
        )

        // ===== 按钮 =====
        binding.historyButton.setTextColor(c.accentBlue)
        binding.controlButton.imageTintList = ColorStateList.valueOf(c.textSecondary)
        binding.settingsButton.backgroundTintList = ColorStateList.valueOf(c.textSecondary)

        // ===== 大数字码表配色 (左列) =====
        binding.speedDisplay.setThemeColors(
            text = c.speedometerText,
            unit = c.speedometerUnit,
            ready = c.accentGreen,
        )

        // ===== 车辆剪影配色 (从主题色派生) =====
        binding.carSilhouette.setThemeColors(
            body = c.surface,
            stroke = c.divider,
            glass = c.background,
            wheel = c.divider,
        )
        // 门/舱未关时的警告红色
        binding.carSilhouette.setWarningColor(c.accentRed)

        // ===== 竖向仪表配色 =====
        binding.verticalGauge.setTrackColor(c.divider)
        binding.verticalGauge.setLabelColor(c.textSecondary)
        binding.verticalGauge.setValueColor(c.textPrimary)

        // ===== 详情区 - 瞬时电耗 =====
        binding.consumptionText.setTextColor(c.textPrimary)
        // ===== 详情区 - 瞬时功率 (v0.4.2) =====
        binding.powerText.setTextColor(c.textPrimary)
    }

    /**
     * 根据车辆实时数据更新所有 UI 元素
     *
     * 每次车辆数据流发射新值时调用,更新仪表盘上的所有数值显示。
     * 对于可能为 null 的 Tesla BLE 字段,使用 "--" 占位符。
     *
     * 注意:Tesla 连接状态文字颜色使用 [currentColors](由主题流维护),
     * 从而在主题切换或连接状态变化时均能正确着色。
     *
     * @param state 最新 UI 状态 (车辆数据 + 单位 + 瞬时电耗)
     */
    private fun updateUI(state: DashboardUiState) {
        val data = state.vehicleData

        // 缓存连接状态,供 applyThemeColors 在主题变化时复用
        isTeslaConnected = data.isTeslaConnected

        // v0.4.2 数据失效保护: 过期帧保留数值展示, 核心视图降低透明度提示状态
        // v0.5.0: GNSS 降级帧的运动学字段是新鲜的, 不参与降透明度
        val stale = data.isDataStale && data.dataSource == DataSource.BLE
        binding.speedDisplay.alpha = if (stale) 0.55f else 1f
        binding.verticalGauge.alpha = if (stale) 0.55f else 1f
        binding.carSilhouette.alpha = if (stale) 0.55f else 1f

        // ===== 顶部栏 =====

        // 档位(P/R/N/D),Tesla BLE 未连接时显示 "--"
        binding.gearText.text = data.gear ?: defaultStr

        // v0.5.0: 温度常驻顶部 (车内/车外)
        binding.tempTopText.text = formatTemperature(
            data.insideTemp,
            data.outsideTemp,
        )

        // ===== 大数字码表速度 (左列, 按单位系统换算) =====
        binding.speedDisplay.maxSpeed = UnitFormatter.maxSpeed(currentUnitSystem)
        binding.speedDisplay.unitText = UnitFormatter.speedUnit(this, currentUnitSystem)
        binding.speedDisplay.setSpeed(
            UnitFormatter.speedValue(data.speed, currentUnitSystem),
        )
        binding.speedDisplay.isReady = data.isTeslaConnected

        // ===== 中部 - 车辆剪影门/舱状态 =====
        binding.carSilhouette.setClosureState(
            ft = data.ft,
            rt = data.rt,
            df = data.df,
            dr = data.dr,
            pf = data.pf,
            pr = data.pr,
        )

        // ===== 中部右侧 - 竖向电量仪表 =====
        val soc = data.batterySOC ?: 0
        binding.verticalGauge.setValue(soc.toFloat())
        binding.verticalGauge.setValueText(
            data.batterySOC?.let { "$it%" } ?: defaultStr
        )
        binding.verticalGauge.setFillColor(getBatteryColor(soc, currentColors))

        // ===== 顶部右侧 - 电量/续航 =====
        binding.socText.text = data.batterySOC?.let { "$it%" } ?: defaultStr
        binding.batteryBar.progress = data.batterySOC ?: 0
        binding.rangeText.text = data.batteryRange?.let {
            getString(
                R.string.format_speed_value,
                UnitFormatter.distanceValue(it, currentUnitSystem),
                UnitFormatter.distanceUnit(this, currentUnitSystem),
            )
        } ?: defaultStr

        // ===== 详情区 - 温度/总里程/瞬时电耗(默认隐藏,展开后显示) =====
        binding.tempText.text = formatTemperature(
            data.insideTemp,
            data.outsideTemp,
        )
        binding.odoText.text = data.odometer?.let {
            getString(
                R.string.format_speed_value,
                UnitFormatter.distanceValue(it, currentUnitSystem),
                UnitFormatter.distanceUnit(this, currentUnitSystem),
            )
        } ?: defaultStr

        // 瞬时电耗 (v0.4): 英制单位下按 kWh/100mi 换算显示
        binding.consumptionText.text = state.consumptionKwhPer100km?.let { c ->
            val imperial = currentUnitSystem == UnitSystem.IMPERIAL
            val value = if (imperial) c * 1.609344f else c
            val unit = if (imperial) R.string.unit_kwh_100mi else R.string.unit_kwh_100km
            getString(R.string.format_distance_value, value, getString(unit))
        } ?: defaultStr

        // 瞬时功率 (v0.4.2): kW, 英制单位下换算为 hp (1 kW = 1.34102 hp)
        binding.powerText.text = data.powerKw?.let { kw ->
            val imperial = currentUnitSystem == UnitSystem.IMPERIAL
            val value = if (imperial) kw * 1.34102f else kw
            val unit = if (imperial) R.string.unit_hp else R.string.unit_kw
            getString(R.string.format_speed_value, value, getString(unit))
        } ?: defaultStr

        // ===== 中列 - 常驻功率 (v0.5.0) =====
        // 瞬时功率 kW, 英制单位下换算为 hp (1 kW = 1.34102 hp)
        binding.powerTopText.text = data.powerKw?.let { kw ->
            val imperial = currentUnitSystem == UnitSystem.IMPERIAL
            val value = if (imperial) kw * 1.34102f else kw
            val unit = if (imperial) R.string.unit_hp else R.string.unit_kw
            getString(R.string.format_speed_value, value, getString(unit))
        } ?: defaultStr

        // ===== 底部 - 里程/G力/位置 =====

        // 本次行程里程 (按单位系统换算)
        binding.tripDistText.text = getString(
            R.string.format_distance_value,
            UnitFormatter.distanceValue(data.tripDistance, currentUnitSystem),
            UnitFormatter.distanceUnit(this, currentUnitSystem),
        )

        // G 力值 — 使用缓存的 StringBuilder 避免每次分配
        binding.gForceText.text = gForceStringBuilder.apply {
            clear()
            append(String.format("%.2f", data.gForce))
            append(" G")
        }.toString()

        // 经纬度(BLE/GNSS 均无数据时显示 "--") (v0.5.0: GNSS 降级也可用)
        val hasPosition = data.hasPosition
        binding.latText.text = if (hasPosition) {
            String.format("%.5f", data.latitude)
        } else {
            defaultStr
        }
        binding.lonText.text = if (hasPosition) {
            String.format("%.5f", data.longitude)
        } else {
            defaultStr
        }

        // 航向角
        binding.headingText.text = if (hasPosition) {
            getString(R.string.format_temperature_value, data.heading.toInt())
        } else {
            defaultStr
        }

        // ===== Tesla BLE 连接状态 =====
        // v0.4.2: 过期数据单独提示 (数值保留但标记过期)
        // v0.5.0: GNSS 降级时提示 "手机定位"
        binding.teslaStatusText.text = when {
            data.isTeslaConnected -> connectedStr
            data.dataSource == DataSource.GNSS -> gnssFallbackStr
            stale -> staleStr
            else -> disconnectedStr
        }
        // 连接状态颜色: 已连接=绿, GNSS 降级/过期/未连接=橙
        binding.teslaStatusText.setTextColor(
            if (data.isTeslaConnected) currentColors.accentGreen else currentColors.accentOrange,
        )
    }

    /**
     * 格式化温度显示
     *
     * 显示格式: "22° / 28°"(车内 / 车外)
     * - 两个温度都有值时显示完整格式
     * - 仅有一个时显示对应部分
     * - 都没有时显示 "--"
     *
     * @param inside 车内温度(°C),null = 不可用
     * @param outside 车外温度(°C),null = 不可用
     * @return 格式化后的温度字符串
     */
    private fun formatTemperature(inside: Float?, outside: Float?): String {
        val insideStr = inside?.let {
            getString(
                R.string.format_temperature_value,
                UnitFormatter.temperatureValue(it, currentUnitSystem),
            )
        }
        val outsideStr = outside?.let {
            getString(
                R.string.format_temperature_value,
                UnitFormatter.temperatureValue(it, currentUnitSystem),
            )
        }

        return when {
            insideStr != null && outsideStr != null ->
                getString(R.string.format_temperature_pair, insideStr, outsideStr)
            insideStr != null -> insideStr
            outsideStr != null -> outsideStr
            else -> defaultStr
        }
    }

    /**
     * 切换详情区(detailSection)的展开/收起状态,带高度动画
     *
     * - 展开:先设为 VISIBLE 并测量目标高度,从 0 动画到目标高度
     * - 收起:从当前高度动画到 0,结束后设为 GONE
     *
     * 动画期间通过不断修改 layoutParams.height 并 requestLayout(),
     * 触发 ConstraintLayout 重新布局,底部栏会随之上移/下移,
     * 实现"展开撑开 / 收起塌缩"的视觉效果。
     */
    private fun toggleDetailSection() {
        val detail = binding.detailSection
        detailExpanded = !detailExpanded

        if (detailExpanded) {
            // ===== 展开 =====
            detail.visibility = View.VISIBLE
            // 以根布局宽度为约束测量详情区的目标高度
            val targetHeight = measureDetailHeight()
            // 起始高度设为 0,从 0 滑动到目标高度
            detail.layoutParams.height = 0
            detail.requestLayout()
            ValueAnimator.ofInt(0, targetHeight).apply {
                duration = DETAIL_ANIM_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    detail.layoutParams.height = animator.animatedValue as Int
                    detail.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 动画结束后恢复 WRAP_CONTENT,以适应后续内容变化
                        detail.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                })
                start()
            }
        } else {
            // ===== 收起 =====
            val startHeight = detail.height
            ValueAnimator.ofInt(startHeight, 0).apply {
                duration = DETAIL_ANIM_DURATION_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { animator ->
                    detail.layoutParams.height = animator.animatedValue as Int
                    detail.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // 高度归零后隐藏,释放底部空间
                        detail.visibility = View.GONE
                    }
                })
                start()
            }
        }
    }

    /**
     * 测量详情区在当前宽度下的自然高度
     *
     * 使用根布局宽度作为 EXACTLY 宽度约束、高度 UNSPECIFIED 进行测量,
     * 得到 detailSection 按内容换行后的目标高度,供展开动画使用。
     *
     * @return 详情区目标高度(px)
     */
    private fun measureDetailHeight(): Int {
        val detail = binding.detailSection
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            binding.root.width,
            View.MeasureSpec.EXACTLY,
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        detail.measure(widthSpec, heightSpec)
        return detail.measuredHeight
    }

    /**
     * 根据电池 SOC 返回对应颜色
     *
     * - SOC > 50%: 绿色 (电量充足)
     * - SOC 20-50%: 橙色 (电量提醒)
     * - SOC < 20%: 红色 (电量警告)
     *
     * @param soc 电池电量百分比 0-100
     * @param colors 当前主题颜色集合
     * @return 对应的强调色
     */
    private fun getBatteryColor(soc: Int, colors: ThemeColors): Int {
        return when {
            soc > 50 -> colors.accentGreen
            soc >= 20 -> colors.accentOrange
            else -> colors.accentRed
        }
    }

    companion object {
        /** 详情区展开/收起动画时长(毫秒) */
        private const val DETAIL_ANIM_DURATION_MS = 300L
    }
}
