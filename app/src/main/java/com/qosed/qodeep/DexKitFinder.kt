package com.qosed.qodeep

import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.base.BaseFinder
import java.io.File

/**
 * DexKit 扫描器。
 *
 * 在后台线程扫描宿主 APK（必要时再扫运行时 dex），把每个任务的命中结果写入 DexKitCache。
 * 全部完成后调用 onFinished —— 由 MainHook 继续安装设置页 hook。
 */
object DexKitFinder {

    @Volatile
    private var libLoaded = false

    private fun ensureLibrary(): Boolean {
        if (libLoaded) return true
        synchronized(this) {
            if (libLoaded) return true
            // 寄生 ClassLoader 下 System.loadLibrary 找不到模块 APK 里的 so，
            // 必须用安装目录的绝对路径加载。
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

    /** 当前进度文本，供设置页进度弹窗显示；null 表示未在查找 */
    @Volatile
    var progressKey: String? = null

    @Volatile
    private var progressIndex = 0

    @Volatile
    private var progressTotal = 0

    /** 后台扫描；无论成败最后都回调 onFinished（保证硬编码兜底也能装上）。 */
    fun doFind(onFinished: () -> Unit) {
        val thread = Thread({
            try {
                progressKey = null
                if (!ensureLibrary()) {
                    XposedBridge.log("[QOdeep] libdexkit 不可用，跳过查找")
                    return@Thread
                }
                val tasks = DexKitTaskRegistry.tasks
                // 页面侧 Env.hostContext 为空，用 Intent 传来的 hostApkPath 兜底
                val sourceDir = Env.hostApkPath.ifEmpty { Env.hostContext?.applicationInfo?.sourceDir.orEmpty() }
                if (sourceDir.isEmpty()) {
                    XposedBridge.log("[QOdeep] 宿主 sourceDir 为空，跳过查找")
                    return@Thread
                }

                XposedBridge.log("[QOdeep] DexKit 查找方法中...")
                progressIndex = 0
                progressTotal = tasks.sumOf { runCatching { it.getQueryMap().size }.getOrDefault(0) }
                // 第一源：宿主 APK 本身，正常安装包一次即可全部命中
                scanSource("host apk", tasks) { DexKitBridge.create(sourceDir) }

                // 第二源：运行时已加载的 dex（壳包的真实代码在运行时 dex 里）
                if (hasUnresolved(tasks)) {
                    scanRuntimeDex(tasks)
                }

                // 全部扫完统一留痕，避免单源扫不到就写空串把缓存锁死
                markUnresolved(tasks)
                DexKitCache.saveCache()
                XposedBridge.log("[QOdeep] DexKit 查找完成")
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
        progressIndex++
        progressKey = "正在查找 $progressIndex / $progressTotal"
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
                    // 占位槽位（slot 前缀）用宿主 dex 里不存在的字符串占位，永远不会命中；
                    // 若算进 hasUnresolved，会导致每次冷启动都去扫 QQ 运行时补丁 dex，
                    // 轻则很慢、重则卡死，设置页入口迟迟装不上。只有真实 key 未命中才扫运行时 dex。
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
