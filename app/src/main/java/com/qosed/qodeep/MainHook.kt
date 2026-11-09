package com.qosed.qodeep

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import android.widget.ImageView
import android.widget.TextView
import com.qosed.qodeep.R
import com.qosed.qodeep.chat.ChatFeaturesInstaller
import com.qosed.qodeep.levelboost.MoodConfig
import com.qosed.qodeep.levelboost.MoodScheduler
import com.qosed.qodeep.liquidglass.LiquidGlassModule
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.base.BaseFinder
import org.luckypray.dexkit.query.matchers.MethodMatcher
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
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit, DexKitTask {

    override val tag: String = "SettingInject"

    /**
     * Zygote 阶段（早于任何 app 进程 fork）：StartupParam.modulePath 是 LSPosed 传入的
     * 模块 APK 绝对路径，这是唯一不依赖 PackageManager 的权威来源——QQ 进程里
     * getApplicationInfo/getPackageInfo 因 Android 11+ 包可见性过滤必然抛
     * NameNotFoundException，ClassLoder 内存加载又挖不到文件路径（实测均失败）。
     * Env 在 zygote 赋值后随 fork 继承到 QQ 进程，后续 initEnv 无需再找。
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        runCatching { Env.moduleApkPath = startupParam.modulePath.orEmpty() }
            .onFailure { XposedBridge.log("[QOdeep] initZygote 获取 modulePath 失败: $it") }
    }

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
        // 原生表情包下载判定方法：com.tencent.qqnt.msg 包下 (PicElement)->boolean、
        // 方法体调用 IZplanOffLineApi.isZPlanMsgOffLine 的方法（类/方法名混淆，靠特征定位）
        queries["pic_download_method"] = FindMethod().apply {
            searchPackages("com.tencent.qqnt.msg")
            matcher {
                returnType("boolean")
                paramTypes("com.tencent.qqnt.kernel.nativeinterface.PicElement")
                addInvoke(MethodMatcher().apply {
                    declaredClass("com.tencent.qqnt.msg.api.IZplanOffLineApi")
                    name("isZPlanMsgOffLine")
                })
            }
        }
        // 占位任务：把查找任务数对齐到 16 个（对应后续 16 个功能），
        // 每个槽位用当前必然不存在的字符串占位，后续加功能时逐个替换成真实查询。
        for (i in 4..16) {
            queries["slot$i"] = FindClass().apply {
                searchPackages("com.tencent.mobileqq.setting")
                matcher { usingStrings("QOdeep.Slot.$i") }
            }
        }
        return queries
    }

    // 真实查询只有 3 个（provider / simpleItemProcessor / settingSection）。
    // slot4~slot16 是占位，扫不到是正常的，不应计入「缓存完整」判断。
    override fun realQueryKeys(): Set<String> =
        setOf("provider", "simpleItemProcessor", "settingSection", "pic_download_method")

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
        runCatching { MoodScheduler.start(hostContext) }
            .onFailure { XposedBridge.log("[QOdeep] 定时说说调度启动失败: $it") }
        // QQ 启动后按配置恢复保活（initEnv 已写好 moduleDataDir，MoodConfig 此时可读）
        runCatching { KeepAlive.refresh(hostContext) }
            .onFailure { XposedBridge.log("[QOdeep] QQ保活恢复失败: $it") }
        runCatching { Parasitics.init(hostContext) }
            .onFailure { XposedBridge.log("[QOdeep] 活动寄生初始化失败: $it") }
        runCatching { hookEmbeddingRules(Env.hostClassLoader) }
            .onFailure { XposedBridge.log("[QOdeep] 分屏规则过滤挂载失败: $it") }
        runCatching { installEntryStyleHook() }
            .onFailure { XposedBridge.log("[QOdeep] 入口样式 hook 安装失败: $it") }
        // 主页底栏液态玻璃：只在 QQ 启动时按开关决定是否挂载整条 hook 链。
        // 关闭后重启 QQ 即彻底恢复原生底栏；不做运行时卸载（reparent / padding 无法安全回滚）。
        if (MoodConfig.getBoolean(KEY_LIQUID_GLASS_BAR, false)) {
            runCatching { LiquidGlassModule.install(Env.hostClassLoader) }
                .onFailure { XposedBridge.log("[QOdeep] 液态玻璃底栏挂载失败: $it") }
        }
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

        // 聊天功能：后台线程逐项安装，单项失败不影响其余功能
        runCatching { ChatFeaturesInstaller.install(hostContext.packageName) }
            .onFailure { XposedBridge.log("[QOdeep] 聊天功能安装失败: $it") }
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
                    // 白色圆角卡片 + 标题 + 当前查找 key。
                    // 默认 AlertDialog 会被 QQ 深色主题渲染成黑底，观感突兀，改用自绘 view。
                    val d = buildProgressCard(activity)
                    dialog = d
                    runCatching { d.show() }
                    // 轮询 DexKitFinder.progressKey 刷新进度文本；完成时关闭对话框
                    val msg = d.window?.findViewById<TextView>(MSG_ID)
                    val tick = object : Runnable {
                        override fun run() {
                            val cur = dialog ?: return
                            if (finished) {
                                runCatching { cur.dismiss() }
                                dialog = null
                                return
                            }
                            msg?.text = DexKitFinder.progressKey ?: "正在查找方法中..."
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

    /** 进度卡片：白底圆角、黑色粗体标题、灰色当前查找 key，无默认对话框黑框。 */
    private fun buildProgressCard(activity: Activity): android.app.AlertDialog {
        val dm = activity.resources.displayMetrics
        val dp = { v: Int -> (v * dm.density).toInt() }
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
        }
        val title = android.widget.TextView(activity).apply {
            text = "QOdeep 查找方法中"
            setTextColor(0xFF222222.toInt())
            textSize = 17f
            paint.isFakeBoldText = true
            id = android.view.View.generateViewId()
        }
        val msg = android.widget.TextView(activity).apply {
            text = "准备开始查找..."
            setTextColor(0xFF8A8A8A.toInt())
            textSize = 13f
            id = MSG_ID
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(title)
        card.addView(msg)
        return android.app.AlertDialog.Builder(activity).setView(card).setCancelable(false).create()
            .apply {
                window?.run {
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setDimAmount(0.25f)
                }
            }
    }

    private fun initEnv(hostContext: Context) {
        Env.hostContext = hostContext
        // 1) 宿主 APK 路径 + 模块数据目录：只依赖 hostContext，与模块包可见性无关。
        //    之前把这两行和 getApplicationInfo 放在同一个 runCatching 里，
        //    getApplicationInfo 一旦抛 NameNotFoundException（寄生环境常见）就会
        //    连带 moduleDataDir 也不赋值 → 缓存路径变成相对路径 → 永远写不进磁盘。
        runCatching {
            Env.hostApkPath = hostContext.applicationInfo.sourceDir.orEmpty()
        }.onFailure { XposedBridge.log("[QOdeep] 宿主 APK 路径获取失败: $it") }

        runCatching {
            val externalDir = hostContext.getExternalFilesDir(null)?.parentFile
            Env.moduleDataDir = externalDir?.let { "${it.absolutePath}/QOdeep/" }
                ?: "${Environment.getExternalStorageDirectory().absolutePath}/Android/data/$QQ_PACKAGE/QOdeep/"
        }.onFailure { XposedBridge.log("[QOdeep] 模块数据目录获取失败: $it") }

        // 2) 模块 APK 路径：codeSource 在 LSPosed 环境返回空（实测），改为从加载模块
        //    类的 ClassLoader 里挖 BaseDexClassLoader.pathList.dexElements[*].path——
        //    LSPosed 用 DexClassLoader 加载模块 APK，elements 里就有模块 APK 绝对路径，
        //    完全不依赖 PackageManager，绕开包可见性过滤的 NameNotFoundException。
        if (Env.moduleApkPath.isEmpty()) {
            Env.moduleApkPath = runCatching {
                val cl = MainHook::class.java.classLoader ?: return@runCatching ""
                val pathList = cl.javaClass.superclass
                    .getDeclaredField("pathList").apply { isAccessible = true }.get(cl)
                    ?: return@runCatching ""
                val elements = pathList.javaClass
                    .getDeclaredField("dexElements").apply { isAccessible = true }.get(pathList) as? Array<*>
                elements.orEmpty().mapNotNull { el ->
                    runCatching {
                        (el ?: return@mapNotNull null).javaClass
                            .getDeclaredField("path").apply { isAccessible = true }.get(el) as? String
                    }.getOrNull()
                }.firstOrNull { it?.contains("qodeep", ignoreCase = true) == true }.orEmpty()
            }.getOrDefault("")
            XposedBridge.log("[QOdeep] ClassLoader 推导模块 APK 路径: ${Env.moduleApkPath.ifEmpty { "(空)" }}")
        }
        // 3) 模块 native lib 目录：优先 getApplicationInfo，失败用模块 APK 路径推导。
        runCatching {
            val pm = hostContext.packageManager
            val appInfo = pm.getApplicationInfo(MODULE_PACKAGE, 0)
            Env.moduleNativeLibDir = appInfo.nativeLibraryDir.orEmpty()
            if (Env.moduleApkPath.isEmpty()) Env.moduleApkPath = appInfo.sourceDir.orEmpty()
        }.onFailure {
            XposedBridge.log("[QOdeep] getApplicationInfo 获取模块信息失败，用 codeSource 兜底: $it")
        }
        if (Env.moduleNativeLibDir.isEmpty() && Env.moduleApkPath.isNotEmpty()) {
            runCatching {
                val apkDir = Env.moduleApkPath.substringBeforeLast('/')
                Env.moduleNativeLibDir = "$apkDir/lib/arm64"
            }.onFailure { XposedBridge.log("[QOdeep] native lib 目录推导失败: $it") }
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

    // ---------------- 嵌入分屏规则过滤 ----------------

    private val ruleFilterLogged = AtomicBoolean(false)

    /**
     * QQ 设置页的 Activity 嵌入分屏（右侧半屏面板）规则由 androidx.window 在 QQ 进程内注册。
     * hook 规则的读/写入口，剔除会把模块壳 Activity 抓进半屏的 SplitPairRule，
     * 让模块页以「同任务 + 直接全屏盖在 QQ 上」的方式打开（不产生第二个任务卡片、无抖动）。
     */
    private fun hookEmbeddingRules(loader: ClassLoader) {
        var hooked = 0
        for (name in arrayOf(
            "androidx.window.embedding.RuleController",   // androidx.window 1.2+
            "androidx.window.embedding.SplitController"   // 旧版 1.0/1.1
        )) {
            val cls = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            for (m in cls.declaredMethods) {
                val isSetter = m.name == "setRules" || m.name == "setSplitRules"
                val isGetter = m.parameterCount == 0 && (m.name == "getRules" || m.name == "getSplitRules")
                val isRegistrar = m.name == "registerRule" && m.parameterCount == 1
                if (!isSetter && !isGetter && !isRegistrar) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (isRegistrar) {
                                if (param.args[0] != null && isBlockingRule(param.args[0])) {
                                    param.setResult(null) // 吞掉会分屏壳 Activity 的规则注册
                                }
                            } else {
                                param.args.forEachIndexed { i, a ->
                                    (a as? Set<*>)?.let { param.args[i] = filterEmbeddingRules(it) }
                                }
                            }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.result as? Set<*>)?.let { param.result = filterEmbeddingRules(it) }
                        }
                    })
                    hooked++
                }
            }
        }
        XposedBridge.log("[QOdeep] 嵌入分屏规则过滤已挂载，hook 方法数=$hooked")
    }

    /** 剔除会把壳 Activity 抓进分屏的规则；没有命中时原集合原样返回。 */
    private fun filterEmbeddingRules(rules: Set<*>): Set<*> {
        var changed = false
        val out = LinkedHashSet<Any>(rules.size)
        for (r in rules) {
            if (r != null && isBlockingRule(r)) {
                changed = true
                if (ruleFilterLogged.compareAndSet(false, true)) {
                    XposedBridge.log("[QOdeep] 剔除分屏规则: ${r.javaClass.simpleName} ${ruleDesc(r)}")
                }
                continue
            }
            if (r != null) out.add(r)
        }
        return if (changed) out else rules
    }

    private fun isBlockingRule(rule: Any): Boolean {
        val cls = rule.javaClass
        val primary = runCatching { cls.getMethod("getPrimaryActivityName").invoke(rule) }.getOrNull()
        val secondary = runCatching { cls.getMethod("getSecondaryActivityName").invoke(rule) }.getOrNull()
        val p = compName(primary)
        val s = compName(secondary)
        // 精确命中：secondary 就是我们可能借用的壳 Activity
        if (Parasitics.isShellActivity(s)) return true
        // 通配 secondary（未指定 secondary）：设置页发起的分屏会把一切子页（含壳）抓进右半屏
        if (s == null && p != null && p.lowercase().contains("setting")) return true
        return false
    }

    private fun ruleDesc(rule: Any): String {
        val p = runCatching { rule.javaClass.getMethod("getPrimaryActivityName").invoke(rule) }.getOrNull()
        val s = runCatching { rule.javaClass.getMethod("getSecondaryActivityName").invoke(rule) }.getOrNull()
        return "primary=${compName(p)} secondary=${compName(s)}"
    }

    /** 兼容 androidx.window 的 ComponentName 与 android 的 ComponentName。 */
    private fun compName(any: Any?): String? = runCatching {
        any!!.javaClass.getMethod("getClassName").invoke(any)?.toString()
    }.getOrNull()

    // ---------------- 设置页注入 ----------------

    /** 正在播放旋转动画的入口头像，复用绑定到其它头像时据此停掉旧动画。 */
    private val rotatingIcons = java.util.WeakHashMap<ImageView, Boolean>()

    /**
     * 记录被放大过的 ImageView 的原尺寸，供 RecyclerView 复用该 View 绑定其它条目时还原，
     * 避免放大后的尺寸残留到别的设置行上。
     */
    private val enlargedIconOriginalBounds = java.util.WeakHashMap<ImageView, IntArray>()

    /**
     * 入口保持 QQ 原生白卡，但头像放大到模块图标同档尺寸（36dp）并持续旋转。
     * 旋转角度锚定系统时钟（IconSpin），与模块主页头像同步转、方向一致。
     */
    private fun installEntryStyleHook() {
        // 通过 ImageView.setImageResource 精准命中入口头像（db_fg）：放大 + 挂无限旋转动画。
        val iconHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val iv = param.thisObject as? ImageView ?: return
                val resId = param.args[0] as? Int ?: return
                runCatching {
                    val lp = iv.layoutParams ?: return@runCatching
                    if (resId == R.drawable.db_fg) {
                        val density = iv.resources.displayMetrics.density
                        val target = (ENTRY_ICON_SIZE_DP * density + 0.5f).toInt()
                        if (lp.width >= target && lp.height >= target) {
                            startEntryIconRotation(iv)
                            return@runCatching
                        }
                        enlargedIconOriginalBounds[iv] = intArrayOf(lp.width, lp.height)
                        if (lp.width > 0) lp.width = target
                        if (lp.height > 0) lp.height = target
                        iv.layoutParams = lp
                        startEntryIconRotation(iv)
                    } else {
                        // 条目被复用绑定其它图标：还原尺寸、停掉旋转，避免残留到别的设置行。
                        if (rotatingIcons.remove(iv) == true) {
                            iv.animate().cancel()
                            iv.rotation = 0f
                        }
                        val saved = enlargedIconOriginalBounds.remove(iv) ?: return@runCatching
                        if (saved[0] > 0) lp.width = saved[0]
                        if (saved[1] > 0) lp.height = saved[1]
                        iv.layoutParams = lp
                    }
                }.onFailure { XposedBridge.log("[QOdeep] 入口图标处理失败: $it") }
            }
        }
        XposedBridge.hookMethod(
            ImageView::class.java.getMethod("setImageResource", Int::class.javaPrimitiveType),
            iconHook
        )

        XposedBridge.log("[QOdeep] 入口头像(放大+旋转) hook 已安装")
    }

    /**
     * 给入口头像挂无限旋转动画。只在「尚无线程在驱动该头像」时启动；每帧用 Choreographer
     * 依据系统时钟设置 rotation，角度锚定 IconSpin 绝对时钟，与模块主页头像严格同步。
     */
    private fun startEntryIconRotation(iv: ImageView) {
        if (rotatingIcons[iv] == true) return
        rotatingIcons[iv] = true
        iv.post {
            if (!iv.isAttachedToWindow) {
                rotatingIcons.remove(iv)
                return@post
            }
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (rotatingIcons[iv] != true || !iv.isAttachedToWindow) {
                        rotatingIcons.remove(iv)
                        return
                    }
                    iv.rotation = IconSpin.currentDegrees()
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(callback)
        }
    }

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

    /** 列表里已有标题为入口名的条目就不再插，防止宿主复用同一份列表时叠加出多个入口。 */
    private fun containsOurEntry(groups: List<Any?>): Boolean {
        for (g in groups) {
            if (g == null) continue
            val itemsMethod = runCatching {
                (g.javaClass.methods + g.javaClass.declaredMethods).distinct().firstOrNull {
                    it.returnType == List::class.java && it.parameterTypes.isEmpty()
                }
            }.getOrNull() ?: continue
            runCatching { itemsMethod.isAccessible = true }
            val items = runCatching { itemsMethod.invoke(g) as? List<*> }.getOrNull() ?: continue
            for (item in items) {
                if (item == null) continue
                val hit = item.javaClass.declaredFields.any { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(item)
                        v is CharSequence && v.toString() == ENTRY_TITLE
                    }.getOrDefault(false)
                }
                if (hit) return true
            }
        }
        return false
    }

    private fun insertEntry(groups: MutableList<Any?>, context: Context) {
        if (groups.isEmpty()) {
            XposedBridge.log("[QOdeep] 分组列表为空")
            return
        }
        if (containsOurEntry(groups)) return

        // 注入模块资源到宿主 Resources：条目渲染时 QQ 会用自己的 Resources 解析
        // 图标资源 ID（R.drawable.db），不注入的话同一 ID 在 QQ 资源表里对应别的图，
        // 入口图标会显示成错误的图（实测为一张蓝色方块图）。
        runCatching { Parasitics.injectModuleResourcesNow(context.resources, Env.moduleApkPath) }
            .onFailure { XposedBridge.log("[QOdeep] 注入模块资源失败: $it") }

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
        // 第一个 Int 参数是图标资源 ID，其余 Int 填 0
        var intCount = 0
        for (i in 3 until params.size) {
            when {
                params[i] == Int::class.javaPrimitiveType -> {
                    // 用完整圆形头像 PNG（db_fg）而非 adaptive-icon（db）：
                    // adaptive-icon 渲染时套圆形 mask 只露中央约 2/3，头像会被放大裁切；
                    // 直接给整幅 PNG 才能显示全部样貌。桌面启动器图标仍用 db 不变。
                    if (intCount == 0) args.add(R.drawable.db_fg) else args.add(0)
                    intCount++
                }
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
            // 用 QQ 进程内的 context 构造 Intent，组件包名会保持为宿主包名，
            // 从而避开 QQ 客户端「离开 QQ」检测；真正类名由 Parasitics 在 binder 层换壳。
            // 同任务启动：页面直接全屏盖在 QQ 上面，最近任务里不会多出卡片；
            // 曾导致半屏抖动的 QQ 嵌入分屏规则已由 hookEmbeddingRules 在源头过滤。
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    // 系统级禁用转场：真正启动的是 QQ 的壳 Activity，它的主题是右侧滑入的
                    // 半屏面板样式。Parasitics 换壳时会原样复制 flags，这个 flag 能传到
                    // system_server，比起传 0 资源 ID 的 ActivityOptions 更可靠（部分 ROM
                    // 会把 0 当无效值回退到壳主题默认动画，于是仍能看见从右边滑进来）。
                    Intent.FLAG_ACTIVITY_NO_ANIMATION)
                // 寄生页面与 MainHook 不同 ClassLoader，Env 字段不通，把环境随 Intent 带过去
                putExtra(SettingsActivity.ENV_DATA_DIR, Env.moduleDataDir)
                putExtra(SettingsActivity.ENV_NATIVE_LIB_DIR, Env.moduleNativeLibDir)
                putExtra(SettingsActivity.ENV_HOST_APK, Env.hostApkPath)
                putExtra(SettingsActivity.ENV_MODULE_APK, Env.moduleApkPath)
            }
            // 同时持久化一份环境，供寄生页在 Activity 被系统重建/进程重启后 Intent 丢失 extra 时兜底恢复
            runCatching {
                context.getSharedPreferences("qodeep_env", Context.MODE_PRIVATE).edit()
                    .putString("dataDir", Env.moduleDataDir)
                    .putString("nativeLibDir", Env.moduleNativeLibDir)
                    .putString("hostApk", Env.hostApkPath)
                    .putString("moduleApk", Env.moduleApkPath)
                    .apply()
            }
            context.startActivity(intent)
            // 双保险：若点击源是 Activity，再显式去一次转场，确保「点一下画面直接变、无跳转感」
            runCatching {
                if (context is Activity) {
                    context.overridePendingTransition(0, 0)
                }
            }
            XposedBridge.log("[QOdeep] 已在 QQ 内打开设置页（寄生）")
        } catch (t: Throwable) {
            XposedBridge.log("[QOdeep] 打开设置页失败: $t")
        }
    }

    /**
     * 构造「瞬开」启动选项：仅 makeCustomAnimation(0, 0) 去掉转场动画，
     * 点击后页面直接出现。
     *
     * 不再用 setLaunchWindowingMode(FULLSCREEN)/setLaunchAlwaysExpand 强制窗口模式：
     * 在 QQ 嵌入分屏环境下，这两个隐藏 API 会让系统先把 Activity 按半屏面板布局、
     * 再切换到全屏，造成「抖动一下再进入」。分屏问题已由 hookEmbeddingRules 在
     * 源头过滤（剔除会抓壳 Activity 的 SplitPairRule），无需在这里二次强制。
     *
     * 当前不启用：0 资源 ID 的自定义动画在部分 ROM 上会被判为无效、回退到壳主题的
     * 右侧滑入。改用 Intent.FLAG_ACTIVITY_NO_ANIMATION（系统级），此函数保留作备用。
     */
    private fun buildFullscreenOptions(context: Context): android.os.Bundle? =
        runCatching {
            android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        }.getOrNull()

    companion object {
        /** 主页底栏液态玻璃开关，默认关闭；开启并重启 QQ 后才挂载宿主 hook。 */
        const val KEY_LIQUID_GLASS_BAR = "liquid_glass_tab_bar"

        private val initialized = AtomicBoolean(false)
        private val startupHooked = AtomicBoolean(false)
        private val patchLoaderCaptured = AtomicBoolean(false)
        private val temporaryUnhooks = mutableListOf<XC_MethodHook.Unhook>()
        private const val QQ_PACKAGE = "com.tencent.mobileqq"
        private const val MODULE_PACKAGE = "com.qosed.qodeep"
        private const val ENTRY_TITLE = "豆包"
        private const val BOTTOM_TITLE = "豆包"
        private const val ENTRY_TYPE = 100
        /** 入口头像的显示尺寸（dp），对齐其它模块图标的观感。 */
        private const val ENTRY_ICON_SIZE_DP = 36
        // 进度卡片里"当前查找 key"文本的 view id（自绘 AlertDialog 内容用）
        private const val MSG_ID = 0x60de0001
    }
}
