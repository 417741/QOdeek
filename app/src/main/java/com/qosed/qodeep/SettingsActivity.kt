package com.qosed.qodeep

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.animation.LinearInterpolator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.qosed.qodeep.levelboost.MoodConfig
import com.qosed.qodeep.levelboost.MoodScheduler
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * 进程内封面位图缓存。
 *
 * 连续「返回 → 再点入口」时每次都是新 Activity，若每轮都要等后台解压线程把封面
 * 解码到主线程再贴上，IO 稍慢就会露出窗口纯白底，表现为概率性「闪一下」。
 * 缓存只保留最近一张（按背景 key），命中时首帧直接贴已解码位图，
 * 顺带省掉每轮一次的整图解码；切背景时自动换掉，内存占用恒定。
 */
private object CoverCache {
    @Volatile private var key: String? = null
    @Volatile private var bmp: Bitmap? = null

    /** 首帧用：此时还没加载背景清单，直接取最近显示过的一张。
     *  未选过背景时清单第一张的 key 未必是默认值，按 key 查反而可能错配并造成跳变。 */
    fun peek(): Bitmap? = synchronized(this) {
        bmp?.takeIf { !it.isRecycled }
    }

    fun get(k: String): Bitmap? = synchronized(this) {
        val b = bmp
        if (key == k && b != null && !b.isRecycled) b else null
    }

    fun put(k: String, b: Bitmap) = synchronized(this) {
        key = k
        bmp = b
    }
}

/**
 * 模块设置页（传统 View 栈）：
 * 全屏节令海报背景 + 一摞 BlurView 真毛玻璃卡片，
 * 弹窗统一走 XPopup，图片加载统一走 Glide。
 */
class SettingsActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var animLoadedKey: String? = null
    /** 首页标题图标是否在自转（时钟驱动，与入口头像同步）；二级页/暂停时置 false。 */
    @Volatile
    private var titleSpinning = false
    private var spinCallback: android.view.Choreographer.FrameCallback? = null

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
        // 首选：模块是正常安装的 APK，用 createPackageContext 拿模块自己的 Resources/ClassLoader。
        // 全程系统封装，不反射 AssetManager、不手动 addAssetPath/addLoaders，
        // 在 MIUI/Android 14 上不会出现空资源表或 hidden API 失败。
        try {
            createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).let {
                moduleCtx = it
                return it
            }
        } catch (t: Throwable) {
            android.util.Log.w("QOdeep", "createPackageContext 失败，回退 buildModuleResources: $t")
        }

        // 兜底 1：从模块 APK 路径构造只含模块资源的 Resources（API 30+ 走 ResourcesLoader）。
        val apkPath = Env.moduleApkPath
        if (apkPath.isNotEmpty()) {
            val built = runCatching {
                val base = resources
                val res = buildModuleResources(apkPath, base)
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
            }.onFailure {
                android.util.Log.w("QOdeep", "buildModuleResources 失败: $it")
            }.getOrNull()
            if (built != null) {
                moduleCtx = built
                return built
            }
        }
        android.util.Log.w("QOdeep", "moduleContext 全部失败，退回宿主 resources")
        return this
    }

    /**
     * 构造只含模块 APK 资源的 Resources。
     * - Android 11+（API 30）：用公开的 ResourcesProvider/ResourcesLoader 加载 APK，
     *   不再反射 hidden API addAssetPath（在 MIUI/Android 14 上会静默失败导致空资源表崩溃）。
     * - Android 10 及以下：回退反射 addAssetPath。
     */
    private fun buildModuleResources(apkPath: String, base: Resources): Resources {
        if (Build.VERSION.SDK_INT >= 30) {
            val pfd = android.os.ParcelFileDescriptor.open(
                File(apkPath), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val provider = android.content.res.loader.ResourcesProvider.loadFromApk(pfd)
            val loader = android.content.res.loader.ResourcesLoader().apply { addProvider(provider) }
            val am = AssetManager::class.java.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            return Resources(am, base.displayMetrics, base.configuration).apply {
                addLoaders(loader)
            }
        }
        val am = AssetManager::class.java.newInstance()
        AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
            .invoke(am, apkPath)
        return Resources(am, base.displayMetrics, base.configuration)
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
        // 壳 Activity 的主题自带「右侧滑入」的窗口动画样式，主题级动画不受
        // overridePendingTransition 约束。窗口一创建就清掉，保证第一帧无位移。
        runCatching { window.setWindowAnimations(0) }
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
            // 壳主题带 backgroundDimEnabled：系统会在窗口首帧压一层暗色遮罩，
            // 光清 FLAG_DIM_BEHIND 不够（部分 ROM 在 flag 生效前已画过一帧暗底，
            // 表现就是「进入时背景暗一下再正常回来」）。把 dim 量一并归零。
            window.setDimAmount(0f)
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
        val content = try {
            android.view.LayoutInflater.from(mctx)
                .inflate(R.layout.activity_settings, null, false)
        } catch (t: Throwable) {
            // 兜底：模块资源表构造失败时，先把模块资源同步注入宿主 Resources 再 inflate。
            // 用宿主 resources 直接 inflate 才能保证后续主题/窗口背景解析不串包。
            android.util.Log.w("QOdeep", "模块资源 inflate 失败，回退宿主注入: $t")
            runCatching { Parasitics.injectModuleResourcesNow(resources, Env.moduleApkPath) }
            android.view.LayoutInflater.from(this)
                .inflate(R.layout.activity_settings, null, false)
        }
        setContentView(content)

        val root = findViewById<FrameLayout>(R.id.root)
        // 第三套真实液态玻璃要取样卡片背后的画面：背景层独立在卡片列之外，
        // 录它的显示列表不会把卡片自身卷进来造成递归。宿主原生条目不设取样源，
        // 自动走零渲染依赖的静态近似。
        LiquidGlass.setBackdrop(findViewById(R.id.bgLayer))
        val bgCover = findViewById<ImageView>(R.id.bgCover)
        // 首帧即有画面：背景封面原本要等后台解压线程 + 主线程解码才贴上，IO 稍慢
        // 就会露出窗口纯白底（连续返回再进入时最明显）。进程内已有上次显示过的
        // 封面时，在首帧绘制前直接复用，白底一帧都不露；首次冷启动缓存为空时
        // 仍走原来的异步加载链路，行为不变。
        CoverCache.peek()?.let { bgCover.setImageBitmap(it) }
        val bgAnim = findViewById<WebView>(R.id.bgAnim)
        val tvHint = findViewById<TextView>(R.id.tvHint)
        val cardTitle = findViewById<FrameLayout>(R.id.cardTitle)
        val cardBg = findViewById<FrameLayout>(R.id.cardBg)
        val cardDexkit = findViewById<FrameLayout>(R.id.cardDexkit)
        val cardSettings = findViewById<FrameLayout>(R.id.cardSettings)
        val cardDiy = findViewById<FrameLayout>(R.id.cardDiy)
        val cardChat = findViewById<FrameLayout>(R.id.cardChat)
        val cardLevel = findViewById<FrameLayout>(R.id.cardLevel)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val titleIcon = findViewById<ImageView>(R.id.titleIcon)
        val mainCards = findViewById<LinearLayout>(R.id.mainCards)
        val cardBasic = findViewById<FrameLayout>(R.id.cardBasic)
        val basicContent = findViewById<FrameLayout>(R.id.basicContent)
        val cardKeepAlive = findViewById<FrameLayout>(R.id.cardKeepAlive)
        val keepAliveSwitchSlot = findViewById<FrameLayout>(R.id.keepAliveSwitchSlot)
        val pixelWindowSwitchSlot = findViewById<FrameLayout>(R.id.pixelWindowSwitchSlot)
        val diyContent = findViewById<LinearLayout>(R.id.diyContent)
        val cardUiStyle = findViewById<FrameLayout>(R.id.cardUiStyle)
        val uiStyleSwitchSlot = findViewById<FrameLayout>(R.id.uiStyleSwitchSlot)
        val tvCurrentUi = findViewById<TextView>(R.id.tvCurrentUi)
        val cardShimmer = findViewById<FrameLayout>(R.id.cardShimmer)
        val shimmerSwitchSlot = findViewById<FrameLayout>(R.id.shimmerSwitchSlot)
        val cardLiquidGlassBar = findViewById<FrameLayout>(R.id.cardLiquidGlassBar)
        val liquidGlassBarSwitchSlot = findViewById<FrameLayout>(R.id.liquidGlassBarSwitchSlot)
        val detailContent = findViewById<FrameLayout>(R.id.detailContent)
        val levelContent = findViewById<FrameLayout>(R.id.levelContent)
        val cardMoodSwitch = findViewById<FrameLayout>(R.id.cardMoodSwitch)
        val moodRow = findViewById<LinearLayout>(R.id.moodRow)
        val moodSwitchSlot = findViewById<FrameLayout>(R.id.moodSwitchSlot)
        val moodDeleteSwitchSlot = findViewById<FrameLayout>(R.id.moodDeleteSwitchSlot)
        val tvMoodSubtitle = findViewById<TextView>(R.id.tvMoodSubtitle)
        val cardCheckin = findViewById<FrameLayout>(R.id.cardCheckin)
        val checkinRow = findViewById<LinearLayout>(R.id.checkinRow)
        val checkinSwitchSlot = findViewById<FrameLayout>(R.id.checkinSwitchSlot)
        val checkinDeleteSwitchSlot = findViewById<FrameLayout>(R.id.checkinDeleteSwitchSlot)
        val tvCheckinSubtitle = findViewById<TextView>(R.id.tvCheckinSubtitle)

        // 卡片液态玻璃：统一走 LiquidGlass 设计系统（浅冷灰透明玻璃 + 细白描边 + 大圆角）。
        // 不用 XML drawable —— 寄生环境资源引用不可靠，圆角还决定玻璃层的裁剪轮廓。
        // detailContent 只作二级内容容器，本身不做玻璃卡；DexKit 卡已移入其中，单独保持玻璃质感
        val glassCards = listOf(cardTitle, cardBg, cardDiy, cardDexkit, cardSettings, cardChat, cardLevel, cardMoodSwitch, cardCheckin, cardUiStyle, cardShimmer, cardLiquidGlassBar, cardBasic, cardKeepAlive)

        // 四档 UI 风格：
        //  0 第一套（默认）：浅冷灰透明玻璃 + 蓝色流光
        //  1 第二套：轻薄冰块玻璃 + 亮银白流光
        //  2 第三套：真实液态玻璃（背后画面模糊 + 边缘折射）+ 粉色流光
        //     Android 12+ 且硬件加速可用才启用，否则自动回退静态近似
        //  3 第四套：液滴玻璃（拖动主页功能时弹出的那套）+ 色散彩虹流光
        val uiPrefs = getSharedPreferences("qodeep_ui", Context.MODE_PRIVATE)
        // 兼容旧版布尔配置 ui_new：开过第二套的迁移到档位 1
        var uiMode = if (uiPrefs.contains("ui_mode")) {
            uiPrefs.getInt("ui_mode", 0).coerceIn(0, 3)
        } else if (uiPrefs.getBoolean("ui_new", false)) {
            1
        } else {
            0
        }
        // 流光开关：默认开启
        LiquidGlass.shimmerOn = uiPrefs.getBoolean("shimmer_on", true)

        fun applyUiStyle(mode: Int) {
            LiquidGlass.uiMode = mode
            LiquidGlass.iceUi = mode == 1
            for (c in glassCards) {
                when (mode) {
                    1 -> LiquidGlass.applyIceFrosted(c)
                    2 -> LiquidGlass.applyLiquidFrosted(c)
                    3 -> LiquidGlass.applyDropletFrosted(c)
                    else -> LiquidGlass.applyFrosted(c)
                }
            }
            tvCurrentUi.text = when (mode) {
                1 -> "冰块玻璃"
                2 -> "液态玻璃"
                3 -> "液滴玻璃"
                else -> "浅冷灰玻璃"
            }
        }
        applyUiStyle(uiMode)

        // 圆形返回按钮：QQ 原生式纯白底 + 短款 Material 向左箭头（全代码绘制，不依赖 XML drawable）
        btnBack.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
        }
        btnBack.setImageDrawable(MaterialBackArrowDrawable(0xFF1A1A1A.toInt()))

        // 首页标题左侧的模块头像：走模块资源 Context 取完整圆形头像，画成正圆后匀速自转。
        // 不依赖 XML drawable / outline 裁剪（寄生环境资源 ID 在宿主资源表里不一定存在），
        // 圆形由 Drawable 自己用 BitmapShader 绘制，View 旋转时轮廓始终是正圆。
        // 图标只在首页出现：功能卡和所有二级界面都不加图标。
        val iconBmp = loadModuleIcon(mctx)
        if (iconBmp != null) {
            titleIcon.setImageDrawable(CircleBitmapDrawable(iconBmp))
            // 角度锚定 IconSpin 系统时钟，与 QQ 设置页入口头像严格同步、方向一致。
            startTitleSpin()
        } else {
            titleIcon.visibility = View.GONE
        }

        // ---- 同页切换（无跳转、无动画）：标题栏原地不动，仅替换标题栏下方内容区域 ----
        fun openDetail(title: String) {
            tvTitle.text = title            // 标题文字改为所选功能名
            titleIcon.visibility = View.GONE  // 二级界面一律不显示首页头像
            stopTitleSpin()                // 二级页停转，不空耗刷新
            btnBack.visibility = View.VISIBLE  // 标题栏内部左侧出现圆形返回箭头
            mainCards.visibility = View.GONE   // 隐藏主页卡片列表
            // 设置页下方显示 DexKit 缓存卡；等级加速页显示定时发说说卡；布局页显示更换背景卡；
            // 基础功能页显示 QQ保活 + 悬浮窗合并卡；聊天功能只保留标题
            diyContent.visibility = if (title == "布局") View.VISIBLE else View.GONE
            detailContent.visibility = if (title == "设置") View.VISIBLE else View.GONE
            levelContent.visibility = if (title == "等级加速") View.VISIBLE else View.GONE
            basicContent.visibility = if (title == "基础功能") View.VISIBLE else View.GONE
        }
        fun backHome() {
            tvTitle.text = "豆包"
            btnBack.visibility = View.GONE
            if (iconBmp != null) {
                titleIcon.visibility = View.VISIBLE
                startTitleSpin()           // 回首页接着转，角度由时钟推导、不重置
            }
            diyContent.visibility = View.GONE
            detailContent.visibility = View.GONE
            levelContent.visibility = View.GONE
            basicContent.visibility = View.GONE
            mainCards.visibility = View.VISIBLE
        }
        btnBack.setOnClickListener { backHome() }
        cardSettings.setOnClickListener { openDetail("设置") }
        cardDiy.setOnClickListener { openDetail("布局") }
        cardChat.setOnClickListener { openDetail("聊天功能") }
        cardLevel.setOnClickListener { openDetail("等级加速") }
        cardBasic.setOnClickListener { openDetail("基础功能") }

        // 自研弹窗宿主（弃用 XPopup 弹窗壳：库资源在寄生环境解析错乱出灰团/圆角失效）
        val popupHost = KitPopupHost(this)

        // 系统返回键：有弹窗先关弹窗；二级内容时回到主页，主页时退出
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupHost.isShowing) {
                    if (popupHost.dismissable()) popupHost.dismiss()
                    return
                }
                if (mainCards.visibility == View.GONE) {
                    backHome()
                } else {
                    finish()
                    // 退出同样要去掉转场：只处理「进入」的话，finish 时壳主题自带的
                    // 退出动画仍会播，QQ 设置页露出几帧后新页面才起，连续「返回→再点」
                    // 就会看到一次闪。finish 之后调用才对退出生效。
                    @Suppress("DEPRECATION")
                    runCatching { overridePendingTransition(0, 0) }
                }
            }
        })

        val tvCurrentBg = findViewById<TextView>(R.id.tvCurrentBg)
        val tvDexReady = findViewById<TextView>(R.id.tvDexReady)
        val tvDexDesc = findViewById<TextView>(R.id.tvDexDesc)
        val dexRebuildRow = findViewById<LinearLayout>(R.id.dexRebuildRow)
        val tvRebuild = findViewById<TextView>(R.id.tvRebuild)

        // ---- 卡片玻璃：去掉静态封面磨砂底图，改用「半透明薄白蒙层」直接透出底层 Spine 动画 ----
        // 之前气泡里铺的是静态 _cover.jpg 小图（Matrix 对齐），导致气泡背景静止、
        // 与底层会动的花草对不上。现改为：卡片只保留 LiquidGlass 的半透明薄白玻璃，
        // 底下的 WebView Spine 动画（花草树木会动）直接透过玻璃显示，气泡画面跟着动。
        val prefs = getSharedPreferences("qodeep_bg", Context.MODE_PRIVATE)
        var bgKey: String? = prefs.getString("bg_key", null)
        var bgDir: File? = null
        var items: List<JieqiCatalog.Item> = emptyList()

        fun refreshCurrent() {
            val current = items.firstOrNull { it.key == bgKey } ?: items.firstOrNull()
            // 未选择过时跟随清单第一张（默认端午）；已选择过则用保存值
            val active = bgKey ?: current?.key ?: "duanwu"
            tvCurrentBg.text = current?.name ?: "端午"
            // 静态封面：优先用进程内已解码的位图，命中即零 IO、零解码；
            // 未命中才同步解码并存进缓存。失败时仍交给 Glide 兜底。
            val cover = bgDir?.let { File(it, "${active}_cover.jpg") }
            if (cover != null && cover.exists()) {
                val cached = CoverCache.get(active)
                val bmp = cached ?: runCatching {
                    android.graphics.BitmapFactory.decodeFile(cover.absolutePath)
                }.getOrNull()
                if (bmp != null) {
                    if (cached == null) CoverCache.put(active, bmp)
                    bgCover.setImageBitmap(bmp)   // 首帧同步显示，立即有画面
                } else Glide.with(this).load(cover).centerCrop().into(bgCover)
            }
            // 动态 Spine 背景：WebView 加载（资源就绪后只 load 一次）
            if (bgDir != null && animLoadedKey != active) {
                animLoadedKey = active
                bgAnim.setBackgroundColor(Color.TRANSPARENT)
                bgAnim.loadUrl("https://appassets.qodeep.local/player.html?key=$active")
            }
        }

        setupWebView(bgAnim) { bgDir }

        // ---- 定时发说说：开关 + 发布后删除开关（与 MainHook 后台调度器共用 settings/mood.json 配置）----
        fun refreshMood() {
            val time = MoodConfig.getString(MoodScheduler.KEY_TIME, MoodScheduler.DEFAULT_TIME)
                .trim().takeIf { MoodScheduler.HH_MM_REGEX.matches(it) } ?: MoodScheduler.DEFAULT_TIME
            val text = MoodConfig.getString(MoodScheduler.KEY_TEXT, "")
                .trim().ifEmpty { MoodScheduler.DEFAULT_TEXT }
            val preview = if (text.length > 12) text.take(12) + "…" else text
            tvMoodSubtitle.text = "点击修改内容 · $time · $preview"
        }
        refreshMood()

        // ---- 四档 UI 滑块：点击依次切到下一套，最右后再回到第一套 ----
        val uiStyleSlider = UiStyleSlider(mctx).apply {
            position = uiMode
            onChanged = { mode ->
                uiMode = mode
                uiPrefs.edit().putInt("ui_mode", mode).apply()
                applyUiStyle(mode)
            }
        }
        uiStyleSwitchSlot.addView(
            uiStyleSlider,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // ---- 流光开关：默认开启，关闭后所有卡片边框扫光停止 ----
        val shimmerSwitch = MoodSwitch(mctx).apply {
            checked = LiquidGlass.shimmerOn
            onChanged = { on ->
                LiquidGlass.shimmerOn = on
                uiPrefs.edit().putBoolean("shimmer_on", on).apply()
                applyUiStyle(uiMode)   // 重新应用背景，让流光开关即时生效
            }
        }
        shimmerSwitchSlot.addView(
            shimmerSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // ---- QQ保活总开关 + 从里面单独拆出的悬浮窗开关：同卡 4+4 合并 ----
        // 配置与 MainHook/调度器共用 settings/mood.json（跨 ClassLoader 靠 mtime 热加载）。
        // 桌面独立 App 只是 UI 预览壳，控件外观/可点状态与 QQ 内保持一致（预览才有意义），
        // 但只有宿主进程才真正写配置、起保活；预览页的开关切换仅停留在视觉。
        val parasitic = KeepAlive.isHostProcess()
        val keepAliveSwitch = MoodSwitch(mctx).apply {
            checked = MoodConfig.getBoolean(KeepAlive.KEY_ENABLED, false)
            onChanged = { on ->
                if (parasitic) {
                    MoodConfig.putBoolean(KeepAlive.KEY_ENABLED, on)
                    runCatching { KeepAlive.refresh(this@SettingsActivity.applicationContext) }
                        .onFailure { android.util.Log.w("QOdeep", "QQ保活刷新失败: $it") }
                }
            }
        }
        // 页面打开时按配置对齐一次状态（例如 QQ 重启后配置仍为开，需重新挂起保活）
        runCatching { KeepAlive.refresh(this@SettingsActivity.applicationContext) }
            .onFailure { android.util.Log.w("QOdeep", "QQ保活初始化失败: $it") }
        keepAliveSwitchSlot.addView(
            keepAliveSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 悬浮窗独立开关：默认开，旧配置里没有这个 key 时保持原来的三项全开行为
        val pixelWindowSwitch = MoodSwitch(mctx).apply {
            checked = MoodConfig.getBoolean(KeepAlive.KEY_PIXEL_WINDOW, true)
            onChanged = { on ->
                if (parasitic) {
                    MoodConfig.putBoolean(KeepAlive.KEY_PIXEL_WINDOW, on)
                    runCatching { KeepAlive.refresh(this@SettingsActivity.applicationContext) }
                        .onFailure { android.util.Log.w("QOdeep", "悬浮窗刷新失败: $it") }
                }
            }
        }
        pixelWindowSwitchSlot.addView(
            pixelWindowSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // ---- 液态玻璃底部导航栏：修改主页底栏，默认关闭 ----
        // 只有宿主进程写配置；桌面预览页仅保留开关外观，不触碰功能。
        val liquidGlassBarSwitch = MoodSwitch(mctx).apply {
            checked = if (parasitic) {
                MoodConfig.getBoolean(MainHook.KEY_LIQUID_GLASS_BAR, false)
            } else {
                uiPrefs.getBoolean("liquid_glass_bar", false)
            }
            onChanged = { on ->
                uiPrefs.edit().putBoolean("liquid_glass_bar", on).apply()
                if (parasitic) {
                    MoodConfig.putBoolean(MainHook.KEY_LIQUID_GLASS_BAR, on)
                }
                // 底栏玻璃在 QQ 启动时一次性挂载，切换后必须重启 QQ 才生效，提示用户重启。
                val verb = if (on) "开启" else "关闭"
                popupHost.confirm(
                    "需重启 QQ",
                    "底部导航栏已$verb，重启 QQ 后生效。",
                    cancelText = "稍后",
                    okText = "立即重启",
                    onOk = { restartQQ() }
                )
            }
        }
        liquidGlassBarSwitchSlot.addView(
            liquidGlassBarSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 自绘开关（系统 Switch 资源在寄生环境不可靠，纯代码绘制零依赖）
        val moodSwitch = MoodSwitch(mctx).apply {
            checked = MoodConfig.getBoolean(MoodScheduler.KEY_ENABLED, false)
            onChanged = { on ->
                MoodConfig.putBoolean(MoodScheduler.KEY_ENABLED, on)
            }
        }
        moodSwitchSlot.addView(
            moodSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 发布后删除说说开关
        val moodDeleteSwitch = MoodSwitch(mctx).apply {
            checked = MoodConfig.getBoolean(MoodScheduler.KEY_DELETE_AFTER, false)
            onChanged = { on ->
                MoodConfig.putBoolean(MoodScheduler.KEY_DELETE_AFTER, on)
            }
        }
        moodDeleteSwitchSlot.addView(
            moodDeleteSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 点击「定时发说说」行 → 时间/内容输入弹窗（开关自消费点击，不冒泡到本行）
        moodRow.setOnClickListener {
            val sheet = MoodConfigSheet(mctx) { refreshMood() }
            popupHost.showCenter(sheet)
            sheet.onDismiss = { popupHost.dismiss() }
        }

        // ---- 空间等级签到：开关 + 签到后删除开关（与 MoodScheduler 调度器共用 settings/mood.json 配置）----
        fun refreshCheckin() {
            val time = MoodConfig.getString(MoodScheduler.KEY_CHECKIN_TIME, MoodScheduler.DEFAULT_CHECKIN_TIME)
                .trim().takeIf { MoodScheduler.HH_MM_REGEX.matches(it) }
                ?: MoodScheduler.DEFAULT_CHECKIN_TIME
            val text = MoodConfig.getString(MoodScheduler.KEY_CHECKIN_TEXT, "")
                .trim().ifEmpty { MoodScheduler.DEFAULT_CHECKIN_TEXT }
            val preview = if (text.length > 12) text.take(12) + "…" else text
            tvCheckinSubtitle.text = "点击修改内容 · $time · $preview"
        }
        refreshCheckin()

        val checkinSwitch = MoodSwitch(mctx).apply {
            checked = MoodConfig.getBoolean(MoodScheduler.KEY_CHECKIN_ENABLED, false)
            onChanged = { on ->
                MoodConfig.putBoolean(MoodScheduler.KEY_CHECKIN_ENABLED, on)
            }
        }
        checkinSwitchSlot.addView(
            checkinSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val checkinDeleteSwitch = MoodSwitch(mctx).apply {
            checked = MoodConfig.getBoolean(MoodScheduler.KEY_CHECKIN_DELETE_AFTER, false)
            onChanged = { on ->
                MoodConfig.putBoolean(MoodScheduler.KEY_CHECKIN_DELETE_AFTER, on)
            }
        }
        checkinDeleteSwitchSlot.addView(
            checkinDeleteSwitch,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 点击「空间等级签到」行 → 时间/内容输入弹窗
        checkinRow.setOnClickListener {
            val sheet = MoodConfigSheet(
                mctx,
                MoodScheduler.KEY_CHECKIN_TIME,
                MoodScheduler.KEY_CHECKIN_TEXT,
                MoodScheduler.DEFAULT_CHECKIN_TIME,
                MoodScheduler.DEFAULT_CHECKIN_TEXT,
                "空间等级签到设置"
            ) { refreshCheckin() }
            popupHost.showCenter(sheet)
            sheet.onDismiss = { popupHost.dismiss() }
        }

        // 更换背景卡 → 宫格选择弹窗
        findViewById<LinearLayout>(R.id.bgRow).setOnClickListener {
            if (items.isEmpty() || bgDir == null) return@setOnClickListener
            val sheet = BgPickerSheet(mctx, items, bgKey ?: items.firstOrNull()?.key ?: "duanwu", bgDir) { key ->
                prefs.edit().putString("bg_key", key).apply()
                bgKey = key
                refreshCurrent()
            }
            popupHost.showCenter(sheet)
            sheet.onDismiss = { popupHost.dismiss() }
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

        // 重建入口 → 进度弹窗（模态，轮询 progressKey）→ 完成/失败弹窗
        dexRebuildRow.setOnClickListener {
            if (rebuilding) return@setOnClickListener
            rebuilding = true
            tvRebuild.setTextColor(0xFF8A8A8A.toInt())
            tvDexReady.setTextColor(0xFF8A8A8A.toInt())
            tvDexReady.text = "查找中..."
            tvDexDesc.text = "正在重新查找，请稍候..."
            val progress = DexProgressSheet(mctx)
            val progressTicker = object : Runnable {
                override fun run() {
                    progress.setKey(DexKitFinder.progressKey ?: "准备开始...")
                    main.postDelayed(this, 100)
                }
            }
            // 模态进度弹窗：遮罩不可点关
            popupHost.showCenter(progress, cancelable = false)
            main.post(progressTicker)
            Thread {
                runCatching {
                    DexKitPageState.clearCache()
                    DexKitFinder.doFind {
                        main.post {
                            main.removeCallbacks(progressTicker)
                            rebuilding = false
                            tvRebuild.setTextColor(0xFF222222.toInt())
                            popupHost.confirm(
                                "查找完成", "DexKit 缓存已重建，重启 QQ 后生效",
                                cancelable = false,
                                onOk = {
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
                                }
                            )
                        }
                    }
                }.onFailure {
                    main.post {
                        main.removeCallbacks(progressTicker)
                        rebuilding = false
                        tvRebuild.setTextColor(0xFF222222.toInt())
                        popupHost.confirm("查找失败", "重建失败: ${it.message}")
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

    override fun onResume() {
        super.onResume()
        // 二级页时动画保持暂停；只有首页才恢复自转
        if (findViewById<View>(R.id.mainCards)?.visibility == View.VISIBLE) startTitleSpin()
    }

    override fun onPause() {
        stopTitleSpin()
        super.onPause()
    }

    override fun onDestroy() {
        stopTitleSpin()
        super.onDestroy()
    }

    /** 启动首页头像自转：每帧从 IconSpin 系统时钟取角度，与入口头像严格同步。 */
    private fun startTitleSpin() {
        if (titleSpinning) return
        titleSpinning = true
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!titleSpinning) return
                findViewById<ImageView>(R.id.titleIcon)?.rotation = IconSpin.currentDegrees()
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        spinCallback = cb
        android.view.Choreographer.getInstance().postFrameCallback(cb)
    }

    /** 停止首页头像自转。 */
    private fun stopTitleSpin() {
        titleSpinning = false
        spinCallback = null
    }

    /**
     * 重启 QQ 使需要重启才生效的改动（如底部导航栏玻璃）落地。
     *
     * 寄生在 QQ 进程时 kill 自己即杀 QQ；独立桌面进程时 am force-stop 杀 QQ。
     * 统一交给独立 shell 执行：先停掉 QQ，1 秒后 monkey 重新拉起，shell 不随
     * 当前进程死亡而中断（与 DexKit 重建后的重启同一套路）。
     */
    private fun restartQQ() {
        runCatching {
            Runtime.getRuntime().exec(
                arrayOf(
                    "sh", "-c",
                    "am force-stop com.tencent.mobileqq; " +
                        "sleep 1; monkey -p com.tencent.mobileqq -c android.intent.category.LAUNCHER 1"
                )
            )
        }.onFailure { android.util.Log.w("QOdeep", "重启 QQ 失败: $it") }
        // 寄生进程：上面 force-stop 会杀掉本进程；独立进程：延迟结束自己，避免残留设置页。
        main.postDelayed({
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }, 400)
    }

    /**
     * 取模块图标位图。寄生页面 Activity 自身的 resources 是 QQ 的，模块资源 ID
     * （0x69 段）在宿主资源表里不一定存在，优先用模块资源 Context；全失败时
     * 直接从模块 APK 里按文件名找 PNG，完全绕开资源表。
     */
    private fun loadModuleIcon(ctx: Context): Bitmap? {
        runCatching { android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.db_fg) }
            .getOrNull()?.let { return it }
        runCatching { android.graphics.BitmapFactory.decodeResource(resources, R.drawable.db_fg) }
            .getOrNull()?.let { return it }
        val apk = Env.moduleApkPath.ifEmpty {
            runCatching { applicationInfo.sourceDir }.getOrDefault("")
        }
        if (apk.isEmpty()) return null
        return runCatching {
            ZipFile(File(apk)).use { zip ->
                val e = zip.entries().asSequence()
                    .firstOrNull { it.name.contains("db_fg") && it.name.endsWith(".png", true) }
                if (e != null) {
                    zip.getInputStream(e).use { android.graphics.BitmapFactory.decodeStream(it) }
                } else null
            }
        }.getOrNull()
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

        // 动画层初始 alpha=0（见布局注释）：等页面首帧真正绘制出来再显示，
        // 避开 WebView 首次 attach 时合成器输出的那帧「暗一下」。
        // 用 AtomicBoolean 保证三层回调只生效一次；任何一层先到即可，
        // 且不做淡入（淡入会引入新的过渡帧，反而又是一次可见变化）。
        val revealed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun reveal() {
            if (revealed.compareAndSet(false, true)) wv.alpha = 1f
        }
        // 最终兜底：某些 ROM 不上报 onPageCommitVisible / onPageFinished，
        // 或页面加载失败时也必须把动画层显示出来，不能让背景永久空白。
        wv.postDelayed({ reveal() }, 1200)

        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageCommitVisible(view: WebView, url: String) {
                reveal()
            }

            override fun onPageFinished(view: WebView, url: String) {
                reveal()
            }

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
                    val headers = mapOf(
                        // 本地资源加长期缓存头：让 WebView 内部缓存 Spine 的
                        // atlas/json/webp 等静态资源，二次进出不再重走文件 IO 与解析，
                        // 消除「反复进出卡顿、背景重新加载」。
                        "Cache-Control" to "max-age=86400, immutable"
                    )
                    android.webkit.WebResourceResponse(mime, null, 200, "OK", headers, java.io.FileInputStream(f))
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
        //    （文件管理器可直接看到，findcache 目录就在这，优先写这里）
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
        private const val MODULE_PACKAGE = "com.qosed.qodeep"
    }
}

/**
 * 把位图 centerCrop 后裁成正圆绘制。
 * 用 BitmapShader 而不是 View 的 outline 裁剪：outline 裁剪发生在 View 合成阶段，
 * 与 View.rotation 叠加时部分 ROM 会出现方角/锯齿；shader 在 Drawable 内部画圆，
 * 无论 View 怎么转，内容始终是正圆。
 */
private class CircleBitmapDrawable(private val bmp: Bitmap) : android.graphics.drawable.Drawable() {

    private val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.shader = shader }
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        if (side <= 0f) return
        val r = side / 2f
        val cx = bounds.left + bounds.width() / 2f
        val cy = bounds.top + bounds.height() / 2f
        val scale = maxOf(side / bmp.width, side / bmp.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(cx - bmp.width * scale / 2f, cy - bmp.height * scale / 2f)
        shader.setLocalMatrix(matrix)
        canvas.drawCircle(cx, cy, r, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * 短款 Material「向左箭头」图标（arrow_back，24dp 参考系）。
 * 全代码绘制矢量路径，不依赖文字符号 / XML drawable，寄生环境资源安全。
 * 根据 bounds 等比缩放居中，紧凑短小。
 */
private class MaterialBackArrowDrawable(private val color: Int) : android.graphics.drawable.Drawable() {

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = android.graphics.Paint.Style.FILL
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h) / 24f
        canvas.save()
        canvas.translate(
            bounds.left + w / 2f - 12f * s,
            bounds.top + h / 2f - 12f * s
        )
        canvas.scale(s, s)
        val p = android.graphics.Path().apply {
            moveTo(20f, 11f)
            lineTo(7.83f, 11f)
            lineTo(13.42f, 5.41f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.42f, 18.59f)
            lineTo(7.83f, 13f)
            lineTo(20f, 13f)
            close()
        }
        canvas.drawPath(p, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** 更换背景弹窗：三列宫格显示各节令静态封面，点选立即生效并持久化。 */
class BgPickerSheet(
    context: Context,
    private val items: List<JieqiCatalog.Item>,
    private val currentKey: String,
    private val dir: File?,
    private val onSelect: (String) -> Unit
) : LinearLayout(context) {

    /** 点选或关闭时由宿主调用 */
    var onDismiss: (() -> Unit)? = null

    init {
        // 寄生环境必须用模块 ClassLoader + 模块资源 inflate
        android.view.LayoutInflater.from(context).inflate(R.layout.popup_bg_picker, this, true)
        // 液态玻璃弹窗卡片：跟随当前 UI 风格（冰块 or 浅冷灰），带流光
        LiquidGlass.applyThemed(this)
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
                val radius = 16f * context.resources.displayMetrics.density
                val bg = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(
                        if (selected) (2f * context.resources.displayMetrics.density).toInt() else 0,
                        0xFF0A7AFF.toInt()
                    )
                }
                iv.background = bg
                iv.clipToOutline = true
                iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                tv.text = item.name
                tv.setTextColor(if (selected) 0xFF0A7AFF.toInt() else 0xFF5A6068.toInt())
                // 缩略图不用 Glide（寄生环境图片加载不可靠，失败时留灰块）：
                // 直接用 BitmapFactory 同步读本地封面，读不到就保持白底，绝不出现灰块。
                val cover = dir?.let { File(it, "${item.key}_cover.jpg") }
                if (cover != null && cover.exists()) {
                    val bmp = runCatching {
                        android.graphics.BitmapFactory.decodeFile(cover.absolutePath)
                    }.getOrNull()
                    if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageDrawable(null)
                } else {
                    iv.setImageDrawable(null)
                }
                h.itemView.setOnClickListener {
                    onSelect(item.key)
                    onDismiss?.invoke()
                }
            }
        }
    }
}

/** DexKit 重建进度弹窗：模态，外部轮询更新当前任务 key。 */
class DexProgressSheet(context: Context) : FrameLayout(context) {

    /** 关闭时由宿主调用 */
    var onDismiss: (() -> Unit)? = null

    private val bar: View

    init {
        android.view.LayoutInflater.from(context).inflate(R.layout.popup_dex_progress, this, true)
        // 液态玻璃弹窗卡片：跟随当前 UI 风格，带流光
        LiquidGlass.applyThemed(this)
        // 进度条：纯代码不确定动画（一个来回扫动的蓝色圆角条），
        // 不用系统 ProgressBar（其 indeterminate 动画资源在寄生环境会渲染成灰块）
        bar = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f * context.resources.displayMetrics.density
                setColor(0xFF0A7AFF.toInt())
            }
        }
        val host = findViewById<View>(R.id.progress)
        (host as ViewGroup).addView(bar, ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        val anim = object : Runnable {
            private var dir = 1
            private var frac = 0f
            override fun run() {
                frac += 0.03f * dir
                if (frac >= 1f) { frac = 1f; dir = -1 }
                if (frac <= 0f) { frac = 0f; dir = 1 }
                val w = host.width
                if (w > 0) {
                    val bw = (w * 0.4f).toInt()
                    val left = (frac * (w - bw)).toInt()
                    bar.layout(left, 0, left + bw, host.height)
                    bar.visibility = View.VISIBLE
                }
                postDelayed(this, 16)
            }
        }
        post(anim)
    }

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

    private fun cacheFile(): File = File(File(Env.moduleDataDir, "findcache"), "dex_map.json")

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

/** 自绘开关：iOS 风格圆角轨道 + 白色滑块，纯代码绘制，寄生环境零资源依赖。 */
private class MoodSwitch(context: Context) : View(context) {

    var checked = false
        set(value) {
            field = value
            invalidate()
        }
    var onChanged: ((Boolean) -> Unit)? = null

    // 与页面冷灰玻璃语言一致：中性灰玻璃轨道 + 细白描边 + 冷白玻璃圆点。
    // 开态不再用蓝色大色块，改用中性冷灰（同卡片玻璃色系），关态更淡，两者仅深浅区分。
    private val density = context.resources.displayMetrics.density
    private val trackOnTop = 0xFF9AA1A9.toInt()      // 开态：中性冷灰玻璃（上略亮）
    private val trackOnBottom = 0xFF7A828C.toInt()
    private val trackOffTop = 0x4D000000.toInt()     // 关态：淡黑玻璃，透出卡片底
    private val trackOffBottom = 0x26000000.toInt()
    private val borderColor = 0x8FFFFFFF.toInt()     // 细白描边，对齐 LiquidGlass.BORDER_COLOR 风格
    private val thumbTop = 0xFFFFFFFF.toInt()        // 圆点：冷白玻璃（上亮下略透）
    private val thumbBottom = 0xE6F2F3F5.toInt()     // 对齐卡片/输入框的冷灰底 F2F3F5
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    init {
        setOnClickListener {
            checked = !checked
            onChanged?.invoke(checked)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val trackRadius = h / 2f
        val strokeW = dpf(1.1f)
        val inset = strokeW / 2f
        trackRect.set(inset, inset, w - inset, h - inset)

        // 轨道填充：上下渐变玻璃
        paint.shader = LinearGradient(
            0f, trackRect.top, 0f, trackRect.bottom,
            intArrayOf(
                if (checked) trackOnTop else trackOffTop,
                if (checked) trackOnBottom else trackOffBottom
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, paint)
        paint.shader = null

        // 细白描边
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.color = borderColor
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, paint)

        // 冷白玻璃圆点：开态在右、关态在左，上亮下略透，融入卡片冷灰玻璃色系
        paint.style = Paint.Style.FILL
        val d = h * 0.78f
        val dotInset = (h - d) / 2f
        val cx = if (checked) w - dotInset - d / 2f else dotInset + d / 2f
        val cy = h / 2f
        val r = d / 2f
        paint.shader = LinearGradient(
            0f, cy - r, 0f, cy + r,
            intArrayOf(thumbTop, thumbBottom),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun dpf(v: Float): Float = v * density
}

/**
 * 四档位 UI 滑块：沿用开关的圆角轨道 / 细白描边 / 冷白圆点风格，
 * 圆点在四个等距位置停靠，点击依次前进（0→1→2→3→0）。
 * 圆点直径与 MoodSwitch 完全一致（h * 0.78），轨道相应加宽以容纳四档。
 */
private class UiStyleSlider(context: Context) : View(context) {

    var position = 0
        set(value) {
            field = value.coerceIn(0, 3)
            invalidate()
        }
    var onChanged: ((Int) -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val trackOnTop = 0xFF9AA1A9.toInt()
    private val trackOnBottom = 0xFF7A828C.toInt()
    private val borderColor = 0x8FFFFFFF.toInt()
    private val thumbTop = 0xFFFFFFFF.toInt()
    private val thumbBottom = 0xE6F2F3F5.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()

    init {
        setOnClickListener {
            position = (position + 1) % 4
            onChanged?.invoke(position)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val trackRadius = h / 2f
        val strokeW = density * 1.1f
        val inset = strokeW / 2f
        trackRect.set(inset, inset, w - inset, h - inset)

        paint.shader = LinearGradient(
            0f, trackRect.top, 0f, trackRect.bottom,
            intArrayOf(trackOnTop, trackOnBottom),
            null,
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.color = borderColor
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, paint)

        // 与 MoodSwitch 同尺寸（h * 0.78）的冷白圆点，四个等距停靠点
        paint.style = Paint.Style.FILL
        val d = h * 0.78f
        val dotInset = (h - d) / 2f
        val r = d / 2f
        val leftX = dotInset + r
        val rightX = w - dotInset - r
        val cx = leftX + (rightX - leftX) * position / 3f
        val cy = h / 2f
        paint.shader = LinearGradient(
            0f, cy - r, 0f, cy + r,
            intArrayOf(thumbTop, thumbBottom),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }
}

/** 定时说说/签到配置弹窗：发送时间 + 内容，保存时校验并持久化到 settings/mood.json。 */
class MoodConfigSheet(
    context: Context,
    private val keyTime: String = MoodScheduler.KEY_TIME,
    private val keyText: String = MoodScheduler.KEY_TEXT,
    private val defTime: String = MoodScheduler.DEFAULT_TIME,
    private val defText: String = MoodScheduler.DEFAULT_TEXT,
    private val title: String = "定时说说设置",
    private val onSaved: () -> Unit
) : LinearLayout(context) {

    var onDismiss: (() -> Unit)? = null

    private lateinit var etTime: EditText
    private lateinit var etText: EditText
    private lateinit var tvError: TextView

    init {
        orientation = VERTICAL
        // 液态玻璃弹窗卡片：跟随当前 UI 风格，带流光
        LiquidGlass.applyThemed(this)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val pad = dp(20)
        setPadding(pad, pad, pad, pad)

        addView(TextView(context).apply {
            text = this@MoodConfigSheet.title
            setTextColor(0xFF111111.toInt())
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = "仅需 HH:mm，无需日期，每天同一时间触发一次"
            setTextColor(0xFF5A6068.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })

        addView(editLabel("发送时间"))
        etTime = editField(InputType.TYPE_CLASS_TEXT, defTime, false)
        addView(etTime)

        addView(editLabel("说说内容"))
        etText = editField(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            defText,
            true
        )
        addView(etText)

        tvError = TextView(context).apply {
            setTextColor(0xFFE5484D.toInt())
            textSize = 12f
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        addView(tvError)

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }
        row.addView(TextView(context).apply {
            text = "取消"
            setTextColor(0xFF6B7280.toInt())
            textSize = 15f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onDismiss?.invoke() }
        })
        row.addView(TextView(context).apply {
            text = "保存"
            setTextColor(0xFF0A7AFF.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener { save() }
        })
        addView(row)

        etTime.setText(MoodConfig.getString(keyTime, defTime))
        etText.setText(MoodConfig.getString(keyText, ""))
    }

    private fun save() {
        val time = etTime.text.toString().trim()
        if (!MoodScheduler.HH_MM_REGEX.matches(time)) {
            tvError.text = "时间格式应为 HH:mm（如 08:30）"
            tvError.visibility = View.VISIBLE
            return
        }
        MoodConfig.putString(keyTime, time)
        MoodConfig.putString(keyText, etText.text.toString().trim())
        onSaved()
        onDismiss?.invoke()
    }

    private fun editLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0xFF111111.toInt())
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun editField(type: Int, hint: String, multiline: Boolean): EditText =
        EditText(context).apply {
            setTextColor(0xFF111111.toInt())
            textSize = 15f
            setHintTextColor(0xFFB4B8BF.toInt())
            setHint(hint)
            inputType = type
            if (multiline) {
                gravity = Gravity.TOP
                minLines = 3
                maxLines = 4
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF2F3F5.toInt())
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
