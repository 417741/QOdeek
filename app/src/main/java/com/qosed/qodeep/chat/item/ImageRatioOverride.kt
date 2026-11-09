package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.api.OnSendMsg
import com.qosed.qodeep.levelboost.FileLog

/**
 * 篡改发送图片比例：
 * 把所有待发送图片元素（MsgElement.picElement）的 picWidth/picHeight 强制改成配置值。
 * 内核按这两个字段决定对端气泡的显示比例，所以改的是「外显尺寸」而非图片本身。
 * 宽高任一 <= 0 视为未配置，直接放行，避免把图片压成 0x0。
 */
object ImageRatioOverride {

    private const val TAG = "ImageRatioOverride"
    private const val KEY_WIDTH = "image_ratio_width"
    private const val KEY_HEIGHT = "image_ratio_height"

    private const val DEF_WIDTH = 0
    private const val DEF_HEIGHT = 0

    fun install() {
        OnSendMsg.install()
        OnSendMsg.registerListener { _, elements ->
            if (!ChatConfig.getBoolean(ChatKeys.IMAGE_RATIO_OVERRIDE, false)) return@registerListener
            applyRatio(elements)
        }
        FileLog.i("$TAG 已注册发送监听")
    }

    private fun applyRatio(elements: MutableList<Any?>) {
        if (elements.isEmpty()) return
        val w = ChatConfig.getInt(KEY_WIDTH, DEF_WIDTH)
        val h = ChatConfig.getInt(KEY_HEIGHT, DEF_HEIGHT)
        if (w <= 0 || h <= 0) return
        for (el in elements) {
            val pic = OnSendMsg.elementField(el, "picElement") ?: continue
            ChatRef.setField(pic, "picWidth", w)
            ChatRef.setField(pic, "picHeight", h)
        }
    }

    fun width(): Int = ChatConfig.getInt(KEY_WIDTH, DEF_WIDTH)
    fun height(): Int = ChatConfig.getInt(KEY_HEIGHT, DEF_HEIGHT)
    fun setSize(w: Int, h: Int) {
        ChatConfig.putInt(KEY_WIDTH, w)
        ChatConfig.putInt(KEY_HEIGHT, h)
    }
}
