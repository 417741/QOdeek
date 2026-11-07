package com.qo.mk

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** 单个应用的信息 */
data class AppInfo(
    val appName: String,
    val packageName: String,
    val sourceDir: String,
    val isSystemApp: Boolean,
    val versionName: String = "",
    val lastUpdateTime: Long = 0L
)

/** 应用排序方式 */
enum class SortMode(val label: String) {
    ALPHA("字母排序"),
    SIZE("使用大小"),
    RECENT("最近安装")
}

/** 应用筛选类型 */
enum class AppFilter(val label: String) {
    ALL("显示所有应用"),
    SYSTEM("显示系统应用"),
    USER("显示用户安装的应用")
}

/** 内存缓存：进程存活期间复用，避免重复加载 */
private var cachedApps: List<AppInfo>? = null

/** 磁盘缓存文件名：冷启动时先用它立即显示，后台再刷新 */
private const val CACHE_FILE = "apps_cache.json"

/** 读取磁盘缓存的应用列表，无缓存或损坏时返回 null */
fun loadCachedApps(context: Context): List<AppInfo>? {
    return try {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppInfo(
                appName = o.getString("name"),
                packageName = o.getString("pkg"),
                sourceDir = o.getString("src"),
                isSystemApp = o.getBoolean("sys"),
                versionName = o.optString("ver"),
                lastUpdateTime = o.optLong("install", 0L)
            )
        }
    } catch (e: Exception) {
        null
    }
}

/** 把应用列表写入磁盘缓存，供下次冷启动立即显示 */
fun saveCachedApps(context: Context, apps: List<AppInfo>) {
    try {
        val arr = JSONArray()
        apps.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("name", a.appName)
                    put("pkg", a.packageName)
                    put("src", a.sourceDir)
                    put("sys", a.isSystemApp)
                    put("ver", a.versionName)
                    put("install", a.lastUpdateTime)
                }
            )
        }
        File(context.filesDir, CACHE_FILE).writeText(arr.toString())
    } catch (e: Exception) {
        // 写入失败不影响主流程
    }
}

/** 加载设备上所有已安装的应用（首次加载较慢，之后走内存缓存） */
fun loadAllApps(context: Context, forceRefresh: Boolean = false): List<AppInfo> {
    val cached = cachedApps
    if (!forceRefresh && cached != null) return cached
    val pm = context.packageManager
    val apps = pm.getInstalledPackages(0)
        .mapNotNull { pi ->
            val info = pi.applicationInfo ?: return@mapNotNull null
            AppInfo(
                appName = info.nonLocalizedLabel?.toString()
                    ?: info.loadLabel(pm).toString(),
                packageName = info.packageName,
                sourceDir = info.sourceDir ?: "",
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                versionName = pi.versionName ?: "",
                lastUpdateTime = pi.lastUpdateTime
            )
        }
        .filter { it.appName.isNotBlank() && it.sourceDir.isNotBlank() }
        .sortedBy { it.appName.lowercase() }
    cachedApps = apps
    saveCachedApps(context, apps)
    return apps
}

/**
 * 增量同步：基于缓存列表更新。
 * 已存在的应用沿用缓存里的名称（不逐个 loadLabel），
 * 已卸载的移除，新安装的才 loadLabel 取名称。
 * 比全量 loadAllApps 快得多，用于冷启动后台刷新。
 */
fun syncApps(context: Context, cached: List<AppInfo>): List<AppInfo> {
    val pm = context.packageManager
    val currentMap = pm.getInstalledPackages(0).associateBy { it.packageName }
    val cachedPkgs = cached.mapTo(HashSet()) { it.packageName }

    val result = ArrayList<AppInfo>(currentMap.size)
    for (app in cached) {
        val pi = currentMap[app.packageName] ?: continue // 已卸载，跳过
        val info = pi.applicationInfo ?: continue
        val src = info.sourceDir ?: app.sourceDir
        if (src.isBlank()) continue
        result.add(
            AppInfo(
                appName = app.appName,
                packageName = app.packageName,
                sourceDir = src,
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                versionName = pi.versionName ?: "",
                lastUpdateTime = pi.lastUpdateTime
            )
        )
    }
    for ((pkg, pi) in currentMap) {
        if (pkg in cachedPkgs) continue
        val info = pi.applicationInfo ?: continue
        val name = info.nonLocalizedLabel?.toString()
            ?: info.loadLabel(pm).toString()
        val src = info.sourceDir ?: ""
        if (name.isBlank() || src.isBlank()) continue
        result.add(
            AppInfo(
                appName = name,
                packageName = pkg,
                sourceDir = src,
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                versionName = pi.versionName ?: "",
                lastUpdateTime = pi.lastUpdateTime
            )
        )
    }
    val sorted = result.sortedBy { it.appName.lowercase() }
    cachedApps = sorted
    saveCachedApps(context, sorted)
    return sorted
}

