package com.qosed.qodeep

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import com.qosed.qodeep.liquidglass.Backdrop
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

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
 * 关键背景：寄生环境（QQ 壳）里 BlurView 那种离屏快照会输出灰块，已验证不可靠。
 * 因此第一、二套只做半透明材质 + 描边 + 高光 + 底部暗边这类确定性绘制，
 * 底层模糊来自封面小图放大；第三套改用显示列表捕获 + RenderEffect 的真实折射，
 * 只在硬件加速且 Android 12+ 时启用，任一步失败都整体回退到确定性绘制。
 */
object LiquidGlass {

    /** 统一圆角。 */
    const val RADIUS_DP = 14f

    /** 统一描边粗细（更明显的细白描边）。 */
    const val STROKE_DP = 1.1f

    /** 真实液态玻璃：透镜弯折带宽度、模糊半径、饱和度，与主页底栏保持同一组参数。 */
    private const val REFRACTION_DP = 24f
    private const val BLUR_DP = 4f
    private const val SATURATION = 1.5f

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
     * 保留给旧调用点：true 表示第二套（冰块）。新代码统一看 uiMode。
     */
    @Volatile
    var iceUi: Boolean = false

    /** 当前 UI 档位：0=浅冷灰，1=冰块，2=液态玻璃，3=液滴玻璃。 */
    @Volatile
    var uiMode: Int = 0

    /** 流光开关：默认开启，关闭后所有卡片的边框扫光停止绘制。 */
    @Volatile
    var shimmerOn: Boolean = true

    /**
     * 第三套真实折射的取样源：设置页里独立于卡片列的背景层。
     * 只存弱引用，Activity 销毁后不会留住整棵 View 树；宿主原生列表条目不设置它。
     */
    @Volatile
    var backdrop: WeakReference<ViewGroup>? = null

    fun setBackdrop(layer: ViewGroup?) {
        backdrop = layer?.let { WeakReference(it) }
    }

    /** 圆角 SDF 透镜：中心直通，只有边缘一圈按距离弯折取样坐标。 */
    private const val LENS_AGSL = """
        uniform shader content;
        uniform float2 size;
        uniform float2 offset;
        uniform float4 cornerRadii;
        uniform float refractionHeight;
        uniform float refractionAmount;
        uniform float depthEffect;
        float radiusAt(float2 coord, float4 radii) {
            if (coord.x >= 0.0) {
                if (coord.y <= 0.0) return radii.y; else return radii.z;
            } else {
                if (coord.y <= 0.0) return radii.x; else return radii.w;
            }
        }
        float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            float outside = length(max(cornerCoord, 0.0)) - radius;
            float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
            return outside + inside;
        }
        float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
            float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
            if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                return sign(coord) * normalize(max(cornerCoord, 0.0));
            } else {
                float gradX = step(cornerCoord.y, cornerCoord.x);
                return sign(coord) * float2(gradX, 1.0 - gradX);
            }
        }
        float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }
        half4 main(float2 coord) {
            float2 halfSize = size * 0.5;
            float2 centeredCoord = (coord + offset) - halfSize;
            float radius = radiusAt(coord, cornerRadii);
            float sd = sdRoundedRect(centeredCoord, halfSize, radius);
            if (-sd >= refractionHeight) { return content.eval(coord); }
            sd = min(sd, 0.0);
            float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
            float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
            float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize,
                    gradRadius) + depthEffect * normalize(centeredCoord));
            float2 refractedCoord = coord + d * grad;
            return content.eval(refractedCoord);
        }
    """

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

    /** 第一套 UI：浅冷灰透明玻璃 + 蓝色流光。 */
    fun applyFrosted(view: View) = applyStyle(view, 0)

    /** 第二套 UI：轻薄冰块玻璃（切面高光边框 + 内部反光）+ 亮银白流光。 */
    fun applyIceFrosted(view: View) = applyStyle(view, 1)

    /** 第三套 UI：真实液态玻璃（背后画面模糊 + 边缘折射）+ 粉色流光。 */
    fun applyLiquidFrosted(view: View) = applyStyle(view, 2)

    /** 第四套 UI：主页拖动功能时弹出的液滴玻璃（白色磨砂 + 内阴影 + 色散流光）。 */
    fun applyDropletFrosted(view: View) = applyStyle(view, 3)

