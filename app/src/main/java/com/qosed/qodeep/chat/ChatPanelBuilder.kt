package com.qosed.qodeep.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.qosed.qodeep.LiquidGlass
import com.qosed.qodeep.R
import com.qosed.qodeep.UiScale
import com.qosed.qodeep.chat.item.ImageRatioOverride
import com.qosed.qodeep.chat.item.ImageSummary
import com.qosed.qodeep.chat.item.VoiceSpeed

/**
 * 聊天功能设置页的界面构建。
 *
 * 卡片全部代码生成而不是写进 XML：17 项功能若每项一张 XML 卡，
 * 后续增删一项要同时改布局、findViewById、列表三处，容易漏。这里按 ChatFeatures 清单驱动，
 * 加功能只需要往清单里加一行。
 *
 * 视觉沿用现有的浅冷灰玻璃卡：内边距 16dp、标题 15sp、副标题 12sp 冷灰。
 */
class ChatPanelBuilder(
    private val context: Context,
    private val onSwitchChanged: (ChatFeatures.Feature, Boolean) -> Unit,
    private val onDetail: (ChatFeatures.Feature) -> Unit
) {

    private val cards = mutableListOf<View>()

    /** 已生成的卡片（供页面统一切换 UI 风格）。 */
    val glassCards: List<View> get() = cards

    fun build(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for (f in ChatFeatures.ALL) root.addView(featureCard(f))
        return root
    }

    private fun dp(v: Int): Int = UiScale.dp(v)
    private fun dimenPx(id: Int): Int {
        val d = context.resources.displayMetrics.density
        return UiScale.dp(context.resources.getDimension(id) / d).toInt()
    }
    // 返回 UiScale 基准下的 px：getDimension 是 sp*scaledDensity，换算到 UiScale.density
    private fun spDimen(id: Int): Float {
        val sd = context.resources.displayMetrics.scaledDensity
        return context.resources.getDimension(id) * (UiScale.density / sd)
    }

    /** 单张功能卡：标题 + 副标题 + 右侧开关，整卡可点进详情。 */
    private fun featureCard(f: ChatFeatures.Feature): FrameLayout {
        val card = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dimenPx(R.dimen.card_height)
            ).apply { topMargin = dimenPx(R.dimen.card_spacing) }
            isClickable = true
        }
        LiquidGlass.applyFrosted(card)
        cards.add(card)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(16), 0)
        }
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(context).apply {
            text = f.title
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, spDimen(R.dimen.card_title_size))
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(context).apply {
            text = f.subtitle
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, spDimen(R.dimen.card_sub_size))
            setTextColor(0xFF1A1A1A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(texts)

        val slot = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(32))
        }
        row.addView(slot)
        card.addView(row, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val sw = LocalSwitch(context)
        sw.checked = ChatFeatures.isEnabled(f.key)
        sw.onChanged = { on ->
            ChatFeatures.setEnabled(f.key, on)
            onSwitchChanged(f, on)
        }
        slot.addView(sw, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // 详情入口：仅当该项确实有可配置项时才可点，纯开关项点了没有意义
        val hasDetail = runCatching { hasDetailOf(f.key) }.getOrDefault(false)
        if (hasDetail) {
            card.isClickable = true
            texts.setOnClickListener { onDetail(f) }
        } else {
            card.isClickable = false
            texts.isClickable = false
        }
        return card
    }

    /** 该项是否有可配置项。 */
    private fun hasDetailOf(key: String): Boolean = !detailOf(key).isNullOrEmpty()

    /**
     * 二级配置项的描述（哪些功能有可调参数）。
     * 返回 null 表示该项只有开关，没有额外配置。
     */
    fun detailOf(key: String): List<DetailField>? = when (key) {
        ChatKeys.IMAGE_RATIO_OVERRIDE -> listOf(
            DetailField("图片宽度", "px 单位，当前 ${ImageRatioOverride.width()}", "number",
                initial = { ImageRatioOverride.width().toString() },
                onCommit = { v ->
                    v.toIntOrNull()?.let { ImageRatioOverride.setSize(it, ImageRatioOverride.height()) }
                }),
            DetailField("图片高度", "px 单位，当前 ${ImageRatioOverride.height()}", "number",
                initial = { ImageRatioOverride.height().toString() },
                onCommit = { v ->
                    v.toIntOrNull()?.let { ImageRatioOverride.setSize(ImageRatioOverride.width(), it) }
                })
        )
        ChatKeys.REPEAT_MSG -> listOf(
            DetailField("修改复读图标", "选择一张图片作为复读按钮图标（取消选择恢复默认）", "image",
                initial = { "" }, onCommit = { })
        )
        ChatKeys.VOICE_SPEED -> listOf(
            DetailField("播放倍速", "点选档位即可，也可直接输入，支持 0.5 ~ 3.0", "number",
                initial = { VoiceSpeed.currentSpeed().toString() },
                onCommit = { v -> v.toFloatOrNull()?.let { VoiceSpeed.setSpeed(it) } },
                options = listOf("0.5", "1.0", "1.25", "1.5", "2.0"))
        )
        ChatKeys.IMAGE_SUMMARY -> listOf(
            DetailField("自定义文字", "发送图片时，预览文字显示为这段内容", "text",
                initial = { ImageSummary.tips() }, onCommit = { v -> ImageSummary.setTips(v) })
        )
        else -> null
    }

    data class DetailField(
        val label: String,
        val hint: String,
        val type: String,
        val initial: () -> String,
        val onCommit: (String) -> Unit,
        /** 预设档位点选；null 表示纯输入框。 */
        val options: List<String>? = null
    )
}
