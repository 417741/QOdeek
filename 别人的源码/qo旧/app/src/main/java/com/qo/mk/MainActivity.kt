package com.qo.mk

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qo.mk.ui.theme.应用管家Theme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.json.JSONObject

private const val PREFS_NAME = "app_manager_prefs"
private const val KEY_FILTER = "filter"
private const val KEY_SORT = "sort"

/** 是否已授予「使用情况访问」权限（存储占用统计需要，与 root 无关） */
fun hasUsageAccess(context: Context): Boolean = try {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
} catch (e: Exception) {
    false
}

/**
 * 用系统 StorageStatsManager 读取每个应用的存储占用
 * （安装包 + 数据 + 缓存 + OBB，与系统设置里显示的口径一致）。
 * 返回：包名 -> 占用字节数。未授权时返回空 Map。
 * 每个 uid 是一次 binder 查询，几百个应用串行会很慢；这里分批并发（每批 16 个）查询。
 */
suspend fun loadStorageStatsMap(context: Context): Map<String, Long> {
    return try {
        val pm = context.packageManager
        val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val results = ArrayList<Pair<String, Long>>()
        for (batch in pm.getInstalledPackages(0).chunked(16)) {
            val batchResults = coroutineScope {
                batch.map { pi ->
                    async(Dispatchers.IO) {
                        val appInfo = pi.applicationInfo ?: return@async null
                        try {
                            val stats = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, appInfo.uid)
                            pi.packageName to (stats.appBytes + stats.dataBytes + stats.cacheBytes)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.awaitAll()
            }
            batchResults.forEach { r -> if (r != null) results.add(r) }
        }
        results.toMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

private const val STORAGE_STATS_CACHE = "storage_stats_cache.json"

/** 读取上次统计的存储占用缓存，冷启动先显示它、后台再刷新，避免空白等待 */
fun loadStorageStatsCache(context: Context): Map<String, Long> {
    return try {
        val f = File(context.filesDir, STORAGE_STATS_CACHE)
        if (!f.exists()) return emptyMap()
        val o = JSONObject(f.readText())
        val map = HashMap<String, Long>(o.length())
        o.keys().forEach { k -> map[k] = o.getLong(k) }
        map
    } catch (e: Exception) {
        emptyMap()
    }
}

/** 保存存储占用结果到磁盘缓存 */
fun saveStorageStatsCache(context: Context, map: Map<String, Long>) {
    try {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        File(context.filesDir, STORAGE_STATS_CACHE).writeText(o.toString())
    } catch (e: Exception) {
        // 写入失败不影响主流程
    }
}

/** 从本地读取上次选择的筛选类型，默认显示所有应用 */
private fun loadFilter(context: Context): AppFilter {
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_FILTER, null) ?: return AppFilter.ALL
    return AppFilter.entries.firstOrNull { it.name == saved } ?: AppFilter.ALL
}

/** 保存当前选择的筛选类型，退出后下次打开仍然生效 */
private fun saveFilter(context: Context, filter: AppFilter) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_FILTER, filter.name).apply()
}

/** 从本地读取上次选择的排序方式，默认按名称（Abcd） */
private fun loadSort(context: Context): SortMode {
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SORT, null) ?: return SortMode.ALPHA
    return SortMode.entries.firstOrNull { it.name == saved } ?: SortMode.ALPHA
}

/** 保存当前选择的排序方式，退出后下次打开仍然生效 */
private fun saveSort(context: Context, sort: SortMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_SORT, sort.name).apply()
}

