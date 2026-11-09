package com.qosed.qodeep.chat

import com.qosed.qodeep.Env
import com.qosed.qodeep.levelboost.FileLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 聊天功能的反射与 hook 工具。
 *
 * 全部走反射而不是编译期引用宿主类，原因：宿主 NT 内核类的字段名/方法签名随版本变动，
 * 编译期绑定会让模块在签名不一致时直接抛 NoSuchMethodError；反射失败只影响单个功能，
 * 并能在日志里留下具体环节。
 */
object ChatRef {

    private const val TAG = "ChatRef"

    fun loader(): ClassLoader = Env.hostClassLoader

    fun classOrNull(name: String): Class<*>? = runCatching {
        Class.forName(name, false, loader())
    }.onFailure {
        FileLog.w("$TAG 找不到类 $name: ${it.message}")
    }.getOrNull()

    fun methodOrNull(cls: Class<*>?, name: String, vararg params: Class<*>): Method? {
        if (cls == null) return null
        return runCatching {
            var c: Class<*>? = cls
            while (c != null) {
                try {
                    return@runCatching c.getDeclaredMethod(name, *params).apply { isAccessible = true }
                } catch (_: NoSuchMethodException) {
                    c = c.superclass
                }
            }
            null
        }.getOrNull()
    }

    /** 按方法名 + 参数个数找方法（沿父类链向上，取第一个非抽象实现）。 */
    fun methodByName(cls: Class<*>?, name: String, argc: Int): Method? {
        if (cls == null) return null
        var c: Class<*>? = cls
        while (c != null) {
            c.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == argc && !Modifier.isAbstract(it.modifiers)
            }?.let { it.isAccessible = true; return it }
            c = c.superclass
        }
        return null
    }

    fun allMethods(cls: Class<*>?, name: String): List<Method> {
        if (cls == null) return emptyList()
        return cls.declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }
    }

    fun fieldOrNull(cls: Class<*>?, name: String): java.lang.reflect.Field? {
        if (cls == null) return null
        var c: Class<*>? = cls
        while (c != null) {
            runCatching { c.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            c = c.superclass
        }
        return null
    }

    fun getField(obj: Any?, name: String): Any? = runCatching {
        fieldOrNull(obj?.javaClass, name)?.get(obj)
    }.getOrNull()

    fun setField(obj: Any?, name: String, value: Any?): Boolean = runCatching {
        val f = fieldOrNull(obj?.javaClass, name) ?: return false
        f.set(obj, value)
        true
    }.getOrDefault(false)

    fun getInt(obj: Any?, name: String, def: Int = 0): Int =
        (getField(obj, name) as? Number)?.toInt() ?: def

    fun getLong(obj: Any?, name: String, def: Long = 0L): Long =
        (getField(obj, name) as? Number)?.toLong() ?: def

    fun getBool(obj: Any?, name: String, def: Boolean = false): Boolean =
        (getField(obj, name) as? Boolean) ?: def

    fun getString(obj: Any?, name: String): String? = getField(obj, name) as? String

    fun invoke(obj: Any?, method: Method?, vararg args: Any?): Any? = runCatching {
        method?.invoke(obj, *args)
    }.onFailure {
        FileLog.w("$TAG 调用 ${method?.name} 失败: ${it.message}")
    }.getOrNull()

    // ---------------- hook 封装 ----------------

    fun hookBefore(member: Member?, cb: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        if (member == null) return false
        return runCatching {
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { cb(param) }
                }
            })
            true
        }.onFailure { FileLog.e("$TAG hook ${member.name} 失败", it) }.getOrDefault(false)
    }

    fun hookAfter(member: Member?, cb: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        if (member == null) return false
        return runCatching {
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { cb(param) }
                }
            })
            true
        }.onFailure { FileLog.e("$TAG hook ${member.name} 失败", it) }.getOrDefault(false)
    }

    fun hookReplace(member: Member?, cb: (XC_MethodHook.MethodHookParam) -> Any?): Boolean {
        if (member == null) return false
        return runCatching {
            XposedBridge.hookMethod(member, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = runCatching { cb(param) }.getOrNull()
                }
            })
            true
        }.onFailure { FileLog.e("$TAG hook ${member.name} 失败", it) }.getOrDefault(false)
    }

    fun hookAllMethods(cls: Class<*>?, name: String, cb: (XC_MethodHook.MethodHookParam) -> Unit): Int {
        if (cls == null) return 0
        val ms = allMethods(cls, name)
        var n = 0
        for (m in ms) if (hookBefore(m, cb)) n++
        return n
    }

    /** 安全取 hook 参数。 */
    fun arg(param: XC_MethodHook.MethodHookParam, index: Int): Any? =
        param.args.getOrNull(index)
}
