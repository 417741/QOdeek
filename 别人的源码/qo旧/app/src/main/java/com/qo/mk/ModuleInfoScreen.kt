package com.qo.mk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import me.lengyu.qedge.hook.entry.QQDeepLink
import me.lengyu.qedge.utils.FrameworkInfo
import me.lengyu.qedge.utils.ModuleActivation

/** 应用管家与 QQ 模块共享的用户信息快照 */
data class SharedUserInfo(
    val uin: String = "",
    val nickname: String = "",
    val moduleVersion: String = "",
    val qqVersion: String = "",
    val lsposedVersion: String = "",
    val lsposedVersionCode: Long = 0,
    val xposedApi: Int = 0,
    val activated: Boolean = false
)

/** QQ 模块数据目录里共享用户信息文件的固定路径（模块在 QQ 进程写入） */
private const val QQ_USERINFO_PATH =
    "/storage/emulated/0/Android/data/com.tencent.mobileqq/QO/userinfo.json"

/** QQ 包名 */
private const val QQ_PACKAGE = "com.tencent.mobileqq"

/** 解析共享用户信息 JSON，失败返回 null */
private fun parseSharedUserInfo(content: String?): SharedUserInfo? {
    if (content.isNullOrBlank()) return null
    return try {
        val json = JSONObject(content)
        SharedUserInfo(
            uin = json.optString("uin", ""),
            nickname = json.optString("nickname", ""),
            moduleVersion = json.optString("moduleVersion", ""),
            qqVersion = json.optString("qqVersion", ""),
            lsposedVersion = json.optString("lsposedVersion", ""),
            lsposedVersionCode = json.optLong("lsposedVersionCode", 0),
            xposedApi = json.optInt("xposedApi", 0),
            activated = json.optBoolean("activated", false)
        )
    } catch (_: Throwable) {
        null
    }
}

/**
 * 读取 QQ 模块写入的共享用户信息。
 * 先直接读文件（部分系统 / 已授权所有文件访问时可用），失败后走 Root 兜底。
 */
fun loadSharedUserInfo(): SharedUserInfo? {
    // 直接读：模块与应用管家同属一个开发者，部分系统或授予所有文件访问权限时可用
    runCatching {
        val file = File(QQ_USERINFO_PATH)
        if (file.exists()) {
            parseSharedUserInfo(file.readText())?.let { return it }
        }
    }
    // Root 兜底：Android 11+ 跨应用 Android/data 需要 root
    if (isDeviceRooted()) {
        readFileWithRoot(QQ_USERINFO_PATH)?.let { content ->
            parseSharedUserInfo(content)?.let { return it }
        }
    }
    return null
}

/** 从 QQ 头像接口加载头像 */
private suspend fun loadAvatar(uin: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val url = URL("https://q.qlogo.cn/headimg_dl?dst_uin=$uin&spec=100&img_type=png")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = true
        val stream = connection.inputStream
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeStream(stream, null, options)
    } catch (_: Throwable) {
        null
    }
}

/**
 * 模块信息页（QAuxiliary 风格）：
 * 用户信息卡片直接展示 + 激活状态 / 模块版本 / 模块设置入口，不含 GitHub / 提示 / 故障排查。
 */
