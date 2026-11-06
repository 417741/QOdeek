package com.qosed.qodeep

import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.base.BaseFinder
import java.io.File

object DexKitFinder {

    @Volatile
    private var libLoaded = false

    private fun ensureLibrary(): Boolean {
        if (libLoaded) return true
        synchronized(this) {
            if (libLoaded) return true
            val dir = Env.moduleNativeLibDir
            if (dir.isNotEmpty()) {
                val so = File(dir, System.mapLibraryName("dexkit"))
                if (so.exists()) {
                    try {
                        System.load(so.absolutePath)
                        libLoaded = true
                        XposedBridge.log("[QOdeep] libdexkit.so 已加载")
                        return true
                    } catch (t: Throwable) {
                        XposedBridge.log("[QOdeep] System.load(dexkit) 失败: ${t.message}")
                    }
                }
            }
            try {
                System.loadLibrary("dexkit")
                libLoaded = true
                return true
            } catch (t: Throwable) {
                XposedBridge.log("[QOdeep] loadLibrary(dexkit) 失败: ${t.message}")
            }
            return false
        }
    }

    @Volatile
    var progressKey: String? = null

    @Volatile
    var toastContext: android.content.Context? = null

    private fun toast(text: String) {
        val ctx = toastContext ?: Env.hostContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    fun doFind(onFinished: () -> Unit) {
        val thread = Thread({
            try {
                progressKey = null
                if (!ensureLibrary()) {
                    XposedBridge.log("[QOdeep] libdexkit 不可用，跳过查找")
                    return@Thread
                }
                val tasks = DexKitTaskRegistry.tasks
                val sourceDir = Env.hostApkPath.ifEmpty { Env.hostContext?.applicationInfo?.sourceDir.orEmpty() }
                if (sourceDir.isEmpty()) {
                    XposedBridge.log("[QOdeep] 宿主 sourceDir 为空，跳过查找")
                    return@Thread
                }

                XposedBridge.log("[QOdeep] DexKit 查找方法中...")
                scanSource("host apk", tasks) { DexKitBridge.create(sourceDir) }

                if (hasUnresolved(tasks)) {
                    scanRuntimeDex(tasks)
                }

                markUnresolved(tasks)
                DexKitCache.saveCache()
                XposedBridge.log("[QOdeep] DexKit 查找完成")
                toast("QOdeep 查找完成")
            } catch (t: Throwable) {
                XposedBridge.log("[QOdeep] DexKit 查找失败: $t")
            } finally {
                progressKey = null
                onFinished()
            }
        }, "QOdeep-DexKit")
        thread.start()
    }

    private fun scanSource(label: String, tasks: List<DexKitTask>, create: () -> DexKitBridge) {
        runCatching { create().use { scanBridge(it, tasks) } }
            .onFailure { XposedBridge.log("[QOdeep] $label 扫描失败: $it") }
    }

    private fun scanBridge(bridge: DexKitBridge, tasks: List<DexKitTask>) {
        for (task in tasks) {
            runCatching {
                for ((name, query) in task.getQueryMap()) {
                    val key = "${task.tag}->$name"
                    if (DexKitCache.contains(key)) continue
                    queryOne(bridge, key, query)
                }
            }.onFailure { XposedBridge.log("[QOdeep] 任务 ${task.tag} 扫描失败: $it") }
        }
    }

    private fun queryOne(bridge: DexKitBridge, key: String, query: BaseFinder) {
        progressKey = key
        toast("QOdeep 查找方法中\n$key")
        when (query) {
            is FindClass -> {
                val descriptor = bridge.findClass(query).firstOrNull()?.descriptor
                if (descriptor != null) {
                    XposedBridge.log("[QOdeep] DexKit 命中: $key -> $descriptor")
                    DexKitCache.put(key, descriptor)
                } else {
                    XposedBridge.log("[QOdeep] DexKit 未命中: $key")
                }
            }
            else -> XposedBridge.log("[QOdeep] 不支持的查询类型: $key")
        }
    }

    private fun scanRuntimeDex(tasks: List<DexKitTask>) {
        var current: ClassLoader? = Env.hostClassLoader
        var depth = 0
        while (current != null && depth < 8 && hasUnresolved(tasks)) {
            val loader = current
            if (loader is BaseDexClassLoader) {
                scanSource("runtime dex #$depth", tasks) { DexKitBridge.create(loader, true) }
            }
            current = loader.parent
            depth++
        }
    }

    private fun hasUnresolved(tasks: List<DexKitTask>): Boolean =
        tasks.any { task ->
            runCatching {
                task.getQueryMap().keys.any { name ->
                    if (name.startsWith("slot")) return@any false
                    !DexKitCache.contains("${task.tag}->$name")
                }
            }.getOrDefault(false)
        }

    private fun markUnresolved(tasks: List<DexKitTask>) {
        for (task in tasks) {
            runCatching {
                task.getQueryMap().keys.forEach { DexKitCache.putUnresolved("${task.tag}->$it") }
            }.onFailure { XposedBridge.log("[QOdeep] 标记未命中失败: $it") }
        }
    }
}
