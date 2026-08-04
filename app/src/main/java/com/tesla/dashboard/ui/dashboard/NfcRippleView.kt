package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.tesla.dashboard.R

/**
 * NFC 涟漪动画 View — 3 层青色同心圆扩散效果
 *
 * 用于 BLE 配对流程 Step 3,替代静态图片,增强用户体验。
 *
 * ## 动画规格
 * - 3 个同心圆,颜色 Tesla Cyan #00D4FF
 * - 每个圆: 1500ms 周期, RESTART, INFINITE
 * - 半径: 0 → maxRadius (线性扩展)
 * - 透明度: 255 → 0 (向外渐隐)
 * - 线宽: 3dp → 1dp (向外变细)
 * - 错开延迟: 0ms / 500ms / 1000ms
 *
 * ## 生命周期
 * - [startRipple]: 启动动画(进入 Step 3 时调用)
 * - [stopRipple]: 停止动画(离开 Step 3 时调用)
 * - [onDetachedFromWindow]: 自动 cancel 所有 animator
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class NfcRippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 涟漪画笔 */
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 涟漪数量 */
    private val rippleCount: Int

    /** 动画周期 ms */
    private val cycleDuration: Long

    /** 错开延迟 ms */
    private val staggerDelay: Long

    /** 最大线宽 dp */
    private val maxStrokeWidth: Float

    /** 最小线宽 dp */
    private val minStrokeWidth: Float

    /** 每个涟漪的进度 (0f → 1f) */
    private val rippleProgresses: FloatArray

    /** 动画器列表 */
    private val animators = mutableListOf<ValueAnimator>()

    /** 最大半径 (onSizeChanged 计算) */
    private var maxRadius = 0f

    /** 是否正在动画 */
    private var isAnimating = false

    /** 涟漪颜色 */
    var rippleColor: Int = 0xFF00D4FF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    init {
        // 读取自定义属性
        context.obtainStyledAttributes(attrs, R.styleable.NfcRippleView).apply {
            rippleColor = getColor(R.styleable.NfcRippleView_rippleColor, 0xFF00D4FF.toInt())
            rippleCount = getInt(R.styleable.NfcRippleView_rippleCount, DEFAULT_RIPPLE_COUNT)
            cycleDuration = getInt(R.styleable.NfcRippleView_rippleDuration, DEFAULT_CYCLE_DURATION).toLong()
            recycle()
        }

        staggerDelay = cycleDuration / rippleCount
        maxStrokeWidth = dpToPx(DEFAULT_MAX_STROKE_DP)
        minStrokeWidth = dpToPx(DEFAULT_MIN_STROKE_DP)
        rippleProgresses = FloatArray(rippleCount) { 0f }

        // 硬件加速
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maxRadius = (minOf(w, h) / 2f - maxStrokeWidth).coerceAtLeast(dpToPx(20f))
    }

    /**
     * 启动涟漪动画
     *
     * 创建 [rippleCount] 个 ValueAnimator,各自带错开延迟,
     * 循环更新对应 [rippleProgresses] 并 invalidate。
     */
    fun startRipple() {
        if (isAnimating) return
        isAnimating = true

        for (i in 0 until rippleCount) {
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = cycleDuration
                startDelay = i * staggerDelay
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    rippleProgresses[i] = anim.animatedValue as Float
                    invalidate()
                }
            }
            animators.add(animator)
            animator.start()
        }
    }

    /**
     * 停止涟漪动画
     *
     * 取消所有 animator,清空进度,触发重绘。
     */
    fun stopRipple() {
        if (!isAnimating) return
        isAnimating = false

        animators.forEach { it.cancel() }
        animators.clear()
        for (i in rippleProgresses.indices) {
            rippleProgresses[i] = 0f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        ripplePaint.color = rippleColor

        for (i in 0 until rippleCount) {
            val progress = rippleProgresses[i]
            if (progress <= 0f) continue

            val radius = progress * maxRadius
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            val strokeWidth = lerp(maxStrokeWidth, minStrokeWidth, progress)

            ripplePaint.alpha = alpha
            ripplePaint.strokeWidth = strokeWidth

            canvas.drawCircle(cx, cy, radius, ripplePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRipple()
    }

    /** 线性插值 */
    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    /** dp 转 px */
    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        private const val DEFAULT_RIPPLE_COUNT = 3
        private const val DEFAULT_CYCLE_DURATION = 1500
        private const val DEFAULT_MAX_STROKE_DP = 3f
        private const val DEFAULT_MIN_STROKE_DP = 1f
    }
}
