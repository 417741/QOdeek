package com.qosed.qodeep.chat.item

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.chat.qq.MsgSender
import com.qosed.qodeep.levelboost.FileLog

/**
 * 长按发送按钮发卡片：
 * 长按输入框旁的发送按钮，把输入框内已填入的卡片 JSON 作为卡片消息发到当前会话。
 *
 * 会话信息通过 hook AIODelegate.show/hide 维护，与输入区 binder 的 bindViewAndData
 * 双策略一起找到发送按钮并挂长按。
 */
object LongClickSendCard {

    private const val TAG = "LongClickSendCard"
    private const val AIO_DELEGATE = "com.tencent.qqnt.aio.activity.AIODelegate"
    private const val LONG_CLICK_TAG_ID = 0x7F293285

    private val CONTACT_RE = Regex("(\\w+)=([^,)]*)")

    /** 当前会话，由 AIODelegate.show / hide 维护。 */
    @Volatile private var currentChatType = 0
    @Volatile private var currentPeerUid = ""

    fun install() {
        hookAIODelegate()
        hookInputBinders()
    }

    // ---- 策略 1：hook AIODelegate.show/hide 维护当前会话 ----

    private fun hookAIODelegate() {
        val cls = ChatRef.classOrNull(AIO_DELEGATE) ?: run {
            FileLog.w("$TAG 找不到 AIODelegate")
            return
        }
        hookAllAfter(cls, "show") { param ->
            val contact = parseContact(param.thisObject)
            if (contact != null) {
                currentChatType = contact.first
                currentPeerUid = contact.second
                HostEnv.activity()?.let { scanAndSetup(it) }
            }
        }
        hookAllAfter(cls, "hide") {
            currentChatType = 0
            currentPeerUid = ""
        }
        FileLog.i("$TAG 已 hook AIODelegate.show/hide")
    }

    // ---- 策略 2：hook 输入区 binder 的 bindViewAndData ----

    private fun hookInputBinders() {
        val candidates = listOf(
            "com.tencent.mobileqq.aio.input.AIODefaultInputViewBinder",
            "com.tencent.mobileqq.aio.input.sendmsg.AIOSendMsgVBDelegate",
            "com.tencent.guild.aio.input.realinput.GuildAioDefaultInputViewBinder"
        )
        for (name in candidates) {
            val cls = ChatRef.classOrNull(name) ?: continue
            var c: Class<*>? = cls
            while (c != null) {
                for (m in c.declaredMethods) {
                    if (m.name != "bindViewAndData") continue
                    ChatRef.hookAfter(m) { param ->
                        runCatching { setupFromThisObject(param.thisObject) }
                    }
                }
                c = c.superclass
            }
        }
        FileLog.i("$TAG 已 hook 输入区 binder")
    }

