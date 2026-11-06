package com.qosed.qodeep

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** FunBox 风格卡片：圆角 + 半透明白底。 */
private fun Modifier.cardStyle(): Modifier = this
    .clip(RoundedCornerShape(16.dp))
    .background(Color(0xCCFFFFFF))

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 寄生打开时窗口主题继承自 QQ 壳 Activity（深色/黑底），强制换成系统亮色主题
        runCatching { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        super.onCreate(savedInstanceState)
        // 寄生页面与 MainHook 不同 ClassLoader，Env 是空副本，从 Intent 恢复宿主环境
        intent?.let { i ->
            i.getStringExtra(ENV_DATA_DIR)?.let { Env.moduleDataDir = it }
            i.getStringExtra(ENV_NATIVE_LIB_DIR)?.let { Env.moduleNativeLibDir = it }
            i.getStringExtra(ENV_HOST_APK)?.let { Env.hostApkPath = it }
        }
        setContent { SettingsPage() }
    }

    companion object {
        const val ENV_DATA_DIR = "qodeep.env.dataDir"
        const val ENV_NATIVE_LIB_DIR = "qodeep.env.nativeLibDir"
        const val ENV_HOST_APK = "qodeep.env.hostApk"
    }
}

@Composable
private fun SettingsPage() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("qodeep_bg", Context.MODE_PRIVATE) }
    var bgKey by remember { mutableStateOf(prefs.getString("bg_key", null)) }
    var showPicker by remember { mutableStateOf(false) }
    // 背景诊断信息（WebView 页面标题 + JS console 报错），排查后移除
    var bgDebug by remember { mutableStateOf("bg: 解压中...") }
    // 寄生在 QQ 里时 file:///android_asset/ 指向 QQ 的 APK，必须先把模块 APK 里的背景资源解压到本地
    val bgDir by produceState<File?>(null) {
        value = withContext(Dispatchers.IO) { BgAssets.ensure(context) }
    }
    val items = remember(bgDir) { bgDir?.let { JieqiCatalog.load(it) } ?: emptyList() }
    val current = items.firstOrNull { it.key == bgKey } ?: items.firstOrNull()
    // 未选择过时跟随清单第一张（默认端午）；已选择过则用保存值
    val activeKey = bgKey ?: current?.key ?: "duanwu"

    Box(Modifier.fillMaxSize().background(Color(0xFFF2F4F8))) {
        // 动态 Spine 背景（资源解压完成后再加载），铺满全屏
        JieqiBackground(
            activeKey, bgDir,
            onDebug = { msg -> bgDebug = "bg: $msg" },
            modifier = Modifier.fillMaxSize()
        )

        // 诊断浮层（排查背景不显示用，随后移除）
        Text(
            text = bgDebug,
            color = Color(0xCCFF3B30),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        )

        // 内容：一列圆角半透明卡片叠在背景上，可滚动（FunBox 风格）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题卡
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .cardStyle()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "QOdeep", color = Color(0xFF222222), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(text = "QQ 功能增强模块", color = Color(0xFF8A8A8A), fontSize = 13.sp)
            }

            // 更换背景卡
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .cardStyle()
                    .clickable { showPicker = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1A0A7AFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "景", color = Color(0xFF0A7AFF), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = "更换背景", color = Color(0xFF222222), fontSize = 16.sp)
                    Text(text = "节令动态海报（骨骼动画），点击选择", color = Color(0xFF8A8A8A), fontSize = 12.sp)
                }
                Text(text = current?.name ?: "端午", color = Color(0xFF8A8A8A), fontSize = 14.sp)
                Spacer(Modifier.size(6.dp))
                Text(text = "›", color = Color(0xFFB0B0B0), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // DexKit 缓存卡
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .cardStyle()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                DexKitSection()
            }
        }
    }

    if (showPicker) {
        BgPickerDialog(
            items = items,
            currentKey = activeKey,
            bgDir = bgDir,
            onSelect = { key ->
                prefs.edit().putString("bg_key", key).apply()
                bgKey = key
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun DexKitSection() {
    val context = LocalContext.current
    var readyText by remember { mutableStateOf("读取中...") }
    var descText by remember { mutableStateOf("") }
    var rebuilding by remember { mutableStateOf(false) }
    var progressKey by remember { mutableStateOf<String?>(null) }

    // 进度弹窗文本轮询 DexKitFinder.progressKey（后台线程写入，100ms 刷新足够跟手）；
    // LaunchedEffect 离开组合时自动取消
    LaunchedEffect(Unit) {
        while (true) {
            progressKey = DexKitFinder.progressKey
            kotlinx.coroutines.delay(100)
        }
    }

    LaunchedEffect(rebuilding) {
        if (rebuilding) {
            readyText = "查找中..."
            descText = "正在重新查找，请稍候..."
        } else {
            val (n, desc) = withContext(Dispatchers.IO) { DexKitPageState.readStatus() }
            readyText = if (n < 0) "未知" else "$n / ${DexKitPageState.TOTAL} 已就绪"
            descText = desc
        }
    }

    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "DexKit 缓存", color = Color(0xFF222222), fontSize = 16.sp)
            Text(
                text = readyText,
                color = if (rebuilding) Color(0xFF8A8A8A) else Color(0xFF34C759),
                fontSize = 14.sp
            )
        }
        Text(text = descText, color = Color(0xFF8A8A8A), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !rebuilding) {
                    rebuilding = true
                    DexKitFinder.toastContext = context.applicationContext
                    Thread {
                        runCatching {
                            // 删缓存 → 当场重新扫描（期间弹「查找方法中」Toast）→ 写新缓存
                            DexKitPageState.clearCache()
                            DexKitFinder.doFind {
                                Handler(Looper.getMainLooper()).post {
                                    rebuilding = false
                                    Toast.makeText(
                                        context, "DexKit 缓存已重建，重启QQ后生效",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }.onFailure {
                            Handler(Looper.getMainLooper()).post {
                                rebuilding = false
                                Toast.makeText(context, "重建失败: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "重建 DexKit 缓存",
                color = if (rebuilding) Color(0xFF8A8A8A) else Color(0xFF222222),
                fontSize = 16.sp
            )
            Text(text = "›", color = Color(0xFFB0B0B0), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(text = "清除缓存并重新扫描，完成后重启 QQ 生效", color = Color(0xFFFF9500), fontSize = 12.sp)
    }

    // 重建期间的模态进度框（仿 QEdge）：无按钮、点外部关闭无效，
    // 遮罩挡住整页操作，查找完成后 rebuilding 置 false 自动消失
    if (rebuilding) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(text = "QOdeep查找方法中", fontSize = 18.sp) },
            text = {
                Text(
                    text = progressKey ?: "准备开始查找...",
                    color = Color.Gray,
                    fontSize = 15.sp
                )
            }
        )
    }
}

/** 全屏透明 WebView，从本地解压目录播放 Spine 骨骼动画背景。 */
@Composable
private fun JieqiBackground(key: String, dir: File?, onDebug: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
                settings.domStorageEnabled = true
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                // 背景层不拦截点击
                isClickable = false
                isFocusable = false
                isLongClickable = false
                setOnTouchListener { _, _ -> true }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript("document.title || '(标题空)'") { onDebug("title=$it") }
                    }
                }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(console: android.webkit.ConsoleMessage?): Boolean {
                        onDebug("console: ${console?.message()}")
                        return true
                    }
                }
            }
        },
        update = { wv ->
            val d = dir
            if (d != null) {
                val url = "file://${d.absolutePath}/player.html?key=$key"
                if (wv.tag != url) {
                    wv.tag = url
                    onDebug("加载: ${d.name}/player.html")
                    wv.loadUrl(url)
                }
            } else {
                onDebug("bg: 解压失败(null)")
            }
        },
        onRelease = { it.destroy() }
    )
}

