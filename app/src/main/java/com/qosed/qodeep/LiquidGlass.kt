package com.qosed.qodeep

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator

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

    /** 统一圆角。 */
    const val RADIUS_DP = 14f

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
     * 全局 UI 风格开关：true=冰块玻璃 UI（第二套），false=浅冷灰玻璃 UI（第一套）。
     * 两套都带流光，仅玻璃材质不同。弹窗/卡片据此统一跟随。
     */
    @Volatile
    var iceUi: Boolean = false

    /** 流光开关：默认开启，关闭后所有卡片的边框扫光停止绘制。 */
    @Volatile
    var shimmerOn: Boolean = true

    /**
     * 应用玻璃到容器 View（无流光，统一浅冷灰玻璃，保留给不需要流光的场景）：
     *  - 设置统一玻璃背景
     *  - 打开 outline 裁剪（圆角真正生效，内部模糊底图也被裁成圆角）
     */
    fun apply(view: View) {
        view.background = card(view.context)
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /** 第一套 UI：浅冷灰透明玻璃 + 流光。 */
    fun applyFrosted(view: View) {
        view.background = FrostedCardDrawable(
            view.context.resources.displayMetrics.density
        )
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /** 第二套 UI：轻薄冰块玻璃（切面高光边框 + 内部反光）+ 流光。 */
    fun applyIceFrosted(view: View) {
        view.background = FrostedCardDrawable(
            view.context.resources.displayMetrics.density,
            isIce = true
        )
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /** 跟随当前 iceUi 风格应用玻璃 + 流光（弹窗/容器统一入口）。 */
    fun applyThemed(view: View) {
        if (iceUi) applyIceFrosted(view) else applyFrosted(view)
    }

    /** 把源图 centerCrop 到目标宽高（与 ImageView scaleType=centerCrop 一致）。 */
    fun centerCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (targetW <= 0 || targetH <= 0) return src
        val scale = maxOf(
            targetW.toFloat() / src.width,
            targetH.toFloat() / src.height
        )
        val sw = (src.width * scale).toInt().coerceAtLeast(targetW)
        val sh = (src.height * scale).toInt().coerceAtLeast(targetH)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = (sw - targetW) / 2
        val y = (sh - targetH) / 2
        return Bitmap.createBitmap(scaled, x, y, targetW, targetH)
    }

    /**
     * Stack Blur 高斯模糊（确定性、零渲染依赖，寄生环境同样可靠）。
     * radius 为像素半径，12–24 为轻度到中度磨砂。
     */
    fun blur(src: Bitmap, radius: Int): Bitmap {
        if (radius < 1) return src
        val w = src.width
        val h = src.height
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        stackBlur(pix, w, h, radius)
        bmp.setPixels(pix, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Stack Blur 核心算法（原地处理 pix）。 */
    private fun stackBlur(pix: IntArray, w: Int, h: Int, radius: Int) {
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum = 0
        var gsum = 0
        var bsum = 0
        var x = 0
        var y = 0
        var i = 0
        var p = 0
        var yp = 0
        var yi = 0
        var yw = 0

        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (y in 0 until h) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            for (i in -radius..radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (y == 0) vmin[x] = minOf(x + radius + 1, wm)
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]

                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (x in 0 until w) {
            rinsum = 0; ginsum = 0; binsum = 0
            routsum = 0; goutsum = 0; boutsum = 0
            rsum = 0; gsum = 0; bsum = 0
            yp = -radius * w
            for (i in -radius..radius) {
                yi = maxOf(0, yp) + x
                sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
            }
            yi = x
            stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or
                        (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (x == 0) vmin[y] = minOf(y + r1, hm) * w
                p = x + vmin[y]

                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]

                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]

                yi += w
            }
        }
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

        // 关键：提供圆角 outline，让 clipToOutline 真正按圆角裁剪内部内容
        // （否则 ViewOutlineProvider.BACKGROUND 拿到的是矩形，clipToOutline 失效）。
        override fun getOutline(outline: android.graphics.Outline) {
            outline.setRoundRect(bounds, radius)
        }
    }

    /**
     * 轻薄冰块玻璃卡片：透明通透填充 + 冰块切面高光边框（左上更亮、右下更暗）+
     * 内部反光 + 外层柔和阴影，模拟薄冰折射。刻意压低反光强度，避免厚重夸张。
     */
    private class IceCardDrawable(density: Float) : Drawable() {

        private val radius = RADIUS_DP * density
        private val strokeW = STROKE_DP * density

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val topBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = 0x99FFFFFF.toInt()   // 上：最亮（约 60%）
        }
        private val leftBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = 0x73FFFFFF.toInt()   // 左：次亮（约 45%）
        }
        private val rightBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = 0x26FFFFFF.toInt()   // 右：较暗（约 15%）
        }
        private val bottomBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = 0x1AFFFFFF.toInt()   // 下：最暗（约 10%）
        }
        private val innerGlow = Paint(Paint.ANTI_ALIAS_FLAG)

        private val rect = RectF()
        private val clip = Path()

        override fun onBoundsChange(bounds: Rect) {
            val inset = strokeW / 2f
            rect.set(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset
            )
            // 通透填充：整体约 22% 白，上下略有差异，让背景清晰透出
            fill.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(0x4DFFFFFF.toInt(), 0x38FFFFFF.toInt(), 0x30FFFFFF.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            // 内部反光：顶部向下的淡白光晕，模拟冰面入射光
            innerGlow.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + rect.height() * 0.55f,
                intArrayOf(0x1FFFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.clipPath(clip)

            // 主体通透填充
            canvas.drawRoundRect(rect, radius, radius, fill)
            // 内部反光（裁剪在圆角内）
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.55f, innerGlow)

            // 四边不同透明度的切面高光边框（单独画四段，模拟冰块切面）
            canvas.drawLine(rect.left, rect.top + radius, rect.left, rect.bottom - radius, leftBorder)
            canvas.drawLine(rect.right, rect.top + radius, rect.right, rect.bottom - radius, rightBorder)
            canvas.drawLine(rect.left + radius, rect.top, rect.right - radius, rect.top, topBorder)
            canvas.drawLine(rect.left + radius, rect.bottom, rect.right - radius, rect.bottom, bottomBorder)
            // 圆角处补画四角（用 top/left 亮色过渡）
            canvas.drawArc(RectF(rect.left, rect.top, rect.left + radius * 2, rect.top + radius * 2), 180f, 90f, false, topBorder)
            canvas.drawArc(RectF(rect.right - radius * 2, rect.top, rect.right, rect.top + radius * 2), 270f, 90f, false, topBorder)
            canvas.drawArc(RectF(rect.left, rect.bottom - radius * 2, rect.left + radius * 2, rect.bottom), 90f, 90f, false, bottomBorder)
            canvas.drawArc(RectF(rect.right - radius * 2, rect.bottom - radius * 2, rect.right, rect.bottom), 0f, 90f, false, bottomBorder)

            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radius)
        }
    }

    /**
     * 毛玻璃卡片背景：绘制顺序（自底向上）——
     *  1. 高斯模糊背景（按卡片在窗口中的位置，从 blurredScreen 裁剪对齐，裁成圆角）
     *  2. 半透明玻璃材质（isIce=false 用 GlassDrawable；isIce=true 用 IceCardDrawable 冰块切面）
     * 文字/内容由卡片 View 自身上层绘制，保持清晰锐利。
     */
    private class FrostedCardDrawable(
        density: Float,
        private val isIce: Boolean = false
    ) : Drawable() {

        private val radius = RADIUS_DP * density
        private val clip = Path()
        private val glass: Drawable = if (isIce) IceCardDrawable(density) else GlassDrawable(density)

        // 银色流光：SweepGradient 绕卡片中心旋转，仅沿圆角边框扫过一段高光
        private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density * 3f
        }
        private val shimmerMatrix = Matrix()
        private var shimmerAngle = 0f
        private val shimmerAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3439L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                shimmerAngle = va.animatedValue as Float
                invalidateSelf()
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            glass.setBounds(bounds)
            val rect = RectF(bounds)
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)

            // 流光结构：贪吃蛇效果——蛇头是一段实心最亮（最明显），后面拖短尾快速淡出。
            // 两套流光：浅冷灰玻璃（默认）灰色流光；冰块玻璃（切换后）亮银白流光（更明显）。
            // 注意：SweepGradient 的 positions 必须覆盖 [0,1]（首 0 尾 1），否则接缝处渲染未定义，
            // 会出现「转到某角度就断成黑点」的现象。
            val head = if (isIce) 0xFFFFFFFF.toInt() else 0xFFBFC4CC.toInt()
            val tail = if (isIce) 0x50E8EDF2.toInt() else 0x50BFC4CC
            shimmerPaint.shader = SweepGradient(
                rect.centerX(), rect.centerY(),
                intArrayOf(
                    0x00FFFFFF,   // 0.0  蛇头前方（透明）
                    head,         // 0.70 蛇头开始（最亮）
                    head,         // 0.80 蛇头保持最亮（实心段）
                    tail,         // 0.90 蛇尾中段（淡化）
                    0x00FFFFFF    // 1.0  蛇尾末端（透明）
                ),
                floatArrayOf(0f, 0.70f, 0.80f, 0.90f, 1f)
            )
            if (shimmerOn && !shimmerAnim.isStarted) shimmerAnim.start()
        }

        override fun draw(canvas: Canvas) {
            // 不画模糊背景位图——卡片背景直接透明透出底层动画/封面（会跟着动）。
            // 只在上面叠玻璃材质 + 流光，保持玻璃质感与文字锐利。

            // 1. 半透明玻璃材质 + 描边 + 底部暗边（复用玻璃绘制）
            glass.draw(canvas)

            // 2. 圆角边框银色流光扫光（低透明度，绕边缘循环）；流光开关关闭时跳过
            if (shimmerOn) {
                val rect = RectF(bounds)
                shimmerMatrix.setRotate(shimmerAngle, rect.centerX(), rect.centerY())
                shimmerPaint.shader?.setLocalMatrix(shimmerMatrix)
                canvas.drawRoundRect(rect, radius, radius, shimmerPaint)
            }
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            if (visible && shimmerOn) {
                if (!shimmerAnim.isStarted) shimmerAnim.start()
            } else {
                shimmerAnim.cancel()
            }
            return changed
        }

        override fun setAlpha(alpha: Int) = glass.setAlpha(alpha)
        override fun setColorFilter(colorFilter: ColorFilter?) = glass.setColorFilter(colorFilter)
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radius)
        }
    }
}
