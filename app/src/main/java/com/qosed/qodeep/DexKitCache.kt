package com.qosed.qodeep

import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method

object DexKitCache {

    private const val NOT_FOUND = ""
    private const val FINGERPRINT_KEY = "__host_apk__"

    private var cacheMap = mutableMapOf<String, String>()
    private var cacheFile: File? = null

    fun init() {
        cacheFile = File(File(Env.moduleDataDir, "dexkit"), "CacheMap.json")
    }

    private fun hostFingerprint(): String {
        val apk = Env.hostApkPath.ifEmpty { Env.hostContext?.applicationInfo?.sourceDir.orEmpty() }
        if (apk.isEmpty()) return ""
        val f = File(apk)
        return "${f.absolutePath}:${f.length()}:${f.lastModified()}"
    }

    fun put(key: String, descriptor: String) {
        cacheMap[key] = descriptor
    }

    fun putUnresolved(key: String) {
        if (!cacheMap.containsKey(key)) cacheMap[key] = NOT_FOUND
    }

    fun getDescriptor(key: String): String? = cacheMap[key]?.takeIf { it.isNotEmpty() }

    fun contains(key: String): Boolean = cacheMap.containsKey(key)

    fun getClass(key: String): Class<*> =
        getDescriptor(key)?.let { DexClass(it).getInstance(Env.hostClassLoader) }
            ?: throw ClassNotFoundException(key)

    fun getMethod(key: String): Method =
        getDescriptor(key)?.let { DexMethod(it).getMethodInstance(Env.hostClassLoader) }
            ?: throw NoSuchMethodException(key)

    fun initCache(): Boolean {
        val f = cacheFile ?: return false
        if (!f.exists()) return false
        return runCatching {
            val json = JSONObject(f.readText())
            val fingerprint = hostFingerprint()
            if (fingerprint.isNotEmpty() && json.optString(FINGERPRINT_KEY) != fingerprint) {
                XposedBridge.log("[QOdeep] 宿主 APK 已变化，丢弃旧缓存")
                clearAll()
                return@runCatching false
            }
            cacheMap.clear()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == FINGERPRINT_KEY) continue
                cacheMap[key] = json.getString(key)
            }
            XposedBridge.log("[QOdeep] DexKit 缓存已加载 (${cacheMap.size} 项)")
            true
        }.onFailure {
            XposedBridge.log("[QOdeep] DexKit 缓存读取失败: ${it.message}")
        }.getOrDefault(false)
    }

    fun saveCache(): Boolean {
        val f = cacheFile ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            val json = JSONObject()
            json.put(FINGERPRINT_KEY, hostFingerprint())
            cacheMap.forEach { (k, v) -> json.put(k, v) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(f)) {
                f.writeText(json.toString())
                tmp.delete()
            }
            true
        }.onFailure {
            XposedBridge.log("[QOdeep] DexKit 缓存保存失败: ${it.message}")
        }.getOrDefault(false)
    }

    fun validateAllTasks(): Boolean {
        val keys = DexKitTaskRegistry.tasks.flatMap { task ->
            task.getQueryMap().keys.map { "${task.tag}->$it" }
        }
        return keys.isNotEmpty() && keys.all { cacheMap.containsKey(it) }
    }

    fun clearAll() {
        cacheMap.clear()
        runCatching { cacheFile?.delete() }
    }
}