@Composable
fun ModuleInfoScreen(
    theme: ThemeOption,
    showBack: Boolean = true,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var userInfo by remember { mutableStateOf<SharedUserInfo?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var colorStep by remember { mutableIntStateOf(0) }
    // 激活判定：进程内探测。应用管家（com.qo.mk）已在 xposedscope 中声明，
    // LSPosed 启用模块后会向本进程注入 XposedBridge，注入即 XposedBridge.getXposedVersion() > 0，
    // 与 QQ 是否在线完全无关。Root 读取仅作版本号来源 + 诊断对照，不再决定「已激活」。
    var injected by remember { mutableStateOf(false) }
    var xposedApi by remember { mutableIntStateOf(0) }
    var rootActivated by remember { mutableStateOf<Boolean?>(null) }
    var rootDiag by remember { mutableStateOf("") }
    // 探活：QQ 进程里的模块应答探活广播 = 模块当前确实在 QQ 里运行（仅作「在线」补充）
    var pingInfo by remember { mutableStateOf<SharedUserInfo?>(null) }
    var pingChecked by remember { mutableStateOf(false) }
    var debugMsg by remember { mutableStateOf("") }
    // Root 读 LSPosed module.prop 得到的版本（无 binder 回传，Root 是唯一可靠数据源）
    var lspRootVersion by remember { mutableStateOf("") }
    var lspRootCode by remember { mutableStateOf("") }
    // 读一次 LSPosed 状态：是否安装 / 本模块是否启用 / 版本号（全部走 Root，与 QQ 无关）
    fun refreshRootState() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val present = LsposedUtils.isLsposedPresent()
                val enabled = LsposedUtils.isModuleEnabled()
                lspRootVersion = LsposedUtils.getLsposedVersion().orEmpty()
                lspRootCode = LsposedUtils.getLsposedVersionCode().orEmpty()
                rootActivated = present && enabled
                rootDiag = when {
                    !isDeviceRooted() -> "未检测到 Root，无法读取 LSPosed 启用状态"
                    !present -> "LSPosed 未安装"
                    !enabled -> "LSPosed 已安装但本模块未启用"
                    else -> ""
                }
            }
        }
    }
    // 进程内探测：本进程是否被 LSPosed 注入（XposedBridge.getXposedVersion() > 0）。
    // 纯反射/静态字段读取，主线程安全，与 QQ、Root 均无关。
    fun refreshActivation() {
        runCatching {
            injected = ModuleActivation.isModuleActivated()
            xposedApi = if (injected) FrameworkInfo.getXposedApiVersion() else 0
        }
    }

    // 回到前台时重读：LSPosed 里切换「启用模块」开关后切回本应用即刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshActivation()
                refreshRootState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        // 主数据源：进程内探测本进程是否被注入（决定「已激活」，与 QQ 无关）
        refreshActivation()
        // 版本号数据源：Root 直读 LSPosed module.prop（进程内拿不到 LSPosed 版本名）
        refreshRootState()

        // 兜底：Root 读不到版本时读已安装的 LSPosed 管理器应用版本（无需 Root）
        scope.launch(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val pi = pm.getPackageInfo("org.lsposed.manager", 0)
                if (lspRootVersion.isEmpty() && !pi.versionName.isNullOrBlank()) {
                    lspRootVersion = pi.versionName!!
                }
                if (lspRootCode.isEmpty() && pi.longVersionCode > 0) {
                    lspRootCode = pi.longVersionCode.toString()
                }
            }
        }

        // 读 QQ 模块写入的 userinfo.json 缓存（勾选 QQ 成功、模块在 QQ 里跑过一次后就有数据）
        val info = loadSharedUserInfo()
        userInfo = info
        if (info != null && info.uin.isNotEmpty()) {
            avatarBitmap = loadAvatar(info.uin)
        }

        // 探活广播：仅用于判断 QQ 模块当前是否在线，不再决定「已激活」
        pingQqModule(context) { replied ->
            pingChecked = true
            pingInfo = replied
            debugMsg = if (replied != null) "QQ 进程应答探活：模块在 QQ 中运行中"
            else "QQ 当前未在线（不影响激活判定）"
            val pi = replied
            if (pi != null && pi.uin.isNotEmpty()) {
                userInfo = pi
                scope.launch { avatarBitmap = loadAvatar(pi.uin) }
            }
        }
        loaded = true
    }

    val info = userInfo
    // 已激活：以「本进程是否被 LSPosed 注入」为准（进程内探测，与 QQ 是否在线、Root 是否授权均无关）。
    val activated = injected
    // 模块运行中：QQ 进程里模块跑过并上报了数据（仅作「在线」补充，不再决定「已激活」）
    val moduleRunning = info != null && (info.activated || info.uin.isNotEmpty())
    // Root 兜底版本推断 API 等级：LSPosed 2.2+ → 102，2.0/2.1 → 101，1.9 → 100
    val lspApiFromRoot = remember(lspRootVersion) {
        val parts = lspRootVersion.substringBefore('(')
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        when {
            parts.size >= 2 && parts[0] >= 3 -> 102
            parts.size >= 2 && parts[0] == 2 && parts[1] >= 2 -> 102
            parts.size >= 2 && parts[0] == 2 -> 101
            parts.size >= 1 && parts[0] == 1 && parts.getOrNull(1)?.let { it >= 9 } == true -> 100
            else -> 0
        }
    }
    // 版本名：Root 读 module.prop（去 "v" 前缀，最完整）> QQ 模块写入的 userinfo.json（兜底）
    val lspVersion = lspRootVersion.removePrefix("v").removePrefix("V").takeIf { it.isNotEmpty() }
        ?: info?.lsposedVersion?.takeIf { it.isNotEmpty() }
    // versionCode：Root 读 module.prop > QQ 模块写入的；版本名已含括号代号时不再重复
    val lspCode = (lspRootCode.ifEmpty {
        if (info != null && info.lsposedVersionCode > 0) info.lsposedVersionCode.toString() else ""
    }).takeIf { it.isNotEmpty() && !lspVersion.orEmpty().contains('(') }
    // Xposed API：进程内实测（XposedBridge.getXposedVersion()）> Root 版本推断（兜底）
    val lspApi = xposedApi.takeIf { it > 0 } ?: lspApiFromRoot
    val frameworkText = buildString {
        when {
            activated && (lspVersion != null || lspApi > 0) -> {
                // 已激活 + 能拿到版本/API：展示 LSPosed 版本 / code / API（对齐 QA 格式）。
                // 旧式入口下版本名不一定拿得到，但 XposedBridge.getXposedVersion() 拿到的 API 一定可靠，
                // 因此只要 API 有值就展示，不再降级成「模块已在 LSPosed 中启用」。
                append("LSPosed")
                if (lspVersion != null) append(" ").append(lspVersion)
                if (lspCode != null) append(" (").append(lspCode).append(")")
                if (lspApi > 0) {
                    if (lspVersion != null) append(", API ").append(lspApi)
                    else append(" · API ").append(lspApi)
                }
            }
            activated && pingInfo != null -> append("QO 模块已注入 QQ，运行中")
            activated -> append("模块已在 LSPosed 中启用")
            moduleRunning && (lspVersion != null || lspApi > 0) -> {
                append("LSPosed")
                if (lspVersion != null) append(" ").append(lspVersion)
                if (lspCode != null) append(" (").append(lspCode).append(")")
                if (lspApi > 0) append(" · API ").append(lspApi)
            }
            moduleRunning -> append("QO 模块运行中，已读取到账号数据")
            else -> append("未检测到 LSPosed 或模块未启用")
        }
    }
    val nickname = info?.nickname?.takeIf { it.isNotEmpty() } ?: if (activated || moduleRunning) "QQ用户" else "未登录"
    val uinDigits = info?.uin?.takeIf { it.isNotEmpty() } ?: "--"

    // 与模块一致的昵称 / QQ 号配色：径向渐变 + 默认档
    val brushes: List<Brush?> = listOf(
        Brush.radialGradient(listOf(Color(0xFF22D3EE), Color(0xFF3B82F6), Color(0xFF6366F1))),
        null
    )
    val colorBrush = brushes[colorStep % brushes.size]
    fun toggleColor() { colorStep = (colorStep + 1) % brushes.size }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(theme.gradient))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Surface(color = AppBarSurface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(48.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showBack) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable(onClick = onBack),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("‹", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        }
                    }
                    Text(
                        "模块信息",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary,
                        modifier = Modifier.padding(
                            start = if (showBack) 8.dp else 16.dp
                        )
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // ===== 用户信息卡片（直接展示） =====
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(30.dp))
                                .background(
                                    if (avatarBitmap == null) Color(0xFFE4E7FF) else Color.Transparent
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap!!.asImageBitmap(),
                                    contentDescription = "用户头像",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("👤", fontSize = 26.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = buildAnnotatedString {
                                    val brush = colorBrush
                                    if (brush == null) append(nickname)
                                    else withStyle(SpanStyle(brush = brush)) { append(nickname) }
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (colorBrush == null) AppTextPrimary else Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { toggleColor() }
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            // QQ 号数字跟随昵称配色，点击同样切换；昵称与 QQ 号相同时不重复显示
                            val showQqLine = info?.uin?.isNotEmpty() == true && nickname != info.uin
                            if (showQqLine) {
                                Text(
                                    text = buildAnnotatedString {
                                        withStyle(SpanStyle(color = AppTextSecondary)) { append("QQ: ") }
                                        val brush = colorBrush
                                        if (brush == null) {
                                            withStyle(SpanStyle(color = AppTextPrimary)) { append(uinDigits) }
                                        } else {
                                            withStyle(SpanStyle(brush = brush)) { append(uinDigits) }
                                        }
                                    },
                                    fontSize = 13.sp,
                                    color = Color.Unspecified,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { toggleColor() }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ===== 运行状态横幅：激活由应用管家进程通过自 Hook 探针 + Root 兜底自检 =====
                val bannerColor = if (activated || moduleRunning) Color(0xFF4CAF7D) else Color(0xFFEF8A2D)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(bannerColor)
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (activated || moduleRunning) "✓" else "!",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White.copy(alpha = 0.25f)),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                when {
                                    !loaded -> "读取中…"
                                    activated -> "已激活"
                                    moduleRunning -> "模块运行中"
                                    else -> "未激活"
                                },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                when {
                                    !loaded -> "正在读取模块信息"
                                    else -> frameworkText
                                },
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ===== 版本卡片（QAuxiliary 风格）：模块版本 + QQ 版本 =====
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "ⓘ",
                            fontSize = 18.sp,
                            color = Color(0xFF3B82F6),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("版本", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                info?.moduleVersion?.takeIf { it.isNotEmpty() }
                                    ?: HostInfoHelper.moduleVersionName(context).ifEmpty { "--" },
                                fontSize = 14.sp,
                                color = AppTextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("QQ版本", fontSize = 13.sp, color = AppTextSecondary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                info?.qqVersion?.takeIf { it.isNotEmpty() }
                                    ?: HostInfoHelper.qqVersionName(context).ifEmpty { "--" },
                                fontSize = 14.sp,
                                color = AppTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "更新模块后需要重启 QQ 方可生效",
                                fontSize = 12.sp,
                                color = AppTextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ===== 模块设置卡片（QAuxiliary 风格）：跳转 QQ 内的 QO 设置 =====
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("⚙", fontSize = 18.sp, color = Color(0xFF3B82F6))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("模块设置", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "点击下方按钮，直达 QQ 内的 QO 模块设置",
                                fontSize = 13.sp,
                                color = AppTextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "打开模块设置 ›",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF3B82F6),
                                modifier = Modifier.clickable {
                                    openQqModuleSetting(context)
                                }
                            )
                        }
                    }
                }

                if (loaded && !activated && !moduleRunning) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "未检测到模块激活。请确认：1) 设备已 Root 且本应用已获 Root 授权；" +
                            "2) LSPosed 中已启用 QO 模块并勾选 QQ 作用域；3) 回到本页刷新。",
                        fontSize = 12.sp,
                        color = AppTextSecondary,
                        lineHeight = 18.sp
                    )
                }

                // 诊断信息：进程内探测结果（主）+ Root 读取结果（对照）直接显示，方便截图排查
                val diagText = buildString {
                    append("注入：").append(if (injected) "已注入本进程" else "未注入")
                    if (xposedApi > 0) append(" (API ").append(xposedApi).append(")")
                    val rootPart = when {
                        rootActivated == true -> "LSPosed 已安装，模块已启用"
                        rootActivated == false && rootDiag.isNotEmpty() -> rootDiag
                        rootActivated == null -> "Root 读取中…"
                        else -> "Root 未知状态"
                    }
                    append(" · Root：").append(rootPart)
                    if (lspRootVersion.isNotEmpty()) append("，版本 ").append(lspRootVersion)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "诊断：$diagText" + if (debugMsg.isNotEmpty()) " · $debugMsg" else "",
                    fontSize = 11.sp,
                    color = AppTextSecondary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/** 应用管家自身版本（模块与应用管家同版本打包），无 Context 时返回空字符串 */
private object HostInfoHelper {
    /** 用 PackageManager 读应用管家自身版本（不依赖 HostInfo 在宿主进程的初始化） */
    fun moduleVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /**
     * 直接读取已安装 QQ 的版本号（应用管家侧用 PackageManager 实时检测，
     * 不依赖 QQ 进程写文件上报）。
     */
    fun qqVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(QQ_PACKAGE, 0).versionName.orEmpty()
    }.getOrDefault("")
}

