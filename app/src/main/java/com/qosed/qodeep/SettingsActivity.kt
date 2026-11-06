package com.qosed.qodeep

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import eightbitlab.com.blurview.BlurView
import com.lxj.xpopup.XPopup
import com.lxj.xpopup.core.CenterPopupView
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 模块设置页（传统 View 栈，对齐 FunBox）：
 * 全屏节令海报背景 + 一摞 BlurView 真毛玻璃卡片，
 * 弹窗统一走 XPopup，图片加载统一走 Glide。
 */
class SettingsActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var animLoadedKey: String? = null

    /**
     * 寄生环境关键：布局 XML 里写了模块专属 View（eightbitlab.com.blurview.BlurView），
     * LayoutInflater.inflate 时用的是 Activity.getClassLoader()，而寄生活动的 ClassLoader
     * 默认是 QQ 宿主进程的 loader，找不到 BlurView → ClassNotFoundException → 崩溃重启。
     * 这里返回「加载本类的模块 ClassLoader」，其 parent 链会兜底系统/宿主类，
     * 模块自己打包的 BlurView/XPopup 等也能正常被 inflate 加载。
     */
    override fun getClassLoader(): ClassLoader =
        javaClass.classLoader ?: super.getClassLoader()

    /**
     * 寄生环境关键 2：Activity 的 getResources() 默认是 QQ 宿主 APK 的资源表，
     * 而 R.layout.activity_settings / @drawable/bg_frosted_card 等都是模块 APK 的资源 ID，
     * 在 QQ 资源表里不存在 → setContentView 抛 Resources$NotFoundException。
     *
     * 不能全局重写 Activity.getResources()：Activity 的主题继承自 QQ 壳（windowBackground
     * 等属性里引用的还是 QQ 资源 ID），全局换成模块资源会把主题解析也带崩。
     * 所以只构造一个「模块资源 Context」专门用于 inflate 布局/弹窗；Activity 本身的
     * resources/theme 保持 QQ 不变，只有 Env.moduleApkPath 非空（onCreate 里已恢复）才构造。
     */
    @Volatile
    private var moduleCtx: Context? = null

    private fun moduleContext(): Context {
        moduleCtx?.let { return it }
        val apkPath = Env.moduleApkPath
        if (apkPath.isEmpty()) return this
        val built = runCatching {
            val am = AssetManager::class.java.newInstance()
            AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
                .invoke(am, apkPath)
            val base = resources
            val res = Resources(am, base.displayMetrics, base.configuration)
            object : android.content.ContextWrapper(this) {
                override fun getResources(): Resources = res
                override fun getSystemService(name: String): Any? {
                    // 让 LayoutInflater.from(ctx) 拿到 clone 到本 Context 的 inflater，
                    // 这样 inflate 时 getResources()/getClassLoader() 走的都是模块侧。
                    if (name == Context.LAYOUT_INFLATER_SERVICE) {
                        val raw = super.getSystemService(name)
                        if (raw is android.view.LayoutInflater) return raw.cloneInContext(this)
                    }
                    return super.getSystemService(name)
                }
            }
        }.getOrNull()
        if (built != null) {
            moduleCtx = built
            return built
        }
        return this
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 手机端崩溃兜底：把整个加载流程包起来，崩了写日志到宿主 cacheDir，不用连电脑也能看崩因
        try {
            onCreateSafe(savedInstanceState)
        } catch (t: Throwable) {
            writeCrashLog(t)
            throw t
        }
    }

    private fun onCreateSafe(savedInstanceState: Bundle?) {
        // 不能用 AppCompatActivity：寄生打开时窗口主题继承自 QQ 壳 Activity
        // （Parasitics 伪造的 ActivityInfo 复制的就是壳 Activity 的 theme 字段），
        // AppCompatActivity 会在 attach 阶段检测窗口主题非 Theme.AppCompat 而抛
        // IllegalStateException → 崩溃 → QQ 重启。ComponentActivity 无此约束（重铸前已验证）。
        super.onCreate(savedInstanceState)
        // 沉浸式：背景动画铺到状态栏/导航栏底下
        runCatching {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
            // 首帧前窗口底色用纯白，避免壳黑底在封面出现前闪黑
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.WHITE))
        }
        // QQ 壳 Activity 的主题是半屏面板样式（右侧滑入、宽度受限），强制全屏不透明窗口
        runCatching {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 关键：QQ 壳主题可能带 windowTranslucentStatus → 系统在状态栏下压一层
            // 半透明黑渐变（表现为「背景全屏了但状态栏还是大黑边」）。必须显式清掉
            // 半透明 flag 并声明自绘系统栏背景，状态栏才真正全透。
            window.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    or android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
            // 若壳是半透明主题，把窗口从 translucent 转回普通全屏窗口
            android.app.Activity::class.java.getDeclaredMethod("convertFromTranslucent")
                .apply { isAccessible = true }.invoke(this)
        }
        // 双保险去掉进入转场动画，确保「秒进无抖动」
        @Suppress("DEPRECATION")
        runCatching { overridePendingTransition(0, 0) }

        // 寄生页面与 MainHook 不同 ClassLoader，Env 是空副本，从 Intent 恢复宿主环境
        intent?.let { i ->
            i.getStringExtra(ENV_DATA_DIR)?.let { Env.moduleDataDir = it }
            i.getStringExtra(ENV_NATIVE_LIB_DIR)?.let { Env.moduleNativeLibDir = it }
            i.getStringExtra(ENV_HOST_APK)?.let { Env.hostApkPath = it }
            i.getStringExtra(ENV_MODULE_APK)?.let { Env.moduleApkPath = it }
        }
        // 兜底：Activity 被系统重建/进程重启时 Intent 可能丢失 extra，从宿主持久化值恢复
        if (Env.moduleDataDir.isEmpty() || Env.moduleApkPath.isEmpty()) {
            runCatching {
                getSharedPreferences("qodeep_env", MODE_PRIVATE).let { p ->
                    Env.moduleDataDir = p.getString("dataDir", "") ?: ""
                    Env.moduleNativeLibDir = p.getString("nativeLibDir", "") ?: ""
                    Env.hostApkPath = p.getString("hostApk", "") ?: ""
                    Env.moduleApkPath = p.getString("moduleApk", "") ?: ""
                }
            }
        }
        // 独立进程兜底：桌面点开（非寄生）时 Intent 无 extra、持久化值也为空，
        // 用模块自己的 context 填 APK 路径 / dataDir，让背景资源也能正常解压显示，
        // 桌面预览效果与 QQ 内一致。
        if (Env.moduleApkPath.isEmpty()) {
            runCatching { Env.moduleApkPath = applicationInfo.sourceDir.orEmpty() }
        }
        if (Env.moduleDataDir.isEmpty()) {
            Env.moduleDataDir = filesDir.absolutePath
        }

        // 用模块资源 Context inflate 主布局：寄生环境 Activity 自身 resources 是 QQ 的，
        // 直接 setContentView(R.layout.xxx) 会因资源 ID 在 QQ 表里不存在而崩。
        val mctx = moduleContext()
        setContentView(
            android.view.LayoutInflater.from(mctx).inflate(R.layout.activity_settings, null, false)
        )

        val root = findViewById<FrameLayout>(R.id.root)
        val bgCover = findViewById<ImageView>(R.id.bgCover)
        val bgAnim = findViewById<WebView>(R.id.bgAnim)
        val tvHint = findViewById<TextView>(R.id.tvHint)
        val cardTitle = findViewById<BlurView>(R.id.cardTitle)
        val cardBg = findViewById<BlurView>(R.id.cardBg)
        val cardDexkit = findViewById<BlurView>(R.id.cardDexkit)
        val tvCurrentBg = findViewById<TextView>(R.id.tvCurrentBg)
        val tvDexReady = findViewById<TextView>(R.id.tvDexReady)
        val tvDexDesc = findViewById<TextView>(R.id.tvDexDesc)
        val dexRebuildRow = findViewById<LinearLayout>(R.id.dexRebuildRow)
        val tvRebuild = findViewById<TextView>(R.id.tvRebuild)

        // 三张卡片：真·高斯模糊毛玻璃（实时采样底下封面 + 动画 WebView）
        // 关键：显式指定 RenderEffectBlur（纯 GPU 的 RenderNode 实现）。
        // BlurView 2.0.6 的默认算法在 SDK_INT < 31 时走 RenderScriptBlur，
        // RenderScript 在 Android 12+ 已废弃、部分国产 ROM 直接 native 崩溃，
        // 表现为「点击入口 QQ 秒重启」。这里强制走 RenderEffect，彻底绕开 RenderScript。
        val blurAlgorithm = if (Build.VERSION.SDK_INT >= 31) {
            eightbitlab.com.blurview.RenderEffectBlur()
        } else {
            // 低版本系统退回到 SDK 默认分支（RenderScript），旧系统 RenderScript 是稳定可用的
            null
        }
        // 模糊采样源：独立背景层 bgLayer（只含封面 + WebView）。
        // 之前直接采 root，快照链路里路过卡片/图标层，窗口尺寸/位置在寄生壳的
        // 进场动画期间抖动时，会把 UI 残影放大糊成一坨灰色（用户截图里的"一大坨黑"）。
        // 采样源换成 bgLayer 后，快照里只可能存在背景内容。
        val bgLayer = findViewById<FrameLayout>(R.id.bgLayer)
        // setupWith 延后到首帧布局完成（post）：确保 init 时拿到真实尺寸，
        // 避免 0x0 初始化产生错误大小的模糊位图被拉伸成色块。
        root.post {
            for (card in listOf(cardTitle, cardBg, cardDexkit)) {
                card.clipToOutline = true
                val facade = if (blurAlgorithm != null) {
                    card.setupWith(bgLayer, blurAlgorithm)
                } else {
                    card.setupWith(bgLayer)
                }
                facade.setFrameClearDrawable(window.decorView.background)
                    .setBlurEnabled(true)
                    .setBlurRadius(18f)
            }
        }

        // 彩色圆角图标块（FunBox 风格）：14% 透明度底 + 前景色字符
        fun chip(host: FrameLayout, glyph: String, argb: Int) {
            host.removeAllViews()
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor((argb and 0x00FFFFFF) or 0x24000000)
            }
            val tv = TextView(this).apply {
                text = glyph
                setTextColor(argb)
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            host.background = bg
            host.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val logo = findViewById<FrameLayout>(R.id.logoChip)
        val logoBg = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(0xFF3D7FFF.toInt(), 0xFF66A1FF.toInt())
            cornerRadius = 18f * resources.displayMetrics.density
        }
        logo.background = logoBg
        chip(findViewById(R.id.chipBg), "景", 0xFF3D7FFF.toInt())
        chip(findViewById(R.id.chipDex), "缓", 0xFFFF9500.toInt())

        val prefs = getSharedPreferences("qodeep_bg", Context.MODE_PRIVATE)
        var bgKey: String? = prefs.getString("bg_key", null)
        var bgDir: File? = null
        var items: List<JieqiCatalog.Item> = emptyList()

        fun refreshCurrent() {
            val current = items.firstOrNull { it.key == bgKey } ?: items.firstOrNull()
            // 未选择过时跟随清单第一张（默认端午）；已选择过则用保存值
            val active = bgKey ?: current?.key ?: "duanwu"
            tvCurrentBg.text = current?.name ?: "端午"
            // 静态封面：Glide 直接从解压目录加载（点开瞬间即有画面）
            val cover = bgDir?.let { File(it, "${active}_cover.jpg") }
            if (cover != null && cover.exists()) {
                Glide.with(this).load(cover).centerCrop().into(bgCover)
            }
            // 动态 Spine 背景：WebView 加载（资源就绪后只 load 一次）
            if (bgDir != null && animLoadedKey != active) {
                animLoadedKey = active
                bgAnim.setBackgroundColor(Color.TRANSPARENT)
                bgAnim.loadUrl("https://appassets.qodeep.local/player.html?key=$active")
            }
        }

        setupWebView(bgAnim) { bgDir }

        // 更换背景卡 → XPopup 宫格选择弹窗
        findViewById<LinearLayout>(R.id.bgRow).setOnClickListener {
            if (items.isEmpty() || bgDir == null) return@setOnClickListener
            val popup = BgPickerPopup(mctx, items, bgKey ?: items.firstOrNull()?.key ?: "duanwu", bgDir) { key ->
                prefs.edit().putString("bg_key", key).apply()
                bgKey = key
                refreshCurrent()
            }
            XPopup.Builder(mctx).asCustom(popup).show()
        }

        // DexKit 缓存状态读取（后台线程，避免 IO 卡主线程）
        var rebuilding = false
        Thread {
            val (n, desc) = DexKitPageState.readStatus()
            main.post {
                tvDexReady.text = if (n < 0) "未知" else "$n / ${DexKitPageState.total} 已实现"
                tvDexReady.setTextColor(if (rebuilding) 0xFF8A8A8A.toInt() else 0xFF34C759.toInt())
                tvDexDesc.text = desc
            }
        }.start()

        // 重建入口 → XPopup 进度弹窗（模态，轮询 progressKey）→ 完成/失败弹窗
        dexRebuildRow.setOnClickListener {
            if (rebuilding) return@setOnClickListener
            rebuilding = true
            tvRebuild.setTextColor(0xFF8A8A8A.toInt())
            tvDexReady.setTextColor(0xFF8A8A8A.toInt())
            tvDexReady.text = "查找中..."
            tvDexDesc.text = "正在重新查找，请稍候..."
            val progress = DexProgressPopup(mctx)
            val progressTicker = object : Runnable {
                override fun run() {
                    progress.setKey(DexKitFinder.progressKey ?: "准备开始...")
                    main.postDelayed(this, 100)
                }
            }
            XPopup.Builder(mctx)
                .dismissOnTouchOutside(false)
                .dismissOnBackPressed(false)
                .asCustom(progress)
                .show()
            main.post(progressTicker)
            Thread {
                runCatching {
                    DexKitPageState.clearCache()
                    DexKitFinder.doFind {
                        main.post {
                            main.removeCallbacks(progressTicker)
                            progress.dismiss()
                            rebuilding = false
                            tvRebuild.setTextColor(0xFF222222.toInt())
                            XPopup.Builder(mctx)
                                .dismissOnTouchOutside(false)
                                .dismissOnBackPressed(false)
                                // 显式传按钮文案：寄生资源环境下 XPopup 库布局里的默认字符串引用
                                // 会解析失败退化成 "@资源ID" 字面量，不能依赖默认文案
                                .asConfirm(
                                    "查找完成", "DexKit 缓存已重建，重启 QQ 后生效",
                                    "取消", "确定",
                                    {
                                        runCatching {
                                            // shell 独立进程不受 QQ 进程死亡影响：1 秒后把 QQ 重新拉起
                                            Runtime.getRuntime().exec(
                                                arrayOf(
                                                    "sh", "-c",
                                                    "sleep 1; monkey -p com.tencent.mobileqq -c android.intent.category.LAUNCHER 1"
                                                )
                                            )
                                        }
                                        main.postDelayed({
                                            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                                        }, 300)
                                    }, null, false
                                ).show()
                        }
                    }
                }.onFailure {
                    main.post {
                        main.removeCallbacks(progressTicker)
                        progress.dismiss()
                        rebuilding = false
                        tvRebuild.setTextColor(0xFF222222.toInt())
                        XPopup.Builder(mctx)
                            .asConfirm("查找失败", "重建失败: ${it.message}", "取消", "确定", {}, null, false)
                            .show()
                    }
                }
            }.start()
        }

        // 背景资源解压：QQ 重启后首次 IO 抖动可能瞬时失败，自动重试 3 次
        Thread {
            var tries = 0
            while (true) {
                val d = BgAssets.ensure(this)
                if (d != null) {
                    main.post {
                        bgDir = d
                        items = JieqiCatalog.load(d)
                        tvHint.visibility = View.GONE
                        refreshCurrent()
                    }
                    break
                }
                tries++
                if (tries >= 3) {
                    main.post {
                        tvHint.visibility = View.VISIBLE
                        tvHint.text = "背景资源加载失败，请重启 QQ 重试"
                    }
                    break
                }
                Thread.sleep(600)
            }
        }.start()
    }

    /** 全屏透明 WebView：Spine 节令动画背景，铺到毛玻璃卡片底下供其采样。 */
    private fun setupWebView(
        wv: WebView,
        dirProvider: () -> File?
    ) {
        wv.setBackgroundColor(Color.TRANSPARENT)
        // 不强制 LAYER_TYPE_HARDWARE：透明 WebView 强制离屏硬件层会让合成器
        // 偶发把透明区域按不透明合成，导致动画元素「暗一下再变明」的闪烁
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.isHorizontalScrollBarEnabled = false
        wv.isVerticalScrollBarEnabled = false
        // 背景层不拦截点击
        wv.isClickable = false
        wv.isFocusable = false
        wv.isLongClickable = false
        wv.setOnTouchListener { _, _ -> true }
        wv.webViewClient = object : android.webkit.WebViewClient() {
            // 把 https://appassets.qodeep.local/* 拦截为解压目录里的本地文件。
            // 页面因此是正常 https 源，fetch/XHR 不再被 file:// 协议禁掉。
            override fun shouldInterceptRequest(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): android.webkit.WebResourceResponse? {
                val u = request.url
                if (u.host != "appassets.qodeep.local") return null
                val d = dirProvider() ?: return null
                val rel = u.path?.trimStart('/') ?: return null
                if (rel.isEmpty() || rel.contains("..")) return null
                val f = File(d, rel)
                if (!f.exists() || !f.isFile) return null
                val mime = when (f.extension.lowercase()) {
                    "html" -> "text/html"
                    "js" -> "application/javascript"
                    "json" -> "application/json"
                    "webp" -> "image/webp"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    else -> "application/octet-stream"
                }
                return runCatching {
                    android.webkit.WebResourceResponse(mime, null, java.io.FileInputStream(f))
                }.getOrNull()
            }
        }
    }

    /** 把崩溃堆栈写到宿主可写目录，手机不连电脑也能直接看到崩因。 */
    private fun writeCrashLog(t: Throwable) {
        val sb = StringBuilder()
        sb.appendLine("==== QOdeep 崩溃日志 ====")
        sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("系统 SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        sb.appendLine("厂商/型号: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("moduleDataDir: ${Env.moduleDataDir}")
        sb.appendLine("moduleApkPath: ${Env.moduleApkPath}")
        sb.appendLine("hostApkPath: ${Env.hostApkPath}")
        sb.appendLine()
        sb.appendLine("异常类型: ${t.javaClass.name}")
        sb.appendLine("异常消息: ${t.message}")
        sb.appendLine()
        sb.appendLine("堆栈:")
        sb.appendLine(android.util.Log.getStackTraceString(t))

        // 三写：
        // 1) Env.moduleDataDir = QQ 的 /sdcard/Android/data/com.tencent.mobileqq/QOdeep/
        //    （文件管理器可直接看到，dexkit 目录就在这，优先写这里）
        // 2) cacheDir（QQ 内部 cache，兜底，文件管理器看不到但一定可写）
        // 3) /sdcard 根目录（旧系统可写，新系统分区存储会失败，尽力而为）
        val targets = mutableListOf<File>()
        if (Env.moduleDataDir.isNotEmpty()) {
            runCatching { targets.add(File(Env.moduleDataDir, "报错日志.txt")) }
        }
        runCatching { targets.add(File(cacheDir, "报错日志.txt")) }
        runCatching {
            targets.add(File(android.os.Environment.getExternalStorageDirectory(), "QOdeep_报错日志.txt"))
        }
        for (f in targets) {
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(sb.toString())
                android.util.Log.e("QOdeep", "崩溃日志已写入: ${f.absolutePath}")
            }.onFailure {
                android.util.Log.e("QOdeep", "写崩溃日志失败(${f.absolutePath}): $it")
            }
        }
        // Java 崩溃时进程仍存活：弹窗直接展示堆栈，不用找文件
        runCatching {
            val msg = "${t.javaClass.name}\n\n${t.message}\n\n详见 QQ/QOdeep/报错日志.txt"
            android.app.AlertDialog.Builder(this)
                .setTitle("QOdeep 崩溃")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    companion object {
        const val ENV_DATA_DIR = "qodeep.env.dataDir"
        const val ENV_NATIVE_LIB_DIR = "qodeep.env.nativeLibDir"
        const val ENV_HOST_APK = "qodeep.env.hostApk"
        const val ENV_MODULE_APK = "qodeep.env.moduleApk"
    }
}

/** 更换背景弹窗：三列宫格显示各节令静态封面，点选立即生效并持久化。 */
class BgPickerPopup(
    context: Context,
    private val items: List<JieqiCatalog.Item>,
    private val currentKey: String,
    private val dir: File?,
    private val onSelect: (String) -> Unit
) : CenterPopupView(context) {

    override fun getImplLayoutId(): Int = R.layout.popup_bg_picker

    override fun onCreate() {
        super.onCreate()
        val grid = findViewById<RecyclerView>(R.id.bgGrid)
        grid.layoutManager = GridLayoutManager(context, 3)
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = android.view.LayoutInflater.from(context)
                    .inflate(R.layout.item_bg_thumb, parent, false)
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun getItemCount(): Int = items.size

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val item = items[pos]
                val iv = h.itemView.findViewById<ImageView>(R.id.ivThumb)
                val tv = h.itemView.findViewById<TextView>(R.id.tvName)
                val selected = item.key == currentKey
                val radius = 10f * context.resources.displayMetrics.density
                val bg = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(0xFFEDEDED.toInt())
                    setStroke(
                        if (selected) (2f * context.resources.displayMetrics.density).toInt() else 0,
                        0xFF0A7AFF.toInt()
                    )
                }
                iv.background = bg
                iv.clipToOutline = true
                tv.text = item.name
                tv.setTextColor(if (selected) 0xFF0A7AFF.toInt() else 0xFF8A8A8A.toInt())
                val cover = dir?.let { File(it, "${item.key}_cover.jpg") }
                if (cover != null && cover.exists()) {
                    Glide.with(context).load(cover).centerCrop().into(iv)
                }
                h.itemView.setOnClickListener { onSelect(item.key); dismiss() }
            }
        }
    }
}