    private fun hookAllAfter(cls: Class<*>?, name: String, cb: (de.robv.android.xposed.XC_MethodHook.MethodHookParam) -> Unit) {
        if (cls == null) return
        var c: Class<*>? = cls
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name == name) ChatRef.hookAfter(m, cb)
            }
            c = c.superclass
        }
    }

    // ---- 会话信息解析 ----

    private fun parseContact(delegate: Any?): Pair<Int, String>? {
        if (delegate == null) return null
        val contact = ChatRef.getField(delegate, "aioContact")
            ?: ChatRef.invoke(delegate, ChatRef.methodByName(delegate.javaClass, "getAIOContact", 0))
            ?: ChatRef.invoke(delegate, ChatRef.methodByName(delegate.javaClass, "getAioContact", 0))
            ?: return null

        val type = ChatRef.getInt(contact, "chatType", 0)
        val uid = ChatRef.getString(contact, "peerUid").orEmpty()
        if (type != 0 && uid.isNotEmpty()) return type to uid
        return parseFromString(contact.toString())
    }

    private fun parseFromString(s: String): Pair<Int, String>? {
        val map = mutableMapOf<String, String>()
        for (m in CONTACT_RE.findAll(s)) {
            map[m.groupValues[1]] = m.groupValues[2].trim('\'')
        }
        val type = map["chatType"]?.toIntOrNull() ?: 0
        val uid = map["peerUid"] ?: ""
        if (type == 0 || uid.isEmpty()) return null
        return type to uid
    }

    // ---- 查找输入框与发送按钮 ----

    private fun scanAndSetup(activity: Activity) {
        val decorView = activity.window?.decorView as? ViewGroup ?: return
        val delays = longArrayOf(300, 800, 1500, 2500)
        for (delay in delays) {
            decorView.postDelayed({
                try {
                    val root = activity.window?.decorView as? ViewGroup ?: return@postDelayed
                    val pair = findInputAndSendButton(root) ?: return@postDelayed
                    setupLongClick(pair.second, pair.first)
                } catch (_: Exception) {
                }
            }, delay)
        }
    }

    private fun setupFromThisObject(obj: Any) {
        var editText: EditText? = null
        var sendBtn: View? = null

        // 固定字段路径：e.e = EditText，e.l = 发送按钮
        try {
            val e = ChatRef.getField(obj, "e")
            if (e != null) {
                editText = ChatRef.getField(e, "e") as? EditText
                for (fieldName in arrayOf("l", "f", "g", "h", "i", "j", "k", "m", "n")) {
                    val candidate = ChatRef.getField(e, fieldName) as? View
                    if (candidate != null && candidate !== editText && candidate.isClickable) {
                        sendBtn = candidate
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 遍历字段找
        if (editText == null || sendBtn == null) {
            var cls: Class<*>? = obj.javaClass
            while (cls != null) {
                for (f in cls.declaredFields) {
                    if (f.type.isPrimitive) continue
                    f.isAccessible = true
                    val v = try { f.get(obj) } catch (_: Exception) { null } ?: continue
                    if (v is EditText && editText == null) editText = v
                    if (v is View && v !== editText && sendBtn == null && v.isClickable && v !is EditText) {
                        sendBtn = v
                    }
                }
                cls = cls.superclass
            }
        }

        // 从 root view 查找
        if (editText == null || sendBtn == null) {
            try {
                val root = (ChatRef.invoke(obj, ChatRef.methodByName(obj.javaClass, "getRoot", 0)) as? ViewGroup)
                    ?: (ChatRef.invoke(obj, ChatRef.methodByName(obj.javaClass, "getView", 0)) as? ViewGroup)
                    ?: (ChatRef.invoke(obj, ChatRef.methodByName(obj.javaClass, "getHostView", 0)) as? ViewGroup)
                if (root != null) {
                    val pair = findInputAndSendButton(root)
                    if (pair != null) {
                        editText = pair.first
                        sendBtn = pair.second
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (editText != null && sendBtn != null) {
            setupLongClick(sendBtn!!, editText!!)
        }
    }

    private fun findInputAndSendButton(root: ViewGroup): Pair<EditText, View>? {
        val editTexts = mutableListOf<EditText>()
        collectEditTexts(root, editTexts)
        val editText = editTexts.firstOrNull() ?: return null
        val sendBtn = findSendButtonNearEditText(root, editText) ?: return null
        return Pair(editText, sendBtn)
    }

    private fun collectEditTexts(root: ViewGroup, out: MutableList<EditText>) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is EditText && child.isShown && child.isEnabled && child.height > 0) {
                out.add(child)
            }
            if (child is ViewGroup) {
                collectEditTexts(child, out)
            }
        }
    }

    private fun findSendButtonNearEditText(root: ViewGroup, editText: EditText): View? {
        var parent = editText.parent as? ViewGroup ?: return null
        for (depth in 0 until 4) {
            for (i in 0 until parent.childCount) {
                val sibling = parent.getChildAt(i)
                if (sibling !== editText && sibling is View && sibling.isClickable
                    && sibling.isShown && sibling !is EditText) {
                    return sibling
                }
            }
            parent = parent.parent as? ViewGroup ?: return null
        }
        return null
    }

    private fun setupLongClick(sendBtn: View, editText: EditText) {
        if (sendBtn.getTag(LONG_CLICK_TAG_ID) != null) return
        sendBtn.setTag(LONG_CLICK_TAG_ID, true)

        sendBtn.setOnLongClickListener {
            if (!ChatConfig.getBoolean(ChatKeys.LONG_CLICK_SEND_CARD, false)) return@setOnLongClickListener false
            try {
                val text = editText.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setOnLongClickListener false
                if (!CopyArkMessage.isArkJson(text)) {
                    toast("卡片格式错误，请输入有效 JSON")
                    return@setOnLongClickListener false
                }
                val chatType = currentChatType
                val peerUid = currentPeerUid
                if (peerUid.isEmpty() || chatType == 0) {
                    toast("无法获取当前会话信息")
                    return@setOnLongClickListener false
                }
                val ok = MsgSender.sendArk(chatType, peerUid, text)
                if (ok) {
                    editText.setText("")
                    toast("卡片已发送")
                } else {
                    toast("发送失败，详见日志")
                }
                true
            } catch (e: Exception) {
                FileLog.e("$TAG 发送卡片异常: ${e.message}")
                toast("发送失败")
                false
            }
        }
    }

    private fun toast(s: String) {
        runCatching { Toast.makeText(HostEnv.appContext(), s, Toast.LENGTH_SHORT).show() }
    }
}
