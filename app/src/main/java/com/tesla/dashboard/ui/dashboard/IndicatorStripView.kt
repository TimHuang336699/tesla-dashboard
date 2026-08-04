package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import com.tesla.dashboard.R

/**
 * 水平指示灯条 View — 排列状态指示图标 (转向灯、大灯、连接状态等)
 *
 * ## 设计规格
 * - 图标水平排列,间距 16dp,尺寸 20x20dp
 * - 激活: 完全不透明, 使用 activeColor
 * - 非激活: 30% 透明度, 使用 inactiveColor
 * - 闪烁: 500ms 循环 (alpha 0.3 → 1.0)
 *
 * ## 使用方式
 * ```kotlin
 * indicatorStrip.setIndicators(listOf(
 *     IndicatorStripView.Indicator("conn", icon, active = true, blinking = false),
 *     IndicatorStripView.Indicator("gps", icon, active = true, blinking = false),
 * ))
 * indicatorStrip.setActiveColor(colors.accentGreen)
 * indicatorStrip.setInactiveColor(colors.textSecondary)
 * ```
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class IndicatorStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 指示灯数据类 */
    data class Indicator(
        val id: String,
        val icon: Drawable,
        var active: Boolean = false,
        var blinking: Boolean = false,
    )

    /** 指示灯列表 */
    private var indicators: List<Indicator> = emptyList()

    /** 激活颜色 */
    private var activeColor: Int = 0xFF30D158.toInt()

    /** 非激活颜色 */
    private var inactiveColor: Int = 0xFF8E8E93.toInt()

    /** 图标间距 */
    private val spacing: Float

    /** 图标尺寸 */
    private val iconSize: Float

    /** 闪烁进度 (0f → 1f) */
    private var blinkProgress: Float = 0f

    /** 闪烁动画器 */
    private var blinkAnimator: ValueAnimator? = null

    /** 是否有闪烁中的指示灯 */
    private var hasBlinking: Boolean = false

    init {
        context.obtainStyledAttributes(attrs, R.styleable.IndicatorStripView).apply {
            spacing = getDimension(R.styleable.IndicatorStripView_isvIndicatorSpacing, dpToPx(16f))
            iconSize = getDimension(R.styleable.IndicatorStripView_isvIndicatorSize, dpToPx(20f))
            recycle()
        }
    }

    /**
     * 设置指示灯列表
     *
     * 替换整个列表,并根据是否有 blinking 项启动/停止闪烁动画。
     */
    fun setIndicators(items: List<Indicator>) {
        indicators = items
        hasBlinking = items.any { it.blinking }
        updateBlinkAnimation()
        requestLayout()
        invalidate()
    }

    /** 更新单个指示灯状态 */
    fun updateIndicator(id: String, active: Boolean, blinking: Boolean = false) {
        indicators.find { it.id == id }?.let {
            it.active = active
            it.blinking = blinking
        }
        val newHasBlinking = indicators.any { it.blinking }
        if (newHasBlinking != hasBlinking) {
            hasBlinking = newHasBlinking
            updateBlinkAnimation()
        }
        invalidate()
    }

    /** 设置激活颜色 */
    fun setActiveColor(color: Int) {
        activeColor = color
        invalidate()
    }

    /** 设置非激活颜色 */
    fun setInactiveColor(color: Int) {
        inactiveColor = color
        invalidate()
    }

    /** 启动/停止闪烁动画 */
    private fun updateBlinkAnimation() {
        blinkAnimator?.cancel()
        if (hasBlinking) {
            blinkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BLINK_DURATION
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    blinkProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            blinkProgress = 0f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = if (indicators.isEmpty()) {
            0
        } else {
            (indicators.size * iconSize + (indicators.size - 1) * spacing).toInt()
        }
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = iconSize.toInt() + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        var x = paddingLeft.toFloat()
        val y = paddingTop.toFloat()

        for (indicator in indicators) {
            val alpha = when {
                indicator.blinking -> (0.3f + 0.7f * blinkProgress)
                indicator.active -> 1.0f
                else -> 0.3f
            }

            val color = if (indicator.active) activeColor else inactiveColor
            indicator.icon.setBounds(
                x.toInt(), y.toInt(),
                (x + iconSize).toInt(), (y + iconSize).toInt()
            )
            indicator.icon.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            indicator.icon.setTint(color)
            indicator.icon.draw(canvas)

            x += iconSize + spacing
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkAnimator?.cancel()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        private const val BLINK_DURATION = 500L
    }
}
