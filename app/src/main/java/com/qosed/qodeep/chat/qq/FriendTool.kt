package com.qosed.qodeep.chat.qq

import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog
import java.security.MessageDigest

/**
 * 好友/群资料的反射访问。
 *
 * uid 与 uin 在 NT 内核里是两套标识：UI 层多数用 uid（u_xxx 形式），
 * 老接口仍按 uin 组织，两边转换只能通过内核好友列表。
 */
object FriendTool {

    private const val TAG = "FriendTool"

    private val uidToUinCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** uid -> uin。查不到时返回空串，调用方按空处理即可。 */
    fun uinFromUid(uid: String): String {
        if (uid.isEmpty()) return ""
        if (!uid.startsWith("u_")) return uid
        uidToUinCache[uid]?.let { return it }
        val uin = runCatching {
            val kernel = kernelService() ?: return@runCatching ""
            val m = ChatRef.methodByName(kernel.javaClass, "getUidFromUin", 1)
            if (m != null) return@runCatching ""
            ChatRef.getString(kernel, "uin") ?: ""
        }.getOrDefault("")
        if (uin.isNotEmpty()) uidToUinCache[uid] = uin
        return uin
    }

    /** 群号、昵称等资料项。 */
    fun groupName(groupUin: String): String = runCatching {
        val kernel = kernelService() ?: return ""
        ChatRef.getString(kernel, "groupName") ?: ""
    }.getOrDefault("")

    fun friendName(uin: String): String = runCatching {
        val app = HostEnv.appRuntime() ?: return ""
        val m = ChatRef.methodByName(app.javaClass, "getCurrentNickName", 0)
        (ChatRef.invoke(app, m) as? String).orEmpty()
    }.getOrDefault("")

    private fun kernelService(): Any? = runCatching {
        val app = HostEnv.appRuntime() ?: return null
        val m = ChatRef.methodByName(app.javaClass, "getRuntimeService", 1)
        ChatRef.invoke(app, m, ChatRef.classOrNull("com.tencent.qqnt.kernel.api.IKernelService") ?: return null)
    }.onFailure { FileLog.d("$TAG 取内核失败: ${it.message}") }.getOrNull()

    /** 群/好友头像地址（可直接下载）。 */
    fun avatarUrl(uin: String, isGroup: Boolean): String = if (isGroup) {
        "https://p.qlogo.cn/gh/$uin/$uin/640"
    } else {
        "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=640"
    }

    fun md5(s: String): String = runCatching {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        d.joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
