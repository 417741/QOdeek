package com.qosed.qodeep

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.animation.LinearInterpolator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatDetailDialog
import com.qosed.qodeep.chat.ChatFeatures
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatPanelBuilder
import com.qosed.qodeep.levelboost.MoodConfig
import com.qosed.qodeep.levelboost.MoodScheduler
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * 模块 UI 的唯一尺寸基准：屏幕宽度归一化。
 *
 * 不同品牌手机的 PPI、系统「显示大小 / 字体大小」设置各不相同，直接沿用系统
 * densityDpi 会让同一套 dp/sp 在各手机上占屏比例不同（表现为「别的手机特别大」）。
 * 这里强制把真实屏幕宽度映射为固定的 [DESIGN_WIDTH_DP]，所有手机一律按同一
 * 比例绘制：62dp 的卡片在任何品牌手机上都是屏宽的 62/400，完全一致。
 *
 * 代码里所有 dp 换算必须走 [dp]，禁止再读 context.resources.displayMetrics.density；
 * XML 里的 dp/sp 由 [lockedConfig] / [lockedMetrics] 锁定的模块 Resources 解析。
 */
object UiScale {
    /** 设计稿宽度（dp）。改这一处即可整体等比缩放全部界面，一处生效全局统一。
     *  数值调大 → 界面整体变小；调小 → 整体变大。 */
    private const val DESIGN_WIDTH_DP = 400f

    @Volatile
    private var scaleDpi = 0

    /** 所有密度都由整数 densityDpi 导出，保证与系统按 Configuration 重算的结果完全一致。 */
    val densityDpi: Int get() = if (scaleDpi > 0) scaleDpi else 480
    val density: Float get() = densityDpi / 160f

    fun init(context: Context) {
        if (scaleDpi > 0) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val dm = runCatching {
            val real = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(real)
            if (real.widthPixels > 0) real else Resources.getSystem().displayMetrics
        }.getOrDefault(Resources.getSystem().displayMetrics)
        // 取物理短边作基准：横竖屏切换不会让界面大小跳动，各品牌按各自短边等比。
        // getRealMetrics 给的是物理像素，不受系统「显示大小 / 字体大小」设置影响。
        // 关键：UI 与背景封面都用屏幕比例缩放，才能保证列表和背景的相对位置、
        // 遮挡关系在不同分辨率的手机上完全一致。
        val short = minOf(dm.widthPixels, dm.heightPixels)
        if (short > 0) scaleDpi = ((short / DESIGN_WIDTH_DP) * 160f + 0.5f).toInt().coerceIn(160, 1280)
    }

    fun lockedConfig(base: Configuration): Configuration = Configuration(base).apply {
        fontScale = 1.0f
        densityDpi = this@UiScale.densityDpi
        // 不动 screenWidthDp / smallestScreenWidthDp：它们决定资源限定符选择，
        // 改了会让 QQ 壳主题选到另一套资源（寄生环境有 Resources$NotFoundException 风险）。
        // 尺寸一致性只需 density，与这两个字段无关。
    }

    fun lockedMetrics(base: DisplayMetrics): DisplayMetrics = DisplayMetrics().apply {
        setTo(base)
        density = this@UiScale.density
        densityDpi = this@UiScale.densityDpi
        scaledDensity = density
    }

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dp(v: Float): Float = v * density

    /**
     * 把一棵刚 inflate 出来的 View 树里已解析成 px 的尺寸，按
     * 「UiScale 目标密度 / 实际 inflate 密度」重算一遍。
     *
     * 这是跨品牌一致的硬兜底：不依赖 createPackageContext / Configuration /
     * DisplayMetrics 锁定是否生效（寄生环境里这些都可能被宿主 QQ 绕过），
     * 只改 View 上已经算好的 px，任何路径下都必然生效。
     */
    fun normalize(root: android.view.View) {
        // 直接取 View 自己解析 XML 属性时用的 Resources，避免外部传错 Context
        val dm = root.resources.displayMetrics
        val kLayout = if (dm.density > 0f) density / dm.density else 1f
        val kText = if (dm.scaledDensity > 0f) density / dm.scaledDensity else kLayout
        // 总是遍历：k=1 时乘 1 幂等，但把 sp 语义的字号固化成 px，
        // 后续系统字体设置变化也不会再影响已生成的界面。
        normalizeView(root, kLayout, kText)
    }

    private fun normalizeView(v: android.view.View, k: Float, kt: Float) {
        v.layoutParams?.let { lp ->
            if (lp.width > 0) lp.width = (lp.width * k + 0.5f).toInt()
            if (lp.height > 0) lp.height = (lp.height * k + 0.5f).toInt()
            if (lp is android.view.ViewGroup.MarginLayoutParams) {
                lp.topMargin = (lp.topMargin * k).toInt()
                lp.bottomMargin = (lp.bottomMargin * k).toInt()
                // layout_marginStart/End 是独立字段，只改 left/right 会漏掉
                if (lp.isMarginRelative) {
                    lp.marginStart = (lp.marginStart * k).toInt()
                    lp.marginEnd = (lp.marginEnd * k).toInt()
                } else {
                    lp.leftMargin = (lp.leftMargin * k).toInt()
                    lp.rightMargin = (lp.rightMargin * k).toInt()
                }
            }
            v.layoutParams = lp
        }
        // paddingStart/End 同理，用 setPaddingRelative 才不会丢相对 padding
        v.setPaddingRelative(
            (v.paddingStart * k).toInt(), (v.paddingTop * k).toInt(),
            (v.paddingEnd * k).toInt(), (v.paddingBottom * k).toInt()
        )
        if (v.minimumHeight > 0) v.minimumHeight = (v.minimumHeight * k).toInt()
        if (v.minimumWidth > 0) v.minimumWidth = (v.minimumWidth * k).toInt()
        if (v is TextView) {
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, v.textSize * kt)
            if (v.compoundDrawablePadding != 0) {
                v.compoundDrawablePadding = (v.compoundDrawablePadding * k).toInt()
            }
            if (v.lineSpacingExtra != 0f) {
                v.setLineSpacing(v.lineSpacingExtra * k, v.lineSpacingMultiplier)
            }
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) normalizeView(v.getChildAt(i), k, kt)
        }
    }
}