/** DexKit 重建进度弹窗：模态，外部轮询更新当前任务 key。 */
class DexProgressPopup(context: Context) : CenterPopupView(context) {

    override fun getImplLayoutId(): Int = R.layout.popup_dex_progress

    fun setKey(text: String) {
        findViewById<TextView>(R.id.tvKey)?.text = text
    }
}

/** 从解压目录加载节令封面缩略图。 */
object JieqiCatalog {
    data class Item(val key: String, val name: String, val season: String)

    fun load(dir: File): List<Item> = runCatching {
        val text = File(dir, "jieqi.json").readText()
        val arr = JSONObject(text).getJSONArray("items")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("key"), o.getString("name"), o.optString("season"))
        }
    }.getOrDefault(emptyList())
}

/**
 * 背景资源解压器。
 *
 * 模块页寄生在 QQ 进程里，WebView 的 file:///android_asset/ 指向 QQ 的 APK，
 * 读不到模块 APK 里的背景资源；所以首次打开时把模块 APK 内 assets/ 下的
 * 背景文件解压到 QQ 自己的 cacheDir（模块更新后按 APK 指纹自动重解压）。
 */
object BgAssets {
    private const val MODULE_PKG = "com.qosed.qodeep"
    private const val MARKER = ".ver"

    @Volatile
    private var cachedDir: File? = null
    @Volatile
    private var cachedFingerprint: String? = null