/** 复制文本到系统剪贴板并提示 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(text, text))
    Toast.makeText(context, "已复制：$text", Toast.LENGTH_SHORT).show()
}

class MainActivity : ComponentActivity() {

    // 卸载队列：批量卸载时逐个弹出系统卸载确认框
    private val uninstallQueue = ArrayDeque<String>()
    private var onUninstallAllDone: (() -> Unit)? = null

    private val uninstallLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (uninstallQueue.isNotEmpty()) {
                launchUninstall(uninstallQueue.removeFirst())
            } else {
                onUninstallAllDone?.invoke()
                onUninstallAllDone = null
            }
        }

    private fun launchUninstall(packageName: String) {
        uninstallLauncher.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 初始化应用管家自身的 HostInfo（用于模块数据目录等）；模块开关配置由 QQ 进程
        // 直接在宿主数据目录本地读写，与应用管家无关联。
        me.lengyu.qedge.utils.HostInfo.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            应用管家Theme {
                var reloadKey by remember { mutableStateOf(0) }
                MainScreen(
                    reloadKey = reloadKey,
                    onReload = { reloadKey++ },
                    onUninstallSingle = { pkg ->
                        onUninstallAllDone = { reloadKey++ }
                        launchUninstall(pkg)
                    },
                    onUninstallBatch = { pkgs ->
                        uninstallQueue.clear()
                        pkgs.forEach { uninstallQueue.addLast(it) }
                        onUninstallAllDone = { reloadKey++ }
                        launchUninstall(uninstallQueue.removeFirst())
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerScreen(
    reloadKey: Int,
    onUninstallSingle: (String) -> Unit,
    onUninstallBatch: (List<String>) -> Unit,
    onReload: () -> Unit,
    onOpenModuleInfo: () -> Unit,
    theme: ThemeOption
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val cachedOnStart = remember { loadCachedApps(context) }
    var allApps by remember { mutableStateOf(cachedOnStart ?: emptyList()) }
    var isLoading by remember { mutableStateOf(cachedOnStart == null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(loadFilter(context)) }
    var sortMode by remember { mutableStateOf(loadSort(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var selectedPackages by remember { mutableStateOf(setOf<String>()) }
    var detailApp by remember { mutableStateOf<AppInfo?>(null) }
    // 各应用存储占用（bytes，来自系统 StorageStatsManager，与系统设置口径一致）
    var usageGranted by remember { mutableStateOf(hasUsageAccess(context)) }
    var storageData by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val searchFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // 下拉刷新：重新加载所有应用（强制刷新，绕过内存缓存）
    val refreshScope = rememberCoroutineScope()
    fun doRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        refreshScope.launch {
            allApps = withContext(Dispatchers.IO) {
                loadAllApps(context, forceRefresh = true)
            }
            if (usageGranted) {
                storageData = withContext(Dispatchers.IO) { loadStorageStatsMap(context) }
                withContext(Dispatchers.IO) { saveStorageStatsCache(context, storageData) }
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(reloadKey) {
        if (reloadKey == 0 && cachedOnStart != null) {
            // 有缓存：列表已立即显示，后台增量同步（只处理新装/卸载，不逐个 loadLabel）
            isRefreshing = true
            allApps = withContext(Dispatchers.IO) {
                syncApps(context, cachedOnStart)
            }
            isRefreshing = false
            isLoading = false
        } else {
            // 首次启动（无缓存）或需要强制刷新（卸载后）
            isLoading = true
            allApps = withContext(Dispatchers.IO) {
                loadAllApps(context, forceRefresh = reloadKey > 0)
            }
            isLoading = false
        }
    }

    // 已授权「使用情况访问」时统计存储占用（不依赖 root）
    LaunchedEffect(usageGranted) {
        if (!usageGranted) {
            storageData = emptyMap()
            return@LaunchedEffect
        }
        // 先显示上次磁盘缓存的结果，冷启动立即有数字，而不是空白等几秒
        storageData = withContext(Dispatchers.IO) { loadStorageStatsCache(context) }
        // 后台并发刷新真实占用，完成后更新并回写缓存
        val fresh = withContext(Dispatchers.IO) { loadStorageStatsMap(context) }
        storageData = fresh
        withContext(Dispatchers.IO) { saveStorageStatsCache(context, fresh) }
    }

    // 后台预加载所有应用图标到内存缓存：滚动时直接命中，避免图标逐个冒出来、快速滑动卡顿
    LaunchedEffect(allApps) {
        val apps = allApps
        if (apps.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (app in apps) {
                if (peekCachedIcon(app.packageName) == null) {
                    loadAppIconCached(context, app.packageName, app.sourceDir)
                }
            }
        }
    }

    // 从系统设置授权返回后自动重新检查并刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = hasUsageAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 打开搜索时自动聚焦输入框
    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }

    // 搜索框没有输入内容时，上下滑动列表自动收起键盘并退出搜索；有输入内容则不退出
    LaunchedEffect(searchActive, searchQuery, listState) {
        if (searchActive && searchQuery.isEmpty()) {
            snapshotFlow { listState.isScrollInProgress }
                .filter { it }
                .collect {
                    focusManager.clearFocus()
                    searchActive = false
                    searchQuery = ""
                }
        }
    }

    // 切换排序方式 / 筛选类型后，把列表滚回顶部，避免沿用旧排序的滚动位置
    LaunchedEffect(sortMode, filter) {
        listState.scrollToItem(0)
    }

    val displayedApps = remember(allApps, filter, searchQuery, sortMode, storageData) {
        val q = searchQuery.trim()
        val filtered = allApps.filter { app ->
            val matchFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.SYSTEM -> app.isSystemApp
                AppFilter.USER -> !app.isSystemApp
            }
            val matchSearch = q.isEmpty() ||
                app.appName.contains(q, ignoreCase = true) ||
                app.packageName.contains(q, ignoreCase = true)
            matchFilter && matchSearch
        }
        // 按勾选的排序方式重新排序列表
        when (sortMode) {
            SortMode.ALPHA -> filtered.sortedBy { it.appName.lowercase() }
            SortMode.SIZE -> filtered.sortedByDescending { storageData[it.packageName] ?: 0L }
            SortMode.RECENT -> filtered.sortedByDescending { it.lastUpdateTime }
        }
    }

    val selectionMode = selectedPackages.isNotEmpty()

    // 返回键：详情弹窗 / 多选 / 搜索 依次回退，而不是直接退出应用
    BackHandler(enabled = detailApp != null || selectionMode || searchActive) {
        when {
            detailApp != null -> detailApp = null
            selectionMode -> selectedPackages = emptySet()
            searchActive -> {
                searchActive = false
                searchQuery = ""
            }
        }
    }

    fun toggleSelection(packageName: String) {
        selectedPackages = if (packageName in selectedPackages) {
            selectedPackages - packageName
        } else {
            selectedPackages + packageName
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(theme.gradient))
    ) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            AppTopBar(
                filter = filter,
                sortMode = sortMode,
                searchActive = searchActive,
                onSearchClick = {
                    searchActive = !searchActive
                    if (!searchActive) searchQuery = ""
                },
                onFilterChange = {
                    filter = it
                    saveFilter(context, it)
                },
                onSortChange = {
                    sortMode = it
                    saveSort(context, it)
                },
                onOpenModuleInfo = onOpenModuleInfo,
                theme = theme
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectionMode) {
                SelectionBottomBar(
                    selectedCount = selectedPackages.size,
                    accent = theme.accent,
                    onSelectAll = { selectedPackages = displayedApps.map { it.packageName }.toSet() },
                    onClear = { selectedPackages = emptySet() },
                    onUninstall = {
                        val targets = allApps.filter { it.packageName in selectedPackages }
                        selectedPackages = emptySet()
                        val systemApps = targets.filter { it.isSystemApp }
                        val userApps = targets.filter { !it.isSystemApp }

                        // 用户应用：走系统卸载确认框
                        if (userApps.isNotEmpty()) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "即将依次弹出 ${userApps.size} 个卸载确认框，请逐个确认"
                                )
                            }
                            onUninstallBatch(userApps.map { it.packageName })
                        }

                        // 系统应用：需要 Root 权限
                        if (systemApps.isNotEmpty()) {
                            scope.launch {
                                val rooted = withContext(Dispatchers.IO) { isDeviceRooted() }
                                if (!rooted) {
                                    snackbarHostState.showSnackbar(
                                        "没有 Root 权限，无法卸载 ${systemApps.size} 个系统应用"
                                    )
                                } else {
                                    val (ok, fail) = withContext(Dispatchers.IO) {
                                        var okCount = 0
                                        var failCount = 0
                                        systemApps.forEach { app ->
                                            if (uninstallPackageWithRoot(app.packageName)) {
                                                okCount++
                                            } else {
                                                failCount++
                                            }
                                        }
                                        okCount to failCount
                                    }
                                    snackbarHostState.showSnackbar(
                                        "系统应用卸载完成：成功 $ok 个，失败 $fail 个"
                                    )
                                    if (ok > 0) onReload()
                                }
                            }
                        }
                    },
                    onExtract = {
                        val targets = allApps.filter { it.packageName in selectedPackages }
                        selectedPackages = emptySet()
                        scope.launch {
                            snackbarHostState.showSnackbar("正在提取 ${targets.size} 个安装包…")
                            val (ok, fail) = withContext(Dispatchers.IO) {
                                var okCount = 0
                                var failCount = 0
                                targets.forEach { app ->
                                    if (extractApk(context, app)) okCount++ else failCount++
                                }
                                okCount to failCount
                            }
                            snackbarHostState.showSnackbar(
                                "提取完成：成功 $ok 个，失败 $fail 个（保存到 Download/应用管家）"
                            )
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (searchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) { Text("✕") }
                        }
                    }
                )
            }

            Text(
                text = "${filter.label} · 共 ${displayedApps.size} 个",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = AppTextTertiary
            )

            // 未授权「使用情况访问」时给出授权入口（存储占用需要该权限，与 root 无关）
            if (!usageGranted) {
                Text(
                    text = "显示存储占用需要「使用情况访问」权限，点此去授权",
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clickable {
                            context.startActivity(
                                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            )
                        },
                    fontSize = 12.sp,
                    color = theme.accent
                )
            }

            if (isRefreshing) {
                Text(
                    text = "正在更新应用列表…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    fontSize = 12.sp,
                    color = AppTextTertiary
                )
            }

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                displayedApps.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有找到应用", color = AppTextTertiary)
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { doRefresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(displayedApps, key = { it.packageName }) { app ->
                                    AppRow(
                                        app = app,
                                        sizeBytes = storageData[app.packageName],
                                        selectionMode = selectionMode,
                                        selected = app.packageName in selectedPackages,
                                        accent = theme.accent,
                                        onClick = {
                                            if (selectionMode) toggleSelection(app.packageName)
                                            else detailApp = app
                                        },
                                        onLongClick = {
                                            if (!selectionMode) {
                                                selectedPackages = setOf(app.packageName)
                                            }
                                        }
                                    )
                                }
                            }
                            VerticalScrollbar(
                                state = listState,
                                accent = theme.accent,
                                modifier = Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // 单个应用详情弹窗：图标 + 信息 + 操作（MT 管理器风格，与全应用玻璃风统一）
    detailApp?.let { app ->
        val icon = remember(app.packageName) { loadAppIconCached(context, app.packageName, app.sourceDir) }
        val sizeText = remember(app.packageName) {
            runCatching {
                val f = File(app.sourceDir)
                if (f.exists()) String.format("%.1f MB", f.length() / 1024.0 / 1024.0) else ""
            }.getOrDefault("")
        }
        val pkgInfo = remember(app.packageName) {
            runCatching { context.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        }
        val ai = pkgInfo?.applicationInfo
        Dialog(
            onDismissRequest = { detailApp = null }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .background(DialogBackgroundBrush)
                    .border(1.dp, DialogBorderBrush, CardShape)
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(CardHighlightBrush)
                )
                Column(Modifier.padding(20.dp)) {
                    // 头部：应用图标 + 名称 / 包名（点击可复制）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = app.appName,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        // 点击头像：提取应用图标到提取安装包的路径
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                extractAppIcon(context, app)
                                            }
                                            snackbarHostState.showSnackbar(
                                                if (ok) "图标已提取到提取路径" else "图标提取失败"
                                            )
                                        }
                                    }
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(AppIconPlaceholderBrush)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.appName,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { copyToClipboard(context, app.appName) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    // 包名（点击可复制）
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            "包名",
                            fontSize = 14.sp,
                            color = AppTextTertiary,
                            modifier = Modifier.width(96.dp)
                        )
                        Text(
                            app.packageName,
                            fontSize = 14.sp,
                            color = AppTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { copyToClipboard(context, app.packageName) }
                        )
                    }
                    InfoRow("版本号", app.versionName.ifBlank { "未知" })
                    InfoRow("安装包大小", sizeText.ifBlank { "未知" })
                    InfoRow("类型", if (app.isSystemApp) "系统应用" else "用户应用")
                    if (ai != null) {
                        InfoRow("目标系统", sdkText(ai.targetSdkVersion))
                        InfoRow("最低系统", sdkText(ai.minSdkVersion))
                    }
                    InfoRow("首次安装", formatTime(pkgInfo?.firstInstallTime ?: 0L))
                    InfoRow("最后更新", formatTime(pkgInfo?.lastUpdateTime ?: 0L))

                    Spacer(Modifier.height(10.dp))
                    // 操作行：卸载贴最左
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                detailApp = null
                                if (app.isSystemApp) {
                                    // 系统应用需要 Root 权限
                                    scope.launch {
                                        val rooted = withContext(Dispatchers.IO) { isDeviceRooted() }
                                        if (!rooted) {
                                            snackbarHostState.showSnackbar(
                                                "没有 Root 权限，无法卸载系统应用"
                                            )
                                        } else {
                                            val ok = withContext(Dispatchers.IO) {
                                                uninstallPackageWithRoot(app.packageName)
                                            }
                                            snackbarHostState.showSnackbar(
                                                if (ok) "已卸载 ${app.appName}"
                                                else "卸载失败（该应用可能受系统保护）"
                                            )
                                            if (ok) onReload()
                                        }
                                    }
                                } else {
                                    onUninstallSingle(app.packageName)
                                }
                            }
                        ) { Text("卸载", color = AppDanger) }
                        TextButton(onClick = { detailApp = null }) { Text("取消") }
                        TextButton(
                            onClick = {
                                detailApp = null
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { extractApk(context, app) }
                                    snackbarHostState.showSnackbar(
                                        if (ok) "已提取到 Download/应用管家" else "提取失败"
                                    )
                                }
                            }
                        ) { Text("提取安装包") }
                    }
                }
            }
        }
    }
}

/** 列表右侧垂直滚动条：根据 LazyListState 的滚动指示状态绘制，颜色跟随当前主题 accent */
@Composable
private fun VerticalScrollbar(
    state: LazyListState,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val indicator = state.scrollIndicatorState ?: return
    val viewport = indicator.viewportSize
    val content = indicator.contentSize
    val offset = indicator.scrollOffset
    // 内容不足一屏或尺寸尚未确定时隐藏
    if (viewport <= 0 || content <= viewport || content >= Int.MAX_VALUE) return

    val density = LocalDensity.current
    val trackPx = with(density) { 6.dp.toPx() }

    Canvas(
        modifier = modifier
            .fillMaxHeight()
            .width(12.dp)
    ) {
        val viewportPx = viewport.toFloat()
        val contentPx = content.toFloat()
        val offsetPx = offset.toFloat()
        // 滑块高度固定为一个应用卡片（气泡）的高度，而不是按视口比例缩放
        val thumbHeightPx = minOf(with(density) { 60.dp.toPx() }, viewportPx)
        val scrollRange = contentPx - viewportPx
        val thumbTopPx =
            if (scrollRange > 0f) (offsetPx / scrollRange) * (viewportPx - thumbHeightPx) else 0f

        drawRoundRect(
            color = accent,
            topLeft = Offset(size.width - trackPx, thumbTopPx),
            size = Size(trackPx, thumbHeightPx),
            cornerRadius = CornerRadius(trackPx / 2f)
        )
    }
}