/**
 * 模块 UI 字号统一入口：直接按 UiScale 写成 px。
 * 直接给 textSize 赋值是 sp 语义，会再乘 View 的 scaledDensity（跟随系统字体大小），
 * 在寄生环境里 View 的 Context 可能是 QQ 宿主，字号就不可控了。
 */
fun TextView.fixedTextSize(sp: Float) {
    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, UiScale.dp(sp))
}

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

    /** 复读图标选择器：选完把图片拷到模块数据目录，路径存 chat.json。 */
    private val repeatIconPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            Thread {
                try {
                    val dir = File(Env.moduleDataDir, "data")
                    dir.mkdirs()
                    val file = File(dir, "repeat_icon.img")
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    ChatConfig.putString("repeat_msg_icon", file.absolutePath)
                    main.post {
                        android.widget.Toast.makeText(
                            applicationContext, "复读图标已更新", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("QOdeep", "保存复读图标失败: $t")
                }
            }.start()
        } else {
            // 取消选择 = 清空自定义图标，回到默认纸飞机
            ChatConfig.putString("repeat_msg_icon", "")
            main.post {
                android.widget.Toast.makeText(
                    applicationContext, "已恢复默认图标", android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
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
     * 只覆盖 Configuration，绝不替换资源表：QQ 壳主题引用的资源 ID 仍在原表里可正常解析，
     * 因此不会触发寄生环境的 Resources$NotFoundException。
     */
    private fun lockedUiConfig(res: Resources): Configuration = UiScale.lockedConfig(res.configuration)



    override fun attachBaseContext(newBase: Context) {
        UiScale.init(newBase)
        super.attachBaseContext(newBase)
        // 只覆盖 Configuration，绝不直接改 Activity.resources.displayMetrics：
        // 寄生环境那是 QQ 宿主共享的 Resources 实例，改了会影响整个 QQ 进程。
        // 模块 UI 全部用 mctx 的独立锁定 Resources，见 moduleContext()。
        runCatching { applyOverrideConfiguration(UiScale.lockedConfig(newBase.resources.configuration)) }
            .onFailure { android.util.Log.w("QOdeep", "锁定 UI 缩放失败: $it") }
    }

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
        // attachBaseContext 在寄生环境未必被调到，这里再算一次（幂等），
        // 保证 UiScale 一定拿到真实屏幕物理短边。
        UiScale.init(this)
        // 首选：模块是正常安装的 APK，用 createPackageContext 拿模块自己的 Resources/ClassLoader。
        // 全程系统封装，不反射 AssetManager、不手动 addAssetPath/addLoaders，
        // 在 MIUI/Android 14 上不会出现空资源表或 hidden API 失败。
        try {
            createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            ).let { base ->
                // 只让资源走锁定配置，Context 本身（theme / ClassLoader）保持原样，
                // 避免 createConfigurationContext 丢掉壳主题导致 inflate 解析 ?attr 失败
                val lockedRes = base.createConfigurationContext(lockedUiConfig(base.resources)).resources
                // 直接强制 DisplayMetrics：XML inflate 时所有 dp/sp 都按这三个值换算，
                // 不依赖 ROM 是否真的把 Configuration.densityDpi 应用到 metrics
                lockedRes.displayMetrics.apply {
                    density = UiScale.density
                    densityDpi = UiScale.densityDpi
                    scaledDensity = UiScale.density
                }
                moduleCtx = object : android.content.ContextWrapper(base) {
                    override fun getResources(): Resources = lockedRes
                    override fun getSystemService(name: String): Any? {
                        if (name == Context.LAYOUT_INFLATER_SERVICE) {
                            val raw = super.getSystemService(name)
                            if (raw is android.view.LayoutInflater) return raw.cloneInContext(this)
                        }
                        return super.getSystemService(name)
                    }
                }
                return moduleCtx!!
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
        android.util.Log.w("QOdeep", "moduleContext 资源表全失败，改用宿主 Context + 锁定尺寸重算")
        // 绝不直接 return this：那样 inflate 会完全沿用 QQ 宿主的 density/fontScale，
        // 各品牌手机、系统「显示大小/字体大小」都会把同一套布局渲染成不同大小。
        // 此时 mctx 只能是 `this`，但 inflate 后会走 normalizeUi() 把 px 尺寸重算，
        // 这条路径同样得到与 UiScale 一致的结果。
        moduleCtx = this
        return this
    }

    /**
     * 构造只含模块 APK 资源的 Resources。
     * - Android 11+（API 30）：用公开的 ResourcesProvider/ResourcesLoader 加载 APK，
     *   不再反射 hidden API addAssetPath（在 MIUI/Android 14 上会静默失败导致空资源表崩溃）。
     * - Android 10 及以下：回退反射 addAssetPath。
     */
    private fun buildModuleResources(apkPath: String, base: Resources): Resources {
        // 与 lockedUiConfig 同一套缩放：density / scaledDensity 都取 UiScale 的归一化值
        val cfg = UiScale.lockedConfig(base.configuration)
        val dm = UiScale.lockedMetrics(base.displayMetrics)
        if (Build.VERSION.SDK_INT >= 30) {
            val pfd = android.os.ParcelFileDescriptor.open(
                File(apkPath), android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val provider = android.content.res.loader.ResourcesProvider.loadFromApk(pfd)
            val loader = android.content.res.loader.ResourcesLoader().apply { addProvider(provider) }
            val am = AssetManager::class.java.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
            return Resources(am, dm, cfg).apply {
                addLoaders(loader)
            }
        }
        val am = AssetManager::class.java.newInstance()
        AssetManager::class.java
            .getDeclaredMethod("addAssetPath", String::class.java)
            .apply { isAccessible = true }
            .invoke(am, apkPath)
        return Resources(am, dm, cfg)
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
        // 关键：无论上面走哪条路径，都把已解析成 px 的尺寸重算到统一基准，
        // 让所有品牌手机 / 系统字体设置下渲染结果完全一致。
        runCatching { UiScale.normalize(content) }
            .onFailure { android.util.Log.w("QOdeep", "UI 尺寸归一化失败: $it") }
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
        val cardAccount = findViewById<FrameLayout>(R.id.cardAccount)
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
        val cardDailySign = findViewById<FrameLayout>(R.id.cardDailySign)
        val dailySignRow = findViewById<LinearLayout>(R.id.dailySignRow)
        val dailySignSwitchSlot = findViewById<FrameLayout>(R.id.dailySignSwitchSlot)
        val tvDailySignSubtitle = findViewById<TextView>(R.id.tvDailySignSubtitle)
        val cardBigVip = findViewById<FrameLayout>(R.id.cardBigVip)
        val bigVipRow = findViewById<LinearLayout>(R.id.bigVipRow)
        val bigVipSwitchSlot = findViewById<FrameLayout>(R.id.bigVipSwitchSlot)
        val tvBigVipSubtitle = findViewById<TextView>(R.id.tvBigVipSubtitle)
        val cardAddFriend = findViewById<FrameLayout>(R.id.cardAddFriend)
        val addFriendRow = findViewById<LinearLayout>(R.id.addFriendRow)
        val addFriendSwitchSlot = findViewById<FrameLayout>(R.id.addFriendSwitchSlot)
        val tvAddFriendSubtitle = findViewById<TextView>(R.id.tvAddFriendSubtitle)
        val cardSpaceBrowse = findViewById<FrameLayout>(R.id.cardSpaceBrowse)
        val spaceBrowseRow = findViewById<LinearLayout>(R.id.spaceBrowseRow)
        val spaceBrowseSwitchSlot = findViewById<FrameLayout>(R.id.spaceBrowseSwitchSlot)
        val tvSpaceBrowseSubtitle = findViewById<TextView>(R.id.tvSpaceBrowseSubtitle)
        val cardRunLog = findViewById<FrameLayout>(R.id.cardRunLog)
        val chatContent = findViewById<FrameLayout>(R.id.chatContent)
        val runLogScroll = findViewById<ScrollView>(R.id.runLogScroll)
        val tvRunLog = findViewById<TextView>(R.id.tvRunLog)
        val btnRunAll = findViewById<TextView>(R.id.btnRunAll)

        // 卡片液态玻璃：统一走 LiquidGlass 设计系统（浅冷灰透明玻璃 + 细白描边 + 大圆角）。
        // 不用 XML drawable —— 寄生环境资源引用不可靠，圆角还决定玻璃层的裁剪轮廓。
        // detailContent 只作二级内容容器，本身不做玻璃卡；DexKit 卡已移入其中，单独保持玻璃质感
        val glassCards = listOf(cardTitle, cardAccount, cardBg, cardDiy, cardDexkit, cardSettings, cardChat, cardLevel, cardMoodSwitch, cardCheckin, cardDailySign, cardBigVip, cardAddFriend, cardSpaceBrowse, cardRunLog, cardUiStyle, cardShimmer, cardLiquidGlassBar, cardBasic, cardKeepAlive, findViewById<View>(R.id.searchBox))

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

        // 聊天功能页卡片是运行时生成的，风格切换时一并重刷（详情卡区在页面下面的 chatPanel 里）
        var chatPanel: ChatPanelBuilder? = null

        fun applyUiStyle(mode: Int) {
            LiquidGlass.uiMode = mode
            LiquidGlass.iceUi = mode == 1
            for (c in glassCards + (chatPanel?.glassCards ?: emptyList())) {
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

        // ---- 账号卡：收起显示头像+昵称+QQ号，点击展开模块版本与 QQ 版本 ----
        // 数据全部走 HostEnv（等价 QE 旧版 MobileQQ.peekAppRuntime() 反射）：
        // uin 用 getCurrentAccountUin，昵称用 getCurrentNickname/getCurrentAccount 多级反射，
        // 不联网、不受公开接口缓存影响，换头像/昵称后实时读新值。
        runCatching {
            val dp = UiScale.density
            fun Int.dpx() = (this * dp).toInt()

            fun readNickFromObj(obj: Any?): String? {
                if (obj == null) return null
                for (f in arrayOf("nickname", "nickName", "mNickname", "strNick")) {
                    val v = com.qosed.qodeep.chat.ChatRef.getString(obj, f)
                    if (!v.isNullOrEmpty()) return v
                }
                for (m in arrayOf("getNickname", "getNick")) {
                    val v = com.qosed.qodeep.chat.ChatRef.invoke(obj, com.qosed.qodeep.chat.ChatRef.methodByName(obj.javaClass, m, 0)) as? String
                    if (!v.isNullOrEmpty()) return v
                }
                return null
            }
            fun resolveNickname(): String {
                val app = com.qosed.qodeep.chat.qq.HostEnv.appRuntime() ?: return ""
                // 1) getCurrentNickname()
                var v = com.qosed.qodeep.chat.ChatRef.invoke(app, com.qosed.qodeep.chat.ChatRef.methodByName(app.javaClass, "getCurrentNickname", 0)) as? String
                if (!v.isNullOrEmpty()) return v
                // 2) getCurrentAccount() 返回对象里读昵称
                val acct = com.qosed.qodeep.chat.ChatRef.invoke(app, com.qosed.qodeep.chat.ChatRef.methodByName(app.javaClass, "getCurrentAccount", 0))
                v = readNickFromObj(acct)
                if (!v.isNullOrEmpty()) return v
                // 3) currentAccount 字段
                v = readNickFromObj(com.qosed.qodeep.chat.ChatRef.getField(app, "currentAccount"))
                if (!v.isNullOrEmpty()) return v
                // 4) 直接读 app 上的昵称字段
                return readNickFromObj(app).orEmpty()
            }

            val accountUin = com.qosed.qodeep.chat.qq.HostEnv.currentUin()
            val nickname = resolveNickname()

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dpx(), 14.dpx(), 16.dpx(), 14.dpx())
            }

            // 头像：透明头像直接透出卡片背景，不垫白底；加载完成前用浅灰圆形占位
            val avatarBox = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(56.dpx(), 56.dpx()).apply { marginEnd = 12.dpx() }
            }
            val avatar = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0x1A000000)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
            avatarBox.addView(avatar)

            val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tvName = TextView(this).apply {
                text = nickname.ifEmpty { "QQ用户" }
                textSize = 18f
                setTextColor(0xFF000000.toInt())
                typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            }
            val tvUin = TextView(this).apply {
                textSize = 14f
                text = SpannableStringBuilder("QQ: ").apply {
                    setSpan(ForegroundColorSpan(0xFF9E9E9E.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(accountUin.ifEmpty { "未知" })
                    setSpan(ForegroundColorSpan(0xFF1A1A1A.toInt()), 4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            nameCol.addView(tvName)
            nameCol.addView(tvUin)

            val headRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(avatarBox)
            headRow.addView(nameCol)
            panel.addView(headRow)

            // 展开区：分隔线 + 模块版本 / QQ 版本两行，默认隐藏
            val expandView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            expandView.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dpx()).apply { topMargin = 12.dpx() }
                setBackgroundColor(0x1A000000)
            })
            fun versionRow(label: String, value: String): View = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10.dpx(), 0, 0)
                addView(TextView(this@SettingsActivity).apply {
                    text = label; textSize = 15f; setTextColor(0xFF9E9E9E.toInt())
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = value; textSize = 16f; setTextColor(0xFF1A1A1A.toInt())
                    typeface = Typeface.defaultFromStyle(Typeface.BOLD)
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
            // 模块版本：直接读模块 APK 的 versionName（寄生环境查模块包信息会被拒，BuildConfig 又未开启生成）
            val moduleVer = runCatching {
                val pm = packageManager
                val apk = Env.moduleApkPath.ifEmpty { applicationInfo.sourceDir }
                pm.getPackageArchiveInfo(apk, 0)?.versionName.orEmpty()
            }.getOrDefault("")
            val qqVer = runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault("")
            expandView.addView(versionRow("模块版本", moduleVer.ifEmpty { "未知" }))
            expandView.addView(versionRow("QQ版本", qqVer.ifEmpty { "未知" }))
            panel.addView(expandView)

            cardAccount.addView(panel)

            // 点击切换展开/收起
            var expanded = false
            cardAccount.setOnClickListener {
                expanded = !expanded
                expandView.visibility = if (expanded) View.VISIBLE else View.GONE
            }

            // 头像：QQ 官方头像接口 + 时间戳参数，换头像后加载到新图（避免 Glide 缓存旧图）
            if (accountUin.isNotEmpty()) {
                val faceUrl = "https://q1.qlogo.cn/g?b=qq&nk=$accountUin&s=100&t=${System.currentTimeMillis()}"
                runCatching {
                    Glide.with(this)
                        .load(faceUrl)
                        .into(avatar)
                }
            }
        }.onFailure { android.util.Log.w("QOdeep", "账号卡构建失败: $it") }

        // 聊天功能页：卡片由功能清单驱动生成，开关直接写 chat.json（宿主 hook 每次回调读文件）
        runCatching {
            val panel = ChatPanelBuilder(
                context = mctx,
                onSwitchChanged = { f, on ->
                    if (on && ChatFeatures.NEEDS_RESTART.contains(f.key)) {
                        android.widget.Toast.makeText(applicationContext, "已启用，重启 QQ 后生效", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                onDetail = { f ->
                    if (f.key == ChatKeys.REPEAT_MSG) {
                        repeatIconPicker.launch("image/*")
                    } else {
                        ChatDetailDialog.show(this, f, mctx)
                    }
                }
            )
            chatPanel = panel
            chatContent.addView(panel.build())
            applyUiStyle(uiMode)
        }.onFailure { android.util.Log.w("QOdeep", "聊天功能页构建失败: $it") }

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
            // 「启动」按钮只属于等级加速页：点了就无视开关与当日标记立即执行全部功能
            btnRunAll.visibility = if (title == "等级加速") View.VISIBLE else View.GONE
            mainCards.visibility = View.GONE   // 隐藏主页卡片列表
            // 设置页下方显示 DexKit 缓存卡；等级加速页显示定时发说说卡；布局页显示更换背景卡；
            // 基础功能页显示 QQ保活 + 悬浮窗合并卡；聊天功能只保留标题
            diyContent.visibility = if (title == "布局") View.VISIBLE else View.GONE
            detailContent.visibility = if (title == "设置") View.VISIBLE else View.GONE
            levelContent.visibility = if (title == "等级加速") View.VISIBLE else View.GONE
            basicContent.visibility = if (title == "基础功能") View.VISIBLE else View.GONE
            chatContent.visibility = if (title == "聊天功能") View.VISIBLE else View.GONE
        }
        fun backHome() {
            tvTitle.text = "豆包"
            btnBack.visibility = View.GONE
            btnRunAll.visibility = View.GONE
            if (iconBmp != null) {
                titleIcon.visibility = View.VISIBLE
                startTitleSpin()           // 回首页接着转，角度由时钟推导、不重置
            }
            diyContent.visibility = View.GONE
            detailContent.visibility = View.GONE
            levelContent.visibility = View.GONE
            basicContent.visibility = View.GONE
            chatContent.visibility = View.GONE
            mainCards.visibility = View.VISIBLE
        }
        // 返回箭头的点击在搜索逻辑定义之后注册（需访问 searchPanel / closeSearch）
        cardSettings.setOnClickListener { openDetail("设置") }
        cardDiy.setOnClickListener { openDetail("布局") }
        cardChat.setOnClickListener { openDetail("聊天功能") }
        cardLevel.setOnClickListener { openDetail("等级加速") }
        cardBasic.setOnClickListener { openDetail("基础功能") }

        // ---- 搜索：标题栏右侧黑色放大镜，首页与所有二级界面常驻。
        // 点击弹出标题下方搜索框，实时过滤全部功能项，点结果跳到对应页并滚动定位。
        // 图标与搜索框背景全部代码绘制（寄生环境不引用 XML drawable）。 ----
        val btnSearch = findViewById<ImageView>(R.id.btnSearch)
        val searchPanel = findViewById<LinearLayout>(R.id.searchPanel)
        val searchBox = findViewById<View>(R.id.searchBox)
        val etSearch = findViewById<EditText>(R.id.etSearch)
        val searchResults = findViewById<LinearLayout>(R.id.searchResults)
        val ivSearchInBox = findViewById<ImageView>(R.id.ivSearchInBox)
        val scrollHost = findViewById<ScrollView>(R.id.scroll)
        // 所有动态尺寸/字号统一取 UiScale 的归一化基准，不再读任何 Context 的 density
        val uiRes = mctx.resources
        fun sdp(v: Int): Int = UiScale.dp(v)
        fun sdpf(v: Float): Float = UiScale.dp(v)
        // 返回 UiScale 基准下的 px：getDimension 是 sp*scaledDensity，换算到 UiScale.density
        fun sspDimen(id: Int): Float {
            val sd = uiRes.displayMetrics.scaledDensity
            return uiRes.getDimension(id) * (UiScale.density / sd)
        }
        // dimen 的 px 按实际 density 还原为 dp，再乘目标密度，跨品牌恒定
        fun sdim(id: Int): Int = UiScale.dp(uiRes.getDimension(id) / uiRes.displayMetrics.density).toInt()
        btnSearch.setImageDrawable(MaterialSearchIconDrawable(0xFF1A1A1A.toInt()))
        ivSearchInBox.setImageDrawable(MaterialSearchIconDrawable(0xFF9E9E9E.toInt()))

        class Entry(val group: String, val title: String, val sub: String, val target: View)

        // 收集文本：递归取容器内前两个非空 TextView 文本（标题 + 副标题/状态）
        fun textsOf(root: View): List<String> {
            val out = mutableListOf<String>()
            fun walk(v: View) {
                if (out.size >= 2) return
                if (v is TextView && !v.text.isNullOrBlank()) out += v.text.toString().trim()
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            return out
        }

        // 构建索引：主页 + 各二级页/聊天功能页内的每张功能卡。
        // 直接遍历现有视图树，无需另维护一份功能清单，新增卡片自动可搜。
        // 判定规则：只把「直接子节点里就有标题文本」的容器当成一张卡，否则继续深入。
        // 之前用"容器里能找到任何文本就记卡"，二级页的外层包装容器会把整页吞成
        // 一条（只取到第一张卡的标题），页内其余功能全部搜不到。
        fun buildIndex(): List<Entry> {
            val list = mutableListOf<Entry>()
            val pages = listOf(
                "首页" to mainCards, "布局" to diyContent, "设置" to detailContent,
                "等级加速" to levelContent, "基础功能" to basicContent,
                "聊天功能" to chatContent
            )
            for ((group, container) in pages) {
                fun collect(v: ViewGroup) {
                    for (i in 0 until v.childCount) {
                        val c = v.getChildAt(i)
                        if (c.visibility == View.GONE) continue
                        var hasDirectText = false
                        if (c is ViewGroup) {
                            for (j in 0 until c.childCount) {
                                if (c.getChildAt(j) is TextView) { hasDirectText = true; break }
                            }
                        }
                        if (hasDirectText) {
                            val t = textsOf(c)
                            // 超长文本是日志/状态区，不是功能标题，跳过
                            if (t.isNotEmpty() && t[0].length <= 20) {
                                list += Entry(group, t[0], t.getOrElse(1) { "" }, c)
                            }
                        } else if (c is ViewGroup) collect(c)
                    }
                }
                if (container is ViewGroup) collect(container)
            }
            return list
        }

        // 目标 View 在 ScrollView 内容里累计的 Y 偏移，用于结果定位滚动
        fun offsetInScroll(v: View): Int {
            var y = 0
            var cur: View = v
            while (cur !== scrollHost && cur.parent is View) {
                y += cur.top
                cur = cur.parent as View
            }
            return y
        }

        var searchIndex: List<Entry>? = null

        // 搜索前所在页面：null=主页，其余为二级页标题。
        // 搜索全程不改变标题栏与各页面状态，关闭时原地恢复，
        // 避免「在聊天功能里点搜索却先跳回主界面」。
        var searchFrom: String? = null

        fun currentPage(): String? = when {
            diyContent.visibility == View.VISIBLE -> "布局"
            detailContent.visibility == View.VISIBLE -> "设置"
            levelContent.visibility == View.VISIBLE -> "等级加速"
            basicContent.visibility == View.VISIBLE -> "基础功能"
            chatContent.visibility == View.VISIBLE -> "聊天功能"
            else -> null
        }

        fun showPageContent(page: String?) {
            mainCards.visibility = if (page == null) View.VISIBLE else View.GONE
            diyContent.visibility = if (page == "布局") View.VISIBLE else View.GONE
            detailContent.visibility = if (page == "设置") View.VISIBLE else View.GONE
            levelContent.visibility = if (page == "等级加速") View.VISIBLE else View.GONE
            basicContent.visibility = if (page == "基础功能") View.VISIBLE else View.GONE
            chatContent.visibility = if (page == "聊天功能") View.VISIBLE else View.GONE
        }

        fun hideAllPageContent() {
            mainCards.visibility = View.GONE
            diyContent.visibility = View.GONE
            detailContent.visibility = View.GONE
            levelContent.visibility = View.GONE
            basicContent.visibility = View.GONE
            chatContent.visibility = View.GONE
        }

        fun closeSearch(goHome: Boolean = true) {
            searchPanel.visibility = View.GONE
            etSearch.setText("")
            runCatching {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
            }
            if (goHome) showPageContent(searchFrom)
            searchFrom = null
        }

        btnBack.setOnClickListener {
            if (searchPanel.visibility == View.VISIBLE) closeSearch() else backHome()
        }

        fun renderResults(query: String) {
            val q = query.trim()
            // 未输入关键字：不显示结果列表，恢复进入搜索前的页面，UI 与打开前完全一致
            if (q.isEmpty()) {
                searchResults.removeAllViews()
                showPageContent(searchFrom)
                return
            }
            if (searchIndex == null) searchIndex = buildIndex()
            val items = searchIndex!!.filter {
                it.title.contains(q, true) || it.sub.contains(q, true) || it.group.contains(q, true)
            }
            searchResults.removeAllViews()
            hideAllPageContent()
            if (items.isEmpty()) {
                val empty = TextView(mctx).apply {
                    text = "没有匹配的功能"
                    setTextColor(0xFF3A3F44.toInt())
                    fixedTextSize(14f)
                    gravity = Gravity.CENTER
                    setPadding(0, sdp(28), 0, sdp(28))
                }
                searchResults.addView(empty)
                return
            }
            for (e in items) {
                val row = LinearLayout(mctx).apply {
                    orientation = LinearLayout.VERTICAL
                    // 结果卡用与主题完全一致的玻璃背景（四档 UI 同款，含流光）
                    when (LiquidGlass.uiMode) {
                        1 -> LiquidGlass.applyIceFrosted(this)
                        2 -> LiquidGlass.applyLiquidFrosted(this)
                        3 -> LiquidGlass.applyDropletFrosted(this)
                        else -> LiquidGlass.applyFrosted(this)
                    }
                    setPadding(sdp(18), sdp(15), sdp(18), sdp(15))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = sdim(R.dimen.card_spacing) }
                }
                row.addView(TextView(mctx).apply {
                    text = e.title
                    setTextColor(0xFF000000.toInt())
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sspDimen(R.dimen.card_title_size))
                    setTypeface(typeface, Typeface.BOLD)
                })
                val subLine = buildString {
                    append(e.group)
                    if (e.sub.isNotBlank() && e.sub != e.title) append(" · ").append(e.sub)
                }
                row.addView(TextView(mctx).apply {
                    text = subLine
                    setTextColor(0xFF1A1A1A.toInt())
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sspDimen(R.dimen.card_sub_size))
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, sdp(3), 0, 0)
                })
                row.setOnClickListener {
                    closeSearch(goHome = false)
                    if (e.group == "首页") backHome() else openDetail(e.group)
                    searchPanel.post {
                        runCatching { scrollHost.smoothScrollTo(0, (offsetInScroll(e.target) - sdp(12)).coerceAtLeast(0)) }
                    }
                }
                searchResults.addView(row)
            }
        }

        btnSearch.setOnClickListener {
            if (searchPanel.visibility == View.VISIBLE) {
                closeSearch()
            } else {
                // 原地展开搜索框：记录当前页，标题栏/页面状态都不动
                searchFrom = currentPage()
                searchPanel.visibility = View.VISIBLE
                renderResults(etSearch.text.toString())
                etSearch.requestFocus()
                runCatching {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (searchPanel.visibility == View.VISIBLE) renderResults(s?.toString() ?: "")
            }
        })

        // 自研弹窗宿主（弃用 XPopup 弹窗壳：库资源在寄生环境解析错乱出灰团/圆角失效）
        val popupHost = KitPopupHost(this, mctx)

        // 系统返回键：有弹窗先关弹窗；二级内容时回到主页，主页时退出
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popupHost.isShowing) {
                    if (popupHost.dismissable()) popupHost.dismiss()
                    return
                }
                // 搜索面板打开时：关闭搜索并原地回到进入搜索前的页面
                if (searchPanel.visibility == View.VISIBLE) {
                    closeSearch()
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

        // ---- 等级加速：日签打卡 / 大会员签到 / 自动加好友 / 空间浏览 ----
        // 四个功能结构一致（开关 + 自定义时间），统一用 registerTimeCard 绑定。

        fun addTimeCard(
            card: FrameLayout,
            row: LinearLayout,
            switchSlot: FrameLayout,
            subtitle: TextView,
            enabledKey: String,
            timeKey: String,
            defTime: String,
            title: String
        ) {
            fun refresh() {
                val time = MoodConfig.getString(timeKey, defTime)
                    .trim().takeIf { MoodScheduler.HH_MM_REGEX.matches(it) } ?: defTime
                subtitle.text = "点击修改时间 · $time"
            }
            refresh()

            val sw = MoodSwitch(mctx).apply {
                checked = MoodConfig.getBoolean(enabledKey, false)
                onChanged = { on -> MoodConfig.putBoolean(enabledKey, on) }
            }
            switchSlot.addView(
                sw,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            row.setOnClickListener {
                val sheet = MoodTimeSheet(mctx, timeKey, defTime, title) { refresh() }
                popupHost.showCenter(sheet)
                sheet.onDismiss = { popupHost.dismiss() }
            }
        }

        addTimeCard(
            cardDailySign, dailySignRow, dailySignSwitchSlot, tvDailySignSubtitle,
            MoodScheduler.KEY_DAILY_SIGN_ENABLED, MoodScheduler.KEY_DAILY_SIGN_TIME,
            MoodScheduler.DEFAULT_DAILY_SIGN_TIME, "QQ 日签打卡设置"
        )
        addTimeCard(
            cardBigVip, bigVipRow, bigVipSwitchSlot, tvBigVipSubtitle,
            MoodScheduler.KEY_BIGVIP_ENABLED, MoodScheduler.KEY_BIGVIP_TIME,
            MoodScheduler.DEFAULT_BIGVIP_TIME, "大会员签到设置"
        )
        addTimeCard(
            cardAddFriend, addFriendRow, addFriendSwitchSlot, tvAddFriendSubtitle,
            MoodScheduler.KEY_ADD_FRIEND_ENABLED, MoodScheduler.KEY_ADD_FRIEND_TIME,
            MoodScheduler.DEFAULT_ADD_FRIEND_TIME, "自动加好友设置"
        )
        addTimeCard(
            cardSpaceBrowse, spaceBrowseRow, spaceBrowseSwitchSlot, tvSpaceBrowseSubtitle,
            MoodScheduler.KEY_SPACE_BROWSE_ENABLED, MoodScheduler.KEY_SPACE_BROWSE_TIME,
            MoodScheduler.DEFAULT_SPACE_BROWSE_TIME, "空间浏览设置"
        )

        // ---- 标题右侧「启动」：无视开关与当日标记立即执行全部功能，过程实时打进日志卡 ----
        btnRunAll.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiScale.dp(17f)
            setColor(0xE62F6FE0.toInt())
        }
        val runLog = ArrayList<String>()
        val runLogTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun appendRunLog(line: String) {
            runLog.add("${runLogTime.format(Date())}  $line")
            if (runLog.size > 300) runLog.removeAt(0)
            tvRunLog.text = runLog.joinToString("\n")
            runLogScroll.post { runLogScroll.fullScroll(View.FOCUS_DOWN) }
        }

        btnRunAll.setOnClickListener {
            runLog.clear()
            tvRunLog.text = ""
            appendRunLog("开始执行（无视开关与当日标记）")
            btnRunAll.isEnabled = false
            btnRunAll.alpha = 0.45f
            MoodScheduler.runAllNow { line ->
                runOnUiThread {
                    appendRunLog(line)
                    // 收尾行到达后恢复按钮，允许再次点击
                    if (line.startsWith("全部执行结束") || line.contains("未登录") ||
                        line.startsWith("执行异常") || line.contains("还没结束")
                    ) {
                        btnRunAll.isEnabled = true
                        btnRunAll.alpha = 1f
                    }
                }
            }
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

/** 标题栏搜索图标：Material 风格黑色放大镜（圆环 + 右下短柄），全代码绘制矢量。 */
private class MaterialSearchIconDrawable(private val color: Int) : android.graphics.drawable.Drawable() {

    private val paint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val s = minOf(w, h) / 24f
        canvas.save()
        canvas.translate(bounds.left + w / 2f - 12f * s, bounds.top + h / 2f - 12f * s)
        canvas.scale(s, s)
        paint.strokeWidth = 2.2f
        // 镜身圆环（镜心略偏左上，给右下柄留空间）
        canvas.drawCircle(10.5f, 10.5f, 6.2f, paint)
        // 镜柄
        canvas.drawLine(15.2f, 15.2f, 20f, 20f, paint)
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
        // 只归一化 XML inflate 出来的子 View，别碰 Sheet 自身（layoutParams 由外部按目标 px 设置）
        runCatching { for (i in 0 until childCount) UiScale.normalize(getChildAt(i)) }
        // 液态玻璃弹窗卡片：跟随当前 UI 风格（冰块 or 浅冷灰），带流光
        LiquidGlass.applyThemed(this)
        val grid = findViewById<RecyclerView>(R.id.bgGrid)
        grid.layoutManager = GridLayoutManager(context, 3)
        grid.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val v = android.view.LayoutInflater.from(context)
                    .inflate(R.layout.item_bg_thumb, parent, false)
                runCatching { UiScale.normalize(v) }
                return object : RecyclerView.ViewHolder(v) {}
            }

            override fun getItemCount(): Int = items.size

            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val item = items[pos]
                val iv = h.itemView.findViewById<ImageView>(R.id.ivThumb)
                val tv = h.itemView.findViewById<TextView>(R.id.tvName)
                val selected = item.key == currentKey
                val radius = UiScale.dp(16f)
                val bg = GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(
                        if (selected) UiScale.dp(2) else 0,
                        0xFF0A7AFF.toInt()
                    )
                }
                iv.background = bg
                iv.clipToOutline = true
                iv.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                tv.text = item.name
                tv.setTextColor(if (selected) 0xFF0A7AFF.toInt() else 0xFF1A1A1A.toInt())
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
        runCatching { for (i in 0 until childCount) UiScale.normalize(getChildAt(i)) }
        // 液态玻璃弹窗卡片：跟随当前 UI 风格，带流光
        LiquidGlass.applyThemed(this)
        // 进度条：纯代码不确定动画（一个来回扫动的蓝色圆角条），
        // 不用系统 ProgressBar（其 indeterminate 动画资源在寄生环境会渲染成灰块）
        bar = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = UiScale.dp(3f)
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
    private val density = UiScale.density
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

    private val density = UiScale.density
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
/**
 * HH:mm 时间输入：小时与分钟是两个独立数字框，中间冒号为固定 TextView。
 * 整条只包着文字（无多余内边距/最小高度）；分钟框删空后继续按删除键会自动跳到
 * 小时框接着删，冒号不会挡住连续删除，不必手动点小时框。
 */
class TimeInputView(context: Context) : LinearLayout(context) {
    private val etHour = numberBox("HH")
    private val etMinute = numberBox("mm")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        minimumHeight = 0
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(0xFFF2F3F5.toInt())
        }
        setPadding(dp(8), dp(2), dp(8), dp(2))
        val boxLp = { LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) }
        addView(etHour, boxLp())
        addView(TextView(context).apply {
            text = ":"
            setTextColor(0xFF000000.toInt())
            fixedTextSize(15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), 0)
        }, boxLp())
        addView(etMinute, boxLp())
        // 小时输满两位自动把焦点交给分钟框（仅用户正在输入时，初始化赋值不抢焦点）
        etHour.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.length == 2 && etHour.hasFocus()) etMinute.requestFocus()
            }
        })
        // 分钟已空时再按删除键：跳到小时框并删掉小时末位，实现连续回删
        etMinute.onEmptyBackspace = {
            etHour.requestFocus()
            etHour.setSelection(etHour.text.length)
            val h = etHour.text
            if (h.isNotEmpty()) h.delete(h.length - 1, h.length)
        }
    }

    /**
     * 拦截输入法在空文本框里按下的删除键。软键盘通常走 deleteSurroundingText
     * 而非 KeyEvent，所以两条路径都要拦；onKeyListener 拦不到软键盘。
     */
    private inner class TimeBox(context: Context) : EditText(context) {
        var onEmptyBackspace: (() -> Unit)? = null

        override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
            val base = super.onCreateInputConnection(outAttrs) ?: return null
            return object : android.view.inputmethod.InputConnectionWrapper(base, false) {
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    if (text.isNullOrEmpty() && beforeLength > 0) {
                        onEmptyBackspace?.invoke()
                        return true
                    }
                    return super.deleteSurroundingText(beforeLength, afterLength)
                }

                override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                    if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                        event.keyCode == android.view.KeyEvent.KEYCODE_DEL && text.isNullOrEmpty()
                    ) {
                        onEmptyBackspace?.invoke()
                        return true
                    }
                    return super.sendKeyEvent(event)
                }
            }
        }
    }

    private fun numberBox(hint: String): TimeBox = TimeBox(context).apply {
        this.hint = hint
        setHintTextColor(0xFFB4B8BF.toInt())
        setTextColor(0xFF000000.toInt())
        fixedTextSize(15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(2))
        background = null
        isSingleLine = true
        // 贴合文字：去掉系统自带上下留白和默认最小高度，空内容时仍保留可点区域
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        minWidth = dp(24)
        setPadding(0, 0, 0, 0)
    }

    fun setTime(value: String) {
        val parts = value.split(":")
        etHour.setText(parts.getOrNull(0)?.take(2) ?: "")
        etMinute.setText(parts.getOrNull(1)?.take(2) ?: "")
        etHour.setSelection(etHour.text.length)
    }

    fun setHintTime(value: String) {
        val parts = value.split(":")
        etHour.hint = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "HH"
        etMinute.hint = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "mm"
    }

    /** 返回规范化 HH:mm（不足两位补零）；某一段为空时保持原样，交给正则校验报错。 */
    fun time(): String {
        val h = etHour.text.toString().trim()
        val m = etMinute.text.toString().trim()
        if (h.isEmpty() || m.isEmpty()) return "$h:$m"
        return "${h.padStart(2, '0')}:${m.padStart(2, '0')}"
    }

    private fun dp(v: Int): Int = UiScale.dp(v)
}