/** 加载应用图标（原始尺寸），失败返回 null。供「提取图标」等需要原图的场景使用。 */
fun loadAppIcon(context: Context, packageName: String): ImageBitmap? {
    return try {
        context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/** 图标内存缓存（进程级），避免滚动回收 / 再次进入时重复解析 APK 图标 */
private val iconCache = LruCache<String, ImageBitmap>(256)

/** 列表图标统一渲染尺寸（px），44dp 在 3x 屏约 132px，留余量 */
private const val ICON_SIZE_PX = 144

/** 磁盘图标缓存目录名 */
private const val ICON_DIR = "app_icons"

/**
 * 加载应用图标（带缓存，缩放到固定尺寸），供列表 / 详情展示使用。
 * 命中顺序：内存缓存 -> 磁盘缓存（按 apk 修改时间判失效）-> 解析 APK 并回写缓存。
 * 相比每次 getApplicationIcon 解析 + 渲染原始大图，冷启动和滚动都快得多。
 */
fun loadAppIconCached(context: Context, packageName: String, sourceDir: String): ImageBitmap? {
    iconCache.get(packageName)?.let { return it }
    val disk = loadIconFromDisk(context, packageName, sourceDir)
    if (disk != null) {
        iconCache.put(packageName, disk)
        return disk
    }
    return try {
        val bmp = context.packageManager.getApplicationIcon(packageName)
            .toBitmap(ICON_SIZE_PX)
        val img = bmp.asImageBitmap()
        iconCache.put(packageName, img)
        saveIconToDisk(context, packageName, bmp)
        img
    } catch (e: Exception) {
        null
    }
}

/** 仅从内存缓存同步取图标（不触发加载），供列表项首帧命中、避免图标闪烁 */
fun peekCachedIcon(packageName: String): ImageBitmap? = iconCache.get(packageName)

private fun loadIconFromDisk(context: Context, packageName: String, sourceDir: String): ImageBitmap? {
    return try {
        val apkTime = File(sourceDir).lastModified()
        if (apkTime <= 0) return null
        val f = File(context.filesDir, "$ICON_DIR/$packageName.png")
        if (!f.exists()) return null
        // apk 之后更新的缓存才有效，否则视为应用已更新、需要重新生成
        if (f.lastModified() < apkTime) return null
        BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

private fun saveIconToDisk(context: Context, packageName: String, bmp: Bitmap) {
    try {
        val dir = File(context.filesDir, ICON_DIR)
        if (!dir.exists()) dir.mkdirs()
        File(dir, "$packageName.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    } catch (e: Exception) {
        // 磁盘缓存写入失败不影响显示
    }
}

private fun Drawable.toBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val w = intrinsicWidth.takeIf { it > 0 } ?: 1
    val h = intrinsicHeight.takeIf { it > 0 } ?: 1
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

/** 缩放到目标尺寸（长边不超过 targetSize），减少渲染大图的时间和内存占用 */
private fun Drawable.toBitmap(targetSize: Int): Bitmap {
    val w = intrinsicWidth.takeIf { it > 0 } ?: targetSize
    val h = intrinsicHeight.takeIf { it > 0 } ?: targetSize
    val scale = minOf(targetSize.toFloat() / w, targetSize.toFloat() / h)
    val outW = maxOf(1, (w * scale).toInt())
    val outH = maxOf(1, (h * scale).toInt())
    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp
}

/**
 * 提取安装包到 Download/应用管家 目录。
 * 使用 MediaStore（分区存储），无需额外存储权限。
 * 返回是否成功。
 */
fun extractApk(context: Context, app: AppInfo): Boolean {
    val source = File(app.sourceDir)
    if (!source.exists()) return false
    val safeName = "${app.appName}_${app.packageName}"
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val fileName = "$safeName.apk"
    return try {
        val mode = loadExtractMode(context)
        val customPath = loadExtractPath(context)
        if (mode == EXTRACT_MODE_CUSTOM && customPath != null) {
            // 自定义文件夹：用 root 直接复制到任意目录（不受分区存储限制）
            val dest = "${customPath.trimEnd('/')}/$fileName"
            copyFileWithRoot(app.sourceDir, dest)
        } else {
            // 默认路径：MediaStore 写入 Download/应用管家
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/应用管家")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } != null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

/**
 * 提取应用图标为 PNG，保存到提取安装包的路径里。
 * 默认：MediaStore 写入 Download/应用管家；自定义路径：root 复制过去。
 * 返回是否成功。
 */
fun extractAppIcon(context: Context, app: AppInfo): Boolean {
    val bitmap = loadAppIcon(context, app.packageName)?.asAndroidBitmap() ?: return false
    val safeName = "${app.appName}_icon".replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val fileName = "$safeName.png"
    return try {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val mode = loadExtractMode(context)
        val customPath = loadExtractPath(context)
        if (mode == EXTRACT_MODE_CUSTOM && customPath != null) {
            // 自定义文件夹：先写缓存，再用 root 复制过去（不受分区存储限制）
            val tmp = File(context.cacheDir, fileName)
            tmp.outputStream().use { bos.writeTo(it) }
            val dest = "${customPath.trimEnd('/')}/$fileName"
            val ok = copyFileWithRoot(tmp.absolutePath, dest)
            tmp.delete()
            ok
        } else {
            // 默认路径：MediaStore 写入 Download/应用管家
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/应用管家")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { bos.writeTo(it) } != null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}
