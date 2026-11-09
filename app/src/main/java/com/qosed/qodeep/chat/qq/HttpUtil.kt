package com.qosed.qodeep.chat.qq

import com.qosed.qodeep.chat.qq.CookieTool
import com.qosed.qodeep.levelboost.FileLog
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 文件下载。
 *
 * 腾讯的图片/语音直链需要带上 Referer 与 Cookie 才不返回 403，
 * 这里的请求头是必须的，不是可选优化。
 */
object HttpUtil {

    private const val TAG = "HttpUtil"

    private const val TIMEOUT = 30_000

    fun downloadSync(url: String, destPath: String): Boolean {
        if (url.isEmpty()) return false
        val dest = File(destPath)
        dest.parentFile?.let { if (!it.exists()) it.mkdirs() }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) QQ/9.0")
                setRequestProperty("Referer", "https://qun.qq.com/")
                val ck = CookieTool.cookie()
                if (ck.isNotEmpty()) setRequestProperty("Cookie", ck)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                FileLog.w("$TAG 下载失败 HTTP $code: ${url.take(80)}")
                return false
            }
            var total = 0L
            conn.inputStream.use { ins ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    out.flush()
                }
            }
            total > 0 && dest.exists()
        } catch (t: Throwable) {
            FileLog.e("$TAG 下载异常: ${url.take(80)}", t)
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 复制本地文件，按长度校验是否完整。 */
    fun copyFile(src: File, dst: File): Boolean = runCatching {
        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
        src.inputStream().use { ins ->
            FileOutputStream(dst).use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
        dst.exists() && dst.length() == src.length()
    }.getOrDefault(false)

    fun extOfImage(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains(".gif") -> ".gif"
            u.contains(".png") -> ".png"
            u.contains(".webp") -> ".webp"
            else -> ".jpg"
        }
    }

    fun extOfVideo(path: String): String {
        val p = path.lowercase()
        return listOf(".mp4", ".mov", ".avi", ".mkv", ".3gp", ".flv", ".webm", ".m4v")
            .firstOrNull { p.endsWith(it) } ?: ".mp4"
    }

    fun extOfPtt(path: String): String {
        val p = path.lowercase()
        return when {
            p.endsWith(".m4a") -> ".m4a"
            p.endsWith(".wav") -> ".wav"
            else -> ".amr"
        }
    }
}