/**
 * 探活 QQ 进程里的模块：发有序广播，QQ 进程里的模块应答 resultCode=1
 * 并回传 userinfo.json 内容。应答成功 = 模块已注入并运行（等效已激活）。
 */
private fun pingQqModule(context: Context, onResult: (SharedUserInfo?) -> Unit) {
    val appContext = context.applicationContext
    val ordered = Intent(QQDeepLink.ACTION_PING).setPackage(QQ_PACKAGE)
    ordered.putExtra(QQDeepLink.EXTRA_TOKEN, QQDeepLink.TOKEN)
    runCatching {
        appContext.sendOrderedBroadcast(
            ordered,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (resultCode != 1) {
                        onResult(null)
                        return
                    }
                    val replied = parseSharedUserInfo(resultData)
                        ?: SharedUserInfo(activated = true)
                    onResult(replied)
                }
            },
            null,
            0,
            null,
            null
        )
    }.onFailure {
        onResult(null)
    }
}

/**
 * 打开 QQ 内的 QO 模块设置。双通道：
 * 1. QQ 进程存活（含后台驻留）：发有序广播，模块在 QQ 进程收到后直接拉起设置页；
 * 2. 未响应（QQ 未运行 / 模块未注入）：回退到拉起 QQ 并带启动页信号（冷启动路径）。
 */