    /**
     * owner 非空且满足运行条件时，第三套启用真实折射；
     * 弹窗与宿主原生条目传空，保持零渲染依赖的确定性绘制。
     */
    private fun applyStyle(view: View, mode: Int, owner: View? = view) {
        view.background = FrostedCardDrawable(
            view.context.resources.displayMetrics.density, mode, true, owner
        )
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /**
     * 跟随当前档位应用玻璃 + 流光（主页卡片/容器统一入口）。
     * 传入 owner：第三套在硬件加速 + Android 12+ 且 backdrop 就绪时，
     * 主页卡片用真实模糊/折射；条件不满足自动回退确定性绘制。
     */
    fun applyThemed(view: View) = applyStyle(view, uiMode, view)

    /**
     * 只取当前档位的玻璃 drawable，不改 View 的 outline 标志。
     * 用于宿主原生列表条目：shimmer=false 时完全不启动动画，避免列表复用时泄漏。
     */
    fun themedDrawable(context: Context, shimmer: Boolean = true, expandPx: Float = 0f): Drawable =
        FrostedCardDrawable(context.resources.displayMetrics.density, uiMode, shimmer, null, expandPx)

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
     * 第三套的静态近似 / 降级回退：确定性绘制「边缘弯折 + 通透 + 入射高光」。
     * 不依赖实时模糊或 AGSL，用于宿主原生列表条目、弹窗，以及真实玻璃不可用的场景。
     * 层级：通透填充 → 顶部液滴状高光 → 边缘透镜亮带 → 细白描边 → 底部暗边。
     */
    private class LiquidCardDrawable(density: Float) : Drawable() {

        private val radius = RADIUS_DP * density
        private val strokeW = STROKE_DP * density
        private val lensW = 3f * density
        private val edgeHeight = EDGE_HEIGHT_DP * density

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lensW
            color = 0x66FFFFFF
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = BORDER_COLOR
        }
        private val droplet = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bottomEdge = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val clip = Path()

        override fun onBoundsChange(bounds: Rect) {
            val inset = strokeW / 2f
            rect.set(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset
            )
            // 比前两套更通透：让底层画面透出来，上下略亮模拟边缘聚光
            fill.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(0x3DFFFFFF, 0x24FFFFFF, 0x30FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            // 顶部液滴状入射高光
            droplet.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + rect.height() * 0.42f,
                intArrayOf(0x59FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
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
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.42f, droplet)
            canvas.drawRect(rect.left, rect.bottom - edgeHeight, rect.right, rect.bottom, bottomEdge)
            canvas.restoreToCount(save)

            // 透镜折射亮带：沿圆角内侧一圈柔光，模拟边缘把背后内容弯折
            val half = lensW / 2f
            val inner = RectF(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half)
            val innerRadius = (radius - half).coerceAtLeast(0f)
            canvas.drawRoundRect(inner, innerRadius, innerRadius, lens)
            canvas.drawRoundRect(rect, radius, radius, border)
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
     * 第三套真实液态玻璃：把卡片背后的画面录进 RenderNode，
     * 依次经过饱和度提升 → 高斯模糊 → 圆角 SDF 透镜折射，再叠表面薄雾与边缘高光。
     * 与主页液态玻璃底栏共用同一套算法和参数。
     *
     * 只在硬件加速 + Android 12 以上实例化；透镜着色器需 Android 13，
     * 12 上退化为「模糊磨砂 + 直边」。任何一步抛错或取样源失效，
     * 整张卡立即回退到确定性绘制，不会把灰块留在寄生 Activity 里。
     */
    @SuppressLint("NewApi")
    private class TrueLiquidCardDrawable(
        owner: View,
        private val density: Float
    ) : Drawable() {

        private val ownerRef = WeakReference(owner)
        private val radius = RADIUS_DP * density
        private val strokeW = STROKE_DP * density
        private val lensW = 3f * density

        private val fallback = LiquidCardDrawable(density)
        private val rect = RectF()
        private val clip = Path()

        private val node = RenderNode("qodeepGlass")
        private val saturate = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
        )
        private var lens: RuntimeShader? = null
        private var chain: RenderEffect? = null
        private var chainW = 0
        private var chainH = 0

        private val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x4DF2F2F7.toInt()
        }
        private val innerLens = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lensW
            color = 0x66FFFFFF
        }
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = BORDER_COLOR
        }

        private val loc = IntArray(2)
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        private var lastW = 0
        private var lastH = 0
        private var captured = false
        private var supported = true

