package com.qosed.qodeep.levelboost

import com.qosed.qodeep.Env
import org.json.JSONObject
import java.io.File

/**
 * 定时说说/空间签到的配置存储（对齐 QEdge 把配置存模块目录的做法）。
 *
 * 持久化到 {Env.moduleDataDir}/data/config.dat，主进程调度器与寄生设置页共用同一份文件，
 * 用户无需 root 即可在文件管理器里查看/删除配置（删掉后所有开关/内容/幂等标记复位）。
 * 路径对齐 QEdge 的 QEdge/data/config.dat。
 *
 * 读写线程安全：内存里持有一份 JSONObject 缓存，读直接返回内存值；写先改内存再落盘。
 */
object MoodConfig {

    private const val FILE_NAME = "data/config.dat"

    private val lock = Any()

    @Volatile
    private var cache: JSONObject? = null

    // 记录 cache 加载时文件的时间戳，用于检测外部（其它进程/单例实例）对 config 的修改，
    // 保证设置页写入后主进程调度器能通过磁盘感知到变化，而不是一直读旧内存缓存。
    @Volatile
    private var cacheStamp = 0L

    private fun configFile(): File? {
        // 每次按当前 Env.moduleDataDir 计算，避免目录在运行时被更新后仍用旧路径
        val dir = Env.moduleDataDir
        if (dir.isEmpty()) return null
        return File(File(dir), FILE_NAME)
    }

    private fun load(): JSONObject {
        val f = configFile()
        val stamp = f?.takeIf { it.exists() }?.lastModified() ?: 0L
        cache?.let { if (stamp == cacheStamp) return it }
        synchronized(lock) {
            cache?.let { if (stamp == cacheStamp) return it }
            val obj = JSONObject()
            if (f != null && f.exists()) {
                runCatching {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        val parsed = JSONObject(text)
                        // 拷贝到新建对象，避免外部引用
                        val keys = parsed.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            obj.put(k, parsed.opt(k))
                        }
                    }
                }
            }
            cache = obj
            cacheStamp = stamp
            return obj
        }
    }

    /**
     * 丢弃内存缓存并重新从磁盘读取。
     * 用户手动删除 config.dat 后调用可立即复位（配合「删文件重新打卡」场景）。
     */
    fun reload() {
        synchronized(lock) {
            cache = null
        }
    }

    private fun persist() {
        val obj = cache ?: return
        val f = configFile() ?: return
        synchronized(lock) {
            runCatching {
                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                f.writeText(obj.toString())
                // 写成功后更新时间戳，避免下次 load 因 mtime 变化而重复读盘
                cacheStamp = f.lastModified()
            }
        }
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        synchronized(lock) {
            return load().optBoolean(key, def)
        }
    }

    fun getString(key: String, def: String): String {
        synchronized(lock) {
            val v = load().optString(key, def)
            return if (v == JSONObject.NULL.toString()) def else v
        }
    }

    fun putBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            load().put(key, value)
            persist()
        }
    }

    fun putString(key: String, value: String) {
        synchronized(lock) {
            load().put(key, value)
            persist()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            load().remove(key)
            persist()
        }
    }

    /** 测试/排障用：定位配置文件路径。 */
    fun configPath(): String = configFile()?.absolutePath ?: ""
}
