package com.qosed.qodeep.chat.item

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.qq.FriendTool
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.levelboost.FileLog
import de.robv.android.xposed.XC_MethodHook
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 防撤回。
 *
 * 两层处理：
 * 1. 推送层改写 protobuf，让撤回指令失效（干净、不影响后续消息）；
 * 2. 视图层留痕，在消息下方补一行「消息已撤回」提示。
 *
 * 为什么改字节而不是反序列化再序列化：内核的 protobuf 类有大量未公开字段，
 * 重新序列化会丢字段、改字段顺序，宿主解析直接失败。这里只做定点字节替换。
 *
 * 注意所有改写都必须在推送线程内联完成，不能丢给线程池后异步等结果——
 * 推送方法返回后内核立刻解析 buffer，异步改已经晚了。
 */
object PreventRecall {

    private const val TAG = "PreventRecall"

    private const val RECALL_PROMPT_VIEW_ID = 0x7F298382

    private const val AIO_MSG_ITEM_CLASS = "com.tencent.mobileqq.aio.msg.AIOMsgItem"

    private const val CMD_INFO_SYNC = "trpc.msg.register_proxy.RegisterProxy.InfoSyncPush"
    private const val CMD_MSG_PUSH = "trpc.msg.olpush.OlPushService.MsgPush"

    /** 私聊撤回 / 群聊撤回 */
    private const val TYPE_C2C_RECALL = 528
    private const val SUBTYPE_C2C_RECALL = 138
    private const val TYPE_GROUP_RECALL = 732
    private const val SUBTYPE_GROUP_RECALL = 17

    private data class RecallMeta(
        val operatorUid: String,
        val chatType: Int,
        val msgSeq: Int,
        val groupUin: String? = null
    ) {
        val key: String
            get() = if (chatType == 2 && groupUin != null) "${groupUin}_$msgSeq" else "${operatorUid}_$msgSeq"
    }

    private val recallMetaMap = ConcurrentHashMap<String, RecallMeta>()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentAdapter: WeakReference<Any>? = null

    private val aioMsgItemFieldCache = ConcurrentHashMap<Class<*>, Any>()

    private val FIELD_NOT_FOUND = Any()

    private fun enabled(): Boolean = ChatConfig.getBoolean(ChatKeys.PREVENT_RECALL, false)

    fun install() {
        hookPush()
        hookAioView()
        FileLog.i("$TAG 已安装")
    }

    // ---------------- 推送层 ----------------

    private fun hookPush() {
        val cls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession\$CppProxy")
            ?: run {
                FileLog.w("$TAG 找不到 WrapperSession")
                return
            }
        val extraCls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.PushExtraInfo")
            ?: Array<Any>::class.java
        val m = ChatRef.methodOrNull(cls, "onMsfPush", String::class.java, ByteArray::class.java, extraCls)
            ?: ChatRef.methodOrNull(cls, "onMsfPush", String::class.java, ByteArray::class.java, Any::class.java)
        if (m == null) {
            FileLog.w("$TAG 未找到 onMsfPush")
            return
        }
        ChatRef.hookBefore(m) { param ->
            if (!enabled()) return@hookBefore
            val cmd = param.args.getOrNull(0) as? String ?: return@hookBefore
            val buffer = param.args.getOrNull(1) as? ByteArray ?: return@hookBefore
            when (cmd) {
                CMD_INFO_SYNC -> handleInfoSyncPush(buffer, param)
                CMD_MSG_PUSH -> handleMsgPush(buffer, param)
            }
        }
    }

