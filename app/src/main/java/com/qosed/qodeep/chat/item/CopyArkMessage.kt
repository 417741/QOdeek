package com.qosed.qodeep.chat.item

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.levelboost.FileLog
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * 复制卡片消息：在卡片消息气泡上方显示「长按复制卡片」按钮，长按复制卡片 JSON。
 *
 * 做法：hook AIOBubbleMsgItemVB.handleUIState，每次气泡 UI 刷新时判断该条消息是否含
 * ArkElement，有则在气泡根 View 上方 addView 一个按钮，长按把 bytesData 写入剪贴板；
 * 没有则移除已挂的按钮。按钮用 View tag 缓存，避免每次刷新都扫整棵子树。
 */
object CopyArkMessage {

    private const val TAG = "CopyArkMessage"
    private const val COPY_BUTTON_ID = 0x7F293284
    private const val AIO_MSG_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"

    /** 按 VB 的 Class 缓存 AIOMsgItem 字段，命中后 O(1)，负结果也缓存。 */
    private val aioMsgItemFieldCache = ConcurrentHashMap<Class<*>, Any>()
    private val FIELD_NOT_FOUND = Any()

    fun install() {
        val vbClass = ChatRef.classOrNull("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
            ?: run {
                FileLog.w("$TAG 找不到 AIOBubbleMsgItemVB")
                return
            }
        val mviUiStateClass = ChatRef.classOrNull("com.tencent.mvi.base.mvi.MviUIState") ?: return
        val m = ChatRef.methodOrNull(vbClass, "handleUIState", mviUiStateClass) ?: run {
            FileLog.w("$TAG 未找到 handleUIState")
            return
        }
        ChatRef.hookAfter(m) { param -> onMsgItemUpdate(param) }
        FileLog.i("$TAG 已 hook handleUIState")
    }

    private fun onMsgItemUpdate(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        try {
            if (!ChatConfig.getBoolean(ChatKeys.COPY_ARK_MESSAGE, false)) return

            val thisObject = param.thisObject
            val itemView = getVBView(thisObject) ?: return
            val rootView = itemView as? ViewGroup ?: return
            val aioMsgItem = findAioMsgItem(thisObject) ?: return
            val msgRecord = ChatRef.invoke(
                aioMsgItem, ChatRef.methodByName(aioMsgItem.javaClass, "getMsgRecord", 0)
            ) ?: return
            val elements = ChatRef.getField(msgRecord, "elements") as? List<Any> ?: return

            // 找 ArkElement 的 bytesData
            var cardData: String? = null
            for (element in elements) {
                val arkElement = runCatching {
                    ChatRef.invoke(element, ChatRef.methodByName(element.javaClass, "getArkElement", 0))
                }.getOrNull()
                if (arkElement != null) {
                    cardData = ChatRef.getString(arkElement, "bytesData")
                    if (cardData != null) break
                }
            }

            val existingCopyBtn = rootView.getTag(COPY_BUTTON_ID) as? View
            if (cardData == null) {
                if (existingCopyBtn != null) {
                    rootView.removeView(existingCopyBtn)
                    rootView.setTag(COPY_BUTTON_ID, null)
                }
                return
            }
            if (existingCopyBtn != null) return

            addCopyButton(rootView, cardData)
        } catch (e: Exception) {
            FileLog.e("$TAG onMsgItemUpdate: ${e.message}")
        }
    }

    private fun getVBView(thisObject: Any): View? {
        try {
            val field = ChatRef.fieldOrNull(thisObject.javaClass, "e")
            if (field != null) {
                val view = field.get(thisObject) as? View
                if (view != null) return view
            }
        } catch (_: Exception) {
        }
        return runCatching {
            ChatRef.invoke(thisObject, ChatRef.methodByName(thisObject.javaClass, "getHostView", 0)) as? View
        }.getOrNull()
    }

    private fun addCopyButton(rootView: ViewGroup, cardData: String) {
        val context = rootView.context
        val textView = TextView(context).apply {
            text = "长按复制卡片"
            textSize = 13f
            setTextColor(0xFF69F0AE.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            id = COPY_BUTTON_ID
            setOnLongClickListener {
                copyToClipboard(context, cardData)
                Toast.makeText(context, "已复制卡片JSON数据", Toast.LENGTH_SHORT).show()
                true
            }
        }
        rootView.addView(textView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        rootView.setTag(COPY_BUTTON_ID, textView)
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("ark_card", text))
    }

    /** 按 Class 缓存 AIOMsgItem 字段，避免每次气泡更新都全字段扫描。 */
    private fun findAioMsgItem(obj: Any): Any? {
        val clazz = obj.javaClass
        val cached = aioMsgItemFieldCache[clazz]
        if (cached === FIELD_NOT_FOUND) return null
        if (cached != null) {
            val f = cached as Field
            return runCatching { f.get(obj) }.getOrNull()
        }
        val found = findFirstFieldOfType(clazz, AIO_MSG_ITEM_CLASS)
        aioMsgItemFieldCache.putIfAbsent(clazz, found ?: FIELD_NOT_FOUND)
        return found?.let { runCatching { it.get(obj) }.getOrNull() }
    }

    /** 沿父类链找第一个「类型可赋值给 targetType」的字段。 */
    private fun findFirstFieldOfType(clazz: Class<*>, typeName: String): Field? {
        val targetType = ChatRef.classOrNull(typeName) ?: return null
        var current: Class<*>? = clazz
        while (current != null) {
            for (field in current.declaredFields) {
                if (targetType.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    return field
                }
            }
            current = current.superclass
        }
        return null
    }

    /** 判断一段文本是不是合法卡片 JSON（长按发送用）。 */
    fun isArkJson(text: String): Boolean = runCatching {
        val o = JSONObject(text.trim())
        o.has("app") || o.has("prompt") || o.has("meta")
    }.getOrDefault(false)
}