    /** 返回解压完成的资源目录；失败返回 null。 */
    fun ensure(context: Context): File? {
        // 模块 APK 路径优先取 Env（MainHook 已在宿主进程可靠拿到），
        // 寄生环境里 getApplicationInfo(MODULE_PKG) 会因包可见性过滤抛
        // NameNotFoundException，只能作为最后兜底。
        val apkPath = Env.moduleApkPath.ifEmpty {
            runCatching {
                context.packageManager.getApplicationInfo(MODULE_PKG, 0).sourceDir
            }.getOrNull().orEmpty()
        }
        val fingerprint = if (apkPath.isEmpty()) {
            null
        } else {
            runCatching {
                val f = File(apkPath)
                "$apkPath:${f.length()}:${f.lastModified()}"
            }.getOrNull()
        }
        // 拿不到模块 APK 路径时，只要旧资源目录还在就直接用它（不因指纹失败而空白）
        if (fingerprint == null) {
            val existing = File(context.cacheDir, "qodeep_bg")
            return existing.takeIf { it.exists() && it.listFiles()?.isNotEmpty() == true }
        }

        synchronized(this) {
            if (cachedDir != null && cachedFingerprint == fingerprint) return cachedDir
            val dir = File(context.cacheDir, "qodeep_bg")
            val marker = File(dir, MARKER)
            val upToDate = dir.exists() &&
                runCatching { marker.readText().trim() == fingerprint }.getOrDefault(false)
            if (!upToDate) {
                // 解压到临时目录、成功后再原子替换：失败时旧目录原封保留，
                // 旧版资源照样能显示背景，杜绝「重解压一次失败 → 背景永久加载失败」
                val tmp = File(context.cacheDir, "qodeep_bg_tmp")
                val extracted = runCatching {
                    tmp.deleteRecursively()
                    tmp.mkdirs()
                    val apkPath = fingerprint.substringBeforeLast(":").substringBeforeLast(":")
                    ZipFile(File(apkPath)).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (!e.name.startsWith("assets/") || e.isDirectory) continue
                            val rel = e.name.removePrefix("assets/")
                            if (rel.isEmpty() || rel.contains('/')) continue
                            zip.getInputStream(e).use { input ->
                                File(tmp, rel).outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                    File(tmp, MARKER).writeText(fingerprint)
                    true
                }.getOrDefault(false)
                if (extracted) {
                    dir.deleteRecursively()
                    if (!tmp.renameTo(dir)) {
                        // rename 失败（同分区不应发生）：直接用临时目录兜底
                        cachedDir = tmp
                        cachedFingerprint = fingerprint
                        return tmp
                    }
                } else {
                    tmp.deleteRecursively()
                    // 解压失败：旧目录还在就先用它（版本可能略旧，总好过空白）
                    if (!dir.exists() || dir.listFiles()?.isNotEmpty() != true) return null
                }
            }
            cachedDir = dir
            cachedFingerprint = runCatching { marker.readText().trim() }.getOrNull()
            return dir
        }
    }
}

/** DexKit 缓存状态读取与重建（QQ 进程内才有宿主目录权限）。 */
object DexKitPageState {
    // 真实查询 key：从注册的 DexKitTask 动态取 realQueryKeys 的并集。
    // 占位槽（slot4~slot16）是后续功能的预留，扫不到是正常的，不参与「是否完整」判断。
    private fun realKeys(): Set<String> =
        DexKitTaskRegistry.tasks.flatMap { task ->
            task.realQueryKeys().map { "${task.tag}->$it" }
        }.toSet()