/** 更换背景弹窗：三列宫格显示各节令静态封面，点选立即生效并持久化。 */
@Composable
private fun BgPickerDialog(
    items: List<JieqiCatalog.Item>,
    currentKey: String,
    bgDir: File?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(text = "更换背景", fontSize = 18.sp) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(380.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items) { item ->
                    val selected = item.key == currentKey
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onSelect(item.key) }
                    ) {
                        val shape = RoundedCornerShape(10.dp)
                        var mod = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.6f)
                            .clip(shape)
                        if (selected) {
                            mod = mod.border(2.dp, Color(0xFF0A7AFF), shape)
                        }
                        Box(mod) {
                            CoverThumb(item.key, bgDir, Modifier.fillMaxSize())
                        }
                        Text(
                            text = item.name,
                            fontSize = 12.sp,
                            color = if (selected) Color(0xFF0A7AFF) else Color.Gray
                        )
                    }
                }
            }
        }
    )
}

/** 从解压目录加载节令封面缩略图。 */
@Composable
private fun CoverThumb(key: String, dir: File?, modifier: Modifier = Modifier) {
    val bmp by produceState<ImageBitmap?>(null, key, dir) {
        if (dir == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(File(dir, "${key}_cover.jpg").absolutePath, opts)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(Color(0xFFEDEDED))) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 节令海报清单（解压目录里的 jieqi.json）。 */
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
        val fingerprint = runCatching {
            val info = context.packageManager.getApplicationInfo(MODULE_PKG, 0)
            val apk = info.sourceDir ?: return null
            val f = File(apk)
            "$apk:${f.length()}:${f.lastModified()}"
        }.getOrNull() ?: return null

        synchronized(this) {
            if (cachedDir != null && cachedFingerprint == fingerprint) return cachedDir
            val dir = File(context.cacheDir, "qodeep_bg")
            val marker = File(dir, MARKER)
            if (!dir.exists() || runCatching { marker.readText().trim() != fingerprint }.getOrDefault(true)) {
                dir.deleteRecursively()
                dir.mkdirs()
                runCatching {
                    val apkPath = fingerprint.substringBeforeLast(":").substringBeforeLast(":")
                    ZipFile(File(apkPath)).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (!e.name.startsWith("assets/") || e.isDirectory) continue
                            // assets/player.html → <dir>/player.html（拍平）
                            val rel = e.name.removePrefix("assets/")
                            if (rel.isEmpty() || rel.contains('/')) continue
                            zip.getInputStream(e).use { input ->
                                File(dir, rel).outputStream().use { input.copyTo(it) }
                            }
                        }
                    }
                    marker.writeText(fingerprint)
                }.onFailure {
                    dir.deleteRecursively()
                    return null
                }
            }
            cachedDir = dir
            cachedFingerprint = fingerprint
            return dir
        }
    }
}

