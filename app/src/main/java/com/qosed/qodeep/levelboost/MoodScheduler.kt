package com.qosed.qodeep.levelboost

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 定时发说说调度器。
 *
 * 仅在 QQ 主进程启动一次 Timer，每分钟 tick 一次；到点且今日未发过才发布，
 * 用 MoodConfig 做跨会话幂等标记。发说说依赖 QQEnv / CookieTool 反射取
 * uin、skey、p_skey，再请求 QZone 的 emotion_cgi_publish_v6 接口。
 *
 * 配置存储：{Env.moduleDataDir}/settings/mood.json（与设置页寄生 Activity 共用同一份文件，
 * 用户无需 root 即可查看/删除配置）。
 */
object MoodScheduler {

    const val KEY_ENABLED = "mood_enabled"
    const val KEY_TIME = "mood_time"
    const val KEY_TEXT = "mood_text"
    const val KEY_DELETE_AFTER = "mood_delete_after"
    const val DEFAULT_TIME = "08:30"
    const val DEFAULT_TEXT = "天天开心~"

    // 空间等级签到（每日签到模板说说）
    const val KEY_CHECKIN_ENABLED = "checkin_enabled"
    const val KEY_CHECKIN_TIME = "checkin_time"
    const val KEY_CHECKIN_TEXT = "checkin_text"
    const val KEY_CHECKIN_DELETE_AFTER = "checkin_delete_after"
    const     val DEFAULT_CHECKIN_TIME = "08:00"
    const val DEFAULT_CHECKIN_TEXT = "QOdeep每日空间签到"

    /** 签到说说的模板配图：远程图片直链（可公网访问），宽高与图片实际尺寸一致。 */
    const val CHECKIN_IMAGE_URL = "https://s41.ax1x.com/2026/11/07/pnfKA0S.jpg"
    const val CHECKIN_IMAGE_WIDTH = 1080
    const val CHECKIN_IMAGE_HEIGHT = 1074

    val HH_MM_REGEX = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    private const val PREFIX_DONE = "mood_done_"
    private const val PREFIX_CHECKIN_DONE = "checkin_done_"
    private const val DELETE_DELAY_MS = 5000L

    // 记录上次开关状态，用于检测「关 → 开」变化时立即触发一次
    private const val KEY_LAST_SWITCH = "mood_last_switch"
    private const val KEY_CHECKIN_LAST_SWITCH = "checkin_last_switch"

    private val started = AtomicBoolean(false)
    private var timer: Timer? = null

    // 单次运行锁，防止关→开检测与定时触发并发重复执行
    private val moodRunning = AtomicBoolean(false)
    private val checkinRunning = AtomicBoolean(false)

    /** 主进程注入点调用一次，启动后台 Timer。 */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        timer = Timer("QOdeep_Mood", true)
        timer?.schedule(object : TimerTask() {
            override fun run() = tick()
        }, 5000L, 60L * 1000L)
        FileLog.i("调度器已启动，配置文件：${MoodConfig.configPath()}")

        // 启动时补执行：记录开关状态；若开启、今日未做且「已过设定时间」才补触发。
        // 只补「错过的时间点」（如设定 08:30、QQ 12 点才启动）；
        // 还没到点的（凌晨启动）绝不提前发，等 tick 到点再执行。
        Thread {
            runCatching {
                val now = Date()
                val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)
                val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)

                val moodOn = isEnabled()
                MoodConfig.putBoolean(KEY_LAST_SWITCH, moodOn)
                if (moodOn && getText().isNotEmpty() && !isDoneToday(today) && hm >= getTime()) {
                    FileLog.i("启动补执行：定时说说已过设定点且今日未发，立即触发")
                    doPublishMood(today)
                }

