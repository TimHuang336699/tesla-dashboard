package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd

/**
 * 转向灯指示视图 2.0 (v0.5.0)
 *
 * 实心方块箭头样式 (矩形柄 + 三角形头):
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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var activeColor: Int = Color.parseColor("#30D158")
    private var idleColor: Int = Color.parseColor("#8E8E93")

    private var state: State = State.OFF
    private var sweepPhase: Float = 0f
    private var sweepAnimator: ValueAnimator? = null
    private val pulseWidth = 0.35f

    /** 左箭头完整 Path */
    private val leftArrowPath = Path()

    /** 右箭头完整 Path */
    private val rightArrowPath = Path()

    /** 左箭头 3 个分段裁剪区域 */
    private val leftClipRects = arrayOf(RectF(), RectF(), RectF())

    /** 右箭头 3 个分段裁剪区域 */
    private val rightClipRects = arrayOf(RectF(), RectF(), RectF())

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
            buildArrowPaths(w, h)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnimator?.cancel()
        sweepAnimator = null
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // 确定每个箭头的颜色
        val leftColor: Int
        val rightColor: Int
        when (state) {
            State.OFF -> { leftColor = idleColor; rightColor = idleColor }
            State.LEFT -> { leftColor = activeColor; rightColor = idleColor }
            State.RIGHT -> { leftColor = idleColor; rightColor = activeColor }
            State.HAZARD -> { leftColor = activeColor; rightColor = activeColor }
        }

        // 绘制左箭头 (3 段扫描)
        drawSegmentedArrow(canvas, leftArrowPath, leftClipRects, leftColor)

        // 绘制右箭头 (3 段扫描)
        drawSegmentedArrow(canvas, rightArrowPath, rightClipRects, rightColor)
    }

    /**
     * 构建方块箭头 Path
     *
     * 左箭头: ◄───  (三角头在左, 矩形柄在右)
     * 右箭头: ───►  (三角头在右, 矩形柄在左)
     */
    private fun buildArrowPaths(w: Int, h: Int) {
        val centerY = h / 2f
        val gap = w * 0.06f

        // 箭头尺寸
        val arrowW = (w / 2f - gap / 2f) * 0.88f
        val stemH = h * 0.48f    // 柄高度
        val headW = arrowW * 0.42f  // 三角头宽度
        val stemW = arrowW - headW  // 柄宽度

        // === 左箭头 ===
        val leftStemRight = w / 2f - gap / 2f
        val leftStemLeft = leftStemRight - stemW
        val leftTip = leftStemLeft - headW

        leftArrowPath.reset()
        leftArrowPath.moveTo(leftTip, centerY)
        leftArrowPath.lineTo(leftStemLeft, centerY - stemH / 2)
        leftArrowPath.lineTo(leftStemRight, centerY - stemH / 2)
        leftArrowPath.lineTo(leftStemRight, centerY + stemH / 2)
        leftArrowPath.lineTo(leftStemLeft, centerY + stemH / 2)
        leftArrowPath.close()

        // 左箭头 3 段裁剪区 (从柄尾到尖端)
        val leftSegW = arrowW / 3f
        for (i in 0 until 3) {
            leftClipRects[i] = RectF(
                leftTip + leftSegW * i,
                centerY - stemH / 2 - 1f,
                leftTip + leftSegW * (i + 1),
                centerY + stemH / 2 + 1f,
            )
        }

        // === 右箭头 (水平镜像) ===
        val rightStemLeft = w / 2f + gap / 2f
        val rightStemRight = rightStemLeft + stemW
        val rightTip = rightStemRight + headW

        rightArrowPath.reset()
        rightArrowPath.moveTo(rightTip, centerY)
        rightArrowPath.lineTo(rightStemRight, centerY - stemH / 2)
        rightArrowPath.lineTo(rightStemLeft, centerY - stemH / 2)
        rightArrowPath.lineTo(rightStemLeft, centerY + stemH / 2)
        rightArrowPath.lineTo(rightStemRight, centerY + stemH / 2)
        rightArrowPath.close()

        // 右箭头 3 段裁剪区 (从柄尾到尖端)
        val rightSegW = arrowW / 3f
        for (i in 0 until 3) {
            rightClipRects[i] = RectF(
                rightStemLeft + rightSegW * i,
                centerY - stemH / 2 - 1f,
                rightStemLeft + rightSegW * (i + 1),
                centerY + stemH / 2 + 1f,
            )
        }
    }

    /**
     * 绘制带扫描效果的方块箭头
     */
    private fun drawSegmentedArrow(
        canvas: Canvas,
        arrowPath: Path,
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

            fillPaint.color = if (lit) brightColor(baseColor) else baseColor

            canvas.save()
            canvas.clipRect(clipRects[i])
            canvas.drawPath(arrowPath, fillPaint)
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