/** 弹窗里的信息行：标签列固定宽 + 内容列（MT 管理器风格排版） */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = AppTextTertiary,
            modifier = Modifier.width(96.dp)
        )
        Text(
            value,
            fontSize = 14.sp,
            color = AppTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 毫秒时间戳 -> yyyy-MM-dd HH:mm:ss */
private fun formatTime(ms: Long): String =
    if (ms <= 0L) "未知"
    else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(ms))

/** API 等级 -> "Android x (API n)" */
private fun sdkText(api: Int): String {
    if (api <= 0) return "未知"
    val name = when (api) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
        31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"
        36 -> "16"; 37 -> "17"
        else -> ""
    }
    return if (name.isEmpty()) "API $api" else "Android $name (API $api)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar(
    filter: AppFilter,
    sortMode: SortMode,
    searchActive: Boolean,
    onSearchClick: () -> Unit,
    onFilterChange: (AppFilter) -> Unit,
    onSortChange: (SortMode) -> Unit,
    onOpenModuleInfo: () -> Unit,
    theme: ThemeOption
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    Surface(
        color = AppBarSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(38.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.app_name),
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            )
            // 排序按钮：位于搜索图标左边，点开后勾选 Abcd / 使用大小 / 最近安装
            Box {
                IconButton(onClick = { sortOpen = true }, modifier = Modifier.size(30.dp)) {
                    Text("⇅", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    SortMode.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            trailingIcon = {
                                if (s == sortMode) Text("✓")
                            },
                            onClick = {
                                onSortChange(s)
                                sortOpen = false
                            }
                        )
                    }
                }
            }
            // 放大镜搜索图标，位于三个点菜单的左边
            IconButton(onClick = onSearchClick, modifier = Modifier.size(30.dp)) {
                Icon(
                    painter = painterResource(
                        if (searchActive) R.drawable.ic_close else R.drawable.ic_search
                    ),
                    contentDescription = if (searchActive) "关闭搜索" else "搜索",
                    tint = AppTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                    Text("⋮", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("模块信息") },
                        onClick = {
                            menuOpen = false
                            onOpenModuleInfo()
                        }
                    )
                    androidx.compose.material3.HorizontalDivider()
                    AppFilter.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            trailingIcon = {
                                if (f == filter) Text("✓")
                            },
                            onClick = {
                                onFilterChange(f)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
        }
    }
}

