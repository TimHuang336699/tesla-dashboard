package com.tesla.dashboard.ui.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 车辆俯视轮廓 View — Dash for Tesla 风格的中央车辆剪影
 *
 * ## 设计特征
 * - 俯视视角的 Tesla 车辆轮廓 (类似 ic_car_tire.png)
 * - 圆角矩形车身 + 4个车轮
 * - 车身使用半透明填充 + 描边
 * - 支持颜色主题切换
 * - 车身分三段着色: 前部(前备箱)、中部(车门)、后部(后备箱)
 *   任一段对应部件未关时显示红色警告
 *
 * ## 绘制层次
 * 1. 车身填充 — 分前/中/后三段, 未关部件段为红色
 * 2. 前后挡风玻璃区域 (更深色)
 * 3. 车身描边
 * 4. 4个车轮 (圆角矩形)
 *
 * @param context 上下文
 * @param attrs XML 属性集
 */
class CarSilhouetteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 车身填充画笔 */
    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 警告色填充画笔 (车门/舱未关) */
    private val warningFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFF453A.toInt()
        alpha = 100
    }

    /** 车身描边画笔 */
    private val bodyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** 玻璃区域画笔 */
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 车轮画笔 */
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** 车轮高亮画笔 (转向灯激活时) */
    private val wheelActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ===== 颜色 =====

    /** 车身颜色 */
    private var bodyColor: Int = 0xFF3A3A5C.toInt()

    /** 描边颜色 */
    private var strokeColor: Int = 0xFF6A6A8C.toInt()

    /** 玻璃颜色 */
    private var glassColor: Int = 0xFF1A1A2E.toInt()

    /** 车轮颜色 */
    private var wheelColor: Int = 0xFF2A2A3E.toInt()

    /** 车轮激活颜色 (转向灯) */
    private var wheelActiveColor: Int = 0xFF30D158.toInt()

    /** 警告颜色 (门/舱未关) */
    private var warningColor: Int = 0xFFFF453A.toInt()

    // ===== 门/舱状态 =====

    /** 前备箱是否打开 */
    private var ftOpen: Boolean = false

    /** 后备箱是否打开 */
    private var rtOpen: Boolean = false

    /** 任一车门是否打开 */
    private var anyDoorOpen: Boolean = false

    // ===== 转向灯 =====

    /** 转向灯状态: null=无, "left"=左转, "right"=右转, "hazard"=双闪 */
    private var turnSignal: String? = null

    /** 闪烁进度 */
    private var blinkProgress: Float = 0f

    init {
        bodyFillPaint.color = bodyColor
        bodyFillPaint.alpha = 60
        bodyStrokePaint.color = strokeColor
        bodyStrokePaint.strokeWidth = dpToPx(2f)
        glassPaint.color = glassColor
        glassPaint.alpha = 120
        wheelPaint.color = wheelColor
        wheelActivePaint.color = wheelActiveColor
    }

    /**
     * 设置主题颜色
     */
    fun setThemeColors(body: Int, stroke: Int, glass: Int, wheel: Int) {
        bodyColor = body
        strokeColor = stroke
        glassColor = glass
        wheelColor = wheel
        bodyFillPaint.color = body
        bodyFillPaint.alpha = 60
        bodyStrokePaint.color = stroke
        glassPaint.color = glass
        glassPaint.alpha = 120
        wheelPaint.color = wheel
        invalidate()
    }

    /**
     * 设置警告色 (门/舱未关时的红色)
     */
    fun setWarningColor(color: Int) {
        warningColor = color
        warningFillPaint.color = color
        warningFillPaint.alpha = 100
        invalidate()
    }

    /**
     * 设置门/舱开关状态
     *
     * @param ft 前备箱是否打开 (null=未知, 按 false 处理)
     * @param rt 后备箱是否打开
     * @param df 驾驶员侧前门
     * @param dr 驾驶员侧后门
     * @param pf 乘客侧前门
     * @param pr 乘客侧后门
     */
    fun setClosureState(
        ft: Boolean?,
        rt: Boolean?,
        df: Boolean?,
        dr: Boolean?,
        pf: Boolean?,
        pr: Boolean?,
    ) {
        ftOpen = ft == true
        rtOpen = rt == true
        anyDoorOpen = df == true || dr == true || pf == true || pr == true
        invalidate()
    }

    /**
     * 设置转向灯状态
     * @param signal null=无, "left"=左转, "right"=右转, "hazard"=双闪
     */
    fun setTurnSignal(signal: String?) {
        turnSignal = signal
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        // 车辆尺寸 (根据 view 大小自适应)
        val carW = minOf(width, height) * 0.32f  // 车宽
        val carL = minOf(width, height) * 0.62f  // 车长

        // 车身圆角矩形 (竖向)
        val bodyRect = RectF(
            cx - carW / 2, cy - carL / 2,
            cx + carW / 2, cy + carL / 2,
        )
        val bodyRadius = carW * 0.22f

        // ===== 1. 绘制车身填充 (分前/中/后三段) =====

        // 前段 (前备箱区域) — 上 1/3
        val frontRect = RectF(
            bodyRect.left, bodyRect.top,
            bodyRect.right, bodyRect.top + carL / 3f,
        )
        // 中段 (车门区域) — 中间 1/3
        val middleRect = RectF(
            bodyRect.left, bodyRect.top + carL / 3f,
            bodyRect.right, bodyRect.top + carL * 2f / 3f,
        )
        // 后段 (后备箱区域) — 下 1/3
        val rearRect = RectF(
            bodyRect.left, bodyRect.top + carL * 2f / 3f,
            bodyRect.right, bodyRect.bottom,
        )

        // 先绘制完整车身底色
        canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, bodyFillPaint)

        // 在未关部件的段上叠加红色警告
        canvas.save()
        canvas.clipRect(bodyRect)

        if (ftOpen) {
            // 前备箱未关 — 前段红色
            canvas.clipRect(frontRect)
            canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, warningFillPaint)
        }
        canvas.restore()

        canvas.save()
        canvas.clipRect(bodyRect)
        if (anyDoorOpen) {
            // 车门未关 — 中段红色
            canvas.clipRect(middleRect, android.graphics.Region.Op.INTERSECT)
            canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, warningFillPaint)
        }
        canvas.restore()

        canvas.save()
        canvas.clipRect(bodyRect)
        if (rtOpen) {
            // 后备箱未关 — 后段红色
            canvas.clipRect(rearRect, android.graphics.Region.Op.INTERSECT)
            canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, warningFillPaint)
        }
        canvas.restore()

        // ===== 2. 绘制玻璃区域 (前后挡风) =====
        val glassW = carW * 0.7f
        val frontGlassH = carL * 0.2f
        val rearGlassH = carL * 0.18f

        // 前挡风玻璃
        val frontGlassRect = RectF(
            cx - glassW / 2, cy - carL * 0.32f,
            cx + glassW / 2, cy - carL * 0.32f + frontGlassH,
        )
        canvas.drawRoundRect(frontGlassRect, dpToPx(4f), dpToPx(4f), glassPaint)

        // 后挡风玻璃
        val rearGlassRect = RectF(
            cx - glassW / 2, cy + carL * 0.14f,
            cx + glassW / 2, cy + carL * 0.14f + rearGlassH,
        )
        canvas.drawRoundRect(rearGlassRect, dpToPx(4f), dpToPx(4f), glassPaint)

        // ===== 3. 绘制车身描边 =====
        canvas.drawRoundRect(bodyRect, bodyRadius, bodyRadius, bodyStrokePaint)

        // ===== 4. 绘制4个车轮 =====
        val wheelW = carW * 0.14f
        val wheelH = carL * 0.16f
        val wheelOffsetX = carW / 2 + dpToPx(1f)
        val wheelOffsetY = carL * 0.28f

        // 车轮位置
        val wheels = listOf(
            RectF(cx - wheelOffsetX - wheelW, cy - wheelOffsetY - wheelH / 2,
                  cx - wheelOffsetX, cy - wheelOffsetY + wheelH / 2),          // 左前
            RectF(cx + wheelOffsetX, cy - wheelOffsetY - wheelH / 2,
                  cx + wheelOffsetX + wheelW, cy - wheelOffsetY + wheelH / 2),  // 右前
            RectF(cx - wheelOffsetX - wheelW, cy + wheelOffsetY - wheelH / 2,
                  cx - wheelOffsetX, cy + wheelOffsetY + wheelH / 2),          // 左后
            RectF(cx + wheelOffsetX, cy + wheelOffsetY - wheelH / 2,
                  cx + wheelOffsetX + wheelW, cy + wheelOffsetY + wheelH / 2),  // 右后
        )

        val wheelRadius = dpToPx(2f)
        wheels.forEachIndexed { index, rect ->
            val isLeft = index == 0 || index == 2
            val isActive = when (turnSignal) {
                "left" -> isLeft
                "right" -> !isLeft
                "hazard" -> true
                else -> false
            }

            if (isActive && blinkProgress > 0.3f) {
                wheelActivePaint.alpha = (blinkProgress * 255).toInt()
                canvas.drawRoundRect(rect, wheelRadius, wheelRadius, wheelActivePaint)
            } else {
                canvas.drawRoundRect(rect, wheelRadius, wheelRadius, wheelPaint)
            }
        }
    }

    /**
     * 更新闪烁进度 (由外部动画驱动)
     */
    fun updateBlinkProgress(progress: Float) {
        blinkProgress = progress
        invalidate()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
