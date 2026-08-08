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
import kotlin.math.abs

/**
 * 速度数字码表 View — 超大数字 + 单位 + READY 状态
 *
 * 赛车风格大数字码表:
 * - 速度数字: Pump 仪表字体 (经典汽车仪表数字), 超大号
 * - 单位文字: km/h, 数字下方
 * - READY 状态: 绿色高亮, 数字上方
 * - 速度变化动画: 300ms, DecelerateInterpolator (数字平滑滚动)
 * - 颜色过渡动画: 300ms, ArgbEvaluator
 *
 * ## 排版
 * READY (顶部, 小)
 *  88     (中部, 超大数字)
 * km/h   (底部, 小)
 *
 * @param context 上下文
 * @param attrs XML 属性集
 * @param defStyleAttr 默认样式
 */
class SpeedDisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 速度数字画笔 (Pump 仪表字体) */
    private val speedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
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

    // ===== 缓存的文本测量数据 (避免 onDraw 中重复计算) =====
    private var cachedSpeedStr: String = "0"
    private var cachedTextSize: Float = 0f
    private var cachedMeasuredWidth: Float = 0f
    private var cachedDensity: Float = 0f
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0

    init {
        context.obtainStyledAttributes(attrs, R.styleable.SpeedDisplayView).apply {
            maxSpeed = getInt(R.styleable.SpeedDisplayView_sdvMaxSpeed, 240).toFloat()
            targetSpeed = getFloat(R.styleable.SpeedDisplayView_sdvSpeed, 0f)
            displaySpeed = targetSpeed
            unitText = getString(R.styleable.SpeedDisplayView_sdvUnit)
                ?: context.getString(R.string.unit_kmh)
            recycle()
        }
        // 速度数字使用 Pump 仪表字体 (回退 sans-serif-medium)
        speedTextPaint.typeface = loadGaugeTypeface()
    }

    private fun loadGaugeTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, "fonts/pump_std_demi_bold.otf")
        } catch (_: Exception) {
            Typeface.create("sans-serif-medium", Typeface.BOLD)
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
        val cy = height / 2f

        val density = resources.displayMetrics.density

        // ===== 1. READY 状态 (顶部) =====
        if (isReady) {
            readyTextPaint.color = currentReadyColor
            readyTextPaint.textSize = 16f * density
            val readyY = cy - 40f * density
            canvas.drawText(context.getString(R.string.ready_label), cx, readyY, readyTextPaint)
        }

        // ===== 2. 超大速度数字 (中部, 核心) =====
        speedTextPaint.color = currentSpeedTextColor
        val speedStr = displaySpeed.toInt().toString()

        // 仅在速度或尺寸变化时重新计算字号和测量
        val needsRecalc = speedStr != cachedSpeedStr ||
            width != cachedWidth ||
            height != cachedHeight ||
            density != cachedDensity

        if (needsRecalc) {
            cachedSpeedStr = speedStr
            cachedWidth = width
            cachedHeight = height
            cachedDensity = density

            // 字号自适应: 高度受限时以高度为基准, 并确保数字撑满宽度
            // 预留底部单位空间 (单位 + 边距), 防止数字过大挤压单位导致显示不全
            val unitHeight = 20f * density + 20f * density
            val maxTextHeight = height - unitHeight - 16f * density
            var textSize = maxTextHeight * 0.62f
            speedTextPaint.textSize = textSize

            // 三位数宽度保护: 数字过宽时按宽度等比缩小字号
            val maxTextWidth = width - 12f * density
            val measured = speedTextPaint.measureText(speedStr)
            if (measured > maxTextWidth) {
                textSize *= maxTextWidth / measured
                speedTextPaint.textSize = textSize
            }
            cachedTextSize = textSize
            cachedMeasuredWidth = speedTextPaint.measureText(speedStr)
        } else {
            speedTextPaint.textSize = cachedTextSize
        }

        val speedMetrics = speedTextPaint.fontMetrics
        val textY = cy - (speedMetrics.ascent + speedMetrics.descent) / 2f + 4f * density
        canvas.drawText(speedStr, cx, textY, speedTextPaint)

        // ===== 3. 单位 (底部, 固定底部对齐, 始终完整显示) =====
        unitTextPaint.color = currentUnitTextColor
        unitTextPaint.textSize = 20f * density
        val unitMetrics = unitTextPaint.fontMetrics
        // 单位基线: 距离 View 底部固定 12dp, 确保不越界裁剪
        val unitY = height - 12f * density - (unitMetrics.descent - unitMetrics.ascent) / 2f
        canvas.drawText(unitText, cx, unitY, unitTextPaint)
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
