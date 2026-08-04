package com.tesla.dashboard.ui.dashboard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.tesla.dashboard.R

/**
 * 自动驾驶状态 View — 显示 AP/FSDC 状态,带脉冲动画
 *
 * ## 状态定义
 * - [APState.INACTIVE]: 灰色, 无动画
 * - [APState.STANDBY]: 蓝色, 无动画
 * - [APState.ACTIVE]: 绿色, 脉冲动画 (2s 循环)
 * - [APState.WARNING]: 橙色, 快速闪烁 (0.5s 循环)
 *
 * ## 绘制内容
 * - 圆角矩形背景 (状态对应色, alpha 由动画驱动)
 * - 方向盘图标 (白色, 居中)
 * - 标签文字 (白色, 12sp, sans-serif-medium)
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class AutonomousStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** AP 状态枚举 */
    enum class APState(val value: Int) {
        INACTIVE(0),
        STANDBY(1),
        ACTIVE(2),
        WARNING(3);

        companion object {
            fun fromValue(v: Int): APState = entries.find { it.value == v } ?: INACTIVE
        }
    }

    /** 背景画笔 */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 外环画笔 (描边) */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 中心毂画笔 */
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 辐条画笔 */
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 标签画笔 */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** 状态文字画笔 */
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** 当前状态 */
    private var state: APState = APState.INACTIVE

    /** 标签文字 */
    var labelText: String = "AP"

    /** 状态文字 */
    var statusText: String = ""
        private set

    // ===== 颜色 =====
    private var inactiveColor: Int = 0xFF8E8E93.toInt()
    private var standbyColor: Int = 0xFF0A84FF.toInt()
    private var activeColor: Int = 0xFF30D158.toInt()
    private var warningColor: Int = 0xFFFF9F0A.toInt()

    /** 脉冲/闪烁进度 (0f → 1f) */
    private var pulseProgress: Float = 0f

    /** 动画器 */
    private var pulseAnimator: ValueAnimator? = null

    /** 圆角矩形区域 */
    private val bgRect = RectF()

    init {
        context.obtainStyledAttributes(attrs, R.styleable.AutonomousStatusView).apply {
            labelText = getString(R.styleable.AutonomousStatusView_asvLabel) ?: "AP"
            state = APState.fromValue(getInt(R.styleable.AutonomousStatusView_asvState, 0))
            recycle()
        }
    }

    /**
     * 设置状态
     *
     * 根据状态启动/停止脉冲动画:
     * - ACTIVE: 2s 脉冲 (alpha 0.6 → 1.0)
     * - WARNING: 0.5s 快闪 (alpha 0.3 → 1.0)
     * - INACTIVE/STANDBY: 无动画
     */
    fun setState(newState: APState) {
        if (state == newState) return
        state = newState

        pulseAnimator?.cancel()
        pulseProgress = 0f

        when (state) {
            APState.ACTIVE -> {
                pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = PULSE_DURATION_ACTIVE
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        pulseProgress = anim.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            APState.WARNING -> {
                pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = PULSE_DURATION_WARNING
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        pulseProgress = anim.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            else -> {
                // INACTIVE / STANDBY 无动画
            }
        }

        invalidate()
    }

    /** 设置状态文字 */
    fun setStatusText(text: String) {
        statusText = text
        invalidate()
    }

    /**
     * 设置主题颜色
     *
     * @param active ACTIVE 状态色 (通常 accentGreen)
     * @param standby STANDBY 状态色 (通常 accentBlue)
     * @param warning WARNING 状态色 (通常 accentOrange)
     * @param inactive INACTIVE 状态色 (通常 textSecondary)
     */
    fun setColors(active: Int, standby: Int, warning: Int, inactive: Int) {
        activeColor = active
        standbyColor = standby
        warningColor = warning
        inactiveColor = inactive
        invalidate()
    }

    /** 获取当前状态对应的主色 */
    private fun stateColor(): Int = when (state) {
        APState.INACTIVE -> inactiveColor
        APState.STANDBY -> standbyColor
        APState.ACTIVE -> activeColor
        APState.WARNING -> warningColor
    }

    /** 获取当前 alpha (由脉冲动画驱动) */
    private fun currentAlpha(): Float = when (state) {
        APState.ACTIVE -> 0.6f + 0.4f * pulseProgress
        APState.WARNING -> 0.3f + 0.7f * pulseProgress
        else -> 1.0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val color = stateColor()
        val alpha = currentAlpha()

        // 绘制圆角矩形背景
        val bgAlpha = (alpha * 0.15f * 255).toInt().coerceIn(0, 255)
        bgPaint.color = color
        bgPaint.alpha = bgAlpha
        val cornerRadius = dpToPx(14f)
        bgRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

        // 绘制方向盘图标 (居中偏上)
        val iconCenterY = height * 0.4f
        val iconRadius = minOf(width, height) * 0.18f

        // 外环
        ringPaint.color = color
        ringPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = dpToPx(2.5f)
        canvas.drawCircle(cx, iconCenterY, iconRadius, ringPaint)

        // 中心毂
        hubPaint.color = color
        hubPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, iconCenterY, iconRadius * 0.25f, hubPaint)

        // 三辐条
        spokePaint.color = color
        spokePaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        val spokeWidth = dpToPx(2.5f)
        for (angle in intArrayOf(0, 120, 240)) {
            val rad = Math.toRadians(angle.toDouble())
            val startX = cx + (iconRadius * 0.25f * Math.cos(rad)).toFloat()
            val startY = iconCenterY + (iconRadius * 0.25f * Math.sin(rad)).toFloat()
            val endX = cx + (iconRadius * Math.cos(rad)).toFloat()
            val endY = iconCenterY + (iconRadius * Math.sin(rad)).toFloat()
            spokePaint.strokeWidth = spokeWidth
            canvas.drawLine(startX, startY, endX, endY, spokePaint)
        }

        // 绘制标签 (下方)
        labelPaint.textSize = dpToPx(11f)
        labelPaint.color = color
        labelPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawText(labelText, cx, height * 0.78f, labelPaint)

        // 绘制状态文字 (更下方)
        if (statusText.isNotEmpty()) {
            statusPaint.textSize = dpToPx(10f)
            statusPaint.color = 0xFF8E8E93.toInt()
            canvas.drawText(statusText, cx, height * 0.92f, statusPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    companion object {
        private const val PULSE_DURATION_ACTIVE = 2000L
        private const val PULSE_DURATION_WARNING = 500L
    }
}
