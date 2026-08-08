package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.tesla.dashboard.R

/**
 * 转向灯指示视图 2.0 (v0.5.0)
 *
 * 使用 VectorDrawable 资源绘制方块箭头:
 * - OFF: 左右箭头均为灰色
 * - LEFT: 左箭头绿色, 右箭头灰色
 * - RIGHT: 左箭头灰色, 右箭头绿色
 * - HAZARD: 左右箭头均为绿色
 *
 * 扫描动画: 从柄尾向尖端依次点亮 (3 段)
 */
class TurnSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State {
        OFF,
        LEFT,
        RIGHT,
        HAZARD,
    }

    private var activeColor: Int = Color.parseColor("#30D158")
    private var idleColor: Int = Color.parseColor("#8E8E93")

    private var state: State = State.OFF
    private var sweepPhase: Float = 0f
    private var sweepAnimator: ValueAnimator? = null
    private val pulseWidth = 0.35f

    /** 左箭头 Drawable */
    private var leftArrowDrawable: Drawable? = null

    /** 右箭头 Drawable */
    private var rightArrowDrawable: Drawable? = null

    /** 左箭头绘制区域 */
    private val leftBounds = RectF()

    /** 右箭头绘制区域 */
    private val rightBounds = RectF()

    /** 左箭头 3 段裁剪区域 */
    private val leftClipRects = arrayOf(RectF(), RectF(), RectF())

    /** 右箭头 3 段裁剪区域 */
    private val rightClipRects = arrayOf(RectF(), RectF(), RectF())

    /** 裁剪用画笔 */
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setState(state: State) {
        if (this.state == state) return
        this.state = state
        updateAnimation()
    }

    fun setColors(active: Int, idle: Int) {
        if (activeColor == active && idleColor == idle) return
        activeColor = active
        idleColor = idle
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            calculateLayout(w, h)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        leftArrowDrawable = ContextCompat.getDrawable(context, R.drawable.ic_turn_signal_left)
        rightArrowDrawable = ContextCompat.getDrawable(context, R.drawable.ic_turn_signal_right)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnimator?.cancel()
        sweepAnimator = null
        leftArrowDrawable = null
        rightArrowDrawable = null
    }

    private fun updateAnimation() {
        sweepAnimator?.cancel()
        sweepAnimator = null
        if (!isAttachedToWindow) return

        if (state == State.OFF) {
            invalidate()
            return
        }

        sweepAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                sweepPhase = animator.animatedValue as Float
                postInvalidateOnAnimation()
            }
            doOnEnd { sweepPhase = 0f }
            start()
        }
    }

    /**
     * 计算箭头布局位置和裁剪区域
     */
    private fun calculateLayout(w: Int, h: Int) {
        val arrowW = w * 0.35f
        val arrowH = h * 0.85f
        val arrowSize = minOf(arrowW, arrowH)
        val padding = w * 0.02f  // 边距

        // 左箭头靠左
        val leftCx = arrowSize / 2f + padding
        val cy = h / 2f

        leftBounds.set(
            leftCx - arrowSize / 2f,
            cy - arrowSize / 2f,
            leftCx + arrowSize / 2f,
            cy + arrowSize / 2f,
        )

        // 右箭头靠右
        val rightCx = w - arrowSize / 2f - padding

        rightBounds.set(
            rightCx - arrowSize / 2f,
            cy - arrowSize / 2f,
            rightCx + arrowSize / 2f,
            cy + arrowSize / 2f,
        )

        // 计算 3 段裁剪区域
        val segW = arrowSize / 3f

        // 左箭头: 从右到左 (柄尾 → 尖端) = 从上到下裁剪
        for (i in 0 until 3) {
            leftClipRects[i] = RectF(
                leftBounds.left + segW * i,
                leftBounds.top,
                leftBounds.left + segW * (i + 1),
                leftBounds.bottom,
            )
        }

        // 右箭头: 从左到右 (柄尾 → 尖端)
        for (i in 0 until 3) {
            rightClipRects[i] = RectF(
                rightBounds.left + segW * i,
                rightBounds.top,
                rightBounds.left + segW * (i + 1),
                rightBounds.bottom,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val leftDrawable = leftArrowDrawable ?: return
        val rightDrawable = rightArrowDrawable ?: return

        // 确定每个箭头的颜色
        val leftColor: Int
        val rightColor: Int
        when (state) {
            State.OFF -> { leftColor = idleColor; rightColor = idleColor }
            State.LEFT -> { leftColor = activeColor; rightColor = idleColor }
            State.RIGHT -> { leftColor = idleColor; rightColor = activeColor }
            State.HAZARD -> { leftColor = activeColor; rightColor = activeColor }
        }

        // 绘制左箭头 (带扫描效果)
        drawDrawableWithSegments(canvas, leftDrawable, leftBounds, leftClipRects, leftColor)

        // 绘制右箭头 (带扫描效果)
        drawDrawableWithSegments(canvas, rightDrawable, rightBounds, rightClipRects, rightColor)
    }

    /**
     * 绘制带扫描效果的 Drawable
     */
    private fun drawDrawableWithSegments(
        canvas: Canvas,
        drawable: Drawable,
        bounds: RectF,
        clipRects: Array<RectF>,
        baseColor: Int,
    ) {
        val isAnimated = baseColor == activeColor

        for (i in 0 until 3) {
            var lit = false
            if (isAnimated && sweepPhase >= 0f) {
                val segmentStart = i / 3f
                val t = sweepPhase + segmentStart
                lit = t % 1f < pulseWidth
            }

            val color = if (lit) brightColor(baseColor) else baseColor

            canvas.save()
            canvas.clipRect(clipRects[i])
            drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            drawable.setBounds(
                bounds.left.toInt(),
                bounds.top.toInt(),
                bounds.right.toInt(),
                bounds.bottom.toInt(),
            )
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    private fun brightColor(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val factor = 1.3f
        return Color.rgb(
            (r * factor).toInt().coerceIn(0, 255),
            (g * factor).toInt().coerceIn(0, 255),
            (b * factor).toInt().coerceIn(0, 255),
        )
    }

    companion object {
        private const val SWEEP_CYCLE_MS = 750L
    }
}