    /** 从 InfoSyncPush 里剔掉撤回条目。 */
    private fun handleInfoSyncPush(buffer: ByteArray, param: XC_MethodHook.MethodHookParam) {
        val newBuffer = runCatching {
            val bodyStart = findSyncInfoBody(buffer) ?: return@runCatching null
            val bodyEnd = findFieldEnd(buffer, bodyStart) ?: return@runCatching null
            val tagKey = readVarInt(buffer, bodyStart) ?: return@runCatching null
            val payloadStart = skipVarInt(buffer, tagKey.nextIndex) ?: return@runCatching null
            val payload = buffer.copyOfRange(payloadStart, bodyEnd)
            val newPayload = rewriteSyncInfoBody(payload) ?: return@runCatching null
            val out = ByteArrayOutputStream(buffer.size + newPayload.size - payload.size)
            out.write(buffer, 0, payloadStart)
            writeVarInt(out, newPayload.size)
            out.write(newPayload)
            out.write(buffer, bodyEnd, buffer.size - bodyEnd)
            out.toByteArray()
        }.onFailure { FileLog.e("$TAG InfoSyncPush 处理失败", it) }.getOrNull()
        if (newBuffer != null) param.args[1] = newBuffer
    }

    private fun rewriteSyncInfoBody(bytes: ByteArray): ByteArray? {
        val output = ByteArrayOutputStream(bytes.size)
        var index = 0
        var changed = false
        while (index < bytes.size) {
            val fieldStart = index
            val key = readVarInt(bytes, index) ?: return null
            index = key.nextIndex
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            val fieldEnd = when (wt) {
                0 -> readVarInt(bytes, index)?.nextIndex ?: return null
                1 -> (index + 8).coerceAtMost(bytes.size)
                2 -> {
                    val len = readVarInt(bytes, index) ?: return null
                    (len.nextIndex + len.value).coerceAtMost(bytes.size)
                }
                5 -> (index + 4).coerceAtMost(bytes.size)
                else -> return null
            }
            // field 8 wire 2 是消息体
            if (fn == 8 && wt == 2) {
                val len = readVarInt(bytes, index) ?: return null
                val start = len.nextIndex
                val end = (start + len.value).coerceAtMost(bytes.size)
                if (isRecallMessage(bytes.copyOfRange(start, end))) {
                    changed = true
                } else {
                    output.write(bytes, fieldStart, fieldEnd - fieldStart)
                }
            } else {
                output.write(bytes, fieldStart, fieldEnd - fieldStart)
            }
            index = fieldEnd
        }
        return if (changed) output.toByteArray() else null
    }