/** DexKit 缓存状态读取与重建（QQ 进程内才有宿主目录权限）。 */
object DexKitPageState {
    const val TOTAL = 16
    private const val FINGERPRINT_KEY = "__host_apk__"

    private fun cacheFile(): File = File(File(Env.moduleDataDir, "dexkit"), "CacheMap.json")

    /** 返回 (已就绪任务数, 描述)；n < 0 表示无法读取。 */
    fun readStatus(): Pair<Int, String> {
        if (Env.moduleDataDir.isEmpty()) return -1 to "请从 QQ 设置页进入本页面"
        val f = cacheFile()
        if (!f.exists()) return 0 to "缓存缺失，重启 QQ 后自动查找"
        return runCatching {
            val json = JSONObject(f.readText())
            val n = json.keys().asSequence().count { it != FINGERPRINT_KEY }
            if (n >= TOTAL) n to "缓存完整，未发现缺失方法"
            else n to "缓存不完整，重启 QQ 后自动补查"
        }.getOrElse { -1 to "缓存读取失败" }
    }

    /** 清空缓存（内存 + 磁盘）；随后的 doFind 会全量重扫并写回。 */
    fun clearCache() {
        // 页面侧 DexKitCache 是独立 ClassLoader 的副本，必须先 init 设置缓存文件路径，
        // 否则扫描完成后 saveCache 因 cacheFile 为 null 而不落盘。
        DexKitCache.init()
        // 关键：必须同时清内存 map。只删磁盘文件的话，cacheMap 里还留着上次扫描结果，
        // scanBridge 的 contains(key) 全为 true → 全部跳过 → 变成「增量补查」而非全量重扫。
        DexKitCache.clearAll()
    }
}
