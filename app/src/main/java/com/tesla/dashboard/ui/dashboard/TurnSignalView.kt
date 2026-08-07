package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import kotlin.math.abs

/**
 * 转向灯指示视图 2.0 (v0.5.0)
 *
 * 替代 v0.2.2 已删除的位图版 TurnSignalView:
 * - **矢量绘制**: 纯 Canvas 三角箭头, 不依赖位图资源, 任意尺寸清晰
 * - **Tesla 风格顺序扫描动画**: 每个箭头分根/中/尖三段,
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
     *
     * OFF 时停止动画 (静态熄灭); LEFT/RIGHT/HAZARD 时启动循环扫描。
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
                // 动画被主动取消时重置进度 (doOnEnd 在 cancel 时也会触发)
                sweepPhase = 0f
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // 左箭头: 左半区; 右箭头: 右半区 (各占 42%, 居中留空)
        val arrowWidth = width * 0.42f
        val centerY = height / 2f
        val baseHalf = height * 0.32f
        val arrowSpan = arrowWidth * 0.9f

        when (state) {
            State.OFF -> {
                drawArrow(canvas, sweepPhase = -1f, cx = width * 0.27f, pointingLeft = true,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
                drawArrow(canvas, sweepPhase = -1f, cx = width * 0.73f, pointingLeft = false,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
            }
            State.LEFT -> {
                drawArrow(canvas, sweepPhase, cx = width * 0.27f, pointingLeft = true,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
                drawArrow(canvas, sweepPhase = -1f, cx = width * 0.73f, pointingLeft = false,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
            }
            State.RIGHT -> {
                drawArrow(canvas, sweepPhase = -1f, cx = width * 0.27f, pointingLeft = true,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
                drawArrow(canvas, sweepPhase, cx = width * 0.73f, pointingLeft = false,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
            }
            State.HAZARD -> {
                drawArrow(canvas, sweepPhase, cx = width * 0.27f, pointingLeft = true,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
                drawArrow(canvas, sweepPhase, cx = width * 0.73f, pointingLeft = false,
                    arrowWidth = arrowWidth, arrowSpan = arrowSpan, baseHalf = baseHalf, centerY = centerY)
            }
        }
    }

    /**
     * 绘制一个转向灯箭头 (3 段式)
     *
     * @param canvas 画布
     * @param sweepPhase 扫描进度 0..1; -1 表示静态熄灭 (全部用熄灭色)
     * @param cx 箭头中心 x
     * @param pointingLeft true=指向左, false=指向右
     * @param arrowWidth 箭头可用宽度
     * @param arrowSpan 箭头实际跨度 (尖端到根部的距离)
     * @param baseHalf 根部半高
     * @param centerY 垂直中心
     */
    private fun drawArrow(
        canvas: Canvas,
        sweepPhase: Float,
        cx: Float,
        pointingLeft: Boolean,
        arrowWidth: Float,
        arrowSpan: Float,
        baseHalf: Float,
        centerY: Float,
    ) {
        // 箭头几何: 根部 x 位于内侧 (靠近中心), 尖端 x 位于外侧
        val direction = if (pointingLeft) -1f else 1f
        val tipX = cx + direction * arrowSpan / 2f
        val baseX = cx - direction * arrowSpan / 2f

        val segmentCount = 3
        for (i in 0 until segmentCount) {
            // 段 i 的 x 区间: 根部(i=0) → 尖端(i=2)
            val xInner = baseX + (tipX - baseX) * (i / segmentCount.toFloat())
            val xOuter = baseX + (tipX - baseX) * ((i + 1) / segmentCount.toFloat())

            // 判断该段是否被扫描脉冲点亮
            var lit = false
            if (sweepPhase >= 0f) {
                // 脉冲从根部(i=0)向尖端(i=2)流动: 段起点相位 = i/segmentCount
                val segmentStart = i / segmentCount.toFloat()
                // 扫描时刻 t 点亮 [t - pulseWidth, t] 区间内的段
                val t = sweepPhase + segmentStart
                lit = t % 1f < pulseWidth
            }

            arrowPaint.color = if (lit) activeColor else idleColor

            val path = Path()
            // 段边界处的半高 (尖端处收拢为 0)
            val halfInner = halfExtent(xInner, baseX, tipX, baseHalf)
            val halfOuter = halfExtent(xOuter, baseX, tipX, baseHalf)

            path.moveTo(xInner, centerY - halfInner)
            path.lineTo(xOuter, centerY - halfOuter)
            path.lineTo(xOuter, centerY + halfOuter)
            path.lineTo(xInner, centerY + halfInner)
            path.close()
            canvas.drawPath(path, arrowPaint)
        }
    }

    /**
     * 计算箭头在给定 x 位置处的半高 (从根部 baseHalf 线性收拢到尖端 0)
     */
    private fun halfExtent(x: Float, baseX: Float, tipX: Float, baseHalf: Float): Float {
        val t = abs(x - baseX) / (tipX - baseX).coerceAtLeast(0.0001f)
        return baseHalf * (1f - t).coerceIn(0f, 1f)
    }

    companion object {
        /** 完整扫描周期 (ms) — 与真实转向灯频率 (~80 次/分钟) 一致 */
        private const val SWEEP_CYCLE_MS = 750L
    }
}
