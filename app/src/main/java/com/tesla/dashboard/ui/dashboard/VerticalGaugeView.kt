package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.tesla.dashboard.R

/**
 * 竖向条形仪表 View — 用于电量/功率等垂直进度显示
 *
 * ## 设计规格
 * - 宽度 24dp, 高度自适应
 * - 背景轨道 (trackColor) + 填充条 (fillColor, 从底部向上)
 * - 顶部标签 (10sp, sans-serif-medium)
 * - 底部数值 (14sp, sans-serif-thin)
 * - 圆角 2dp
 * - 数值变化动画: 300ms, DecelerateInterpolator
 *
 * ## 电量颜色策略
 * Activity 根据 SOC 阈值调用 [setFillColor]:
 * - SOC > 50%: accentGreen
 * - SOC 20-50%: accentOrange
 * - SOC < 20%: accentRed
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class VerticalGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 背景轨道画笔 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 填充条画笔 */
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 标签画笔 */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** 数值画笔 */
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }

    /** 最大值 */
    private var maxValue: Float = 1f

    /** 当前显示值 (动画中间值) */
    private var displayValue: Float = 0f

    /** 目标值 */
    private var targetValue: Float = 0f

    /** 标签文字 */
    var label: String = ""

    /** 数值文字 */
    var valueText: String = "--"
        private set

    /** 圆角半径 */
    private val cornerRadius: Float = dpToPx(2f)

    /** 填充区域 */
    private val fillRect = RectF()

    /** 值动画 */
    private var valueAnimator: ValueAnimator? = null

    init {
        context.obtainStyledAttributes(attrs, R.styleable.VerticalGaugeView).apply {
            label = getString(R.styleable.VerticalGaugeView_vgLabel) ?: ""
            targetValue = getFloat(R.styleable.VerticalGaugeView_vgValue, 0f)
            displayValue = targetValue
            maxValue = getFloat(R.styleable.VerticalGaugeView_vgMaxValue, 1f)
            trackPaint.color = getColor(R.styleable.VerticalGaugeView_vgTrackColor, 0xFF38383A.toInt())
            fillPaint.color = getColor(R.styleable.VerticalGaugeView_vgFillColor, 0xFF0A84FF.toInt())
            recycle()
        }
    }

    /**
     * 设置当前值 (带动画)
     *
     * @param value 当前值 (0 ~ maxValue)
     * @param animate 是否启用动画
     */
    fun setValue(value: Float, animate: Boolean = true) {
        val clamped = value.coerceIn(0f, maxValue)
        targetValue = clamped

        if (!animate) {
            displayValue = clamped
            invalidate()
            return
        }

        valueAnimator?.cancel()
        valueAnimator = ValueAnimator.ofFloat(displayValue, clamped).apply {
            duration = VALUE_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                displayValue = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 设置填充色 */
    fun setFillColor(color: Int) {
        fillPaint.color = color
        invalidate()
    }

    /** 设置轨道色 */
    fun setTrackColor(color: Int) {
        trackPaint.color = color
        invalidate()
    }

    /** 设置标签色 */
    fun setLabelColor(color: Int) {
        labelPaint.color = color
        invalidate()
    }

    /** 设置数值色 */
    fun setValueColor(color: Int) {
        valuePaint.color = color
        invalidate()
    }

    /** 设置数值文字 */
    fun setValueText(text: String) {
        valueText = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f

        // 计算仪表条区域 (顶部留标签空间, 底部留数值空间)
        val labelHeight = dpToPx(16f)
        val valueHeight = dpToPx(20f)
        val barTop = labelHeight.toFloat()
        val barBottom = height.toFloat() - valueHeight
        val barHeight = barBottom - barTop
        val barLeft = cx - dpToPx(3f)
        val barRight = cx + dpToPx(3f)

        // 绘制背景轨道
        trackPaint.color = trackPaint.color
        canvas.drawRoundRect(
            barLeft, barTop, barRight, barBottom,
            cornerRadius, cornerRadius, trackPaint
        )

        // 绘制填充条 (从底部向上)
        val fillRatio = (displayValue / maxValue).coerceIn(0f, 1f)
        val fillHeight = barHeight * fillRatio
        if (fillHeight > 0) {
            fillRect.set(
                barLeft, barBottom - fillHeight,
                barRight, barBottom
            )
            canvas.drawRoundRect(fillRect, cornerRadius, cornerRadius, fillPaint)
        }

        // 绘制标签 (顶部)
        labelPaint.textSize = dpToPx(10f)
        labelPaint.alpha = 255
        canvas.drawText(label, cx, dpToPx(12f), labelPaint)

        // 绘制数值 (底部)
        valuePaint.textSize = dpToPx(14f)
        val valueY = height - dpToPx(4f)
        canvas.drawText(valueText, cx, valueY, valuePaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        valueAnimator?.cancel()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        private const val VALUE_ANIM_DURATION = 300L
    }
}
