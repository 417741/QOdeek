package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.api.OnSendMsg
import com.qosed.qodeep.levelboost.FileLog

/**
 * AI 表情标签：
 * 发送「纯表情包」（整条消息只有一个元素，且是图片、无文字）时，
 * 把 picElement.picSubType 从 1 改成 14，让对端识别为 AI 表情。
 *
 * 只在单元素时生效：多个元素说明用户还夹带了文字等内容，改写会让气泡渲染错乱。
 */
object EmotionAiTag {

    private const val TAG = "EmotionAiTag"

    /** 普通表情包的子类型 */
    private const val SUB_TYPE_NORMAL = 1

    /** AI 表情的子类型 */
    private const val SUB_TYPE_AI = 14

    fun install() {
        OnSendMsg.install()
        OnSendMsg.registerListener { _, elements ->
            if (!ChatConfig.getBoolean(ChatKeys.EMOTION_AI_TAG, false)) return@registerListener
            applyAiTag(elements)
        }
        FileLog.i("$TAG 已注册发送监听")
    }

    private fun applyAiTag(elements: MutableList<Any?>) {
        if (elements.size != 1) return
        val element = elements[0] ?: return
        // 有文字等其他内容时不动
        if (OnSendMsg.elementField(element, "textElement") != null) return
        val pic = OnSendMsg.elementField(element, "picElement") ?: return
        val subType = ChatRef.getInt(pic, "picSubType", 0)
        if (subType != SUB_TYPE_NORMAL) return
        if (ChatRef.setField(pic, "picSubType", SUB_TYPE_AI)) {
            FileLog.d("$TAG 已标记为 AI 表情")
        }
    }
}
