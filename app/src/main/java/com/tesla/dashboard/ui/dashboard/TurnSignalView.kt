package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.tesla.dashboard.R

/**
 * 转向灯指示 View — 复刻 Dash for Tesla 1.8.0 绿色箭头转向灯
 *
 * ## 设计特征
 * - 使用 Dash for Tesla 的左右箭头图标资源 (ic_left.png / ic_right.png)
 * - 激活时绿色 (#12D37C) + 闪烁动画
 * - 非激活时灰色低透明度
 * - 支持左转/右转/双闪模式
 * - 警告场景 (门未关) 可切换红色
 *
 * ## 闪烁模式
 * - 500ms 循环 (alpha 0.2 → 1.0)
 * - 使用 LinearInterpolator 实现匀速闪烁
 *
 * @param context 上下文
 * @param attrs XML 属性集
 * @param defStyleAttr 默认样式
 */
class TurnSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 激活颜色 (Dash 绿) */
    private var activeColor: Int = 0xFF12D37C.toInt()

    /** 非激活颜色 */
    private var inactiveColor: Int = 0xFF3A3A3A.toInt()

    /** 左箭头图标 (Dash 资源) */
    private var leftBitmap: Bitmap? = null

    /** 右箭头图标 (Dash 资源) */
    private var rightBitmap: Bitmap? = null

    /** 箭头画笔 (含颜色滤镜) */
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    /** 闪烁进度 (0f → 1f) */
    private var blinkProgress: Float = 0f

    /** 闪烁动画器 */
    private var blinkAnimator: ValueAnimator? = null

    /** 转向灯状态 */
    enum class TurnSignalState {
        NONE,       // 无转向灯
        LEFT,       // 左转
        RIGHT,      // 右转
        HAZARD,     // 双闪
    }

    private var state: TurnSignalState = TurnSignalState.NONE

    init {
        try {
            leftBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_left)
        } catch (_: Exception) { /* 资源缺失时跳过 */ }
        try {
            rightBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_right)
        } catch (_: Exception) { /* 资源缺失时跳过 */ }
    }

    /**
     * 设置转向灯状态
     * @param state 转向灯状态
     */
    fun setState(state: TurnSignalState) {
        if (this.state == state) return
        this.state = state
        updateBlinkAnimation()
        invalidate()
    }

    /**
     * 设置主题颜色
     */
    fun setColors(active: Int, inactive: Int) {
        activeColor = active
        inactiveColor = inactive
        invalidate()
    }

    /** 启动/停止闪烁动画 */
    private fun updateBlinkAnimation() {
        blinkAnimator?.cancel()
        if (state != TurnSignalState.NONE) {
            blinkAnimator = ValueAnimator.ofFloat(0.2f, 1f).apply {
                duration = BLINK_DURATION
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        // 图标尺寸 (宽 140 x 高 1624 的长条, 截取上半部分箭头区域)
        val iconHeight = height * 0.9f
        val iconWidth = iconHeight * (leftBitmap?.width ?: 140).toFloat() / (leftBitmap?.height ?: 1624).toFloat()
        val iconHeightAdj = iconWidth * (leftBitmap?.height ?: 1624).toFloat() / (leftBitmap?.width ?: 140).toFloat()

        // 绘制左箭头
        val leftActive = state == TurnSignalState.LEFT || state == TurnSignalState.HAZARD
        drawIcon(canvas, leftBitmap, cx - iconWidth * 0.8f, cy, iconWidth, iconHeightAdj, leftActive, isLeft = true)

        // 绘制右箭头
        val rightActive = state == TurnSignalState.RIGHT || state == TurnSignalState.HAZARD
        drawIcon(canvas, rightBitmap, cx + iconWidth * 0.8f, cy, iconWidth, iconHeightAdj, rightActive, isLeft = false)
    }

    /**
     * 绘制单个箭头图标 (带颜色滤镜与透明度)
     */
    private fun drawIcon(
        canvas: Canvas,
        bmp: Bitmap?,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        active: Boolean,
        isLeft: Boolean,
    ) {
        if (bmp == null) return

        val alpha = if (active) (blinkProgress * 255).toInt().coerceIn(0, 255) else 60
        val color = if (active) activeColor else inactiveColor

        arrowPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        arrowPaint.alpha = alpha

        val left = cx - w / 2f
        val top = cy - h / 2f
        val right = cx + w / 2f
        val bottom = cy + h / 2f

        canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, right, bottom), arrowPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkAnimator?.cancel()
    }

    companion object {
        private const val BLINK_DURATION = 500L
    }
}