    // 分母：槽位总数（真实任务 + 预留占位槽），用于展示「已实现 / 共 N」的进度。
    // 显示成「3 / 16」，保留 16 个功能槽位的概念，后续加功能自动 +1 分子。
    val total: Int
        get() = DexKitTaskRegistry.tasks.sumOf { it.getQueryMap().size }

    private const val FINGERPRINT_KEY = "__host_apk__"

    private fun cacheFile(): File = File(File(Env.moduleDataDir, "dexkit"), "CacheMap.json")

    /** 返回 (已命中的真实任务数, 描述)；n < 0 表示无法读取。 */
    fun readStatus(): Pair<Int, String> {
        if (Env.moduleDataDir.isEmpty()) return -1 to "请从 QQ 设置页进入本页面"
        val f = cacheFile()
        if (!f.exists()) return 0 to "缓存缺失，重启 QQ 后自动查找"
        val keys = realKeys()
        if (keys.isEmpty()) return -1 to "未注册查询任务"
        return runCatching {
            val json = JSONObject(f.readText())
            val n = keys.count { k -> json.optString(k).isNotEmpty() }
            // 判断「完整」只看真实任务是否全命中，占位槽不计入
            if (n >= keys.size) n to "缓存完整，未发现缺失方法"
            else n to "缓存不完整，重启 QQ 后自动补查"
        }.getOrElse { -1 to "缓存读取失败" }
    }

    fun clearCache() {
        // 页面侧 DexKitCache 是独立 ClassLoader 的副本，必须先 init 设置缓存文件路径，
        // 否则扫描完成后 saveCache 因 cacheFile 为 null 而不落盘。
        DexKitCache.init()
        // 关键：必须同时清内存 map。只删磁盘文件的话，cacheMap 里还留着上次扫描结果，
        // scanBridge 的 contains(key) 全为 true → 全部跳过 → 变成「增量补查」而非全量重扫。
        DexKitCache.clearAll()
    }
}
