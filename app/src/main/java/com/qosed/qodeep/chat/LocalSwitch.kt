package com.qosed.qodeep.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.qosed.qodeep.UiScale

/**
 * 聊天功能页用的自绘开关。
 *
 * 页面里的开关类在设置页内部是私有的，跨文件取不到，这里复刻同一套绘制参数
 * （中性冷灰轨道 + 细白描边 + 冷白圆点），保证聊天功能卡与其它卡片的开关观感一致。
 */
class LocalSwitch(context: Context) : View(context) {

    var checked = false
        set(value) {
            field = value
            invalidate()
        }
    var onChanged: ((Boolean) -> Unit)? = null

    private val density = UiScale.density
    private val trackOnTop = 0xFF9AA1A9.toInt()
    private val trackOnBottom = 0xFF7A828C.toInt()
    private val trackOffTop = 0x4D000000.toInt()
    private val trackOffBottom = 0x26000000.toInt()
    private val borderColor = 0x8FFFFFFF.toInt()
    private val thumbTop = 0xFFFFFFFF.toInt()
    private val thumbBottom = 0xE6F2F3F5.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    init {
        setOnClickListener {
            checked = !checked
            onChanged?.invoke(checked)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val trackRadius = h / 2f
        val strokeW = 1.1f * density
        val inset = strokeW / 2f
        trackRect.set(inset, inset, w - inset, h - inset)

        paint.shader = LinearGradient(
            0f, trackRect.top, 0f, trackRect.bottom,
            intArrayOf(
                if (checked) trackOnTop else trackOffTop,
                if (checked) trackOnBottom else trackOffBottom
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.color = borderColor
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, paint)

        paint.style = Paint.Style.FILL
        val d = h * 0.78f
        val dotInset = (h - d) / 2f
        val cx = if (checked) w - dotInset - d / 2f else dotInset + d / 2f
        val cy = h / 2f
        val r = d / 2f
        paint.shader = LinearGradient(
            0f, cy - r, 0f, cy + r,
            intArrayOf(thumbTop, thumbBottom),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }
}
