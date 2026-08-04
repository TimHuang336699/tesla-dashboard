package com.tesla.dashboard.ui.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tesla.dashboard.R
import com.tesla.dashboard.data.model.VehicleData
import com.tesla.dashboard.databinding.ActivityDashboardBinding
import com.tesla.dashboard.ui.history.HistoryActivity
import com.tesla.dashboard.ui.settings.SettingsActivity
import com.tesla.dashboard.util.ThemeColors
import com.tesla.dashboard.util.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard 主界面 Activity
 *
 * 以横屏全屏沉浸式模式展示车辆实时数据仪表盘,采用 Apple CarPlay 极简风格:
 * - 顶部:档位(左)+ 电量/续航(右)
 * - 中部:速度表(SpeedometerView 圆环动画)
 * - 底部:Tesla 连接状态、行程里程、G力、经纬度、航向、历史/设置按钮
 * - 详情区(可展开):温度、总里程、电量进度条
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
class DashboardActivity : AppCompatActivity() {

    /** ViewBinding 实例,在 onCreate 中初始化 */
    private lateinit var binding: ActivityDashboardBinding

    /** Dashboard ViewModel,由 Hilt 自动提供 */
    private val viewModel: DashboardViewModel by viewModels()

    /** 主题管理器,由 Hilt 自动注入 */
    @Inject
    lateinit var themeManager: ThemeManager

    /** 当前主题颜色(由 colors 流更新,供 updateUI 在数据变化时复用) */
    private var currentColors: ThemeColors = ThemeColors.Dark

    /** 最近一次 Tesla 连接状态(由 vehicleData 流更新,供主题应用时复用) */
    private var isTeslaConnected: Boolean = false

    /** 详情区(detailSection)是否已展开 */
    private var detailExpanded = false

