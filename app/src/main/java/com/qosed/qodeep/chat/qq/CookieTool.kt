package com.qosed.qodeep.chat.qq

import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog

/**
 * 腾讯系鉴权信息的读取。
 *
 * rkey 由宿主在拉取图片时动态下发，无法本地生成，只能 hook 下来缓存；
 * 失效后宿主会自己刷新，这里只负责把最新值存住供本地拼接图片直链使用。
 */
object CookieTool {

    private const val TAG = "CookieTool"

    @Volatile
    private var friendRkey: String = ""

    @Volatile
    private var groupRkey: String = ""

    fun updateFriendRkey(v: String?) {
        if (!v.isNullOrEmpty() && v != friendRkey) {
            friendRkey = v
            FileLog.d("$TAG 好友 rkey 已更新 ${v.take(12)}...")
        }
    }

    fun updateGroupRkey(v: String?) {
        if (!v.isNullOrEmpty() && v != groupRkey) {
            groupRkey = v
            FileLog.d("$TAG 群 rkey 已更新 ${v.take(12)}...")
        }
    }

    /** @param isFriend true 取好友 rkey，false 取群 rkey */
    fun rkey(isFriend: Boolean): String? {
        val v = if (isFriend) friendRkey else groupRkey
        return v.takeIf { it.isNotEmpty() }
    }

    fun currentUin(): String = HostEnv.currentUin()

    /** 头像/资料等需要 cookie 的请求用。 */
    fun cookie(): String = runCatching {
        val app = HostEnv.appRuntime() ?: return ""
        val m = ChatRef.methodByName(app.javaClass, "getCurrentAccountUin", 0)
        val uin = (ChatRef.invoke(app, m) as? String).orEmpty()
        if (uin.isEmpty()) "" else "uin=o${uin.padStart(10, '0')};"
    }.getOrDefault("")
}
