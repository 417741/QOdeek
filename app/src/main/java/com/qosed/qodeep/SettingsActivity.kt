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

private fun Modifier.cardStyle(): Modifier = this
    .clip(RoundedCornerShape(16.dp))
    .background(Color(0xCCFFFFFF))

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        super.onCreate(savedInstanceState)
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
    var bgDebug by remember { mutableStateOf("bg: 解压中...") }
    val bgDir by produceState<File?>(null) {
        value = withContext(Dispatchers.IO) { BgAssets.ensure(context) }
    }
    val items = remember(bgDir) { bgDir?.let { JieqiCatalog.load(it) } ?: emptyList() }
    val current = items.firstOrNull { it.key == bgKey } ?: items.firstOrNull()
    val activeKey = bgKey ?: current?.key ?: "duanwu"

    Box(Modifier.fillMaxSize().background(Color(0xFFF2F4F8))) {
        JieqiBackground(
            activeKey, bgDir,
            onDebug = { msg -> bgDebug = "bg: $msg" },
            modifier = Modifier.fillMaxSize()
        )

        Text(
            text = bgDebug,
            color = Color(0xCCFF3B30),
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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

@Composable
private fun JieqiBackground(key: String, dir: File?, onDebug: (String) -> Unit, modifier: Modifier = Modifier) {
    // 让 WebViewClient 闭包始终能读到最新的解压目录（factory 只执行一次，不能直接捕获 dir 参数）
    val dirRef = remember { mutableStateOf<File?>(null) }
    dirRef.value = dir

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
                isClickable = false
                isFocusable = false
                isLongClickable = false
                setOnTouchListener { _, _ -> true }
                webViewClient = object : android.webkit.WebViewClient() {
                    // 把 https://appassets.qodeep.local/* 拦截为解压目录里的本地文件。
                    // 页面因此是正常 https 源，fetch/XHR 不再被 file:// 协议禁掉。
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        val u = request.url
                        if (u.host != "appassets.qodeep.local") return null
                        val d = dirRef.value ?: return null
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
            val d = dirRef.value
            if (d != null) {
                val url = "https://appassets.qodeep.local/player.html?key=$key"
                if (wv.tag != url) {
                    wv.tag = url
                    onDebug("加载: 拦截域名 player.html")
                    wv.loadUrl(url)
                }
            } else {
                onDebug("bg: 解压失败(null)")
            }
        },
        onRelease = { it.destroy() }
    )
}

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

object BgAssets {
    private const val MODULE_PKG = "com.qosed.qodeep"
    private const val MARKER = ".ver"

    @Volatile
    private var cachedDir: File? = null
    @Volatile
    private var cachedFingerprint: String? = null

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

object DexKitPageState {
    const val TOTAL = 16
    private const val FINGERPRINT_KEY = "__host_apk__"

    private fun cacheFile(): File = File(File(Env.moduleDataDir, "dexkit"), "CacheMap.json")

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

    fun clearCache() {
        DexKitCache.init()
        DexKitCache.clearAll()
    }
}
