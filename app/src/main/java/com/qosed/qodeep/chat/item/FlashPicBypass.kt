package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog

/**
 * 闪照破解：
 * 闪照的识别位是 MsgRecord.subMsgType 的 8192 位（8194 = 普通子类型 | 闪照位）。
 * 把所有读取 MsgRecord 的方法在调用前把该位清掉，界面就按普通图片渲染，不再阅后即焚。
 *
 * 之所以 hook 全部方法而不是某一个 getter：读取 subMsgType 的入口在多个层（UI、AIO 逻辑、下载），
 * 只要有一处拿到带闪照位的值就会重新进入焚毁流程。
 */
object FlashPicBypass {

    private const val TAG = "FlashPicBypass"

    private const val MSG_RECORD = "com.tencent.qqnt.kernel.nativeinterface.MsgRecord"

    private const val FLASH_SUB_TYPE = 8194
    private const val FLASH_BIT = 8192

    fun install() {
        val cls = ChatRef.classOrNull(MSG_RECORD)
        if (cls == null) {
            FileLog.w("$TAG 找不到 MsgRecord")
            return
        }
        var count = 0
        for (m in cls.declaredMethods) {
            val name = m.name
            if (name in EXCLUDED) continue
            m.isAccessible = true
            if (ChatRef.hookBefore(m) { param ->
                    if (!ChatConfig.getBoolean(ChatKeys.FLASH_PIC_BYPASS, false)) return@hookBefore
                    val obj = param.thisObject ?: return@hookBefore
                    val sub = ChatRef.getInt(obj, "subMsgType", 0)
                    if (sub and FLASH_BIT != 0 || sub == FLASH_SUB_TYPE) {
                        ChatRef.setField(obj, "subMsgType", sub and FLASH_BIT.inv())
                    }
                }
            ) count++
        }
        FileLog.i("$TAG 已 hook MsgRecord $count 个方法")
    }

    private val EXCLUDED = setOf("getClass", "hashCode", "equals", "notify", "notifyAll", "wait", "toString")
}
