package com.qosed.qodeep.chat.api

import com.qosed.qodeep.chat.ChatRef
import java.io.File

/**
 * 一条消息的只读快照。
 *
 * 长按菜单、复读、复制卡片等功能都从这里取数据，这样各功能不必各自反射 MsgRecord 的十几个字段，
 * 也避免把宿主对象直接暴露给上层（宿主对象生命周期短，跨线程持有会读到已复用的字段）。
 */
class MsgData(record: Any?) {

    val raw: Any? = record

    val chatType: Int = ChatRef.getInt(record, "chatType")
    val msgType: Int = ChatRef.getInt(record, "msgType")
    val subMsgType: Int = ChatRef.getInt(record, "subMsgType")
    val sendType: Int = ChatRef.getInt(record, "sendType")
    val sendStatus: Int = ChatRef.getInt(record, "sendStatus")
    val editable: Boolean = ChatRef.getBool(record, "editable")

    val peerUin: String = ChatRef.getLong(record, "peerUin").let { if (it == 0L) "" else it.toString() }
    val peerUid: String = ChatRef.getString(record, "peerUid") ?: ""
    val userUin: String = ChatRef.getLong(record, "senderUin").let { if (it == 0L) "" else it.toString() }
    val userUid: String = ChatRef.getString(record, "senderUid") ?: ""
    val userName: String = ChatRef.getString(record, "sendNickName") ?: ""
    val peerName: String = ChatRef.getString(record, "peerName") ?: ""

    val time: Long = ChatRef.getLong(record, "msgTime")
    val msgId: Long = ChatRef.getLong(record, "msgId")
    val msgSeq: Long = ChatRef.getLong(record, "msgSeq")
    val msgRandom: Long = ChatRef.getLong(record, "msgRandom")

    val guildId: String = ChatRef.getString(record, "guildId") ?: ""
    val channelId: String = ChatRef.getString(record, "channelId") ?: ""

    /** 文本拼接结果（文字、卡片 JSON 都并进去）。 */
    var msg: String = ""
        private set

    val picList = ArrayList<String>()
    val videoList = ArrayList<String>()
    val pttList = ArrayList<String>()
    val atList = ArrayList<String>()

    /** 最近的本地路径（语音/视频/文件）。 */
    var path: String = ""
        private set

    var replyMsgId: Long = 0
    var replySenderUid: String = ""
        private set

    init {
        processElements(ChatRef.getField(record, "elements") as? List<*>)
    }

    private fun processElements(elements: List<*>?) {
        if (elements.isNullOrEmpty()) return
        val sb = StringBuilder()
        for (el in elements) {
            if (el == null) continue
            val type = ChatRef.getInt(el, "elementType")
            runCatching {
                when (type) {
                    1 -> textElement(el, sb)
                    2 -> picElement(el, sb)
                    3 -> fileElement(el)
                    4 -> pttElement(el)
                    5 -> videoElement(el)
                    7 -> replyElement(el)
                    10 -> arkElement(el, sb)
                    49 -> filterElement(el)
                }
            }
        }
        msg = sb.toString()
    }

    private fun textElement(el: Any, sb: StringBuilder) {
        val t = ChatRef.getField(el, "textElement") ?: return
        sb.append(ChatRef.getString(t, "content") ?: "")
        // atType == 2 表示 @ 某人
        if (ChatRef.getInt(t, "atType") == 2) {
            val uin = ChatRef.getString(t, "atNtUid")?.takeIf { it.isNotEmpty() }
                ?: ChatRef.getLong(t, "atUid").toString()
            atList.add(uin)
        }
    }

    private fun picElement(el: Any, sb: StringBuilder) {
        val p = ChatRef.getField(el, "picElement") ?: return
        val url = ChatRef.getString(p, "originImageUrl") ?: return
        if (url.isEmpty()) return
        // 原图直链需要带 rkey 才能访问
        val full = "https://multimedia.nt.qq.com.cn$url" + rkey()
        sb.append("[pic=").append(full).append("]")
        picList.add(full)
    }

    private fun rkey(): String {
        val u = com.qosed.qodeep.chat.qq.CookieTool.rkey(chatType == 1)
        return u ?: ""
    }

    private fun fileElement(el: Any) {
        val f = ChatRef.getField(el, "fileElement") ?: return
        ChatRef.getString(f, "filePath")?.takeIf { it.isNotEmpty() }?.let { path = it }
    }

    private fun pttElement(el: Any) {
        val p = ChatRef.getField(el, "pttElement") ?: return
        val name = ChatRef.getString(p, "fileName") ?: return
        val direct = ChatRef.getString(p, "filePath")
        val found = if (!direct.isNullOrEmpty() && File(direct).exists()) {
            direct
        } else {
            pttPath(name)
        }
        if (found.isNotEmpty()) {
            path = found
            pttList.add(found)
        }
    }

    private fun videoElement(el: Any) {
        val v = ChatRef.getField(el, "videoElement") ?: return
        val md5 = ChatRef.getString(v, "originVideoMd5")?.takeIf { it.isNotEmpty() }
            ?: ChatRef.getString(v, "videoMd5")?.takeIf { it.isNotEmpty() }
            ?: return
        val direct = ChatRef.getString(v, "filePath")
        val found = if (!direct.isNullOrEmpty() && File(direct).exists()) direct else videoPath(md5)
        if (found.isNotEmpty()) {
            path = found
            videoList.add(found)
        }
    }

    private fun replyElement(el: Any) {
        val r = ChatRef.getField(el, "replyElement") ?: return
        replyMsgId = ChatRef.getLong(r, "replayMsgId")
        replySenderUid = ChatRef.getString(r, "senderUidStr") ?: ""
    }

    private fun arkElement(el: Any, sb: StringBuilder) {
        val a = ChatRef.getField(el, "arkElement") ?: return
        ChatRef.getString(a, "bytesData")?.let { sb.append(it) }
    }

    private fun filterElement(el: Any) {
        val f = ChatRef.getField(el, "filterMsgElement") ?: return
        val md5 = ChatRef.getString(f, "videoMd5")?.takeIf { it.isNotEmpty() } ?: return
        val direct = ChatRef.getString(f, "filePath")
        val found = if (!direct.isNullOrEmpty() && File(direct).exists()) direct else videoPath(md5)
        if (found.isNotEmpty()) {
            path = found
            videoList.add(found)
        }
    }

    private fun videoPath(md5: String): String {
        val root = com.qosed.qodeep.chat.qq.HostEnv.tencentDir().absolutePath
        return "$root/shortvideo/$md5/$md5.mp4"
    }

    private fun pttPath(name: String): String {
        val uin = com.qosed.qodeep.chat.qq.CookieTool.currentUin()
        if (uin.isEmpty()) return ""
        val root = com.qosed.qodeep.chat.qq.HostEnv.tencentDir().absolutePath
        return "$root/$uin/ptt/$name"
    }

    val isGroup: Boolean get() = chatType == 2

    /** 宿主 Contact（好友/群），供复读时取在线语音、视频地址等 kernel 接口使用。 */
    val contact: Any? by lazy {
        com.qosed.qodeep.chat.qq.MsgSender.contact(chatType, peerUid)
    }
}
