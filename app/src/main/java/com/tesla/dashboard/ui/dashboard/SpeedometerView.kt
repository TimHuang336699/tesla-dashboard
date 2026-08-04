package com.tesla.dashboard.ui.dashboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.tesla.dashboard.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dash for Tesla 风格速度表 — 半圆形仪表 (180°) + 刻度线 + 橙色渐变指针
 *
 * ## 设计特征 (模仿 Dash for Tesla)
 * - 180° 半圆仪表, 从左侧 (9点) 到右侧 (3点), 顶部半圆
 * - 深色背景弧 + 白色刻度线和数字 (0, 20, 40... 240)
 * - 橙色渐变弧段指针: 从基座(透明)到尖端(不透明橙色)的扫掠效果
 * - 中心大号速度数字 + km/h 单位
 * - 平滑动画过渡 (300ms, DecelerateInterpolator)
 *
 * ## 角度系统
 * - Android Canvas: 0° = 正右方 (3点), 顺时针递增
 * - 仪表范围: startAngle=180° (9点/左侧) → sweep=180° → 0° (3点/右侧)
 * - 速度 0 对应 180° (左), 最大速度对应 360°/0° (右)
 * - 当前速度角度 = 180° + (speed / maxSpeed) * 180°
 *
 * ## 指针渐变
 * 使用 SweepGradient 从透明 (180°/0速度) 到不透明橙色 (当前速度角度),
 * 营造 Dash for Tesla 标志性的橙色扫掠拖尾效果。
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ===== 配置参数 =====

    /** 最大速度刻度 km/h */
    var maxSpeed: Float = 240f
        set(value) {
            field = value.coerceAtLeast(1f)
            invalidate()
        }

    /** 主刻度间隔 (每 20 km/h 一个数字标签) */
    var majorTickInterval: Int = 20
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    /** 次刻度间隔 (每 10 km/h 一个小刻度) */
    var minorTickInterval: Int = 10
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    // ===== 速度状态 =====

    /** 当前显示速度 (动画中间值) */
    private var displaySpeed: Float = 0f

    /** 目标速度 (动画终点) */
    private var targetSpeed: Float = 0f

    // ===== 颜色 (目标值) =====

    private var _pointerColor: Int = 0xFFFF6B00.toInt()       // 橙色指针
    private var _pointerTipColor: Int = 0xFFFFAA00.toInt()     // 指针尖端亮色
    private var _bgArcColor: Int = 0xFF1A1A2E.toInt()          // 背景弧 (深蓝黑)
    private var _tickColor: Int = 0xFFCCCCCC.toInt()           // 刻度线颜色
    private var _tickTextColor: Int = 0xFFAAAAAA.toInt()       // 刻度数字颜色
    private var _speedTextColor: Int = 0xFFFFFFFF.toInt()      // 中心速度数字颜色
    private var _unitTextColor: Int = 0xFF888888.toInt()       // 单位文字颜色

    // ===== 当前绘制颜色 (由动画驱动) =====

    private var currentPointerColor: Int = _pointerColor
    private var currentBgArcColor: Int = _bgArcColor
    private var currentTickColor: Int = _tickColor
    private var currentTickTextColor: Int = _tickTextColor
    private var currentSpeedTextColor: Int = _speedTextColor
    private var currentUnitTextColor: Int = _unitTextColor

    // ===== Paint =====

    /** 背景弧画笔 */
    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 指针弧画笔 (使用 Shader) */
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 主刻度线画笔 */
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 次刻度线画笔 */
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 刻度数字画笔 */
    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** 中心速度数字画笔 (加粗显示) */
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    /** 单位文字画笔 */
    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    /** 指针尖端亮点画笔 */
    private val pointerTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ===== 动画 =====

    /** 速度动画 */
    private var speedAnimator: ValueAnimator? = null

    /** 颜色过渡动画 */
    private var colorAnimator: ValueAnimator? = null

    /** ARGB 求值器 */
    private val argbEvaluator = ArgbEvaluator()

    /** 弧形绘制区域 */
    private val arcRect = RectF()

    /** 内部弧形区域 (刻度线) */
    private val tickRect = RectF()

    /** 是否绘制中心文字 (可由 SpeedDisplayView 覆盖) */
    var drawText: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    init {
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.SpeedometerView, 0, 0)
            maxSpeed = ta.getFloat(R.styleable.SpeedometerView_maxSpeed, 240f)
            displaySpeed = ta.getFloat(R.styleable.SpeedometerView_currentSpeed, 0f)
            targetSpeed = displaySpeed
            ta.recycle()
        }
    }

    // ===== 公开属性 =====

    /** 指针颜色 */
    var pointerColor: Int
        get() = _pointerColor
        set(value) { _pointerColor = value; animateToTargets() }

    /** 背景弧颜色 */
    var bgArcColor: Int
        get() = _bgArcColor
        set(value) { _bgArcColor = value; animateToTargets() }

    /** 刻度颜色 */
    var tickColor: Int
        get() = _tickColor
        set(value) { _tickColor = value; animateToTargets() }

    /** 刻度文字颜色 */
    var tickTextColor: Int
        get() = _tickTextColor
        set(value) { _tickTextColor = value; animateToTargets() }

    /** 速度文字颜色 */
    var speedTextColor: Int
        get() = _speedTextColor
        set(value) { _speedTextColor = value; animateToTargets() }

    /** 单位文字颜色 */
    var unitTextColor: Int
        get() = _unitTextColor
        set(value) { _unitTextColor = value; animateToTargets() }

    /**
     * 一次性设置全部主题颜色 (带动画过渡)
     */
    fun setThemeColors(
        pointer: Int,
        bg: Int,
        tick: Int,
        tickText: Int,
        speedText: Int,
        unit: Int,
    ) {
        if (_pointerColor == pointer && _bgArcColor == bg && _tickColor == tick &&
            _tickTextColor == tickText && _speedTextColor == speedText && _unitTextColor == unit
        ) return
        _pointerColor = pointer
        _bgArcColor = bg
        _tickColor = tick
        _tickTextColor = tickText
        _speedTextColor = speedText
        _unitTextColor = unit
        animateToTargets()
    }

    /**
     * 兼容旧接口 — 仅设置进度弧/背景/文字颜色
     */
    fun setThemeColors(progress: Int, bg: Int, text: Int, unit: Int) {
        setThemeColors(
            pointer = progress,
            bg = bg,
            tick = unit,
            tickText = unit,
            speedText = text,
            unit = unit,
        )
    }

    /**
     * 颜色过渡动画 (300ms)
     */
    private fun animateToTargets() {
        if (currentPointerColor == _pointerColor && currentBgArcColor == _bgArcColor &&
            currentTickColor == _tickColor && currentTickTextColor == _tickTextColor &&
            currentSpeedTextColor == _speedTextColor && currentUnitTextColor == _unitTextColor
        ) {
            invalidate()
            return
        }

        colorAnimator?.cancel()
        val sp = currentPointerColor; val sb = currentBgArcColor
        val st = currentTickColor; val stt = currentTickTextColor
        val ss = currentSpeedTextColor; val su = currentUnitTextColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                currentPointerColor = argbEvaluator.evaluate(f, sp, _pointerColor) as Int
                currentBgArcColor = argbEvaluator.evaluate(f, sb, _bgArcColor) as Int
                currentTickColor = argbEvaluator.evaluate(f, st, _tickColor) as Int
                currentTickTextColor = argbEvaluator.evaluate(f, stt, _tickTextColor) as Int
                currentSpeedTextColor = argbEvaluator.evaluate(f, ss, _speedTextColor) as Int
                currentUnitTextColor = argbEvaluator.evaluate(f, su, _unitTextColor) as Int
                invalidate()
            }
            start()
        }
        invalidate()
    }

    /**
     * 设置当前速度 (带动画)
     */
    fun setSpeed(speed: Float, animate: Boolean = true) {
        val clamped = speed.coerceIn(0f, maxSpeed)
        targetSpeed = clamped

        if (!animate) {
            displaySpeed = clamped
            invalidate()
            return
        }

        speedAnimator?.cancel()
        speedAnimator = ValueAnimator.ofFloat(displaySpeed, clamped).apply {
            duration = SPEED_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                displaySpeed = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height * 0.55f  // 中心上移, 给半圆更多垂直空间

        // 半径计算:
        // 1. 高度约束: 半圆需要 cy 之上有 radius 的空间, 顶部留 ~6dp 余量
        // 2. 宽度约束: 半径不能超过宽度的一半, 左右各留 ~8dp 给刻度数字
        // 取两者中较小的, 确保完整显示
        val maxRadiusByHeight = (cy - dpToPx(6f)).coerceAtLeast(dpToPx(40f))
        val maxRadiusByWidth = (width / 2f - dpToPx(8f)).coerceAtLeast(dpToPx(40f))
        val radius = minOf(maxRadiusByHeight, maxRadiusByWidth)

        // 设置弧形区域
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 内圈刻度区域
        val tickRadius = radius - dpToPx(8f)
        tickRect.set(cx - tickRadius, cy - tickRadius, cx + tickRadius, cy + tickRadius)

        // ===== 1. 绘制背景弧 (180° 半圆, 顶部) =====
        bgArcPaint.color = currentBgArcColor
        bgArcPaint.strokeWidth = dpToPx(8f)
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, bgArcPaint)

        // ===== 2. 绘制刻度线和数字 =====
        drawTicks(canvas, cx, cy, radius)

        // ===== 3. 绘制橙色渐变指针 =====
        drawPointer(canvas, cx, cy, radius)

        // ===== 4. 绘制中心速度数字 =====
        if (drawText) {
            drawCenterText(canvas, cx, cy, radius)
        }
    }

    /**
     * 绘制刻度线和数字
     *
     * 主刻度: 每 majorTickInterval 一个, 长刻度线 + 数字标签
     * 次刻度: 每 minorTickInterval 一个, 短刻度线
     */
    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val majorTickLen = dpToPx(12f)
        val minorTickLen = dpToPx(6f)
        val tickTextOffset = dpToPx(24f)

        val totalTicks = (maxSpeed.toInt() / minorTickInterval)

        for (i in 0..totalTicks) {
            val speedValue = i * minorTickInterval
            if (speedValue > maxSpeed) break

            // 角度: 180° (左/0速度) → 360° (右/最大速度)
            val angleDeg = START_ANGLE + (speedValue.toFloat() / maxSpeed) * SWEEP_ANGLE
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val isMajor = speedValue % majorTickInterval == 0
            val tickLen = if (isMajor) majorTickLen else minorTickLen

            // 外端点 (弧上)
            val outerX = cx + radius * cos(angleRad).toFloat()
            val outerY = cy + radius * sin(angleRad).toFloat()

            // 内端点
            val innerR = radius - tickLen
            val innerX = cx + innerR * cos(angleRad).toFloat()
            val innerY = cy + innerR * sin(angleRad).toFloat()

            // 绘制刻度线
            if (isMajor) {
                majorTickPaint.color = currentTickColor
                majorTickPaint.strokeWidth = dpToPx(2f)
                majorTickPaint.alpha = 200
                canvas.drawLine(outerX, outerY, innerX, innerY, majorTickPaint)
            } else {
                minorTickPaint.color = currentTickColor
                minorTickPaint.strokeWidth = dpToPx(1f)
                minorTickPaint.alpha = 100
                canvas.drawLine(outerX, outerY, innerX, innerY, minorTickPaint)
            }

            // 绘制数字标签 (仅主刻度)
            if (isMajor) {
                val labelR = radius - tickTextOffset
                val labelX = cx + labelR * cos(angleRad).toFloat()
                val labelY = cy + labelR * sin(angleRad).toFloat()

                tickTextPaint.color = currentTickTextColor
                tickTextPaint.textSize = dpToPx(11f)
                tickTextPaint.alpha = 180

                // 文字垂直居中修正
                val textOffset = (tickTextPaint.descent() + tickTextPaint.ascent()) / 2f
                canvas.drawText(speedValue.toString(), labelX, labelY - textOffset, tickTextPaint)
            }
        }
    }

    /**
     * 绘制橙色渐变指针 — Dash for Tesla 标志性扫掠效果
     *
     * 指针是一个弧段, 从 0 速度位置 (180°) 延伸到当前速度位置。
     * 使用 SweepGradient 实现从透明 (基座) 到不透明橙色 (尖端) 的渐变。
     */
    private fun drawPointer(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (displaySpeed <= 0f) return

        val speedRatio = (displaySpeed / maxSpeed).coerceIn(0f, 1f)
        val pointerSweep = SWEEP_ANGLE * speedRatio

        if (pointerSweep < 0.5f) return

        // 指针弧的半径 (略小于外弧, 在刻度内侧)
        val pointerRadius = radius - dpToPx(2f)
        val pointerRect = RectF(
            cx - pointerRadius, cy - pointerRadius,
            cx + pointerRadius, cy + pointerRadius,
        )

        // ===== 方法: 分段绘制实现渐变效果 =====
        // 将指针弧分为多段, 每段 alpha 从 0 → 255 渐变
        val segments = 30
        val segmentSweep = pointerSweep / segments

        // 指针宽度 (较宽的弧段, 非细线)
        val pointerWidth = dpToPx(6f)
        pointerPaint.strokeWidth = pointerWidth

        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments  // 0 → 1
            val segStart = START_ANGLE + i * segmentSweep
            val alpha = (progress * 255).toInt().coerceIn(0, 255)

            // 颜色从暗橙 → 亮橙
            val r = Color.red(currentPointerColor)
            val g = Color.green(currentPointerColor)
            val b = Color.blue(currentPointerColor)

            // 尖端更亮
            val tipBoost = progress * 0.3f
            val finalR = (r + (255 - r) * tipBoost).toInt().coerceIn(0, 255)
            val finalG = (g + (200 - g) * tipBoost).toInt().coerceIn(0, 255)
            val finalB = b

            pointerPaint.color = Color.argb(alpha, finalR, finalG, finalB)
            canvas.drawArc(pointerRect, segStart, segmentSweep + 0.5f, false, pointerPaint)
        }

        // ===== 指针尖端亮点 =====
        val tipAngleDeg = START_ANGLE + pointerSweep
        val tipAngleRad = Math.toRadians(tipAngleDeg.toDouble())
        val tipX = cx + pointerRadius * cos(tipAngleRad).toFloat()
        val tipY = cy + pointerRadius * sin(tipAngleRad).toFloat()

        // 外层光晕
        pointerTipPaint.color = currentPointerColor
        pointerTipPaint.alpha = 80
        canvas.drawCircle(tipX, tipY, dpToPx(8f), pointerTipPaint)

        // 中层
        pointerTipPaint.alpha = 160
        canvas.drawCircle(tipX, tipY, dpToPx(5f), pointerTipPaint)

        // 核心亮点
        pointerTipPaint.color = 0xFFFFCC00.toInt()
        pointerTipPaint.alpha = 255
        canvas.drawCircle(tipX, tipY, dpToPx(3f), pointerTipPaint)
    }

    /**
     * 绘制中心速度数字和单位
     */
    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // 速度数字 (大号, 居中偏上, 加粗 sans-serif-medium)
        speedTextPaint.color = currentSpeedTextColor
        speedTextPaint.textSize = radius * 0.42f

        val speedStr = displaySpeed.toInt().toString()
        val textY = cy - (speedTextPaint.descent() + speedTextPaint.ascent()) / 2f - radius * 0.05f
        canvas.drawText(speedStr, cx, textY, speedTextPaint)

        // km/h 单位 (数字下方)
        unitTextPaint.color = currentUnitTextColor
        unitTextPaint.textSize = radius * 0.1f
        unitTextPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val unitY = textY + radius * 0.28f
        canvas.drawText(context.getString(R.string.unit_kmh), cx, unitY, unitTextPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        speedAnimator?.cancel()
        colorAnimator?.cancel()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        /** 弧起始角度 (180° = 9点钟方向/左侧) */
        private const val START_ANGLE = 180f

        /** 弧扫过角度 (180° = 半圆, 到3点钟方向/右侧) */
        private const val SWEEP_ANGLE = 180f

        /** 速度动画时长 */
        private const val SPEED_ANIM_DURATION = 300L

        /** 颜色过渡动画时长 */
        private const val COLOR_ANIM_DURATION = 300L
    }
}
