package com.qosed.qodeep

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 自研轻量弹窗宿主：弃用 XPopup 的 DialogFragment 弹窗壳。
 *
 * Why: 寄生环境里 XPopup 的库资源（dimen/drawable/布局参数）与宿主资源表冲突，
 * 实测弹窗卡片背景错乱成巨大无圆角灰团（用户截图），独立 App 正常。
 * 做法：直接往 Activity 自己的 decorView 加一层全屏遮罩 + 居中内容，
 * 全部用代码自绘（GradientDrawable 圆角），零库资源依赖，两端必然一致。
 */
class KitPopupHost(val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: FrameLayout? = null
    private var cancelable = true

    /** 当前是否有弹窗在显示（供返回键拦截判断）。 */
    val isShowing: Boolean get() = overlay != null

    /** 当前弹窗是否允许用户关闭（cancelable=false 的进度弹窗返回键不关闭）。 */
    fun dismissable(): Boolean = cancelable

    /**
     * 居中弹出内容（内容自备圆角卡片背景）；cancelable=false 时点遮罩/返回键不关闭。
     * matchWidth=false 时内容按自身宽度包裹（如 confirm 小卡片），四周留出的遮罩可点关闭。
     */
    fun showCenter(content: View, cancelable: Boolean = true, matchWidth: Boolean = true) {
        this.cancelable = cancelable
        main.post {
            val fl = overlay
            val lp = FrameLayout.LayoutParams(
                if (matchWidth) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            if (fl == null) {
                val decor = activity.window.decorView as ViewGroup
                val ov = FrameLayout(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // 遮罩用半透明黑：既能遮住底下宿主背景（QQ 主题可能带灰），又不至于全黑。
                    // 关键：不要让它透明到透出宿主 windowBackground 的灰色。
                    background = GradientDrawable().apply { setColor(0x99000000.toInt()) }
                    setOnClickListener { if (this@KitPopupHost.cancelable) dismiss() }
                }
                decor.addView(ov)
                ov.startAnimation(alpha(0f, 1f))
                overlay = ov
                // 内容点击默认吞掉，避免事件穿透到遮罩导致误关（自绘弹窗不是独立 Window）
                content.setOnClickListener {}
                ov.addView(content, lp)
                // 内容横向留出 24dp 边距
                lp.setMargins(dp(24), 0, dp(24), 0)
                content.startAnimation(alpha(0f, 1f, 1.05f))
            } else {
                // 已有弹窗时替换内容（如进度→完成确认）：同样要吞掉点击，防止穿透到遮罩
                fl.removeAllViews()
                content.setOnClickListener {}
                fl.addView(content, lp)
                lp.setMargins(dp(24), 0, dp(24), 0)
            }
        }
    }

    fun dismiss() {
        main.post {
            val fl = overlay ?: return@post
            overlay = null
            val out = alpha(1f, 0f)
            out.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(a: Animation?) {}
                override fun onAnimationRepeat(a: Animation?) {}
                override fun onAnimationEnd(a: Animation?) {
                    (fl.parent as? ViewGroup)?.removeView(fl)
                }
            })
            fl.startAnimation(out)
        }
    }

    private fun alpha(from: Float, to: Float, scale: Float = 1f): AlphaAnimation =
        AlphaAnimation(from, to).apply { duration = (180 * scale).toLong() }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}

/** 液态玻璃弹窗卡片容器，跟随当前 UI 档位（与其它弹窗/主页卡片一致）。 */
fun popupCard(context: Context, paddingDp: Int = 20): LinearLayout =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        LiquidGlass.applyThemed(this)
        setPadding(dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp), dp(context, paddingDp))
    }

/** 纯代码白底圆角背景：不依赖任何 XML drawable，寄生环境必然正确。 */
fun roundedWhite(context: Context, radiusDp: Int = 30): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * context.resources.displayMetrics.density
        setColor(Color.WHITE)
    }

/** 双按钮确认弹窗（替代 XPopup asConfirm，文案显式传，寄生资源安全）。 */
fun KitPopupHost.confirm(
    title: String,
    message: String,
    cancelText: String = "取消",
    okText: String = "确定",
    cancelable: Boolean = true,
    onOk: (() -> Unit)? = null
) {
    val ctx = activity
    val card = popupCard(ctx)
    val tvTitle = TextView(ctx).apply {
        text = title
        setTextColor(0xFF111111.toInt())
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }
    val tvMsg = TextView(ctx).apply {
        text = message
        setTextColor(0xFF44484D.toInt())
        textSize = 14f
        setPadding(0, dp(ctx, 10), 0, 0)
    }
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(ctx, 20), 0, 0)
    }
    val tvCancel = TextView(ctx).apply {
        text = cancelText
        setTextColor(0xFF6B7280.toInt())
        textSize = 15f
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
        setOnClickListener { dismiss() }
    }
    val tvOk = TextView(ctx).apply {
        text = okText
        setTextColor(0xFF0A7AFF.toInt())
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
        setOnClickListener { dismiss(); onOk?.invoke() }
    }
    row.addView(tvCancel)
    row.addView(tvOk)
    card.addView(tvTitle)
    card.addView(tvMsg)
    card.addView(row)
    // 小卡片按内容宽度包裹，四周遮罩可点关闭（不铺满整条，否则横向空白也被卡片吞掉点击）
    showCenter(card, cancelable, matchWidth = false)
}

private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
