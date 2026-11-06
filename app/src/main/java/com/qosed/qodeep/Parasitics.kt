package com.qosed.qodeep

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object Parasitics {

    private const val ACTIVITY_PROXY_INTENT = "ACTIVITY_PROXY_INTENT"

    private val ACTIVITY_CANDIDATES = arrayOf(
        "com.tencent.mobileqq.activity.photo.CameraPreviewActivity",
        "com.tencent.mobileqq.activity.QQSettingSettingActivity",
        "com.tencent.mobileqq.activity.QPublicFragmentActivity",
        "com.tencent.mobileqq.activity.GeneralSettingActivity",
        "com.tencent.mobileqq.activity.NotificationActivity"
    )

    private val moduleLoader: ClassLoader get() = Parasitics::class.java.classLoader!!

    private var initialized = false
    private var stubActivity: String? = null
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val registry = HashMap<String, Class<out Activity>>()

    // ---------------- 动态活动注册 ----------------

    @Synchronized
    private fun register(clazz: Class<*>) {
        registry[clazz.name] = clazz as Class<out Activity>
    }

    private fun containsActivity(className: String?): Boolean =
        className != null && registry.containsKey(className)

    @Suppress("UNCHECKED_CAST")
    private fun getActivityClass(className: String): Class<out Activity>? = registry[className]

    private fun isTargetActivity(className: String?): Boolean {
        if (className == null) return false
        if (containsActivity(className)) return true
        return className.startsWith("$MODULE_PACKAGE.")
    }

    // ---------------- 初始化 ----------------

    fun init(hostContext: Context) {
        register(SettingsActivity::class.java)
        ModulePathHolder.path = runCatching {
            hostContext.packageManager.getApplicationInfo(MODULE_PACKAGE, 0).sourceDir
        }.getOrNull()
        initForStubActivity(hostContext)
    }

    fun ensureInitialized(ctx: Context) {
        if (!initialized) initForStubActivity(ctx)
    }

    private fun initForStubActivity(ctx: Context) {
        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return

            val instrumentation = activityThread.deepGet("mInstrumentation") as? Instrumentation
            if (instrumentation != null && instrumentation !is ProxyInstrumentation) {
                activityThread.deepSet("mInstrumentation", ProxyInstrumentation(instrumentation))
            }

            val mH = activityThread.deepGet("mH") as? Handler
            val originalCallback = mH?.deepGet("mCallback") as? Handler.Callback
            mH?.deepSet("mCallback", Handler.Callback { msg ->
                val handledObj = when (msg.what) {
                    100, 159 -> msg.obj
                    else -> null
                }
                handledObj?.let { handleLaunchMessage(it, msg.what) }
                originalCallback?.handleMessage(msg) ?: false
            })

            hookIActivityManager()
            hookIPackageManager(ctx, activityThread)
            runCatching { injectModuleResources(ctx.resources) }

            initialized = true
            XposedBridge.log("[QOdeep] 活动寄生已初始化 stub=$stubActivity")
        }.onFailure { XposedBridge.log("[QOdeep] 活动寄生初始化失败: $it") }
    }

    // ---------------- IActivityTaskManager 劫持 ----------------

    private fun hookIActivityManager() {
        val singleton = resolveActivityManagerSingleton() ?: return
        val mInstance = singleton.deepGet("mInstance") ?: return
        val iface = Class.forName("android.app.IActivityTaskManager")
        val proxy = Proxy.newProxyInstance(moduleLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "asBinder") {
                return@newProxyInstance (mInstance as android.os.IInterface).asBinder()
            }
            if (method.name == "startActivity" && args != null) {
                val idx = args.indexOfFirst { it is Intent }
                if (idx != -1) {
                    val raw = args[idx] as Intent
                    val component = raw.component
                    if (component != null && component.packageName == QQ_PACKAGE &&
                        isTargetActivity(component.className)
                    ) {
                        val stub = stubActivity ?: Env.hostContext?.let { resolveStub(it) }
                        if (stub != null) {
                            args[idx] = Intent().apply {
                                setClassName(QQ_PACKAGE, stub)
                                putExtra(ACTIVITY_PROXY_INTENT, raw)
                                flags = raw.flags
                            }
                        }
                    }
                }
            }
            invokeOriginal(mInstance, method, args)
        }
        singleton.deepSet("mInstance", proxy)
    }

    private fun resolveActivityManagerSingleton(): Any? = runCatching {
        val singleton = Class.forName("android.app.ActivityTaskManager")
            .deepGetStatic("IActivityTaskManagerSingleton")
        singleton?.javaClass?.getMethod("get")?.invoke(singleton)
        singleton
    }.recoverCatching {
        Class.forName("android.app.ActivityManager").deepGetStatic("IActivityManagerSingleton")
    }.getOrNull()

    @Synchronized
    private fun resolveStub(ctx: Context): String? {
        stubActivity?.let { return it }
        for (name in ACTIVITY_CANDIDATES) {
            runCatching {
                ctx.packageManager.getActivityInfo(ComponentName(ctx.packageName, name), 0)
                stubActivity = name
                return name
            }
        }
        return null
    }

    // ---------------- IPackageManager 劫持（伪造 ActivityInfo） ----------------

    private fun hookIPackageManager(ctx: Context, activityThread: Any) {
        val sPackageManager = activityThread.deepGet("sPackageManager") ?: return
        val iface = Class.forName("android.content.pm.IPackageManager")
        val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "getActivityInfo" && !args.isNullOrEmpty()) {
                val component = args[0] as? ComponentName
                if (component != null && component.packageName == QQ_PACKAGE &&
                    isTargetActivity(component.className)
                ) {
                    val flags = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                    val forged = makeProxyActivityInfo(component.className, flags)
                    if (forged != null) return@newProxyInstance forged
                }
            }
            invokeOriginal(sPackageManager, method, args)
        }
        activityThread.deepSet("sPackageManager", proxy)
        ctx.packageManager.deepSet("mPM", proxy)
    }

    private fun makeProxyActivityInfo(className: String, flags: Long): ActivityInfo? {
        return try {
            val ctx = Env.hostContext ?: return null
            val pm = ctx.packageManager ?: return null
            for (name in ACTIVITY_CANDIDATES) {
                try {
                    val proto = pm.getActivityInfo(ComponentName(ctx.packageName, name), flags.toInt())
                    proto.targetActivity = null
                    proto.taskAffinity = null
                    proto.descriptionRes = 0
                    proto.name = className
                    proto.configChanges = proto.configChanges or ActivityInfo.CONFIG_UI_MODE
                    return proto
                } catch (_: Exception) {
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- 消息队列还原 Intent ----------------

    private fun handleLaunchMessage(obj: Any, what: Int) {
        if (what == 100) {
            val intent = obj.deepGet("intent") as? Intent ?: return
            unwrapIntent(intent)?.let { obj.deepSet("intent", it) }
        } else if (what == 159) {
            val callbacks = runCatching {
                obj.javaClass.methods.firstOrNull { it.name == "getCallbacks" }?.invoke(obj) as? List<*>
            }.getOrNull() ?: return
            callbacks.forEach { item ->
                if (item != null && item.javaClass.name.contains("LaunchActivityItem")) {
                    val intent = item.deepGet("mIntent") as? Intent ?: return
                    val original = unwrapIntent(intent) ?: return
                    item.deepSet("mIntent", original)
                    if (Build.VERSION.SDK_INT >= 31) {
                        fixActivityClientRecordForApi31(obj, original)
                    }
                }
            }
        }
    }

    private fun fixActivityClientRecordForApi31(transaction: Any, originalIntent: Intent) {
        runCatching {
            val token = transaction.javaClass.methods
                .firstOrNull { it.name == "getActivityToken" }?.invoke(transaction) as? IBinder ?: return
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null)
            val acr = at.javaClass.methods
                .firstOrNull { it.name == "getLaunchingActivity" }?.invoke(at, token) ?: return
            acr.deepSet("intent", originalIntent)
        }
    }

    private fun unwrapIntent(intent: Intent): Intent? = runCatching {
        val clone = intent.clone() as Intent
        clone.extras?.classLoader = Env.hostClassLoader
        if (clone.hasExtra(ACTIVITY_PROXY_INTENT)) {
            clone.getParcelableExtra<Intent>(ACTIVITY_PROXY_INTENT)?.apply {
                extras?.classLoader = moduleLoader
            }
        } else {
            null
        }
    }.getOrNull()

    // ---------------- 模块资源注入 ----------------

    fun injectModuleResources(res: Resources?) {
        if (res == null) return
        if (runCatching { res.getString(R.string.app_name) }.isSuccess) return
        val path = ModulePathHolder.path ?: return

        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val loader = ResLoaderHolder.loader ?: run {
                    val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                    val provider = ResourcesProvider.loadFromApk(pfd)
                    ResourcesLoader().apply { addProvider(provider) }.also { ResLoaderHolder.loader = it }
                }
                val task = Runnable {
                    try {
                        res.addLoaders(loader)
                    } catch (_: IllegalArgumentException) {
                        addAssetPathFallback(res, path)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) task.run() else mainHandler.post(task)
            }
        } else {
            addAssetPathFallback(res, path)
        }
    }

    private fun addAssetPathFallback(res: Resources, path: String) {
        runCatching {
            val m = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
            m.isAccessible = true
            m.invoke(res.assets, path)
        }
    }

    // ---------------- Instrumentation 替换 Activity 实例 ----------------

    private class ProxyInstrumentation(private val base: Instrumentation) : Instrumentation() {
        override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity {
            Parasitics.getActivityClass(className)?.let { return it.newInstance() }
            return try {
                base.newActivity(cl, className, intent)
            } catch (e: ClassNotFoundException) {
                Parasitics.moduleLoader.loadClass(className).newInstance() as Activity
            }
        }

        override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
            injectModuleResources(activity.resources)
            if (icicle != null && isTargetActivity(activity.javaClass.name)) {
                icicle.classLoader = moduleLoader
            }
            base.callActivityOnCreate(activity, icicle)
        }

        override fun callActivityOnCreate(activity: Activity, icicle: Bundle?, persistentState: PersistableBundle?) {
            injectModuleResources(activity.resources)
            if (icicle != null && isTargetActivity(activity.javaClass.name)) {
                icicle.classLoader = moduleLoader
            }
            base.callActivityOnCreate(activity, icicle, persistentState)
        }
    }

    // ---------------- 通用反射 ----------------

    private fun invokeOriginal(target: Any, method: Method, args: Array<Any?>?): Any? = try {
        method.invoke(target, *args.orEmpty())
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    private object ModulePathHolder { var path: String? = null }
    private object ResLoaderHolder { var loader: ResourcesLoader? = null }

    private fun Class<*>.deepField(name: String): Field? {
        var c: Class<*>? = this
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        return null
    }

    private fun Any.deepGet(name: String): Any? = javaClass.deepField(name)?.get(this)
    private fun Any.deepSet(name: String, value: Any?) {
        javaClass.deepField(name)?.set(this, value)
    }

    private fun Class<*>.deepGetStatic(name: String): Any? = deepField(name)?.get(null)

    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val MODULE_PACKAGE = "com.qosed.qodeep"
}
