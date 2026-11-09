package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.api.OnSendMsg
import com.qosed.qodeep.chat.qq.MsgSender
import com.qosed.qodeep.levelboost.FileLog

/**
 * 视频转泡泡消息：
 * 拦截所有发送路径，把消息里的普通视频元素原地替换成泡泡视频元素，
 * 对端显示为可点开的泡泡卡片而不是普通视频。
 */
object VideoToBubble {

    private const val TAG = "VideoToBubble"

    @Volatile
    private var registered = false

    fun install() {
        if (registered) return
        registered = true
        OnSendMsg.registerListener { contact, elements ->
            runCatching {
                if (!ChatConfig.getBoolean(ChatKeys.VIDEO_TO_BUBBLE, false)) return@registerListener
                if (contact == null || elements.isEmpty()) return@registerListener
                replaceVideoWithBubble(elements)
            }.onFailure { FileLog.e("$TAG onSend: ${it.message}") }
        }
        FileLog.i("$TAG 已安装（发送前替换视频为泡泡）")
    }

    private fun replaceVideoWithBubble(elements: MutableList<Any?>) {
        var idx = -1
        var videoElement: Any? = null
        var path: String? = null
        for ((i, el) in elements.withIndex()) {
            val ve = ChatRef.getField(el, "videoElement") ?: continue
            val fp = ChatRef.getString(ve, "filePath")
            if (!fp.isNullOrBlank()) {
                idx = i
                videoElement = ve
                path = fp
                break
            }
        }
        if (idx < 0 || videoElement == null || path.isNullOrEmpty()) return

        val bubbleEl = MsgSender.createBubbleVideoElement(path) ?: run {
            FileLog.e("$TAG createBubbleVideoElement 返回 null，放弃替换")
            return
        }
        ChatRef.setField(bubbleEl, "videoElement", videoElement)
        elements.removeAt(idx)
        elements.add(0, bubbleEl)
    }
}