        init {
            if (Build.VERSION.SDK_INT >= 33) {
                lens = runCatching { RuntimeShader(LENS_AGSL) }.getOrNull()
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            fallback.setBounds(bounds)
            rect.set(bounds)
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            val view = ownerRef.get()
            val backdrop = LiquidGlass.backdrop?.get()
            if (!supported || !canvas.isHardwareAccelerated || view == null ||
                backdrop == null || backdrop.width <= 0 || !view.isShown
            ) {
                fallback.draw(canvas)
                return
            }
            val w = bounds.width()
            val h = bounds.height()
            if (w <= 0 || h <= 0) {
                fallback.draw(canvas)
                return
            }
            try {
                drawGlass(canvas, view, backdrop, w, h)
                canvas.drawRoundRect(rect, radius, radius, surface)
                val half = strokeW / 2f
                canvas.drawRoundRect(
                    rect.left + half, rect.top + half,
                    rect.right - half, rect.bottom - half,
                    (radius - half).coerceAtLeast(0f), (radius - half).coerceAtLeast(0f),
                    border
                )
                val innerHalf = lensW / 2f
                canvas.drawRoundRect(
                    rect.left + innerHalf, rect.top + innerHalf,
                    rect.right - innerHalf, rect.bottom - innerHalf,
                    (radius - innerHalf).coerceAtLeast(0f),
                    (radius - innerHalf).coerceAtLeast(0f),
                    innerLens
                )
            } catch (t: Throwable) {
                supported = false
                fallback.draw(canvas)
            }
        }

        private fun drawGlass(canvas: Canvas, view: View, backdrop: ViewGroup, w: Int, h: Int) {
            // 不再外扩采样：录制区尺寸与卡片一致（w×h），采样原点即卡片屏幕位置。
            // 这样 shader 的 size=(w,h) 与 content（node 的 w×h）尺寸完全对齐，
            // 折射不会错位、不会放大；且采样区永在屏幕内，贴边也不会越界露灰底。
            // 代价仅是边缘折射采不到「卡片外 24dp」的内容，透镜扭曲略减，但稳定性彻底解决。
            val nw = w
            val nh = h
            view.getLocationOnScreen(loc)
            val x = loc[0]
            val y = loc[1]
            // 流光开着时卡片本来就每帧重绘，顺带刷新折射内容；
            // 关掉流光后静止画面不重录，避免显示列表持续损坏引发无休止的重绘。
            val needCapture = !captured || x != lastX || y != lastY ||
                    nw != lastW || nh != lastH || backdrop.isDirty || LiquidGlass.shimmerOn
            if (needCapture) {
                node.setPosition(0, 0, nw, nh)
                val rc = node.beginRecording(nw, nh)
                try {
                    Backdrop.draw(rc, backdrop, false, x, y, nw, nh)
                } finally {
                    node.endRecording()
                }
                lastX = x
                lastY = y
                lastW = nw
                lastH = nh
                captured = true
            }
            if (chain == null || chainW != w || chainH != h) {
                val blur = BLUR_DP * density
                val frost = RenderEffect.createBlurEffect(
                    blur, blur, saturate, Shader.TileMode.CLAMP
                )
                val shader = lens
                chain = if (shader != null) {
                    shader.setFloatUniform("size", w.toFloat(), h.toFloat())
                    shader.setFloatUniform("offset", 0f, 0f)
                    shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                    shader.setFloatUniform("refractionHeight", REFRACTION_DP * density)
                    shader.setFloatUniform("refractionAmount", -REFRACTION_DP * density)
                    shader.setFloatUniform("depthEffect", 0f)
                    RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"), frost
                    )
                } else {
                    frost
                }
                chainW = w
                chainH = h
            }
            node.setRenderEffect(chain!!)
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawRenderNode(node)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) = fallback.setAlpha(alpha)
        override fun setColorFilter(colorFilter: ColorFilter?) = fallback.setColorFilter(colorFilter)
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getOutline(outline: Outline) = fallback.getOutline(outline)
    }

    /**
     * 卡片背景合成层：四档玻璃材质（第三套可是真实折射，第四套是液滴玻璃）
     * 再叠圆角边框流光。文字/内容由卡片 View 自身上层绘制，保持清晰锐利。
     */
    private class FrostedCardDrawable(
        private val density: Float,
        private val mode: Int = 0,
        private val shimmerEnabled: Boolean = true,
        owner: View? = null,
        /** 宿主列表装饰层会在条目 bounds 外多画一圈白底，外扩几像素把它盖住。 */
        private val expandPx: Float = 0f
    ) : Drawable() {

        /** 四档统一 14dp 圆角，第四套只换材质不改形状。 */
        private var cornerRadius = RADIUS_DP * density
        private val clip = Path()
        private val drawRect = RectF()
        private val glass: Drawable = when (mode) {
            1 -> IceCardDrawable(density)
            2 -> if (owner != null && Build.VERSION.SDK_INT >= 31 &&
                LiquidGlass.backdrop?.get() != null
            ) {
                TrueLiquidCardDrawable(owner, density)
            } else {
                LiquidCardDrawable(density)
            }
            3 -> DropletCardDrawable(density)
            else -> GlassDrawable(density)
        }

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
            val rect = RectF(bounds)
            val gr = Rect(bounds)
            if (expandPx != 0f) {
                rect.inset(-expandPx, -expandPx)
                gr.inset(-expandPx.toInt(), -expandPx.toInt())
            }
            drawRect.set(rect)
            glass.setBounds(gr)
            cornerRadius = RADIUS_DP * density
            clip.reset()
            clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)

            // 流光结构：贪吃蛇效果——蛇头是一段实心最亮（最明显），后面拖短尾快速淡出。
            // 蓝 / 亮银白 / 粉 / 色散彩虹（第四套对应液滴拖拽时的色差）。
            // 注意：SweepGradient 的 positions 必须覆盖 [0,1]（首 0 尾 1），否则接缝处渲染未定义，
            // 会出现「转到某角度就断成黑点」的现象。
            val colors = when (mode) {
                1 -> intArrayOf(
                    0x00FFFFFF, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
                    0x50E8EDF2.toInt(), 0x00FFFFFF, 0x00FFFFFF
                )
                2 -> intArrayOf(
                    0x00FFFFFF, 0xFFFF7EB6.toInt(), 0xFFFF7EB6.toInt(),
                    0x50FF7EB6.toInt(), 0x00FFFFFF, 0x00FFFFFF
                )
                3 -> intArrayOf(
                    0x00FFFFFF, 0xFF7EC8FF.toInt(), 0xFFB39DFF.toInt(),
                    0xFFFF9EC4.toInt(), 0x00FFFFFF, 0x00FFFFFF
                )
                else -> intArrayOf(
                    0x00FFFFFF, 0xFF4A90E2.toInt(), 0xFF4A90E2.toInt(),
                    0x504A90E2, 0x00FFFFFF, 0x00FFFFFF
                )
            }
            shimmerPaint.shader = SweepGradient(
                rect.centerX(), rect.centerY(),
                colors,
                floatArrayOf(0f, 0.60f, 0.66f, 0.685f, 0.71f, 1f)
            )
            if (shimmerEnabled && shimmerOn && !shimmerAnim.isStarted) shimmerAnim.start()
        }

        override fun draw(canvas: Canvas) {
            // 不画模糊背景位图——卡片背景直接透明透出底层动画/封面（会跟着动）。
            // 只在上面叠玻璃材质 + 流光，保持玻璃质感与文字锐利。
            glass.draw(canvas)

            if (shimmerEnabled && shimmerOn) {
                val rect = drawRect
                shimmerMatrix.setRotate(shimmerAngle, rect.centerX(), rect.centerY())
                shimmerPaint.shader?.setLocalMatrix(shimmerMatrix)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, shimmerPaint)
            }
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            if (visible && shimmerEnabled && shimmerOn) {
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
            outline.setRoundRect(bounds, cornerRadius)
        }
    }

    /**
     * 第四套：主页拖动功能图标时弹出的液滴玻璃。
     * 形态取参考实现的静止态 —— 40% 白色磨砂表面、10% 深色 wash、
     * 边缘内阴影与细白高光；圆角与其它三套统一为 14dp，
     * 拖拽时的折射与色散用彩虹流光表达。
     */
    private class DropletCardDrawable(private val density: Float) : Drawable() {

        private val strokeW = STROKE_DP * density
        private val innerW = 8f * density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1A000000
        }
        private val topShadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bottomShadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            color = BORDER_COLOR
        }
        private val bloom = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = 0x4DFFFFFF
        }
        private val rect = RectF()
        private val clip = Path()
        private var radius = 0f

        override fun onBoundsChange(bounds: Rect) {
            val inset = strokeW / 2f
            rect.set(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset
            )
            radius = RADIUS_DP * density
            fill.shader = LinearGradient(
                0f, rect.top, 0f, rect.bottom,
                intArrayOf(
                    0xB8FFFFFF.toInt(), 0x99F2F2F7.toInt(), 0x8CF2F2F7.toInt()
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            topShadow.shader = LinearGradient(
                0f, rect.top, 0f, rect.top + innerW,
                intArrayOf(0x26000000, 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            bottomShadow.shader = LinearGradient(
                0f, rect.bottom - innerW, 0f, rect.bottom,
                intArrayOf(0x00000000, 0x1A000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            clip.reset()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }

        override fun draw(canvas: Canvas) {
            canvas.drawRoundRect(rect, radius, radius, fill)
            canvas.drawRoundRect(rect, radius, radius, wash)

            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + innerW, topShadow)
            canvas.drawRect(rect.left, rect.bottom - innerW, rect.right, rect.bottom, bottomShadow)
            canvas.restoreToCount(save)

            val half = bloom.strokeWidth / 2f
            canvas.drawRoundRect(
                rect.left + half, rect.top + half,
                rect.right - half, rect.bottom - half,
                (radius - half).coerceAtLeast(0f), (radius - half).coerceAtLeast(0f), bloom
            )
            canvas.drawRoundRect(rect, radius, radius, border)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getOutline(outline: Outline) {
            outline.setRoundRect(bounds, radius)
        }
    }
}