    /**
     * Activity 创建入口
     *
     * 执行顺序:
     * 1. 配置全屏沉浸式窗口
     * 2. 初始化 ViewBinding
     * 3. 设置按钮点击/长按监听
     * 4. 立即应用当前主题颜色(避免初始 XML 回退色闪现)
     * 5. 开始观察 ViewModel 数据
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 全屏沉浸式配置
        setupImmersiveMode()

        // 2. 初始化 ViewBinding
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 3. 设置按钮
        setupButtons()

        // 4. 立即应用当前主题颜色(StateFlow 当前值),避免初始回退色闪现
        applyThemeColors(themeManager.colors.value)

        // 5. 观察数据
        observeViewModel()
    }

    /**
     * 配置全屏沉浸式模式
     *
     * - [WindowCompat.setDecorFitsSystemWindows](false): 内容延伸到系统栏区域
     * - [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]: 屏幕常亮(仪表盘场景必需)
     * - [WindowInsetsControllerCompat.hide]: 隐藏状态栏和导航栏
     * - [BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]: 滑动边缘时短暂显示系统栏后自动隐藏
     * - Layout 在 [android:resizeableActivity]=true + 全面 configChanges 下,
     *   窗口尺寸变化 (分屏/折叠屏/动态分辨率) 由 ConstraintLayout 自动重排,
     *   Activity 不会重建, 保持当前车速/主题/连接状态不丢失
     */
    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 允许内容延伸到刘海/挖孔区域 (Android P+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 设置按钮点击与长按监听
     *
     * - historyButton:跳转到历史行程页面
     * - settingsButton(点击):跳转到设置页面
     * - settingsButton(长按):切换详情区展开/收起,带高度动画
     */
    private fun setupButtons() {
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
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
     * 观察 ViewModel 的 StateFlow
     *
     * 使用 [repeatOnLifecycle] 在 Activity STARTED 时开始收集,
     * 在 STOPPED 时自动取消,避免后台浪费资源。
     *
     * 仅收集两个流:
     * - [DashboardViewModel.vehicleData]: 车辆实时数据,更新 UI
     * - [ThemeManager.colors]: 主题颜色集合,实时刷新全部配色
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 收集车辆数据 — 更新仪表盘数值
                launch {
                    viewModel.vehicleData.collect { data ->
                        updateUI(data)
                    }
                }

                // 收集主题颜色 — 实时应用配色(无需重建 Activity)
                launch {
                    themeManager.colors.collect { colors ->
                        applyThemeColors(colors)
                    }
                }
            }
        }
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
     * - 速度表各部分配色(经 [SpeedometerView.setThemeColors] 传入)
     *
     * @param c 当前主题颜色集合
     */
    private fun applyThemeColors(c: ThemeColors) {
        // 缓存当前主题,供 updateUI 在数据变化时复用
        currentColors = c

        // ===== 根布局背景 =====
        binding.root.setBackgroundColor(c.background)

        // ===== 顶部栏 =====
        binding.gearText.setTextColor(c.accentBlue)
        binding.socText.setTextColor(c.textPrimary)
        binding.rangeText.setTextColor(c.textSecondary)
        binding.topDivider.setBackgroundColor(c.divider)

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
        binding.settingsButton.backgroundTintList = ColorStateList.valueOf(c.textSecondary)

        // ===== 速度数字显示配色 (左列, 原 AP 位置) =====
        binding.speedDisplay.setThemeColors(
            text = c.textPrimary,
            unit = c.textSecondary,
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
     * @param data 最新的车辆数据
     */
    private fun updateUI(data: VehicleData) {
        // 缓存连接状态,供 applyThemeColors 在主题变化时复用
        isTeslaConnected = data.isTeslaConnected

        // ===== 顶部栏 =====

        // 档位(P/R/N/D),Tesla BLE 未连接时显示 "--"
        binding.gearText.text = data.gear ?: "--"

        // ===== 中部 - 速度数字显示 (位于左列, 原 AP 状态位置) =====
        binding.speedDisplay.setSpeed(data.speed)
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
        binding.verticalGauge.setValueText(data.batterySOC?.let { "$it%" } ?: "--")
        binding.verticalGauge.setFillColor(getBatteryColor(soc, currentColors))

        // ===== 顶部右侧 - 电量/续航 =====
        binding.socText.text = data.batterySOC?.let { "$it%" } ?: "--"
        binding.batteryBar.progress = data.batterySOC ?: 0
        binding.rangeText.text = data.batteryRange?.let { "${it.toInt()} km" } ?: "--"

        // ===== 详情区 - 温度/总里程(默认隐藏,展开后显示) =====
        binding.tempText.text = formatTemperature(
            data.insideTemp,
            data.outsideTemp,
        )
        binding.odoText.text = data.odometer?.let { "${it.toInt()} km" } ?: "--"

        // ===== 底部 - 里程/G力/位置 =====

        // 本次行程里程
        binding.tripDistText.text = String.format("%.1f km", data.tripDistance)

        // G 力值
        binding.gForceText.text = String.format("%.2f G", data.gForce)

        // 经纬度(BLE 未连接时显示 "--")
        binding.latText.text = if (data.isTeslaConnected) {
            String.format("%.5f", data.latitude)
        } else {
            "--"
        }
        binding.lonText.text = if (data.isTeslaConnected) {
            String.format("%.5f", data.longitude)
        } else {
            "--"
        }

        // 航向角
        binding.headingText.text = if (data.isTeslaConnected) {
            "${data.heading.toInt()}°"
        } else {
            "--"
        }

        // ===== Tesla BLE 连接状态 =====
        binding.teslaStatusText.text = if (data.isTeslaConnected) {
            getString(R.string.tesla_connected)
        } else {
            getString(R.string.tesla_disconnected)
        }
        // 连接状态颜色使用当前主题的绿/橙强调色
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
        val insideStr = inside?.let { "${it.toInt()}°" }
        val outsideStr = outside?.let { "${it.toInt()}°" }

        return when {
            insideStr != null && outsideStr != null -> "$insideStr / $outsideStr"
            insideStr != null -> insideStr
            outsideStr != null -> outsideStr
            else -> "--"
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
