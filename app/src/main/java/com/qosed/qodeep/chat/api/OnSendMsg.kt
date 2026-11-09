package com.qosed.qodeep.chat.api

import com.qosed.qodeep.levelboost.FileLog
import com.qosed.qodeep.chat.ChatRef
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

/**
 * 「发送消息」拦截点。
 *
 * 宿主所有消息最终都汇聚到 NT 内核 IKernelMsgService 的 sendMsg 上，
 * 在它之前统一拦截一次，就能覆盖文本、图片、卡片、视频等所有发送路径。
 *
 * 注意：这里必须在 sendMsg 执行「之前」改 elements，因为内核会立刻把列表序列化下发；
 * after 阶段再改已经来不及。
 */
object OnSendMsg {

    private const val TAG = "OnSendMsg"

    private const val CPP_PROXY = "com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService\$CppProxy"

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<SendMsgListener>()

    @Volatile
    private var installed = false

    fun interface SendMsgListener {
        /** @param contact 内核 Contact 对象（反射取值，字段名 chatType/peerUid 等）
         *  @param elements 待发送的 MsgElement 列表，可原地增删改 */
        fun onSend(contact: Any?, elements: MutableList<Any?>)
    }

    fun registerListener(l: SendMsgListener) {
        if (!listeners.contains(l)) listeners.add(l)
        install()
    }

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val cls = ChatRef.classOrNull(CPP_PROXY) ?: run {
                FileLog.w("$TAG 找不到 $CPP_PROXY，发送拦截不可用")
                return
            }
            val sendMsg = cls.declaredMethods.firstOrNull {
                it.name == "sendMsg" && it.parameterTypes.size == 5
            }?.apply { isAccessible = true }
            if (sendMsg == null) {
                FileLog.w("$TAG 未找到 5 参数 sendMsg")
                return
            }
            ChatRef.hookBefore(sendMsg) { param ->
                if (listeners.isEmpty()) return@hookBefore
                dispatch(param)
            }
            FileLog.i("$TAG 发送拦截已安装")
        }
    }

    private fun dispatch(param: XC_MethodHook.MethodHookParam) {
        if (param.args.size < 3) return
        val contact = param.args[1] ?: return
        @Suppress("UNCHECKED_CAST")
        val elements = param.args[2] as? MutableList<Any?> ?: return
        for (l in listeners) {
            runCatching { l.onSend(contact, elements) }
                .onFailure { FileLog.e("$TAG 监听器异常", it) }
        }
    }

    /** 反射取 Contact 的 chatType 与 peerUid（各版本字段名稳定）。 */
    fun chatTypeOf(contact: Any?): Int = ChatRef.getInt(contact, "chatType", 0)

    fun peerUidOf(contact: Any?): String = ChatRef.getString(contact, "peerUid") ?: ""

    /** 反射取元素里某个子元素（picElement/textElement/videoElement...）。 */
    fun elementField(element: Any?, name: String): Any? = ChatRef.getField(element, name)

    /** 元素类型码（MsgElement.elementType）。 */
    fun elementType(element: Any?): Int = ChatRef.getInt(element, "elementType", 0)
}