/**
 * 只配置时间的弹窗（等级加速类功能用：日签/大会员/加好友/空间浏览）。
 * 结构与 MoodConfigSheet 一致，仅去掉内容输入框。
 */
class MoodTimeSheet(
    context: Context,
    private val keyTime: String,
    private val defTime: String,
    private val title: String,
    private val onSaved: () -> Unit
) : LinearLayout(context) {

    var onDismiss: (() -> Unit)? = null

    private lateinit var etTime: TimeInputView
    private lateinit var tvError: TextView

    init {
        orientation = VERTICAL
        LiquidGlass.applyThemed(this)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val pad = dp(20)
        setPadding(pad, pad, pad, pad)

        addView(TextView(context).apply {
            text = this@MoodTimeSheet.title
            setTextColor(0xFF000000.toInt())
            fixedTextSize(18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = "仅需 HH:mm，无需日期，每天同一时间触发一次"
            setTextColor(0xFF1A1A1A.toInt())
            fixedTextSize(12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        })

        addView(TextView(context).apply {
            text = "触发时间"
            setTextColor(0xFF000000.toInt())
            fixedTextSize(14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(6))
        })
        etTime = TimeInputView(context)
        etTime.setHintTime(defTime)
        addView(etTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        tvError = TextView(context).apply {
            setTextColor(0xFFE5484D.toInt())
            fixedTextSize(12f)
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
            setTextColor(0xFF444444.toInt())
            fixedTextSize(15f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onDismiss?.invoke() }
        })
        row.addView(TextView(context).apply {
            text = "保存"
            setTextColor(0xFF0A7AFF.toInt())
            fixedTextSize(15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener { save() }
        })
        addView(row)

        etTime.setTime(MoodConfig.getString(keyTime, defTime))
    }

    private fun save() {
        val time = etTime.time()
        if (!MoodScheduler.HH_MM_REGEX.matches(time)) {
            tvError.text = "时间格式应为 HH:mm（如 08:30）"
            tvError.visibility = View.VISIBLE
            return
        }
        MoodConfig.putString(keyTime, time)
        onSaved()
        onDismiss?.invoke()
    }

    private fun dp(v: Int): Int = UiScale.dp(v)
}

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

    private lateinit var etTime: TimeInputView
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
            setTextColor(0xFF000000.toInt())
            fixedTextSize(18f)
            typeface = Typeface.DEFAULT_BOLD
        })
        addView(TextView(context).apply {
            text = "仅需 HH:mm，无需日期，每天同一时间触发一次"
            setTextColor(0xFF1A1A1A.toInt())
            fixedTextSize(12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, 0)
        })

        addView(editLabel("发送时间"))
        etTime = TimeInputView(context).also { it.setHintTime(defTime) }
        addView(etTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addView(editLabel("说说内容"))
        etText = editField(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            defText,
            true
        )
        addView(etText)

        tvError = TextView(context).apply {
            setTextColor(0xFFE5484D.toInt())
            fixedTextSize(12f)
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
            setTextColor(0xFF444444.toInt())
            fixedTextSize(15f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { onDismiss?.invoke() }
        })
        row.addView(TextView(context).apply {
            text = "保存"
            setTextColor(0xFF0A7AFF.toInt())
            fixedTextSize(15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setOnClickListener { save() }
        })
        addView(row)

        etTime.setTime(MoodConfig.getString(keyTime, defTime))
        etText.setText(MoodConfig.getString(keyText, ""))
    }

    private fun save() {
        val time = etTime.time()
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
        setTextColor(0xFF000000.toInt())
        fixedTextSize(14f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun editField(type: Int, hint: String, multiline: Boolean): EditText =
        EditText(context).apply {
            setTextColor(0xFF000000.toInt())
            fixedTextSize(15f)
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

    private fun dp(v: Int): Int = UiScale.dp(v)
}
