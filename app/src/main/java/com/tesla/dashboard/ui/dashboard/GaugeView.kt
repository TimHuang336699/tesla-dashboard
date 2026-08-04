package com.tesla.dashboard.ui.dashboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.tesla.dashboard.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dash for Tesla 风格完整圆形仪表盘
 *
 * 复刻 Dash for Tesla 1.8.0 的经典仪表设计:
 * - 圆形表盘背景 (ic_dashboard_0.png 圆盘资源 + 深色底)
 * - 环绕刻度 (主刻度带数字, 次刻度短线)
 * - 从 0 速度位置扫掠到当前速度的渐变指针 (Dash 标志性效果)
 * - 中心大号速度数字 (Pump 仪表字体) + km/h 单位
 * - 指针尖端高亮亮点
 *
 * ## 角度系统
 * - Android Canvas: 0° = 正右方 (3点), 顺时针递增
 * - 仪表范围: startAngle=135° → sweep=270° (3/4 圆, 缺左下角)
 * - 速度 0 对应 135°, 最大速度对应 405° (即 45°)
 *
 * ## 字体
 * - 中心数字优先使用 PumpStdDemiBold.otf (经典汽车仪表字体)
 * - 回退到 sans-serif-medium
 *
 * @param context 上下文
 * @param attrs XML 属性集
 * @param defStyleAttr 默认样式
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ===== 配置 =====

    /** 最大速度 km/h */
    var maxSpeed: Float = 240f
        set(value) {
            field = value.coerceAtLeast(1f)
            invalidate()
        }

    /** 主刻度间隔 (数字标签) */
    private val majorTickInterval: Int = 20

    /** 次刻度间隔 */
    private val minorTickInterval: Int = 10

    // ===== 速度状态 =====

    private var displaySpeed: Float = 0f
    private var targetSpeed: Float = 0f

    // ===== 颜色 (目标值) =====

    private var _pointerColor: Int = 0xFF00A0FF.toInt()
    private var _pointerTipColor: Int = 0xFF7FD4FF.toInt()
    private var _bgArcColor: Int = 0xFF16233A.toInt()
    private var _tickColor: Int = 0xFFCCCCCC.toInt()
    private var _tickTextColor: Int = 0xFFAAAAAA.toInt()
    private var _speedTextColor: Int = 0xFFFFFFFF.toInt()
    private var _unitTextColor: Int = 0xFF888888.toInt()

    // ===== 当前绘制颜色 (动画驱动) =====

    private var currentPointerColor: Int = _pointerColor
    private var currentBgArcColor: Int = _bgArcColor
    private var currentTickColor: Int = _tickColor
    private var currentTickTextColor: Int = _tickTextColor
    private var currentSpeedTextColor: Int = _speedTextColor
    private var currentUnitTextColor: Int = _unitTextColor

    // ===== Paint =====

    private val bgArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val pointerTipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ===== 资源 =====

    /** 仪表圆盘背景图 (Dash 资源) */
    private var gaugeFaceBitmap: Bitmap? = null

    /** 指针资源图 (Dash 资源, 未使用则用 Canvas 渐变弧) */
    private var pointerBitmap: Bitmap? = null

    // ===== 动画 =====

    private var speedAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    private val arcRect = RectF()
    private val tickRect = RectF()
    private val faceRect = Rect()

    /** 是否绘制中心文字 */
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

        // 加载 Dash 仪表盘面资源 (可选, 失败则用纯色底)
        try {
            val face = BitmapFactory.decodeResource(resources, R.drawable.ic_dashboard_0)
            if (face != null) {
                gaugeFaceBitmap = face
            }
        } catch (_: Exception) { /* 资源缺失时跳过 */ }

        try {
            val ptr = BitmapFactory.decodeResource(resources, R.drawable.ic_db_pointer_1)
            if (ptr != null) {
                pointerBitmap = ptr
            }
        } catch (_: Exception) { /* 资源缺失时跳过 */ }

        // 中心数字使用 Pump 仪表字体
        speedTextPaint.typeface = loadGaugeTypeface()
    }

    private fun loadGaugeTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, "fonts/pump_std_demi_bold.otf")
        } catch (_: Exception) {
            Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }

    // ===== 公开 API =====

    var pointerColor: Int
        get() = _pointerColor
        set(value) { _pointerColor = value; animateToTargets() }

    var bgArcColor: Int
        get() = _bgArcColor
        set(value) { _bgArcColor = value; animateToTargets() }

    var tickColor: Int
        get() = _tickColor
        set(value) { _tickColor = value; animateToTargets() }

    var tickTextColor: Int
        get() = _tickTextColor
        set(value) { _tickTextColor = value; animateToTargets() }

    var speedTextColor: Int
        get() = _speedTextColor
        set(value) { _speedTextColor = value; animateToTargets() }

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
        val cy = height / 2f

        val maxRadiusByHeight = (cy - dpToPx(4f)).coerceAtLeast(dpToPx(40f))
        val maxRadiusByWidth = (width / 2f - dpToPx(6f)).coerceAtLeast(dpToPx(40f))
        val radius = minOf(maxRadiusByHeight, maxRadiusByWidth)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val tickRadius = radius - dpToPx(14f)
        tickRect.set(cx - tickRadius, cy - tickRadius, cx + tickRadius, cy + tickRadius)

        // ===== 1. 绘制仪表盘面 (圆形底) =====
        drawFace(canvas, cx, cy, radius)

        // ===== 2. 绘制背景弧 =====
        bgArcPaint.color = currentBgArcColor
        bgArcPaint.strokeWidth = dpToPx(10f)
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, bgArcPaint)

        // ===== 3. 绘制刻度 =====
        drawTicks(canvas, cx, cy, radius)

        // ===== 4. 绘制指针 =====
        drawPointer(canvas, cx, cy, radius)

        // ===== 5. 中心文字 =====
        if (drawText) {
            drawCenterText(canvas, cx, cy, radius)
        }
    }

    /**
     * 绘制圆形表盘底面
     *
     * 优先绘制 Dash 的 ic_dashboard_0 圆盘资源(缩放填充),
     * 资源缺失时回退为纯色圆形底。
     */
    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val bmp = gaugeFaceBitmap
        if (bmp != null) {
            val left = (cx - radius).toInt()
            val top = (cy - radius).toInt()
            val right = (cx + radius).toInt()
            val bottom = (cy + radius).toInt()
            faceRect.set(left, top, right, bottom)
            canvas.drawBitmap(bmp, null, faceRect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0xFF101820.toInt()
            }
            canvas.drawCircle(cx, cy, radius, facePaint)
        }
    }

    /**
     * 绘制刻度线和数字
     */
    private fun drawTicks(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val majorTickLen = dpToPx(14f)
        val minorTickLen = dpToPx(7f)
        val tickTextOffset = dpToPx(26f)

        val totalTicks = (maxSpeed.toInt() / minorTickInterval)

        for (i in 0..totalTicks) {
            val speedValue = i * minorTickInterval
            if (speedValue > maxSpeed) break

            val angleDeg = START_ANGLE + (speedValue.toFloat() / maxSpeed) * SWEEP_ANGLE
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val isMajor = speedValue % majorTickInterval == 0
            val tickLen = if (isMajor) majorTickLen else minorTickLen

            val outerX = cx + radius * cos(angleRad).toFloat()
            val outerY = cy + radius * sin(angleRad).toFloat()

            val innerR = radius - tickLen
            val innerX = cx + innerR * cos(angleRad).toFloat()
            val innerY = cy + innerR * sin(angleRad).toFloat()

            if (isMajor) {
                majorTickPaint.color = currentTickColor
                majorTickPaint.strokeWidth = dpToPx(2f)
                majorTickPaint.alpha = 220
                canvas.drawLine(outerX, outerY, innerX, innerY, majorTickPaint)
            } else {
                minorTickPaint.color = currentTickColor
                minorTickPaint.strokeWidth = dpToPx(1f)
                minorTickPaint.alpha = 110
                canvas.drawLine(outerX, outerY, innerX, innerY, minorTickPaint)
            }

            if (isMajor) {
                val labelR = radius - tickTextOffset
                val labelX = cx + labelR * cos(angleRad).toFloat()
                val labelY = cy + labelR * sin(angleRad).toFloat()

                tickTextPaint.color = currentTickTextColor
                tickTextPaint.textSize = dpToPx(12f)
                tickTextPaint.alpha = 200

                val textOffset = (tickTextPaint.descent() + tickTextPaint.ascent()) / 2f
                canvas.drawText(speedValue.toString(), labelX, labelY - textOffset, tickTextPaint)
            }
        }
    }

    /**
     * 绘制渐变指针 — Dash 标志性扫掠效果
     *
     * 指针是从 0 速度位置延伸到当前速度位置的弧段,
     * alpha 从基座(透明)到尖端(不透明)渐变, 尖端带高亮亮点。
     */
    private fun drawPointer(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (displaySpeed <= 0f) return

        val speedRatio = (displaySpeed / maxSpeed).coerceIn(0f, 1f)
        val pointerSweep = SWEEP_ANGLE * speedRatio

        if (pointerSweep < 0.5f) return

        val pointerRadius = radius - dpToPx(2f)
        val pointerRect = RectF(
            cx - pointerRadius, cy - pointerRadius,
            cx + pointerRadius, cy + pointerRadius,
        )

        // 分段渐变弧
        val segments = 36
        val segmentSweep = pointerSweep / segments
        val pointerWidth = dpToPx(7f)
        pointerPaint.strokeWidth = pointerWidth

        for (i in 0 until segments) {
            val progress = (i + 1).toFloat() / segments
            val segStart = START_ANGLE + i * segmentSweep
            val alpha = (progress * 255).toInt().coerceIn(0, 255)

            val r = Color.red(currentPointerColor)
            val g = Color.green(currentPointerColor)
            val b = Color.blue(currentPointerColor)

            val tipBoost = progress * 0.35f
            val finalR = (r + (255 - r) * tipBoost).toInt().coerceIn(0, 255)
            val finalG = (g + (220 - g) * tipBoost).toInt().coerceIn(0, 255)
            val finalB = (b + (255 - b) * tipBoost).toInt().coerceIn(0, 255)

            pointerPaint.color = Color.argb(alpha, finalR, finalG, finalB)
            canvas.drawArc(pointerRect, segStart, segmentSweep + 0.6f, false, pointerPaint)
        }

        // 指针尖端高亮
        val tipAngleDeg = START_ANGLE + pointerSweep
        val tipAngleRad = Math.toRadians(tipAngleDeg.toDouble())
        val tipX = cx + pointerRadius * cos(tipAngleRad).toFloat()
        val tipY = cy + pointerRadius * sin(tipAngleRad).toFloat()

        pointerTipPaint.color = currentPointerColor
        pointerTipPaint.alpha = 80
        canvas.drawCircle(tipX, tipY, dpToPx(10f), pointerTipPaint)

        pointerTipPaint.alpha = 170
        canvas.drawCircle(tipX, tipY, dpToPx(6f), pointerTipPaint)

        pointerTipPaint.color = _pointerTipColor
        pointerTipPaint.alpha = 255
        canvas.drawCircle(tipX, tipY, dpToPx(3.5f), pointerTipPaint)
    }

    /**
     * 绘制中心速度数字和单位
     */
    private fun drawCenterText(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // 速度数字 (Pump 字体, 大号)
        speedTextPaint.color = currentSpeedTextColor
        speedTextPaint.textSize = radius * 0.48f

        val speedStr = displaySpeed.toInt().toString()
        val textY = cy - (speedTextPaint.descent() + speedTextPaint.ascent()) / 2f - radius * 0.02f
        canvas.drawText(speedStr, cx, textY, speedTextPaint)

        // km/h 单位
        unitTextPaint.color = currentUnitTextColor
        unitTextPaint.textSize = radius * 0.13f
        unitTextPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val unitY = textY + radius * 0.26f
        canvas.drawText("km/h", cx, unitY, unitTextPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        speedAnimator?.cancel()
        colorAnimator?.cancel()
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    companion object {
        /** 弧起始角度 (135° — 左下) */
        private const val START_ANGLE = 135f

        /** 弧扫过角度 (270° — 3/4 圆) */
        private const val SWEEP_ANGLE = 270f

        private const val SPEED_ANIM_DURATION = 300L
        private const val COLOR_ANIM_DURATION = 300L
    }
}