    private fun isRecallMessage(bytes: ByteArray): Boolean {
        var index = 0
        var msgType = 0
        var subType = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            index = key.nextIndex
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            when (wt) {
                0 -> index = readVarInt(bytes, index)?.nextIndex ?: break
                2 -> {
                    val len = readVarInt(bytes, index) ?: break
                    val subStart = len.nextIndex
                    val subEnd = (subStart + len.value).coerceAtMost(bytes.size)
                    index = subEnd
                    if (fn == 2) {
                        val (t, s) = parseMsgTypeAndSubType(bytes.copyOfRange(subStart, subEnd))
                        msgType = t
                        subType = s
                    }
                }
                else -> index = skipProtoField(bytes, index, wt) ?: break
            }
        }
        return (msgType == TYPE_C2C_RECALL && subType == SUBTYPE_C2C_RECALL) ||
            (msgType == TYPE_GROUP_RECALL && subType == SUBTYPE_GROUP_RECALL)
    }

    private fun parseMsgTypeAndSubType(bytes: ByteArray): Pair<Int, Int> {
        var msgType = 0
        var subType = 0
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            index = key.nextIndex
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            if (wt == 0) {
                val v = readVarInt(bytes, index) ?: break
                index = v.nextIndex
                when (fn) {
                    1 -> msgType = v.value
                    2 -> subType = v.value
                }
            } else {
                index = skipProtoField(bytes, index, wt) ?: break
            }
        }
        return msgType to subType
    }

    private fun handleMsgPush(buffer: ByteArray, param: XC_MethodHook.MethodHookParam) {
        val result = runCatching { rewriteMsgPush(buffer) }
            .onFailure { FileLog.e("$TAG MsgPush 处理失败", it) }
            .getOrNull() ?: return
        result.buffer?.let { param.args[1] = it }
        result.meta?.let { meta ->
            recallMetaMap[meta.key] = meta
            mainHandler.postDelayed({
                if (currentAdapter?.get() == null) {
                    // 视图还没绑定，稍后再刷新
                    mainHandler.postDelayed({ triggerAioRefresh() }, 150)
                } else {
                    triggerAioRefresh()
                }
            }, 200)
        }
    }

    private data class RewriteResult(val buffer: ByteArray?, val meta: RecallMeta?)

    private fun rewriteMsgPush(buffer: ByteArray): RewriteResult? {
        var index = 0
        var msgType = 0
        var subType = 0
        var opPayloadStart = -1
        var opPayloadEnd = -1
        while (index < buffer.size) {
            val key = readVarInt(buffer, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            when (wt) {
                0 -> index = readVarInt(buffer, index)?.nextIndex ?: break
                2 -> {
                    val len = readVarInt(buffer, index) ?: break
                    val subStart = len.nextIndex
                    val subEnd = (subStart + len.value).coerceAtMost(buffer.size)
                    index = subEnd
                    if (fn == 1) {
                        val r = parseMsgPushQQMessage(buffer.copyOfRange(subStart, subEnd))
                        msgType = r.first
                        subType = r.second
                        if (r.third >= 0) {
                            opPayloadStart = subStart + r.third
                            opPayloadEnd = subStart + r.fourth
                        }
                    }
                }
                else -> index = skipProtoField(buffer, index, wt) ?: break
            }
        }
        if (opPayloadStart < 0 || opPayloadEnd <= opPayloadStart) return null
        val isRecall = (msgType == TYPE_C2C_RECALL && subType == SUBTYPE_C2C_RECALL) ||
            (msgType == TYPE_GROUP_RECALL && subType == SUBTYPE_GROUP_RECALL)
        if (!isRecall) return null
        val meta = extractRecallMeta(buffer, opPayloadStart, opPayloadEnd, msgType)
        val newBuffer = when {
            msgType == TYPE_C2C_RECALL -> rewriteC2CRecall(buffer, opPayloadStart, opPayloadEnd)
            msgType == TYPE_GROUP_RECALL ->
                if (opPayloadEnd - opPayloadStart > 7) {
                    rewriteGroupRecall(buffer, opPayloadStart + 7, opPayloadEnd)
                } else null
            else -> null
        }
        return RewriteResult(newBuffer, meta)
    }

    /** 返回 (msgType, subType, opPayloadStart, opPayloadEnd)，后两者为 -1 表示未找到。 */
    private fun parseMsgPushQQMessage(bytes: ByteArray): Quad {
        var msgType = 0
        var subType = 0
        var opStart = -1
        var opEnd = -1
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            when (wt) {
                0 -> index = readVarInt(bytes, index)?.nextIndex ?: break
                2 -> {
                    val len = readVarInt(bytes, index) ?: break
                    val subStart = len.nextIndex
                    val subEnd = (subStart + len.value).coerceAtMost(bytes.size)
                    when (fn) {
                        2 -> {
                            val (t, s) = parseMsgTypeAndSubType(bytes.copyOfRange(subStart, subEnd))
                            msgType = t
                            subType = s
                        }
                        3 -> {
                            // messageBody，其 field 2 是 operationInfo
                            val body = bytes.copyOfRange(subStart, subEnd)
                            var si = 0
                            while (si < body.size) {
                                val sk = readVarInt(body, si) ?: break
                                val sfn = sk.value ushr 3
                                val swt = sk.value and 0x07
                                si = sk.nextIndex
                                if (sfn == 2 && swt == 2) {
                                    val sl = readVarInt(body, si) ?: break
                                    opStart = subStart + sl.nextIndex
                                    opEnd = subStart + sl.nextIndex + sl.value
                                    break
                                }
                                si = skipProtoField(body, si, swt) ?: break
                            }
                        }
                    }
                    index = subEnd
                }
                else -> index = skipProtoField(bytes, index, wt) ?: break
            }
        }
        return Quad(msgType, subType, opStart, opEnd)
    }

    private data class Quad(val first: Int, val second: Int, val third: Int, val fourth: Int)

    // ---------------- 元数据提取 ----------------

    private fun extractRecallMeta(buffer: ByteArray, start: Int, end: Int, msgType: Int): RecallMeta? {
        if (start < 0 || end > buffer.size || end <= start) return null
        val payload = buffer.copyOfRange(start, end)
        return when (msgType) {
            TYPE_C2C_RECALL -> extractC2CMeta(payload)
            TYPE_GROUP_RECALL -> if (payload.size > 7) extractGroupMeta(payload.copyOfRange(7, payload.size)) else null
            else -> null
        }
    }

    private fun extractC2CMeta(bytes: ByteArray): RecallMeta? {
        val inner = extractLengthDelimited(bytes, 1) ?: return null
        var operatorUid = ""
        var peerUid = ""
        var msgSeq = 0
        var index = 0
        while (index < inner.size) {
            val key = readVarInt(inner, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            when {
                fn == 1 && wt == 2 -> {
                    val l = readVarInt(inner, index) ?: break
                    operatorUid = String(inner, l.nextIndex, l.value, Charsets.UTF_8)
                    index = l.nextIndex + l.value
                }
                fn == 2 && wt == 2 -> {
                    val l = readVarInt(inner, index) ?: break
                    peerUid = String(inner, l.nextIndex, l.value, Charsets.UTF_8)
                    index = l.nextIndex + l.value
                }
                fn == 20 && wt == 0 -> {
                    val v = readVarInt(inner, index) ?: break
                    msgSeq = v.value
                    index = v.nextIndex
                }
                else -> index = skipProtoField(inner, index, wt) ?: break
            }
        }
        if (peerUid.isEmpty()) return null
        val selfUin = HostEnv.currentUin()
        val peerUin = FriendTool.uinFromUid(peerUid)
        val operatorUin = if (operatorUid.isNotEmpty()) FriendTool.uinFromUid(operatorUid) else ""
        // peerUid 等于自己时，说明这条是对方发的，真正的会话对象是 operator
        val keyUin = if (peerUin == selfUin) operatorUin else peerUin
        return RecallMeta(keyUin, 1, msgSeq)
    }

    private fun extractGroupMeta(bytes: ByteArray): RecallMeta? {
        var groupUin: String? = null
        var operatorUid: String? = null
        var msgSeq = 0
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            when {
                fn == 4 && wt == 0 -> {
                    val v = readVarInt(bytes, index) ?: break
                    groupUin = v.value.toString()
                    index = v.nextIndex
                }
                fn == 11 && wt == 2 -> {
                    val len = readVarInt(bytes, index) ?: break
                    val subStart = len.nextIndex
                    val subEnd = (subStart + len.value).coerceAtMost(bytes.size)
                    val sub = bytes.copyOfRange(subStart, subEnd)
                    var si = 0
                    while (si < sub.size) {
                        val sk = readVarInt(sub, si) ?: break
                        val sfn = sk.value ushr 3
                        val swt = sk.value and 0x07
                        si = sk.nextIndex
                        when {
                            sfn == 1 && swt == 2 -> {
                                val sl = readVarInt(sub, si) ?: break
                                operatorUid = String(sub, sl.nextIndex, sl.value, Charsets.UTF_8)
                                si = sl.nextIndex + sl.value
                            }
                            sfn == 3 && swt == 2 -> {
                                val sl = readVarInt(sub, si) ?: break
                                val ms = sl.nextIndex
                                val me = (ms + sl.value).coerceAtMost(sub.size)
                                val info = sub.copyOfRange(ms, me)
                                var mi = 0
                                while (mi < info.size) {
                                    val mk = readVarInt(info, mi) ?: break
                                    val mfn = mk.value ushr 3
                                    val mwt = mk.value and 0x07
                                    mi = mk.nextIndex
                                    if (mfn == 1 && mwt == 0) {
                                        val mv = readVarInt(info, mi) ?: break
                                        msgSeq = mv.value
                                        mi = mv.nextIndex
                                    } else {
                                        mi = skipProtoField(info, mi, mwt) ?: break
                                    }
                                }
                                si = me
                            }
                            else -> si = skipProtoField(sub, si, swt) ?: break
                        }
                    }
                    index = subEnd
                }
                else -> index = skipProtoField(bytes, index, wt) ?: break
            }
        }
        if (groupUin == null || operatorUid == null) return null
        return RecallMeta(operatorUid, 2, msgSeq, groupUin)
    }

    // ---------------- 改写 ----------------

    private fun rewriteC2CRecall(buffer: ByteArray, start: Int, end: Int): ByteArray? {
        val payload = buffer.copyOfRange(start, end)
        val newPayload = rewriteC2CMsgSeq(payload) ?: return null
        val out = ByteArrayOutputStream(buffer.size)
        out.write(buffer, 0, start)
        out.write(newPayload)
        out.write(buffer, end, buffer.size - end)
        return out.toByteArray()
    }

    private fun rewriteC2CMsgSeq(bytes: ByteArray): ByteArray? {
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            val tagStart = index
            index = key.nextIndex
            if (fn == 1 && wt == 2) {
                val len = readVarInt(bytes, index) ?: break
                val subStart = len.nextIndex
                val subEnd = (subStart + len.value).coerceAtMost(bytes.size)
                val newSub = rewriteMsgSeqField(bytes.copyOfRange(subStart, subEnd), 20) ?: return null
                val out = ByteArrayOutputStream(bytes.size)
                out.write(bytes, 0, tagStart)
                writeVarInt(out, (fn shl 3) or 2)
                writeVarInt(out, newSub.size)
                out.write(newSub)
                out.write(bytes, subEnd, bytes.size - subEnd)
                return out.toByteArray()
            }
            index = skipProtoField(bytes, index, wt) ?: break
        }
        return null
    }

    private fun rewriteGroupRecall(buffer: ByteArray, start: Int, end: Int): ByteArray? {
        val payload = buffer.copyOfRange(start, end)
        val newPayload = rewriteGroupMsgSeq(payload) ?: return null
        val out = ByteArrayOutputStream(buffer.size)
        out.write(buffer, 0, start)
        out.write(newPayload)
        out.write(buffer, end, buffer.size - end)
        return out.toByteArray()
    }

    private fun rewriteGroupMsgSeq(bytes: ByteArray): ByteArray? {
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            val tagStart = index
            index = key.nextIndex
            if (fn == 11 && wt == 2) {
                val len = readVarInt(bytes, index) ?: break
                val subStart = len.nextIndex
                val subEnd = (subStart + len.value).coerceAtMost(bytes.size)
                val newSub = rewriteMsgInfoSeq(bytes.copyOfRange(subStart, subEnd)) ?: return null
                val out = ByteArrayOutputStream(bytes.size)
                out.write(bytes, 0, tagStart)
                writeVarInt(out, (fn shl 3) or 2)
                writeVarInt(out, newSub.size)
                out.write(newSub)
                out.write(bytes, subEnd, bytes.size - subEnd)
                return out.toByteArray()
            }
            index = skipProtoField(bytes, index, wt) ?: break
        }
        return null
    }

    private fun rewriteMsgInfoSeq(bytes: ByteArray): ByteArray? {
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            val tagStart = index
            index = key.nextIndex
            if (fn == 3 && wt == 2) {
                val len = readVarInt(bytes, index) ?: break
                val subStart = len.nextIndex
                val subEnd = (subStart + len.value).coerceAtMost(bytes.size)
                val newSub = rewriteMsgSeqField(bytes.copyOfRange(subStart, subEnd), 1) ?: return null
                val out = ByteArrayOutputStream(bytes.size)
                out.write(bytes, 0, tagStart)
                writeVarInt(out, (fn shl 3) or 2)
                writeVarInt(out, newSub.size)
                out.write(newSub)
                out.write(bytes, subEnd, bytes.size - subEnd)
                return out.toByteArray()
            }
            index = skipProtoField(bytes, index, wt) ?: break
        }
        return null
    }

    /** 把指定 varint 字段改成 1，让撤回指向一条不存在的消息。 */
    private fun rewriteMsgSeqField(bytes: ByteArray, targetField: Int): ByteArray? {
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            if (fn == targetField && wt == 0) {
                val old = readVarInt(bytes, index) ?: return null
                val out = ByteArrayOutputStream(bytes.size)
                out.write(bytes, 0, index)
                writeVarInt(out, 1)
                out.write(bytes, old.nextIndex, bytes.size - old.nextIndex)
                return out.toByteArray()
            }
            index = skipProtoField(bytes, index, wt) ?: break
        }
        return null
    }

    private fun extractLengthDelimited(bytes: ByteArray, targetField: Int): ByteArray? {
        var index = 0
        while (index < bytes.size) {
            val key = readVarInt(bytes, index) ?: break
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            if (fn == targetField && wt == 2) {
                val len = readVarInt(bytes, index) ?: break
                val start = len.nextIndex
                val end = (start + len.value).coerceAtMost(bytes.size)
                if (end <= start) return null
                return bytes.copyOfRange(start, end)
            }
            index = skipProtoField(bytes, index, wt) ?: break
        }
        return null
    }

    // ---------------- 视图层 ----------------

    private fun hookAioView() {
        val vb = ChatRef.classOrNull("com.tencent.mobileqq.aio.msglist.holder.AIOBubbleMsgItemVB")
            ?: run {
                FileLog.w("$TAG 找不到气泡 VB，撤回提示不可用")
                return
            }
        val stateCls = ChatRef.classOrNull("com.tencent.mvi.base.mvi.MviUIState") ?: return
        val m = ChatRef.methodOrNull(vb, "handleUIState", stateCls) ?: return
        ChatRef.hookAfter(m) { param -> runCatching { paintRecallPrompt(param) } }
    }

    private fun paintRecallPrompt(param: XC_MethodHook.MethodHookParam) {
        if (recallMetaMap.isEmpty() || !enabled()) return
        val vb = param.thisObject ?: return
        val itemView = viewOf(vb) as? ViewGroup ?: return
        if (currentAdapter?.get() == null) captureAdapter(itemView)
        val msgItem = findAioMsgItem(vb) ?: return
        val record = ChatRef.invoke(msgItem, ChatRef.methodByName(msgItem.javaClass, "getMsgRecord", 0)) ?: return
        val peerUin = when (val p = ChatRef.invoke(record, ChatRef.methodByName(record.javaClass, "getPeerUin", 0))) {
            is Number -> p.toString()
            is String -> p
            else -> return
        }
        val msgSeq = when (val s = ChatRef.invoke(record, ChatRef.methodByName(record.javaClass, "getMsgSeq", 0))) {
            is Number -> s.toInt()
            else -> return
        }
        recallMetaMap["${peerUin}_$msgSeq"] ?: return
        val existing = itemView.getTag(RECALL_PROMPT_VIEW_ID) as? View
        if (existing != null && existing.parent === itemView) return
        val tv = TextView(itemView.context).apply {
            text = "消息已撤回"
            textSize = 13f
            setTextColor(Color.parseColor("#12B7F5"))
            gravity = Gravity.CENTER
            id = RECALL_PROMPT_VIEW_ID
        }
        itemView.addView(
            tv,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        itemView.setTag(RECALL_PROMPT_VIEW_ID, tv)
    }

    private fun viewOf(vb: Any): View? {
        (ChatRef.getField(vb, "e") as? View)?.let { return it }
        return ChatRef.invoke(vb, ChatRef.methodByName(vb.javaClass, "getHostView", 0)) as? View
    }

    private fun findAioMsgItem(obj: Any): Any? {
        val cached = aioMsgItemFieldCache[obj.javaClass]
        if (cached === FIELD_NOT_FOUND) return null
        if (cached is java.lang.reflect.Field) {
            return runCatching { cached.get(obj) }.getOrNull()
        }
        val target = ChatRef.classOrNull(AIO_MSG_ITEM_CLASS)
            ?: ChatRef.classOrNull("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        var c: Class<*>? = obj.javaClass
        while (target != null && c != null) {
            for (f in c.declaredFields) {
                if (f.type != target) continue
                f.isAccessible = true
                aioMsgItemFieldCache[obj.javaClass] = f
                return runCatching { f.get(obj) }.getOrNull()
            }
            c = c.superclass
        }
        aioMsgItemFieldCache[obj.javaClass] = FIELD_NOT_FOUND
        return null
    }

    private fun captureAdapter(view: View) {
        var parent: Any? = view.parent
        var depth = 0
        while (parent != null && depth < 50) {
            val name = parent.javaClass.name
            if (name.contains("RecyclerView")) {
                val adapter = ChatRef.invoke(parent, ChatRef.methodByName(parent.javaClass, "getAdapter", 0))
                if (adapter != null) {
                    currentAdapter = WeakReference(adapter)
                    return
                }
            }
            parent = (parent as? ViewGroup)?.parent
            depth++
        }
        val activity = HostEnv.activity() ?: return
        val decor = (activity.window?.decorView as? ViewGroup) ?: return
        if (findRecyclerInTree(decor, 0)) triggerAioRefresh()
    }

    private fun findRecyclerInTree(root: ViewGroup, depth: Int): Boolean {
        if (depth > 30) return false
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            if (child.javaClass.name.contains("RecyclerView")) {
                val adapter = ChatRef.invoke(child, ChatRef.methodByName(child.javaClass, "getAdapter", 0))
                if (adapter != null) {
                    currentAdapter = WeakReference(adapter)
                    return true
                }
            }
            if (child is ViewGroup && findRecyclerInTree(child, depth + 1)) return true
        }
        return false
    }

    private fun triggerAioRefresh() {
        val adapter = currentAdapter?.get() ?: return
        ChatRef.invoke(adapter, ChatRef.methodByName(adapter.javaClass, "notifyDataSetChanged", 0))
    }

    // ---------------- VarInt ----------------

    data class ProtoVarInt(val value: Int, val nextIndex: Int)

    private fun readVarInt(bytes: ByteArray, startIndex: Int): ProtoVarInt? {
        var result = 0
        var shift = 0
        var index = startIndex
        while (index < bytes.size && shift < Int.SIZE_BITS) {
            val b = bytes[index].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            index++
            if (b and 0x80 == 0) return ProtoVarInt(result, index)
            shift += 7
        }
        return null
    }

    private fun skipVarInt(bytes: ByteArray, startIndex: Int): Int? {
        var index = startIndex
        while (index < bytes.size) {
            if (bytes[index].toInt() and 0x80 == 0) return index + 1
            index++
        }
        return null
    }

    private fun skipProtoField(bytes: ByteArray, index: Int, wireType: Int): Int? = when (wireType) {
        0 -> readVarInt(bytes, index)?.nextIndex
        1 -> (index + 8).coerceAtMost(bytes.size)
        2 -> {
            val len = readVarInt(bytes, index) ?: return null
            (len.nextIndex + len.value).coerceAtMost(bytes.size)
        }
        5 -> (index + 4).coerceAtMost(bytes.size)
        else -> null
    }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun findSyncInfoBody(buffer: ByteArray): Int? {
        var index = 0
        while (index < buffer.size) {
            val key = readVarInt(buffer, index) ?: return null
            val fn = key.value ushr 3
            val wt = key.value and 0x07
            index = key.nextIndex
            if (fn == 1 && wt == 2) {
                val len = readVarInt(buffer, index) ?: return null
                index = len.nextIndex
                val subIndex = index
                val subKey = readVarInt(buffer, subIndex) ?: return null
                if ((subKey.value ushr 3) == 1 && (subKey.value and 0x07) == 2) return subIndex
                return null
            }
            index = skipProtoField(buffer, index, wt) ?: return null
        }
        return null
    }

    private fun findFieldEnd(buffer: ByteArray, tagIndex: Int): Int? {
        val key = readVarInt(buffer, tagIndex) ?: return null
        return skipProtoField(buffer, key.nextIndex, key.value and 0x07)
    }
}
