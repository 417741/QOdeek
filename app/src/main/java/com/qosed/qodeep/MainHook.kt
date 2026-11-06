package com.qosed.qodeep

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.base.BaseFinder
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Xposed 入口（legacy API）。
 *
 * 启动分两层：
 * 1) QFix 双路抢真实 ClassLoader —— QQ 热补丁会把真正宿主类挂在补丁 loader 上，
 *    直接 hook lpparam.classLoader 会「装上了也永远不触发」。
 * 2) DexKit 动态查找 —— 设置页相关类名是随版本变化的混淆名，用 DexKit 扫描宿主 dex
 *    按特征（父类/字符串）找出来，配合硬编码兜底，做到不挑 QQ 版本。
 *
 * 拿到真实类后：hook 设置页列表构建方法，构造独立分组插到「账号与安全」上方，
 * 条目点击 -> 打开本模块 SettingsActivity。
 */
class MainHook : IXposedHookLoadPackage, DexKitTask {

    override val tag: String = "SettingInject"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != QQ_PACKAGE) return
        // 设置页只在主进程；子进程(:MSF/:tool)不需要注入，避免重复扫描 dex
        if (lpparam.processName != QQ_PACKAGE) {
            XposedBridge.log("[QOdeep] 跳过子进程: ${lpparam.processName}")
            return
        }
        XposedBridge.log("[QOdeep] 注入主进程: ${lpparam.processName}")
        try {
            hookStartup(lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[QOdeep] 启动 hook 安装失败: $t")
        }
    }

    // ---------------- DexKit 查询定义 ----------------

    override fun getQueryMap(): Map<String, BaseFinder> {
        val queries = linkedMapOf<String, BaseFinder>()
        // 设置页 provider：setting.main 包下继承 SettingConfigProvider 的类（混淆名随版本变）
        queries["provider"] = FindClass().apply {
            searchPackages("com.tencent.mobileqq.setting.main")
            matcher { superClass("com.tencent.mobileqq.setting.processor.SettingConfigProvider") }
        }
        // 通用条目处理器：常量池里有 "SimpleItemProcessor" 字符串的类
        queries["simpleItemProcessor"] = FindClass().apply {
            searchPackages("com.tencent.mobileqq.setting.processor")
            matcher { usingStrings("SimpleItemProcessor") }
        }
        // 分组处理器：常量池里有 "itemProcessors" 字符串的类
        queries["settingSection"] = FindClass().apply {
            searchPackages("com.tencent.mobileqq.setting.processor")
            matcher { usingStrings("itemProcessors") }
        }
        // 占位任务：先把查找任务数对齐到 16 个（对应后续 16 个功能），
        // 每个槽位用当前必然不存在的字符串占位，后续加功能时逐个替换成真实查询。
        for (i in 4..16) {
            queries["slot$i"] = FindClass().apply {
                searchPackages("com.tencent.mobileqq.setting")
                matcher { usingStrings("QOdeep.Slot.$i") }
            }
        }
        return queries
    }

    // ---------------- 启动双路：QFix 补丁 loader + BaseApplicationImpl 兜底 ----------------

    private fun hookStartup(cl: ClassLoader) {
        try {
            val attach = cl.loadClass("com.tencent.common.app.QFixApplicationImplProxy")
                .getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            addTemporary(hookQFixAttach(attach, cl))
            XposedBridge.log("[QOdeep] QFix 代理 hook 已安装")
        } catch (t: Throwable) {
            XposedBridge.log("[QOdeep] QFix 代理不可用，仅走 BaseApplicationImpl: $t")
        }
        addTemporary(hookApplicationOnCreate(cl))
    }

    private fun hookQFixAttach(attach: Method, cl: ClassLoader): XC_MethodHook.Unhook {
        val constructorUnhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())
        val closed = AtomicBoolean(false)
        val teardown = Runnable {
            synchronized(constructorUnhooks) {
                closed.set(true)
                for (u in constructorUnhooks) {
                    try { u.unhook() } catch (_: Throwable) {}
                }
                constructorUnhooks.clear()
            }
        }
        return XposedBridge.hookMethod(attach, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (ctor in BaseDexClassLoader::class.java.declaredConstructors) {
                    try {
                        val unhook = XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val loader = param.thisObject as ClassLoader
                                val s = loader.toString()
                                if (s.contains(MODULE_PACKAGE)) return
                                if (s.contains("com.tencent.") || s.contains("TinkerClassLoader") ||
                                    s.contains("DelegateLastClassLoader")
                                ) {
                                    if (patchLoaderCaptured.compareAndSet(false, true)) {
                                        XposedBridge.log("[QOdeep] 抢到补丁 ClassLoader: $s")
                                        teardown.run()
                                        doRealStartup(loader)
                                    }
                                }
                            }
                        })
                        if (unhook != null) {
                            synchronized(constructorUnhooks) {
                                if (closed.get()) unhook.unhook() else constructorUnhooks.add(unhook)
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                teardown.run()
                if (!patchLoaderCaptured.get()) {
                    XposedBridge.log("[QOdeep] 未捕获热更 ClassLoader，回退 BaseApplicationImpl")
                    doRealStartup(cl)
                }
            }
        })
    }

    /** 拿到真实宿主 ClassLoader 后只执行一次：挂 Application.onCreate */
    private fun doRealStartup(realLoader: ClassLoader) {
        if (!startupHooked.compareAndSet(false, true)) return
        unhookTemporary()
        Env.hostClassLoader = realLoader
        hookApplicationOnCreate(realLoader)
    }

    private fun hookApplicationOnCreate(cl: ClassLoader): XC_MethodHook.Unhook? {
        return try {
            val unhook = XposedHelpers.findAndHookMethod(
                "com.tencent.common.app.BaseApplicationImpl", cl, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (initialized.compareAndSet(false, true)) {
                            try {
                                onHostReady(param.thisObject as Context)
                            } catch (t: Throwable) {
                                XposedBridge.log("[QOdeep] 宿主初始化失败: $t")
                            }
                        }
                    }
                }
            )
            XposedBridge.log("[QOdeep] BaseApplicationImpl.onCreate hook 已安装 loader=${cl.javaClass.name}")
            unhook
        } catch (t: Throwable) {
            XposedBridge.log("[QOdeep] BaseApplicationImpl.onCreate hook 失败: $t")
            null
        }
    }

    /** Application 创建完毕：准备环境，走 DexKit 缓存/扫描分支，最后装设置页 hook。 */
    private fun onHostReady(hostContext: Context) {
        initEnv(hostContext)
        runCatching { Parasitics.init(hostContext) }
            .onFailure { XposedBridge.log("[QOdeep] 活动寄生初始化失败: $it") }
        DexKitCache.init()
        DexKitTaskRegistry.register(this)

        val cacheValid = DexKitCache.initCache() && DexKitCache.validateAllTasks()
        if (cacheValid) {
            XposedBridge.log("[QOdeep] DexKit 缓存有效，直接注入")
            installSettingHook()
        } else {
            XposedBridge.log("[QOdeep] DexKit 缓存缺失，开始查找方法")
            findWithProgressUi(hostContext) {
                runCatching { installSettingHook() }
                    .onFailure { XposedBridge.log("[QOdeep] 设置页 hook 安装失败: $it") }
            }
        }
    }

    /**
     * 带进度弹窗的后台扫描：缓存被删后重进 QQ 时，对齐其它模块「弹出查找提示」的体验——
     * 等宿主第一个 Activity 显示后在其上弹不可取消的对话框，实时刷新进度，扫完自动关闭。
     * 扫描快于首屏完成时则不弹，避免一闪而过。
     */
    private fun findWithProgressUi(hostContext: Context, onDone: () -> Unit) {
        val mainHandler = Handler(Looper.getMainLooper())
        var finished = false
        var dialog: android.app.AlertDialog? = null

        fun close() {
            finished = true
            mainHandler.post {
                runCatching { dialog?.dismiss() }
                dialog = null
            }
        }

        DexKitFinder.doFind {
            runCatching { onDone() }
                .onFailure { XposedBridge.log("[QOdeep] 设置页 hook 安装失败: $it") }
            close()
        }

        // registerActivityLifecycleCallbacks 在 Application 上，hostContext 是宿主 Application 实例
        val app = hostContext.applicationContext as? Application
        if (app == null) {
            // 拿不到 Application 实例就退化为静默扫描
            return
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var fired = false
            override fun onActivityResumed(activity: Activity) {
                if (fired || finished) return
                fired = true
                mainHandler.post {
                    if (finished) return@post
                    val d = android.app.AlertDialog.Builder(activity)
                        .setTitle("QOdeep")
                        .setMessage("准备开始查找...")
                        .setCancelable(false)
                        .create()
                    dialog = d
                    runCatching { d.show() }
                    // 轮询 DexKitFinder.progressKey 刷新进度文本；完成时关闭对话框
                    val tick = object : Runnable {
                        override fun run() {
                            val cur = dialog ?: return
                            if (finished) {
                                runCatching { cur.dismiss() }
                                dialog = null
                                return
                            }
                            cur.setMessage(DexKitFinder.progressKey ?: "正在查找方法中...")
                            mainHandler.postDelayed(this, 200)
                        }
                    }
                    mainHandler.post(tick)
                }
            }
            override fun onActivityCreated(p0: Activity, p1: Bundle?) {}
            override fun onActivityStarted(p0: Activity) {}
            override fun onActivityPaused(p0: Activity) {}
            override fun onActivityStopped(p0: Activity) {}
            override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
            override fun onActivityDestroyed(p0: Activity) {}
        })
    }

    private fun initEnv(hostContext: Context) {
        Env.hostContext = hostContext
        runCatching {
            Env.hostApkPath = hostContext.applicationInfo.sourceDir.orEmpty()
            val pm = hostContext.packageManager
            val appInfo = pm.getApplicationInfo(MODULE_PACKAGE, 0)
            Env.moduleNativeLibDir = appInfo.nativeLibraryDir.orEmpty()
            Env.moduleApkPath = appInfo.sourceDir.orEmpty()
            val externalDir = hostContext.getExternalFilesDir(null)?.parentFile
            Env.moduleDataDir = externalDir?.let { "${it.absolutePath}/QOdeep/" }
                ?: "${Environment.getExternalStorageDirectory().absolutePath}/Android/data/$QQ_PACKAGE/QOdeep/"
        }.onFailure {
            XposedBridge.log("[QOdeep] 初始化环境失败: $it")
        }
    }

    @Synchronized
    private fun addTemporary(unhook: XC_MethodHook.Unhook?) {
        if (unhook != null) temporaryUnhooks.add(unhook)
    }

    @Synchronized
    private fun unhookTemporary() {
        for (u in temporaryUnhooks) {
            try { u.unhook() } catch (_: Throwable) {}
        }
        temporaryUnhooks.clear()
    }

    // ---------------- 设置页注入 ----------------

    private fun installSettingHook() {
        val providerClass = resolveProviderClass()
            ?: throw ClassNotFoundException("SettingConfigProvider")
        val buildMethod = (providerClass.methods + providerClass.declaredMethods).distinct().firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == Context::class.java &&
                List::class.java.isAssignableFrom(m.returnType)
        } ?: throw NoSuchMethodException("列表构建方法未找到 in ${providerClass.name}")

        XposedBridge.hookMethod(buildMethod, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val context = param.args[0] as? Context ?: return
                    @Suppress("UNCHECKED_CAST")
                    val groups = param.result as? MutableList<Any?> ?: return
                    insertEntry(groups, context)
                } catch (t: Throwable) {
                    XposedBridge.log("[QOdeep] 插入入口失败: $t")
                }
            }
        })
        XposedBridge.log("[QOdeep] 设置页列表方法 hook 已安装: ${providerClass.name}.${buildMethod.name}")
    }

    /** 优先 DexKit 查找结果，失败回退到本版本反编译确认的硬编码类名。 */
    private fun resolveProviderClass(): Class<*>? =
        runCatching { DexKitCache.getClass("$tag->provider") }.getOrNull()
            ?: runCatching { Env.hostClassLoader.loadClass("com.tencent.mobileqq.setting.main.b") }.getOrNull()

    private fun insertEntry(groups: MutableList<Any?>, context: Context) {
        if (groups.isEmpty()) {
            XposedBridge.log("[QOdeep] 分组列表为空")
            return
        }

        val sectionClass = runCatching { DexKitCache.getClass("$tag->settingSection") }.getOrNull()
            ?: groups.firstOrNull { it != null }?.javaClass
        val itemClass = runCatching { DexKitCache.getClass("$tag->simpleItemProcessor") }.getOrNull()
            ?: findSimpleItemProcessorFromList(groups)
            ?: runCatching { Env.hostClassLoader.loadClass("com.tencent.mobileqq.setting.processor.i") }.getOrNull()

        if (sectionClass == null || itemClass == null) {
            XposedBridge.log("[QOdeep] 未找到分组类或条目类")
            return
        }

        val clickLambda = makeClickLambda(context)
        val processor = createProcessor(itemClass, context, clickLambda)
        val newSection = createSection(sectionClass, processor)

        // index 0 是 QQ 原生搜索框分组（setting.main.processor.t，与账号与安全条目同包名，
        // findAccountIndex 会误命中），因此插入位置至少为 1，保证搜索框保持在页面顶部。
        val index = findAccountIndex(groups, sectionClass).takeIf { it > 0 } ?: 1
        val insertAt = if (index <= groups.size) index else groups.size
        groups.add(insertAt, newSection)
        XposedBridge.log("[QOdeep] 入口已插入 (index=$insertAt, section=${sectionClass.name}, item=${itemClass.name})")
    }

    /** 「账号与安全」分组：条目类位于 setting.main.processor 包的第一组。 */
    private fun findAccountIndex(groups: List<Any?>, sectionClass: Class<*>): Int {
        val itemsMethod = runCatching {
            (sectionClass.methods + sectionClass.declaredMethods).distinct().firstOrNull {
                it.returnType == List::class.java && it.parameterTypes.isEmpty()
            }
        }.getOrNull() ?: return -1
        itemsMethod.isAccessible = true

        for ((idx, group) in groups.withIndex()) {
            if (group == null) continue
            val items = runCatching { itemsMethod.invoke(group) as? List<*> }.getOrNull() ?: continue
            if (items.any { it != null && it.javaClass.`package`?.name == "com.tencent.mobileqq.setting.main.processor" }) {
                return idx
            }
        }
        return -1
    }

    private fun createProcessor(processorClass: Class<*>, context: Context, clickLambda: Any): Any {
        val ctor = processorClass.declaredConstructors.firstOrNull { cons ->
            val p = cons.parameterTypes
            p.size >= 3 && p[0] == Context::class.java && p[1] == Int::class.javaPrimitiveType &&
                CharSequence::class.java.isAssignableFrom(p[2])
        } ?: throw NoSuchMethodException("SimpleItemProcessor 构造器未找到 in ${processorClass.name}")

        ctor.isAccessible = true
        val params = ctor.parameterTypes
        val args = mutableListOf<Any?>()
        args.add(context)
        args.add(ENTRY_TYPE)
        args.add(ENTRY_TITLE)
        for (i in 3 until params.size) {
            when {
                params[i] == Int::class.javaPrimitiveType -> args.add(0)
                params[i] == String::class.java -> args.add("")
                else -> args.add(null)
            }
        }
        val instance = ctor.newInstance(*args.toTypedArray())

        // 设置点击回调：优先 Function0 单参方法，其次 Function1
        val methods = (processorClass.methods + processorClass.declaredMethods).distinct()
        var set = false
        for (m in methods) {
            if (m.parameterTypes.size == 1 && m.parameterTypes[0].name == "kotlin.jvm.functions.Function0") {
                m.isAccessible = true
                m.invoke(instance, clickLambda)
                set = true
                break
            }
        }
        if (!set) {
            for (m in methods) {
                if (m.parameterTypes.size == 1 && m.parameterTypes[0].name == "kotlin.jvm.functions.Function1") {
                    m.isAccessible = true
                    m.invoke(instance, makeFunction1Lambda(context))
                    break
                }
            }
        }
        return instance
    }

    private fun createSection(sectionClass: Class<*>, processor: Any): Any {
        val ctor = sectionClass.declaredConstructors.firstOrNull { cons ->
            cons.parameterTypes.isNotEmpty() && cons.parameterTypes[0] == List::class.java
        } ?: throw NoSuchMethodException("SettingGroupProcessor 构造器未找到 in ${sectionClass.name}")

        ctor.isAccessible = true
        val params = ctor.parameterTypes
        val args = mutableListOf<Any?>()
        args.add(listOf(processor))
        var charSeqIndex = 0
        for (i in 1 until params.size) {
            when {
                CharSequence::class.java.isAssignableFrom(params[i]) -> {
                    args.add(if (charSeqIndex == 0) ENTRY_TITLE else BOTTOM_TITLE)
                    charSeqIndex++
                }
                params[i] == Int::class.javaPrimitiveType -> args.add(0)
                else -> args.add(null)
            }
        }
        return ctor.newInstance(*args.toTypedArray())
    }

    /** 从分组列表现有条目里推断 SimpleItemProcessor 类（DexKit 未命中时兜底）。 */
    private fun findSimpleItemProcessorFromList(groups: List<Any?>): Class<*>? {
        for (group in groups) {
            if (group == null) continue
            val result = runCatching {
                val listMethod = group.javaClass.declaredMethods.firstOrNull {
                    it.returnType == List::class.java && it.parameterTypes.isEmpty()
                }
                listMethod?.isAccessible = true
                val items = listMethod?.invoke(group) as? List<*>
                if (items != null) {
                    for (item in items) {
                        if (item == null) continue
                        val cls = item.javaClass
                        if (isSimpleItemProcessor(cls)) return@runCatching cls
                        val superCls = cls.superclass
                        if (superCls != null && isSimpleItemProcessor(superCls)) {
                            val found = findConcreteItemProcessorClass(superCls)
                            if (found != null) return@runCatching found
                        }
                    }
                }
                null
            }
            if (result.isSuccess && result.getOrNull() != null) return result.getOrThrow()
        }
        return null
    }

    private fun isSimpleItemProcessor(clazz: Class<*>): Boolean = runCatching {
        val pkg = clazz.`package`?.name
        if (pkg == null || !pkg.startsWith("com.tencent.mobileqq.setting.processor")) {
            false
        } else {
            clazz.declaredConstructors.any { cons ->
                val p = cons.parameterTypes
                p.size >= 3 && p[0] == Context::class.java && p[1] == Int::class.javaPrimitiveType &&
                    CharSequence::class.java.isAssignableFrom(p[2])
            }
        }
    }.getOrDefault(false)

    /** 列表里可能是基类，尝试同包下常见混淆名找具体子类。 */
    private fun findConcreteItemProcessorClass(baseClass: Class<*>): Class<*>? = runCatching {
        val pkg = baseClass.`package`?.name
        if (pkg == null) {
            null
        } else {
            var found: Class<*>? = null
            for (name in listOf("i", "h", "g", "f", "e", "d", "j", "k")) {
                runCatching {
                    val cls = Env.hostClassLoader.loadClass("$pkg.$name")
                    if (baseClass.isAssignableFrom(cls) && isSimpleItemProcessor(cls)) {
                        found = cls
                    }
                }
                if (found != null) break
            }
            found
        }
    }.getOrNull()

    // ---------------- 点击回调与打开模块页 ----------------

    private fun makeClickLambda(context: Context): Any {
        val function0Class = Env.hostClassLoader.loadClass("kotlin.jvm.functions.Function0")
        val unit = Env.hostClassLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        return Proxy.newProxyInstance(Env.hostClassLoader, arrayOf(function0Class)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> { openSettings(context); unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun makeFunction1Lambda(context: Context): Any {
        val function1Class = Env.hostClassLoader.loadClass("kotlin.jvm.functions.Function1")
        val unit = Env.hostClassLoader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        return Proxy.newProxyInstance(Env.hostClassLoader, arrayOf(function1Class)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> { openSettings(context); unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> null
            }
        }
    }

    private fun openSettings(context: Context) {
        try {
            Parasitics.ensureInitialized(context)
            // 单例：已有模块页任务在后台就直接带回前台，避免 MULTIPLE_TASK 每次点击都新建任务
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val existing = am?.let { findModuleTask(it) } ?: -1
            if (existing >= 0) {
                runCatching { am?.moveTaskToFront(existing, 0) }
                return
            }
            // 用 QQ 进程内的 context 构造 Intent，组件包名会保持为宿主包名，
            // 从而避开 QQ 客户端「离开 QQ」检测；真正类名由 Parasitics 在 binder 层换壳。
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                // 逃出 QQ 设置页的嵌入分屏：新任务不参与 split，直接全屏、无「从右边抖到左边」；
                // 上面的复用检查保证不会因 MULTIPLE_TASK 堆出多个任务。
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                // 寄生页面与 MainHook 不同 ClassLoader，Env 字段不通，把环境随 Intent 带过去
                putExtra(SettingsActivity.ENV_DATA_DIR, Env.moduleDataDir)
                putExtra(SettingsActivity.ENV_NATIVE_LIB_DIR, Env.moduleNativeLibDir)
                putExtra(SettingsActivity.ENV_HOST_APK, Env.hostApkPath)
            }
            context.startActivity(intent, buildFullscreenOptions(context))
            XposedBridge.log("[QOdeep] 已在 QQ 内打开设置页（寄生）")
        } catch (t: Throwable) {
            XposedBridge.log("[QOdeep] 打开设置页失败: $t")
        }
    }

    /** 按 taskDescription 标记查找已存在的模块页任务 id，找不到返回 -1。 */
    private fun findModuleTask(am: ActivityManager): Int {
        val tasks = runCatching { am.getRunningTasks(64) }.getOrNull() ?: return -1
        for (t in tasks) {
            // TaskDescription.label 在不同 compileSdk 上字段名有差异，反射读取最稳
            val label = runCatching {
                val desc = t.javaClass.getField("description").get(t) ?: return@runCatching null
                desc.javaClass.getMethod("getLabel").invoke(desc)?.toString()
            }.getOrNull()
            if (label == MODULE_TASK_LABEL) return t.id
        }
        return -1
    }

    /**
     * 构造「瞬开 + 逃出 QQ 嵌入分屏」的启动选项：
     * 1. makeCustomAnimation(0, 0) —— 去掉转场动画，点击后页面直接出现（对齐 FunBox 观感）；
     * 2. 显式声明全屏窗口模式 —— 系统的嵌入逻辑遇到明确窗口模式的启动会跳过嵌入；
     * 3. alwaysExpand（Android 13+ 隐藏 API）—— 声明此启动必须铺满整屏。
     * 两者都失败时返回 null，退回普通启动。
     */
    private fun buildFullscreenOptions(context: Context): android.os.Bundle? {
        val opts = android.app.ActivityOptions.makeCustomAnimation(context, 0, 0)
        runCatching {
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType!!)
                .invoke(opts, 1) // WindowConfiguration.WINDOWING_MODE_FULLSCREEN
        }
        runCatching {
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchAlwaysExpand")
                .invoke(opts)
        }
        return runCatching { opts.toBundle() }.getOrNull()
    }

    companion object {
        private val initialized = AtomicBoolean(false)
        private val startupHooked = AtomicBoolean(false)
        private val patchLoaderCaptured = AtomicBoolean(false)
        private val temporaryUnhooks = mutableListOf<XC_MethodHook.Unhook>()
        private const val QQ_PACKAGE = "com.tencent.mobileqq"
        private const val MODULE_PACKAGE = "com.qosed.qodeep"
        private const val ENTRY_TITLE = "QOdeep"
        private const val BOTTOM_TITLE = ""
        private const val ENTRY_TYPE = 100
        private const val MODULE_TASK_LABEL = "QOdeep模块页"
    }
}