private fun openQqModuleSetting(context: Context) {
    val appContext = context.applicationContext

    // 冷启动：拉起 QQ 并带启动页深链信号（QQ 进程不存在 / 模块未注入时使用）
    fun launchQqWithSignal() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(QQ_PACKAGE) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra(QQDeepLink.DEEP_LINK_EXTRA, QQDeepLink.TARGET_SETTING)
        runCatching { appContext.startActivity(intent) }
    }

    // 只把 QQ 现有任务栈切到前台：launcher Intent 不带 CLEAR_TOP、不带深链信号，
    // 系统复用已有任务并显示栈顶——即模块刚打开的设置页，不会新开 QQ 主界面覆盖它。
    // （QQ 后台进程里 startActivity 受 MIUI 后台弹窗限制只建 Activity 不切前台，
    //   而应用管家是前台应用，这里发起的启动不受限制，所以由管家侧负责切前台。）
    fun bringQqTaskToFront() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(QQ_PACKAGE) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    val handled = java.util.concurrent.atomic.AtomicBoolean(false)
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 兜底：800ms 内没等到 QQ 进程的有序广播应答，视为模块未响应，直接冷启动（避免一直卡着）
    val fallbackTask = Runnable {
        if (handled.compareAndSet(false, true)) {
            launchQqWithSignal()
        }
    }
    mainHandler.postDelayed(fallbackTask, 800L)

    val ordered = Intent(QQDeepLink.ACTION_OPEN_SETTING).setPackage(QQ_PACKAGE)
    ordered.putExtra(QQDeepLink.EXTRA_TOKEN, QQDeepLink.TOKEN)
    runCatching {
        appContext.sendOrderedBroadcast(
            ordered,
            null,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (!handled.compareAndSet(false, true)) return
                    mainHandler.removeCallbacks(fallbackTask)
                    if (resultCode == 1) {
                        // QQ 进程已在后台打开设置页：立即把 QQ 任务栈整体切到前台（栈顶即设置页）。
                        // 不用 launcher 之外的方式（避免 CLEAR_TOP 新开主界面覆盖设置页）。
                        mainHandler.post { bringQqTaskToFront() }
                    } else {
                        launchQqWithSignal()
                    }
                }
            },
            null,
            0,
            null,
            null
        )
    }.onFailure {
        mainHandler.removeCallbacks(fallbackTask)
        if (handled.compareAndSet(false, true)) launchQqWithSignal()
    }
}

/** 玻璃卡片容器：与设置页 / 应用列表一致的玻璃拟态 */
@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AppCardBackgroundBrush)
            .border(
                width = 1.dp,
                brush = AppCardBorderBrush,
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        content()
    }
}