                val ckOn = isCheckinEnabled()
                MoodConfig.putBoolean(KEY_CHECKIN_LAST_SWITCH, ckOn)
                if (ckOn && !isCheckinDoneToday(today) && hm >= getCheckinTime()) {
                    FileLog.i("启动补执行：空间签到已过设定点且今日未签，立即触发")
                    doCheckin(today)
                }
            }.onFailure { FileLog.e("启动补执行异常", it) }
        }.start()
    }

    // ---- 配置读取（供 tick 使用；设置页通过 MoodConfig 操作同一份 settings/mood.json）----

    fun isEnabled(): Boolean = MoodConfig.getBoolean(KEY_ENABLED, false)

    fun getTime(): String =
        MoodConfig.getString(KEY_TIME, DEFAULT_TIME).trim()
            .takeIf { HH_MM_REGEX.matches(it) } ?: DEFAULT_TIME

    fun getText(): String =
        MoodConfig.getString(KEY_TEXT, "").trim().ifEmpty { DEFAULT_TEXT }

    fun isDeleteAfter(): Boolean = MoodConfig.getBoolean(KEY_DELETE_AFTER, false)

    fun isCheckinEnabled(): Boolean = MoodConfig.getBoolean(KEY_CHECKIN_ENABLED, false)

    fun getCheckinTime(): String =
        MoodConfig.getString(KEY_CHECKIN_TIME, DEFAULT_CHECKIN_TIME).trim()
            .takeIf { HH_MM_REGEX.matches(it) } ?: DEFAULT_CHECKIN_TIME

    fun getCheckinText(): String =
        MoodConfig.getString(KEY_CHECKIN_TEXT, "").trim().ifEmpty { DEFAULT_CHECKIN_TEXT }

    fun isCheckinDeleteAfter(): Boolean =
        MoodConfig.getBoolean(KEY_CHECKIN_DELETE_AFTER, false)

    // ---- 幂等标记 ----

    private fun isDoneToday(today: String): Boolean =
        MoodConfig.getBoolean(PREFIX_DONE + today, false)

    private fun isCheckinDoneToday(today: String): Boolean =
        MoodConfig.getBoolean(PREFIX_CHECKIN_DONE + today, false)

    private fun markCheckinDoneToday(today: String) {
        MoodConfig.putBoolean(PREFIX_CHECKIN_DONE + today, true)
        cleanupOldMarks(today)
    }

    private fun markDoneToday(today: String) {
        MoodConfig.putBoolean(PREFIX_DONE + today, true)
        cleanupOldMarks(today)
    }

    /** 清理 7 天前的旧幂等标记，避免 config 文件无限增长。用真日期计算，跨月/跨年也正确。 */
    private fun cleanupOldMarks(today: String) {
        val cal = java.util.Calendar.getInstance()
        runCatching {
            val df = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
            cal.time = df.parse(today) ?: return@runCatching
        }.onFailure { return }
        val out = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        for (back in 4..7) {
            val c = cal.clone() as java.util.Calendar
            c.add(java.util.Calendar.DAY_OF_MONTH, -back)
            MoodConfig.remove(PREFIX_DONE + out.format(c.time))
            MoodConfig.remove(PREFIX_CHECKIN_DONE + out.format(c.time))
        }
    }

    /** 命中判断：允许目标分钟或下一分钟（1 分钟抖动容错，幂等标记保证不重复）。 */
    private fun hitTime(hm: String, target: String): Boolean {
        if (hm == target) return true
        val parts = target.split(':')
        if (parts.size != 2) return false
        val h = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        var h2 = h
        var m2 = m + 1
        if (m2 >= 60) {
            m2 = 0
            h2 = (h2 + 1) % 24
        }
        return hm == "%02d:%02d".format(h2, m2)
    }

    private fun tick() {
        runCatching {
            val now = Date()
            val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now)

            // ============ 定时说说：开关「关 → 开」时立即执行一次（非定时点开启场景） ============
            // 开关状态检测不依赖 uin，先无条件记录/检测，真正要发时才在 doPublishMood 内部取 uin。
            val moodOn = isEnabled()
            val moodLast = MoodConfig.getBoolean(KEY_LAST_SWITCH, false)
            if (moodOn && !moodLast && getText().isNotEmpty() && !isDoneToday(today)) {
                FileLog.i("定时说说开关由关变开，立即触发")
                doPublishMood(today)
            }
            if (moodOn != moodLast) {
                MoodConfig.putBoolean(KEY_LAST_SWITCH, moodOn)
            }

            // ============ 空间签到：开关「关 → 开」时立即执行一次 ============
            val ckOn = isCheckinEnabled()
            val ckLast = MoodConfig.getBoolean(KEY_CHECKIN_LAST_SWITCH, false)
            if (ckOn && !ckLast && !isCheckinDoneToday(today)) {
                FileLog.i("空间签到开关由关变开，立即触发")
                doCheckin(today)
            }
            if (ckOn != ckLast) {
                MoodConfig.putBoolean(KEY_CHECKIN_LAST_SWITCH, ckOn)
            }

            // ============ 定时说说：用户自定义 HH:mm 触发 ============
            val tgt = getTime()
            val text = getText()
            val need = moodOn && text.isNotEmpty() && hitTime(hm, tgt) &&
                !isDoneToday(today)
            if (need) {
                doPublishMood(today)
            }

            // ============ 空间等级签到：到点触发 ============
            val ckTgt = getCheckinTime()
            val ckNeed = ckOn && hitTime(hm, ckTgt) && !isCheckinDoneToday(today)
            if (ckNeed) {
                doCheckin(today)
            }
        }.onFailure { FileLog.e("tick 异常", it) }
    }

    /** 发布定时说说（带运行锁，成功后才写幂等标记）。 */
    private fun doPublishMood(today: String) {
        if (!moodRunning.compareAndSet(false, true)) return
        Thread {
            runCatching {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("定时说说：取不到 uin，跳过本次发布")
                    return@runCatching
                }
                val tgt = getTime()
                val text = getText()
                val (ok, tid) = publishMood(text)
                if (ok) {
                    markDoneToday(today)
                    deleteAfterPublish(isDeleteAfter(), tid)
                }
                FileLog.i("定时说说发布 ok=$ok tid=$tid time=$tgt")
            }.onFailure { FileLog.e("定时说说发布异常", it) }
                .also { moodRunning.set(false) }
        }.start()
    }

    /** 空间等级签到（带运行锁，成功后才写幂等标记）。 */
    private fun doCheckin(today: String) {
        if (!checkinRunning.compareAndSet(false, true)) return
        Thread {
            runCatching {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("空间签到：取不到 uin，跳过本次签到")
                    return@runCatching
                }
                val ckTgt = getCheckinTime()
                val (ok, tid) = qzoneClockIn(getCheckinText())
                if (ok) {
                    markCheckinDoneToday(today)
                    deleteAfterPublish(isCheckinDeleteAfter(), tid)
                }
                FileLog.i("空间等级签到 ok=$ok tid=$tid time=$ckTgt")
            }.onFailure { FileLog.e("空间等级签到异常", it) }
                .also { checkinRunning.set(false) }
        }.start()
    }

    /**
     * 发一条纯文字说说。
     * 接口：taotao.qzone.qq.com emotion_cgi_publish_v6。
     * @return (是否成功, 说说 tid)
     */
    fun publishMood(msg: String): Pair<Boolean, String> {
        return runCatching {
            val uin = QQEnv.getCurrentUin()
            if (uin.isEmpty()) return@runCatching false to ""
            val skey = CookieTool.getSkey() ?: return@runCatching false to ""
            val pSkey = CookieTool.getPskey("qzone.qq.com") ?: return@runCatching false to ""
            val bkn = CookieTool.getBkn(skey)
            val cookieUin = QQEnv.getCookieUin()

            val urlStr = "https://user.qzone.qq.com/proxy/domain/taotao.qzone.qq.com/cgi-bin/emotion_cgi_publish_v6?g_tk=$bkn"
            val encodedMsg = URLEncoder.encode(msg, "UTF-8")
            val referer = URLEncoder.encode("https://user.qzone.qq.com/$uin/infocenter?via=toolbar", "UTF-8")

            val postData = "syn_tweet_verson=1&paramstr=1&pic_template=&richtype=&richval=&special_url=&subrichtype=" +
                "&con=qm$encodedMsg&feedversion=1&ver=1&ugc_right=1&to_sign=1" +
                "&hostuin=$uin&code_version=1&format=json&qzreferrer=$referer"

            val cookie = "uin=$cookieUin; skey=$skey; p_uin=$cookieUin; p_skey=$pSkey"
            val result = httpPostForm(urlStr, postData, cookie)
            val json = runCatching { JSONObject(result.trim()) }.getOrNull()
                ?: return@runCatching false to ""
            val ok = json.optInt("code", -1) == 0
            val tid = json.optString("t1_tid").ifEmpty { json.optString("tid") }
            ok to tid
        }.onFailure {
            FileLog.e("publishMood 异常", it)
        }.getOrDefault(false to "")
    }

    /**
     * 空间等级签到：走 h5.qzone.qq.com publishDiyMood/publishmood，
     * 发一条「每日签到」模板说说（miniprogram randomsignin 模板 + 模板图），
     * 观感就是 QQ 空间里常见的打卡/签到卡片，文字内容可自定义。
     * @return (是否成功, 签到说说 tid)
     */
    fun qzoneClockIn(content: String): Pair<Boolean, String> {
        return runCatching {
            val uin = QQEnv.getCurrentUin()
            if (uin.isEmpty()) return@runCatching false to ""
            val skey = CookieTool.getSkey() ?: return@runCatching false to ""
            val pSkey = CookieTool.getPskey("qzone.qq.com") ?: return@runCatching false to ""
            val gtk = CookieTool.getBkn(pSkey)
            val cookieUin = QQEnv.getCookieUin()

            val t = System.currentTimeMillis()
            val checkinfall = """{"uuid":"$t","id":"union_${t}_$uin","desc":"","cover_url":"","cover_width":0,"cover_height":0,"location":"","material_id":"13||1102","material_cate_1":"13","material_cate_2":"","from":"miniprogram","adId":"randomsignin"}"""
            val eventTags = "${uin}_QOdeep_每日签到"
            val enc = URLEncoder.encode(CHECKIN_IMAGE_URL, "UTF-8")
            val richvalList = "aurl=$enc" +
                "&murl=$enc" +
                "&m_width=$CHECKIN_IMAGE_WIDTH&m_length=$CHECKIN_IMAGE_HEIGHT" +
                "&burl=$enc" +
                "&b_width=$CHECKIN_IMAGE_WIDTH&b_length=$CHECKIN_IMAGE_HEIGHT" +
                "&pic_type=1000&templateId=1&who=2"

            val safeContent = content.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeCheckinfall = checkinfall.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeEventTags = eventTags.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeRichval = richvalList.replace("\\", "\\\\").replace("\"", "\\\"")

            val body = """{"uin":$uin,"content":"$safeContent","extend_info":{"checkinfall":"$safeCheckinfall","comm_self_define_tail_id":"5"},"format":"json","frames":10,"inCharset":"utf-8","issynctoweibo":"0","isWinPhone":"3","outCharset":"utf-8","richtype":"1","richval":"$safeRichval","right_info":{"ugc_right":1},"source":{"subtype":"33"},"stored_extend_info":{"is_diy":"1","event_tags":"$safeEventTags","pic_jump_type":"0","sign_content":"","use_useragent_tail":"1"},"lbsinfo":{"lbs_nm":"","lbs_type":1,"lbs_x":"","lbs_y":""}}"""

            val urlStr = "https://h5.qzone.qq.com/webapp/json/publishDiyMood/publishmood?g_tk=$gtk"
            val cookie = "uin=$cookieUin; skey=$skey; p_uin=$cookieUin; p_skey=$pSkey"

            val result = httpPostJson(urlStr, body, cookie)
            val json = runCatching { JSONObject(result.trim()) }.getOrNull()
            if (json == null) {
                FileLog.e("qzoneClockIn 返回非 JSON: ${result.take(300)}")
                return@runCatching false to ""
            }
            val data = json.optJSONObject("data")
            val innerRet = data?.optInt("ret", 0) ?: 0
            val ok = json.optInt("ret", -1) == 0 && innerRet == 0
            if (!ok) {
                FileLog.e("qzoneClockIn 接口返回失败: ${result.take(300)}")
            }
            ok to data?.optString("tid").orEmpty()
        }.onFailure {
            FileLog.e("qzoneClockIn 异常", it)
        }.getOrDefault(false to "")
    }

    /** 发布成功后按配置延迟删除该条说说（默认不删）。 */
    private fun deleteAfterPublish(enabled: Boolean, tid: String) {
        if (!enabled || tid.isEmpty()) return
        Thread {
            runCatching {
                Thread.sleep(DELETE_DELAY_MS)
                val ok = deleteMood(tid)
                FileLog.i("发布后删除说说 tid=$tid ok=$ok")
            }.onFailure { FileLog.e("发布后删除说说异常", it) }
        }.start()
    }

    /**
     * 删除空间说说。
     * 接口：mobile.qzone.qq.com operation/operation_add，res_type=311 表示说说。
     */
    fun deleteMood(tid: String): Boolean {
        return runCatching {
            if (tid.isEmpty()) return@runCatching false
            val uin = QQEnv.getCurrentUin()
            if (uin.isEmpty()) return@runCatching false
            val skey = CookieTool.getSkey() ?: return@runCatching false
            val pSkey = CookieTool.getPskey("qzone.qq.com") ?: return@runCatching false
            val gtk = CookieTool.getBkn(pSkey)
            val cookieUin = QQEnv.getCookieUin()

            val urlStr = "https://mobile.qzone.qq.com/operation/operation_add?g_tk=$gtk"
            val postData = "opr_type=delugc&res_type=311&res_id=$tid&real_del=0&res_uin=$uin&format=json"
            val cookie = "uin=$cookieUin; skey=$skey; p_uin=$cookieUin; p_skey=$pSkey"

            val result = httpPostForm(urlStr, postData, cookie)
            val json = runCatching { JSONObject(result.trim()) }.getOrNull()
                ?: return@runCatching false
            json.optInt("code", -1) == 0 &&
                (json.optJSONObject("data")?.optInt("ret", 0) ?: 0) == 0
        }.onFailure {
            FileLog.e("deleteMood 异常", it)
        }.getOrDefault(false)
    }

    /** POST JSON 体（签到接口用，Content-Type 为 application/json）。 */
    private fun httpPostJson(urlStr: String, body: String, cookie: String): String {
        var conn: HttpURLConnection? = null
        var reader: BufferedReader? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.doInput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.72 MQQBrowser/6.2 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Referer", "https://h5.qzone.qq.com/")

            val out = DataOutputStream(conn.outputStream)
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()

            reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        } catch (e: Exception) {
            FileLog.e("httpPostJson 异常: ${e.message}")
            ""
        } finally {
            try { reader?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    private fun httpPostForm(urlStr: String, data: String, cookie: String): String {
        var conn: HttpURLConnection? = null
        var reader: BufferedReader? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.doInput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/89.0.4389.72 MQQBrowser/6.2 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Referer", "https://h5.qzone.qq.com/")

            val out = DataOutputStream(conn.outputStream)
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()

            reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        } catch (e: Exception) {
            FileLog.e("httpPostForm 异常: ${e.message}")
            ""
        } finally {
            try { reader?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }
}
