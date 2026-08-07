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
 * 基于 Material Design 实心三角形图标的转向灯指示器:
 * - **矢量绘制**: 纯 Canvas 实心三角形, 不依赖位图资源, 任意尺寸清晰
 * - **Tesla 风格顺序扫描动画**: 每个箭头分 3 段,
 *   亮起脉冲从箭头根部向尖端依次流动 (与真实转向灯一致)
 * - **双闪模式**: 左右箭头同步扫描
 * - **主题色联动**: 通过 [setColors] 设置激活色/熄灭色, 随主题切换
 *
 * 说明: Tesla BLE 协议不暴露转向灯真实状态, 本视图为装饰性显示
 * (常显静态箭头), 状态接口 [setState] 保留, 供未来接入真实信号数据。
 *
 * ## 状态
 * - [State.OFF]: 静态熄灭 (不启动动画, 省电)
 * - [State.LEFT] / [State.RIGHT]: 单侧扫描动画
 * - [State.HAZARD]: 双侧同步扫描
 */
class TurnSignalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * 转向灯状态
     */
    enum class State {
        /** 熄灭 (静态) */
        OFF,

        /** 左转 */
        LEFT,

        /** 右转 */
        RIGHT,

        /** 双闪 */
        HAZARD,
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 激活色 (扫描亮起时的颜色, 默认主题绿) */
    private var activeColor: Int = Color.parseColor("#30D158")

    /** 熄灭色 (静态箭头颜色, 深灰) */
    private var idleColor: Int = Color.parseColor("#3A3F47")

    /** 当前状态 */
    private var state: State = State.OFF

    /** 扫描动画进度 0..1 (重复循环) */
    private var sweepPhase: Float = 0f

    /** 扫描动画 */
    private var sweepAnimator: ValueAnimator? = null

    /** 扫描脉冲在箭头上的宽度比例 (根→尖为一个完整脉冲) */
    private val pulseWidth = 0.38f

    /** 左箭头 Path (预计算, onSizeChanged 时更新) */
    private val leftArrowPath = Path()

    /** 右箭头 Path (预计算, onSizeChanged 时更新) */
    private val rightArrowPath = Path()

    /**
     * 设置转向灯状态
     *
     * @param state 目标状态
     */
    fun setState(state: State) {
        if (this.state == state) return
        this.state = state
        updateAnimation()
    }

    /**
     * 设置主题色
     *
     * @param active 激活色 (亮起脉冲)
     * @param idle 熄灭色 (静态箭头)
     */
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

    /**
     * 根据状态启停扫描动画
     */
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
            doOnEnd {
                sweepPhase = 0f
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        when (state) {
            State.OFF -> {
                drawArrowSegments(canvas, leftArrowPath, sweepPhase = -1f, pointingLeft = true)
                drawArrowSegments(canvas, rightArrowPath, sweepPhase = -1f, pointingLeft = false)
            }
            State.LEFT -> {
                drawArrowSegments(canvas, leftArrowPath, sweepPhase, pointingLeft = true)
                drawArrowSegments(canvas, rightArrowPath, sweepPhase = -1f, pointingLeft = false)
            }
            State.RIGHT -> {
                drawArrowSegments(canvas, leftArrowPath, sweepPhase = -1f, pointingLeft = true)
                drawArrowSegments(canvas, rightArrowPath, sweepPhase, pointingLeft = false)
            }
            State.HAZARD -> {
                drawArrowSegments(canvas, leftArrowPath, sweepPhase, pointingLeft = true)
                drawArrowSegments(canvas, rightArrowPath, sweepPhase, pointingLeft = false)
            }
        }
    }

    /**
     * 构建左右箭头的 Path (Material Design 实心三角形)
     *
     * 左三角: 尖端在左, 根部在右
     * 右三角: 尖端在右, 根部在左
     *
     * 两者互为水平镜像, 保证完全对称。
     */
    private fun buildArrowPaths(w: Int, h: Int) {
        val centerY = h / 2f
        val arrowW = w * 0.30f    // 三角形宽度 (水平)
        val arrowH = h * 0.70f    // 三角形高度 (垂直)

        // === 左三角: 尖端在左 (x 小), 根部在右 (x 大) ===
        val leftTipX = w * 0.08f
        val leftBaseX = leftTipX + arrowW

        leftArrowPath.reset()
        leftArrowPath.moveTo(leftTipX, centerY)              // 尖端 (左侧)
        leftArrowPath.lineTo(leftBaseX, centerY - arrowH / 2) // 根部上
        leftArrowPath.lineTo(leftBaseX, centerY + arrowH / 2) // 根部下
        leftArrowPath.close()

        // === 右三角: 尖端在右 (x 大), 根部在左 (x 小) ===
        val rightBaseX = w - leftBaseX
        val rightTipX = w - leftTipX

        rightArrowPath.reset()
        rightArrowPath.moveTo(rightTipX, centerY)               // 尖端 (右侧)
        rightArrowPath.lineTo(rightBaseX, centerY - arrowH / 2) // 根部上
        rightArrowPath.lineTo(rightBaseX, centerY + arrowH / 2) // 根部下
        rightArrowPath.close()
    }

    /**
     * 绘制箭头的 3 段扫描效果
     *
     * @param pointingLeft true=左箭头(根部在右), false=右箭头(根部在左)
     */
    private fun drawArrowSegments(
        canvas: Canvas,
        arrowPath: Path,
        sweepPhase: Float,
        pointingLeft: Boolean,
    ) {
        val bounds = RectF()
        arrowPath.computeBounds(bounds, true)

        val segmentCount = 3
        val segW = bounds.width() / segmentCount

        for (i in 0 until segmentCount) {
            // 段 i 的 x 范围: 根部(i=0) → 尖端(i=2)
            val segLeft: Float
            val segRight: Float
            if (pointingLeft) {
                // 左箭头: 根部在右, 尖端在左
                segRight = bounds.right - segW * i
                segLeft = bounds.right - segW * (i + 1)
            } else {
                // 右箭头: 根部在左, 尖端在右
                segLeft = bounds.left + segW * i
                segRight = bounds.left + segW * (i + 1)
            }

            // 判断该段是否被扫描脉冲点亮
            var lit = false
            if (sweepPhase >= 0f) {
                val segmentStart = i / segmentCount.toFloat()
                val t = sweepPhase + segmentStart
                lit = t % 1f < pulseWidth
            }

            arrowPaint.color = if (lit) activeColor else idleColor

            // 用 clipPath 裁剪当前段, 再绘制完整箭头
            canvas.save()
            val clipPath = Path()
            clipPath.addRect(segLeft, bounds.top - 1f, segRight, bounds.bottom + 1f, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.restore()
        }
    }

    companion object {
        /** 完整扫描周期 (ms) — 与真实转向灯频率 (~80 次/分钟) 一致 */
        private const val SWEEP_CYCLE_MS = 750L
    }
}
