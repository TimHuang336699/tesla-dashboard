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
 * 基于 Tesla 真实转向灯样式的 V 形箭头:
 * - **矢量绘制**: 纯 Canvas V 形箭头 (chevron), 不依赖位图资源
 * - **Tesla 风格顺序扫描动画**: 每个 V 形分 3 段,
 *   亮起脉冲从外侧向内侧依次流动 (与真实转向灯一致)
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
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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

    /** 扫描脉冲在箭头上的宽度比例 */
    private val pulseWidth = 0.35f

    /** 左箭头 V 形 Path (预计算) */
    private val leftArrowPath = Path()

    /** 右箭头 V 形 Path (预计算) */
    private val rightArrowPath = Path()

    /** 描边宽度 */
    private var strokePx: Float = 0f

    /**
     * 设置转向灯状态
     */
    fun setState(state: State) {
        if (this.state == state) return
        this.state = state
        updateAnimation()
    }

    /**
     * 设置主题色
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
            strokePx = h * 0.12f
            arrowPaint.strokeWidth = strokePx
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

        when (state) {
            State.OFF -> {
                drawChevron(canvas, leftArrowPath, sweepPhase = -1f)
                drawChevron(canvas, rightArrowPath, sweepPhase = -1f)
            }
            State.LEFT -> {
                drawChevron(canvas, leftArrowPath, sweepPhase)
                drawChevron(canvas, rightArrowPath, sweepPhase = -1f)
            }
            State.RIGHT -> {
                drawChevron(canvas, leftArrowPath, sweepPhase = -1f)
                drawChevron(canvas, rightArrowPath, sweepPhase)
            }
            State.HAZARD -> {
                drawChevron(canvas, leftArrowPath, sweepPhase)
                drawChevron(canvas, rightArrowPath, sweepPhase)
            }
        }
    }

    /**
     * 构建 V 形 (chevron) 箭头 Path
     *
     * 左 V:  <<  (尖端在左, 开口在右)
     * 右 V:  >>  (尖端在右, 开口在左)
     *
     * 每个 V 由两段线段组成, 形成一个锐角。
     */
    private fun buildArrowPaths(w: Int, h: Int) {
        val centerY = h / 2f
        val vWidth = w * 0.22f     // V 的水平宽度
        val vHeight = h * 0.42f    // V 的垂直半高 (从中心到上下端点)

        // === 左 V: << ===
        // 尖端 (顶点) 在左, 两个端点在右
        val leftTipX = w * 0.12f
        val leftOpenX = leftTipX + vWidth

        leftArrowPath.reset()
        leftArrowPath.moveTo(leftOpenX, centerY - vHeight)  // 右上端点
        leftArrowPath.lineTo(leftTipX, centerY)              // 尖端 (左侧)
        leftArrowPath.lineTo(leftOpenX, centerY + vHeight)  // 右下端点
        leftArrowPath.close()

        // === 右 V: >> (水平镜像) ===
        val rightTipX = w - leftTipX
        val rightOpenX = w - leftOpenX

        rightArrowPath.reset()
        rightArrowPath.moveTo(rightOpenX, centerY - vHeight)  // 左上端点
        rightArrowPath.lineTo(rightTipX, centerY)              // 尖端 (右侧)
        rightArrowPath.lineTo(rightOpenX, centerY + vHeight)  // 左下端点
        rightArrowPath.close()
    }

    /**
     * 绘制 V 形箭头的扫描效果
     *
     * 将 V 形分为 3 段 (上臂、尖端、下臂), 根据 sweepPhase 依次点亮。
     */
    private fun drawChevron(canvas: Canvas, chevronPath: Path, sweepPhase: Float) {
        val bounds = RectF()
        chevronPath.computeBounds(bounds, true)

        val segmentCount = 3
        val segH = bounds.height() / segmentCount

        for (i in 0 until segmentCount) {
            // 段 i 的 y 范围: 从上到下
            val segTop = bounds.top + segH * i
            val segBottom = bounds.top + segH * (i + 1)

            // 判断该段是否被扫描脉冲点亮
            var lit = false
            if (sweepPhase >= 0f) {
                val segmentStart = i / segmentCount.toFloat()
                val t = sweepPhase + segmentStart
                lit = t % 1f < pulseWidth
            }

            arrowPaint.color = if (lit) activeColor else idleColor

            // 用 clipPath 裁剪当前段, 再绘制完整 V 形
            canvas.save()
            val clipPath = Path()
            clipPath.addRect(bounds.left - 1f, segTop, bounds.right + 1f, segBottom, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawPath(chevronPath, arrowPaint)
            canvas.restore()
        }
    }

    companion object {
        /** 完整扫描周期 (ms) — 与真实转向灯频率 (~80 次/分钟) 一致 */
        private const val SWEEP_CYCLE_MS = 750L
    }
}
