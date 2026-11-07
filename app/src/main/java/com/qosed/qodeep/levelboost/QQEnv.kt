package com.qosed.qodeep.levelboost

import com.qosed.qodeep.Env

/**
 * QQ 当前环境工具类（纯反射实现）。
 *
 * QOdeep 没有 QQ 的 stub 依赖，宿主类一律通过 [Env.hostClassLoader] 用字符串类名反射加载，
 * 与 QEdge 的 QQCurrentEnv 等价，但去掉了对 mqq.app / com.tencent.* 的编译期依赖。
 */
object QQEnv {

    @Volatile
    private var cachedUin: String = ""

    /**
     * 取当前 AppRuntime（QQAppInterface 实例）。
     * 顺序：MobileQQ.getMobileQQ().peekAppRuntime() -> waitAppRuntime()，
     * 兜底 BaseApplicationImpl.getApplication().peekAppRuntime()。
     */
    fun getAppRuntime(): Any? {
        runCatching {
            val mobileQQClass = Env.hostClassLoader.loadClass("mqq.app.MobileQQ")
            val mobileQQ = mobileQQClass.getMethod("getMobileQQ").invoke(null) ?: return@runCatching
            peekAppRuntime(mobileQQ)?.let { return it }
            runCatching {
                mobileQQ.javaClass.getMethod("waitAppRuntime").invoke(mobileQQ)
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val baseAppClass = Env.hostClassLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            val app = baseAppClass.getMethod("getApplication").invoke(null) ?: return@runCatching null
            peekAppRuntime(app)
        }.getOrNull()
    }

    private fun peekAppRuntime(obj: Any): Any? = runCatching {
        obj.javaClass.getMethod("peekAppRuntime").invoke(obj)
    }.getOrNull()

    /** 当前登录 QQ 号（字符串，失败返回空串）。 */
    fun getCurrentUin(): String {
        if (cachedUin.isNotEmpty()) return cachedUin
        val app = getAppRuntime() ?: return ""
        for (m in listOf("getCurrentAccountUin", "getAccount", "getAccountUin")) {
            val v = runCatching { app.javaClass.getMethod(m).invoke(app) }.getOrNull()
            if (v != null && v.toString().isNotEmpty()) {
                cachedUin = v.toString()
                break
            }
        }
        if (cachedUin.isEmpty()) {
            cachedUin = findField(app, "currentUin")?.toString().orEmpty()
        }
        return cachedUin
    }

    /** cookie 用的 uin 字段：10 位补零，前缀 o。 */
    fun getCookieUin(): String {
        val uin = getCurrentUin()
        if (uin.isEmpty()) return "o0000000000"
        return if (uin.length >= 10) "o$uin" else "o" + uin.padStart(10, '0')
    }

    private fun findField(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            val f = runCatching { c.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
            if (f != null) return runCatching { f.get(obj) }.getOrNull()
            c = c.superclass
        }
        return null
    }
}
