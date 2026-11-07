package com.qosed.qodeep

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider

/**
 * 液态玻璃设计系统 —— 统一所有可见 UI 容器（卡片/面板/弹窗）的玻璃质感。
 *
 * 视觉层级（本版）：从「偏白半透明」改为「浅冷灰透明玻璃」——
 *  - 半透明浅冷灰材质，降低白色覆盖，让底层插画透出来
 *  - 顶部自带柔和边缘高光（混入填充渐变），模拟光照入射
 *  - 底部微弱暗色边缘，让气泡从背景中分离、形成清晰卡片层级
 *  - 细白描边（更明显但不刺眼），一眼看出独立气泡容器
 *  - 大圆角
 *
 * 关键背景：寄生环境（QQ 壳）里 backdrop 实时模糊（BlurView）会输出灰块，已验证不可靠，
 * 所以玻璃的「模糊」来自各容器底层的封面小图放大，本类只负责半透明材质 + 描边 +
 * 高光 + 底部暗边这几层确定性的绘制，零渲染依赖、两端画面必然一致。
 */
object LiquidGlass {

    /** 统一圆角（大圆角，液态玻璃的柔和感）。 */
    const val RADIUS_DP = 30f

    /** 统一描边粗细（更明显的细白描边）。 */
    const val STROKE_DP = 1.1f

    /** 薄淡白色半透明填充：上淡白下略透，几乎均匀，避免冷灰偏色与强烈曲面感。 */
    private const val TINT_TOP = 0xC2FFFFFF.toInt()    // 顶部：淡白（约 76%）
    private const val TINT_MID = 0xA8FFFFFF.toInt()    // 中部：薄白（约 66%）
    private const val TINT_BOTTOM = 0x8FFFFFFF.toInt() // 底部：略透白（约 56%）

    /** 细白描边色（明显但柔和，保证边界清晰）。 */
    private const val BORDER_COLOR = 0xA6FFFFFF.toInt()

    /** 底部微弱暗色边缘（分离气泡与背景，极淡避免生硬）。 */
    private const val BOTTOM_EDGE_COLOR = 0x1A000000.toInt()

    /** 底部暗色边缘高度。 */
    private const val EDGE_HEIGHT_DP = 10f

    fun card(context: Context): Drawable =
        GlassDrawable(context.resources.displayMetrics.density)

    /**
     * 应用玻璃到容器 View：
     *  - 设置统一玻璃背景
     *  - 打开 outline 裁剪（圆角真正生效，内部模糊底图也被裁成圆角）
     */
    fun apply(view: View) {
        view.background = card(view.context)
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /**
     * 自绘玻璃 drawable：确定性绘制，不依赖 XML drawable / GPU 渲染。
     * 层级（自底向上）：浅冷灰透明渐变填充 → 底部微弱暗色边缘 → 细白描边。
     */
    private class GlassDrawable(density: Float) : Drawable() {

        private val radius = RADIUS_DP * density
        private val strokeW = STROKE_DP * density
        private val edgeHeight = EDGE_HEIGHT_DP * density

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = BORDER_COLOR
        }
        private val bottomEdge = Paint(Paint.ANTI_ALIAS_FLAG)

        private val rect = RectF()
        private val clip = Path()

        override fun onBoundsChange(bounds: Rect) {
            super.onBoundsChange(bounds)
            val inset = strokeW / 2f
            rect.set(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset
            )
            // 主体填充：上亮下通透的浅冷灰渐变，顶部天然带高光
            fill.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(TINT_TOP, TINT_MID, TINT_BOTTOM),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            // 底部暗色边缘：向下的渐变，让气泡从背景分离
            bottomEdge.shader = LinearGradient(
                0f, rect.bottom - edgeHeight, 0f, rect.bottom,
                intArrayOf(0x00FFFFFF, BOTTOM_EDGE_COLOR),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(rect, radius, radius, fill)

            // 底部暗色边缘裁剪在圆角内
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rect.left, rect.bottom - edgeHeight, rect.right, rect.bottom, bottomEdge)
            canvas.restoreToCount(save)

            canvas.drawRoundRect(rect, radius, radius, border)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
