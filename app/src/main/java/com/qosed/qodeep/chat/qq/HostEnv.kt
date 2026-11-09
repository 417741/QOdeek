package com.qosed.qodeep.chat.qq

import com.qosed.qodeep.Env
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog
import java.io.File

/**
 * 宿主运行时环境的访问入口。
 *
 * 全部反射获取，不编译期引用宿主类：模块会在 QQ 与 TIM 两个宿主里运行，
 * 两边的 AppRuntime 类型不同，编译期绑定会让其中一个直接崩在类加载阶段。
 */
object HostEnv {

    private const val TAG = "HostEnv"

    @Volatile
    private var cachedUin: String? = null

    /** 缓存当前账号；多账号切换时由 cookie 变化兜底，不必每次反射。 */
    fun currentUin(): String {
        cachedUin?.let { return it }
        val uin = runCatching {
            val app = appRuntime() ?: return@runCatching ""
            val m = ChatRef.methodByName(app.javaClass, "getCurrentAccountUin", 0)
            (ChatRef.invoke(app, m) as? String).orEmpty()
        }.getOrDefault("").ifEmpty {
            runCatching {
                val app = appRuntime() ?: return@runCatching ""
                ChatRef.getString(app, "currentUin").orEmpty()
            }.getOrDefault("")
        }
        if (uin.isNotEmpty()) cachedUin = uin
        return uin
    }

    fun currentUid(): String = runCatching {
        val app = appRuntime() ?: return@runCatching ""
        val m = ChatRef.methodByName(app.javaClass, "getCurrentUid", 0)
        (ChatRef.invoke(app, m) as? String).orEmpty()
    }.getOrDefault("")

    /** 宿主 AppRuntime（QQAppInterface / TIM 对应实现），统一按 Object 使用。 */
    fun appRuntime(): Any? = runCatching {
        var cls = ChatRef.classOrNull("com.tencent.mobileqq.app.MobileQQ")
        if (cls == null) cls = ChatRef.classOrNull("com.tencent.tim.app.MobileQQ")
        if (cls != null) {
            val inst = ChatRef.invoke(null, ChatRef.methodByName(cls, "getMobileQQ", 0))
            if (inst != null) {
                val peek = ChatRef.methodByName(inst.javaClass, "peekAppRuntime", 0)
                val rt = ChatRef.invoke(inst, peek)
                if (rt != null) return rt
                val wait = ChatRef.methodByName(inst.javaClass, "waitAppRuntime", 0)
                val rt2 = ChatRef.invoke(inst, wait)
                if (rt2 != null) return rt2
            }
        }
        // 兜底：BaseApplicationImpl
        val base = ChatRef.classOrNull("com.tencent.common.app.BaseApplicationImpl")
            ?: return null
        val app = ChatRef.invoke(null, ChatRef.methodByName(base, "getApplication", 0)) ?: return null
        ChatRef.invoke(app, ChatRef.methodByName(app.javaClass, "peekAppRuntime", 0))
    }.onFailure { FileLog.w("$TAG 取 AppRuntime 失败: ${it.message}") }.getOrNull()

    /** 当前前台 Activity（长按菜单、弹窗需要）。 */
    fun activity(): android.app.Activity? = runCatching {
        val atCls = Class.forName("android.app.ActivityThread")
        val at = atCls.getMethod("currentActivityThread").invoke(null)
        val activities = ChatRef.getField(at, "mActivities") as? Map<*, *> ?: return null
        for (record in activities.values) {
            if (record == null) continue
            val paused = ChatRef.getField(record, "paused") as? Boolean ?: false
            if (paused) continue
            (ChatRef.getField(record, "activity") as? android.app.Activity)?.let { return it }
        }
        null
    }.getOrNull()

    /** 宿主的 Android/data 目录前缀。 */
    fun hostDataDir(): String = runCatching {
        val pkg = Env.hostContext?.packageName ?: "com.tencent.mobileqq"
        "/sdcard/Android/data/$pkg"
    }.getOrDefault("/sdcard/Android/data/com.tencent.mobileqq")

    /** 宿主应用上下文。 */
    fun appContext(): android.content.Context? =
        Env.hostContext ?: activity()?.applicationContext

    /** 宿主腾讯目录（Tencent/MobileQQ）根。 */
    fun tencentDir(): File = File(hostDataDir(), "Tencent/MobileQQ")

    fun clearCache() {
        cachedUin = null
    }
}