// 卡片玻璃拟态样式：统一从 ThemeColors 取色，避免多处硬编码导致风格不一
private val CardShape = RoundedCornerShape(24.dp)
private val IconShape = RoundedCornerShape(14.dp)
private val CardBackgroundBrush = AppCardBackgroundBrush
private val CardHighlightBrush = AppCardHighlightBrush
private val CardBorderBrush = AppCardBorderBrush
private val DialogBackgroundBrush = AppDialogBackgroundBrush
private val DialogBorderBrush = AppDialogBorderBrush
private val IconPlaceholderBrush = AppIconPlaceholderBrush

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    sizeBytes: Long?,
    selectionMode: Boolean,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    // 先同步命中内存缓存（后台预加载已填充），命中则首帧即有图标、不闪烁；
    // 未命中才异步加载，避免快速滚动时图标逐个冒出来造成卡顿
    val icon by produceState<ImageBitmap?>(
        initialValue = peekCachedIcon(app.packageName),
        app.packageName
    ) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            loadAppIconCached(context, app.packageName, app.sourceDir)
        }
    }

    // 注意：移除了原先 12dp 彩色阴影（shadow 是滚动卡顿的主要来源），
    // 保留玻璃渐变 + 描边维持视觉，换取 60fps 流畅滚动。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(CardShape)
            .background(CardBackgroundBrush)
            .border(1.dp, CardBorderBrush, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // 顶部镜面高光
        Box(
            Modifier
                .matchParentSize()
                .background(CardHighlightBrush)
        )
        // 选中态着色（跟随当前主题 accent，保证与其他强调色一致）
        if (selected) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(selectionOverlay(accent))
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconBitmap = icon
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(IconShape)
                )
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(IconShape)
                        .background(IconPlaceholderBrush)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.appName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = AppTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "包名 ${app.packageName}",
                    fontSize = 12.sp,
                    color = AppTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (sizeBytes != null) {
                Spacer(Modifier.width(8.dp))
                // 右侧显示存储占用（系统 StorageStats 口径：安装包+数据+缓存+OBB）
                Text(
                    formatStorage(sizeBytes),
                    fontSize = 12.sp,
                    color = AppTextSecondary
                )
            }
            if (selectionMode) {
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
        }
    }
}

/** 把字节数格式化成系统风格的大小文本（490MB / 220KB / 0B） */
private fun formatStorage(bytes: Long): String = when {
    bytes < 1024 -> "0B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    bytes < 1024L * 1024 * 1024 -> {
        val mb = bytes / 1048576.0
        if (mb >= 100) String.format("%.0fMB", mb) else String.format("%.2fMB", mb)
    }
    else -> String.format("%.2fGB", bytes / 1073741824.0)
}

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    accent: Color,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onUninstall: () -> Unit,
    onExtract: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackgroundBrush)
            .border(1.dp, CardBorderBrush, CardShape)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(CardHighlightBrush)
        )
        Column(Modifier.navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onClear) { Text("取消选择") }
                Spacer(Modifier.weight(1f))
                Text("已选 $selectedCount 项", fontSize = 13.sp, color = AppTextPrimary)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onUninstall, modifier = Modifier.weight(1f)) {
                    Text("批量卸载", color = AppDanger)
                }
                Button(
                    onClick = onExtract,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = Color.White
                    )
                ) {
                    Text("批量提取")
                }
            }
        }
    }
}

