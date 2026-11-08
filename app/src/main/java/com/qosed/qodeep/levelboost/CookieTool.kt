package com.qosed.qodeep.levelboost

import com.qosed.qodeep.Env

/**
 * Cookie / bkn 工具类（纯反射实现）。
 *
 * 通过 AppRuntime 拿 TicketManager，反射 getSkey / getPskey，再算 g_tk(bkn)。
 */
object CookieTool {

    /** 拿到 TicketManager：先 getManager(2)，失败再 getRuntimeService(TicketManager.class, "")。 */
    private fun getTicketManager(): Any? {
        val app = QQEnv.getAppRuntime() ?: return null
        runCatching {
            val m = app.javaClass
                .getMethod("getManager", Int::class.javaPrimitiveType)
                .invoke(app, 2)
            if (m != null) return m
        }
        runCatching {
            val ticketClass = Env.hostClassLoader.loadClass("mqq.manager.TicketManager")
            val m = app.javaClass
                .getMethod("getRuntimeService", Class::class.java, String::class.java)
                .invoke(app, ticketClass, "")
            if (m != null) return m
        }
        return null
    }

    /** 当前 uin 的 skey。 */
    fun getSkey(): String? {
        val manager = getTicketManager() ?: return null
        val uin = QQEnv.getCurrentUin()
        return runCatching {
            manager.javaClass.getMethod("getSkey", String::class.java).invoke(manager, uin)?.toString()
        }.getOrNull()
    }

    /** 指定域名（如 qzone.qq.com）的 p_skey。 */
    fun getPskey(url: String): String? {
        val manager = getTicketManager() ?: return null
        val uin = QQEnv.getCurrentUin()
        return runCatching {
            manager.javaClass
                .getMethod("getPskey", String::class.java, String::class.java)
                .invoke(manager, uin, url)?.toString()
        }.getOrNull()
    }

    /** g_tk / bkn 算法。 */
    fun getBkn(key: String): Long {
        var hash = 5381L
        for (ch in key) {
            hash += (hash shl 5) + ch.code
        }
        return hash and 0x7FFFFFFF
    }
}
