package com.qosed.qodeep

import android.content.Context
import android.content.Intent
import android.os.Environment
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

class MainHook : IXposedHookLoadPackage, DexKitTask {

    override val tag: String = "SettingInject"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != QQ_PACKAGE) return
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
        queries["provider"] = FindClass().apply {
            searchPackages("com.tencent.mobileqq.setting.main")
            matcher { superClass("com.tencent.mobileqq.setting.processor.SettingConfigProvider") }
        }
        queries["simpleItemProcessor"] = FindClass().apply {
            searchPackages("com.tencent.mobileqq.setting.processor")
            matcher { usingStrings("SimpleItemProcessor") }
        }
        queries["settingSection"] = FindClass().apply {
            searchPackages("com.tencent.mobileqq.setting.processor")
            matcher { usingStrings("itemProcessors") }
        }
        for (i in 4..16) {
            queries["slot$i"] = FindClass().apply {
                searchPackages("com.tencent.mobileqq.setting")
                matcher { usingStrings("QOdeep.Slot.$i") }
            }
        }
        return queries
    }

    // ---------------- 启动 ----------------

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
            DexKitFinder.doFind {
                runCatching { installSettingHook() }
                    .onFailure { XposedBridge.log("[QOdeep] 设置页 hook 安装失败: $it") }
            }
        }
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

        val index = findAccountIndex(groups, sectionClass).takeIf { it > 0 } ?: 1
        val insertAt = if (index <= groups.size) index else groups.size
        groups.add(insertAt, newSection)
        XposedBridge.log("[QOdeep] 入口已插入 (index=$insertAt, section=${sectionClass.name}, item=${itemClass.name})")
    }

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
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                putExtra(SettingsActivity.ENV_DATA_DIR, Env.moduleDataDir)
                putExtra(SettingsActivity.ENV_NATIVE_LIB_DIR, Env.moduleNativeLibDir)
                putExtra(SettingsActivity.ENV_HOST_APK, Env.hostApkPath)
            }
            context.startActivity(intent)
            XposedBridge.log("[QOdeep] 已在 QQ 内打开设置页（寄生）")
        } catch (t: Throwable) {
            XposedBridge.log("[QOdeep] 打开设置页失败: $t")
        }
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
    }
}
