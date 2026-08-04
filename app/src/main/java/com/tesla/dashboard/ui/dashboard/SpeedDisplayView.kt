package com.tesla.dashboard.ui.dashboard

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.tesla.dashboard.R

/**
 * 速度数字显示 View — 居中大号数字 + 单位 + READY 状态
 *
 * 从 [SpeedometerView] 中分离文字绘制职责,专注数字排版和动画。
 * 设计为叠加在 SpeedometerView 弧线中心使用。
 *
 * ## 设计规格
 * - 速度数字: sans-serif-thin, textSize = height * 0.55
 * - 单位文字: sans-serif, textSize = height * 0.13
 * - READY 状态: sans-serif-medium, textSize = height * 0.06
 * - 速度变化动画: 300ms, DecelerateInterpolator
 * - 颜色过渡动画: 300ms, ArgbEvaluator
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class SpeedDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 速度数字画笔 (加粗显示) */
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    /** 单位画笔 */
    private val unitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** READY 状态画笔 */
    private val readyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** 最大速度 */
    var maxSpeed: Float = 240f

    /** 当前显示速度 (动画中间值) */
    private var displaySpeed: Float = 0f

    /** 目标速度 */
    private var targetSpeed: Float = 0f

    /** 单位文字 */
    var unitText: String = "km/h"

    /** 是否显示 READY */
    var isReady: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // ===== 目标颜色 =====
    private var _speedTextColor: Int = 0xFFFFFFFF.toInt()
    private var _unitTextColor: Int = 0xFF8E8E93.toInt()
    private var _readyColor: Int = 0xFF30D158.toInt()

    // ===== 当前绘制颜色 =====
    private var currentSpeedTextColor: Int = _speedTextColor
    private var currentUnitTextColor: Int = _unitTextColor
    private var currentReadyColor: Int = _readyColor

    /** 速度动画 */
    private var speedAnimator: ValueAnimator? = null

    /** 颜色动画 */
    private var colorAnimator: ValueAnimator? = null

    /** ArgB 求值器 */
    private val argbEvaluator = ArgbEvaluator()

    init {
        context.obtainStyledAttributes(attrs, R.styleable.SpeedDisplayView).apply {
            maxSpeed = getInt(R.styleable.SpeedDisplayView_sdvMaxSpeed, 240).toFloat()
            targetSpeed = getFloat(R.styleable.SpeedDisplayView_sdvSpeed, 0f)
            displaySpeed = targetSpeed
            unitText = getString(R.styleable.SpeedDisplayView_sdvUnit) ?: "km/h"
            recycle()
        }
    }

    /**
     * 设置当前速度 (带动画)
     *
     * @param speed 速度值 km/h
     * @param animate 是否启用平滑动画
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

    /**
     * 一次性设置全部主题颜色 (带平滑过渡动画)
     *
     * @param text 速度文字颜色
     * @param unit 单位文字颜色
     * @param ready READY 文字颜色
     */
    fun setThemeColors(text: Int, unit: Int, ready: Int) {
        if (_speedTextColor == text && _unitTextColor == unit && _readyColor == ready) return

        val startText = currentSpeedTextColor
        val startUnit = currentUnitTextColor
        val startReady = currentReadyColor

        _speedTextColor = text
        _unitTextColor = unit
        _readyColor = ready

        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COLOR_ANIM_DURATION
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                currentSpeedTextColor = argbEvaluator.evaluate(fraction, startText, text) as Int
                currentUnitTextColor = argbEvaluator.evaluate(fraction, startUnit, unit) as Int
                currentReadyColor = argbEvaluator.evaluate(fraction, startReady, ready) as Int
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        // 居中点: 与原 SpeedometerView 保持 cy = height * 0.55 偏移 (半圆弧中心)
        // 保持该偏移可让从叠加模式迁移到独立 View 时视觉一致 — 数字偏上约 5%
        val cy = height * 0.55f

        // 半径计算: 高度受限时取高度, 宽度受限时取宽度
        // 注意: 居中数字 + 单位 + READY 三行, 整体高度 ≈ radius * 0.7
        // 半径取宽高中较小者的一半再缩 0.9 留出内边距
        val density = resources.displayMetrics.density
        val maxRadiusByHeight = (cy - 6f * density).coerceAtLeast(40f * density)
        val maxRadiusByWidth = (width / 2f - 8f * density).coerceAtLeast(40f * density)
        val radius = minOf(maxRadiusByHeight, maxRadiusByWidth)

        // 速度数字 (加粗, 基于半径而非高度, 确保不会大于圆环)
        speedTextPaint.color = currentSpeedTextColor
        speedTextPaint.textSize = radius * 0.42f
        val speedStr = displaySpeed.toInt().toString()
        val speedY = cy - (speedTextPaint.descent() + speedTextPaint.ascent()) / 2f - radius * 0.05f
        canvas.drawText(speedStr, cx, speedY, speedTextPaint)

        // 单位
        unitTextPaint.color = currentUnitTextColor
        unitTextPaint.textSize = radius * 0.1f
        val unitY = speedY + radius * 0.28f
        canvas.drawText(unitText, cx, unitY, unitTextPaint)

        // READY 状态
        if (isReady) {
            readyTextPaint.color = currentReadyColor
            readyTextPaint.textSize = radius * 0.07f
            val readyY = unitY + radius * 0.12f
            canvas.drawText("READY", cx, readyY, readyTextPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        speedAnimator?.cancel()
        colorAnimator?.cancel()
    }

    companion object {
        private const val SPEED_ANIM_DURATION = 300L
        private const val COLOR_ANIM_DURATION = 300L
    }
}
