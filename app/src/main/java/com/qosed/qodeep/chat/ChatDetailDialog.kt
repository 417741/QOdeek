package com.qosed.qodeep.chat

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.qosed.qodeep.KitPopupHost
import com.qosed.qodeep.UiScale
import com.qosed.qodeep.fixedTextSize
import com.qosed.qodeep.popupCard

/**
 * 聊天功能的二级配置弹窗。
 *
 * 只有带可调参数的功能才会走到这里（图片比例、语音倍速、图片外显等），
 * 纯开关项在列表里就不响应点击。弹窗走自研宿主，避免寄生环境资源错乱。
 */
object ChatDetailDialog {

    private val main = Handler(Looper.getMainLooper())

    fun show(activity: Activity, feature: ChatFeatures.Feature, uiCtx: android.content.Context = activity) {
        val builder = ChatPanelBuilder(uiCtx, { _, _ -> }, {})
        val fields = builder.detailOf(feature.key)
        val host = KitPopupHost(activity, uiCtx)

        val card = popupCard(uiCtx)
        card.addView(TextView(uiCtx).apply {
            text = feature.title
            fixedTextSize(18f)
            setTextColor(0xFF000000.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        if (feature.subtitle.isNotEmpty()) {
            card.addView(TextView(uiCtx).apply {
                text = feature.subtitle
                fixedTextSize(12f)
                setTextColor(0xFF1A1A1A.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(uiCtx, 6), 0, 0)
            })
        }

        if (fields.isNullOrEmpty()) {
            card.addView(TextView(uiCtx).apply {
                text = "该项只需开关，无额外配置。"
                fixedTextSize(14f)
                setTextColor(0xFF222222.toInt())
                setPadding(0, dp(uiCtx, 14), 0, 0)
            })
        } else {
            for (f in fields) card.addView(fieldBlock(uiCtx, f))
        }

        card.addView(TextView(uiCtx).apply {
            text = "关闭"
            fixedTextSize(15f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF0A7AFF.toInt())
            gravity = Gravity.END
            setPadding(dp(uiCtx, 16), dp(uiCtx, 18), dp(uiCtx, 4), dp(uiCtx, 4))
            setOnClickListener { host.dismiss() }
        })

        host.showCenter(card)
    }

    /** 单个配置项：标签 + 说明 + 可选档位行 + 输入框，点档位直接生效，输入框可自定义。 */
    private fun fieldBlock(ctx: android.content.Context, f: ChatPanelBuilder.DetailField): LinearLayout {
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 14), 0, 0)
        }
        wrap.addView(TextView(ctx).apply {
            text = f.label
            fixedTextSize(14f)
            setTextColor(0xFF000000.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        wrap.addView(TextView(ctx).apply {
            text = f.hint
            fixedTextSize(11f)
            setTextColor(0xFF1A1A1A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 6))
        })
        val input = EditText(ctx).apply {
            setText(runCatching { f.initial() }.getOrDefault(""))
            fixedTextSize(14f)
            setSingleLine(true)
            setTextColor(0xFF000000.toInt())
            setHintTextColor(0xFFB0B6BD.toInt())
            if (f.type == "number") inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 10).toFloat()
                setColor(0x66FFFFFF)
                setStroke(dp(ctx, 1), 0x33000000)
            }
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8))
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) runCatching { f.onCommit(input.text.toString().trim()) }
        }
        wrap.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val options = f.options
        if (!options.isNullOrEmpty()) {
            wrap.addView(optionRow(ctx, f, options, input), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) })
        }
        return wrap
    }

    /** 档位行：每档一个圆角块，点击即提交并高亮；选中色为绿色，与弹窗主题一致。 */
    private fun optionRow(
        ctx: android.content.Context,
        f: ChatPanelBuilder.DetailField,
        options: List<String>,
        input: EditText
    ): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = mutableListOf<TextView>()
        val current = input.text.toString()

        fun paint(cell: TextView, selected: Boolean) {
            cell.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(if (selected) 0xFF34C759.toInt() else 0x66FFFFFF)
                setStroke(dp(ctx, 1), 0x33000000)
            }
            cell.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        }

        options.forEachIndexed { idx, opt ->
            val cell = TextView(ctx).apply {
                text = "${opt}×"
                fixedTextSize(14f)
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
                paint(this, current.startsWith(opt))
                setOnClickListener {
                    input.setText(opt)
                    runCatching { f.onCommit(opt) }
                    cells.forEach { c -> paint(c, c === this) }
                    input.clearFocus()
                }
            }
            cells.add(cell)
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx < options.lastIndex) marginEnd = dp(ctx, 8)
            })
        }
        return row
    }

    private fun dp(context: android.content.Context, v: Int): Int = UiScale.dp(v)
}
