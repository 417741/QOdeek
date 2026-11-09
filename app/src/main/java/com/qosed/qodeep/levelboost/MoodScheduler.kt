package com.qosed.qodeep.levelboost

import android.content.Context
import com.qosed.qodeep.Env
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

    // ---- QQ 日签打卡（等级加速，官方 ti.qq.com 接口） ----
    const val KEY_DAILY_SIGN_ENABLED = "daily_sign_enabled"
    const val KEY_DAILY_SIGN_TIME = "daily_sign_time"
    const val DEFAULT_DAILY_SIGN_TIME = "00:10"

    // ---- 大会员签到（等级加速） ----
    const val KEY_BIGVIP_ENABLED = "bigvip_enabled"
    const val KEY_BIGVIP_TIME = "bigvip_time"
    const val DEFAULT_BIGVIP_TIME = "00:20"

    // ---- 自动加好友（等级加速，每次随机加若干个机器人） ----
    const val KEY_ADD_FRIEND_ENABLED = "add_friend_enabled"
    const val KEY_ADD_FRIEND_TIME = "add_friend_time"
    const val DEFAULT_ADD_FRIEND_TIME = "00:30"

    // ---- 空间浏览（等级加速，访问好友空间动态） ----
    const val KEY_SPACE_BROWSE_ENABLED = "space_browse_enabled"
    const val KEY_SPACE_BROWSE_TIME = "space_browse_time"
    const val DEFAULT_SPACE_BROWSE_TIME = "00:40"

    val HH_MM_REGEX = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

    /**
     * 等级加速机器人 QQ（加好友用）。
     *
     * 只保留实测可添加的三个号：其它加速机器人会被服务端以
     * "该机器人暂不支持添加" 拒绝，加进去只会白等 5 秒。
     */
    private val BOT_UINS = arrayOf(
        "3889239358", "3889045760", "3889697341"
    )

    /**
     * 依次向机器人发好友申请，直到成功发出 [count] 个或候选耗尽。
     *
     * 候选顺序：优先「还不是好友」的（能产生真正的新好友申请），
     * 其余（已是好友的，会先删除再重新添加）作为补位；
     * 被服务端拒绝（如"该机器人暂不支持添加"）会自动换下一个补上。
     *
     * @param onLine 每处理完一个目标回调一行描述（用于界面日志）
     * @return 成功发出的申请个数
     */
    private fun addFriendsUntil(count: Int, onLine: ((String) -> Unit)? = null): Int {
        val all = BOT_UINS.toList()
        val friendMap = HashMap<String, Boolean>()
        for (b in all) friendMap[b] = isFriend(b)
        val fresh = all.filter { friendMap[it] == false }.shuffled()
        val rest = all.filter { friendMap[it] == true }.shuffled()
        val candidates = (fresh + rest).take(count * 2)

        var ok = 0
        for ((i, bot) in candidates.withIndex()) {
            if (ok >= count) break
            val wasFriend = friendMap[bot] == true
            val (sent, reason) = sendAddFriendRequest(bot)
            val succeeded = sent && reason.isEmpty()
            if (succeeded) ok++
            val line = when {
                !sent -> "$bot：发送失败"
                reason.isNotEmpty() -> "$bot：被拒绝（$reason）"
                wasFriend -> "$bot：申请已发出（原为好友，已先删除再加）"
                else -> "$bot：申请已发出"
            }
            FileLog.i("加好友 $line")
            onLine?.invoke(line)
            if (ok < count && i < candidates.lastIndex) Thread.sleep(5000)
        }
        FileLog.i("加好友汇总 $ok/$count")
        return ok
    }

    private const val PREFIX_DONE = "mood_done_"
    private const val PREFIX_CHECKIN_DONE = "checkin_done_"
    private const val PREFIX_DAILY_SIGN_DONE = "daily_sign_done_"
    private const val PREFIX_BIGVIP_DONE = "bigvip_done_"
    private const val PREFIX_ADD_FRIEND_DONE = "add_friend_done_"
    private const val PREFIX_SPACE_BROWSE_DONE = "space_browse_done_"
    private const val DELETE_DELAY_MS = 5000L

    /** 空间等级签到最大尝试次数（服务端偶发限频，重试即可成功）。 */
    private const val CHECKIN_ATTEMPTS = 3

    /** 两次签到尝试之间的间隔。 */
    private const val CHECKIN_RETRY_GAP_MS = 3000L

    /** 与「定时说说」相邻执行时的间隔，避免两条说说撞在服务端限频窗口里。 */
    private const val MOOD_PUBLISH_GAP_MS = 5000L

    // 记录上次开关状态，用于检测「关 → 开」变化时立即触发一次
    private const val KEY_LAST_SWITCH = "mood_last_switch"
    private const val KEY_CHECKIN_LAST_SWITCH = "checkin_last_switch"
    private const val KEY_DAILY_SIGN_LAST_SWITCH = "daily_sign_last_switch"
    private const val KEY_BIGVIP_LAST_SWITCH = "bigvip_last_switch"
    private const val KEY_ADD_FRIEND_LAST_SWITCH = "add_friend_last_switch"
    private const val KEY_SPACE_BROWSE_LAST_SWITCH = "space_browse_last_switch"

    private val started = AtomicBoolean(false)
    private var timer: Timer? = null

    // 单次运行锁，防止关→开检测与定时触发并发重复执行
    private val moodRunning = AtomicBoolean(false)
    private val checkinRunning = AtomicBoolean(false)
    private val dailySignRunning = AtomicBoolean(false)
    private val bigVipRunning = AtomicBoolean(false)
    private val addFriendRunning = AtomicBoolean(false)
    private val spaceBrowseRunning = AtomicBoolean(false)

    /** 最近一次成功发说说的时刻（毫秒），用于让签到和说说错开，避开服务端限频。 */
    @Volatile
    private var lastMoodPublishMs = 0L

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

                val dsOn = isDailySignEnabled()
                MoodConfig.putBoolean(KEY_DAILY_SIGN_LAST_SWITCH, dsOn)
                if (dsOn && !isDailySignDoneToday(today) && hm >= getDailySignTime()) {
                    FileLog.i("启动补执行：日签打卡已过设定点且今日未做，立即触发")
                    doDailySign(today)
                }

                val bvOn = isBigVipEnabled()
                MoodConfig.putBoolean(KEY_BIGVIP_LAST_SWITCH, bvOn)
                if (bvOn && !isBigVipDoneToday(today) && hm >= getBigVipTime()) {
                    FileLog.i("启动补执行：大会员签到已过设定点且今日未签，立即触发")
                    doBigVip(today)
                }

                val afOn = isAddFriendEnabled()
                MoodConfig.putBoolean(KEY_ADD_FRIEND_LAST_SWITCH, afOn)
                if (afOn && !isAddFriendDoneToday(today) && hm >= getAddFriendTime()) {
                    FileLog.i("启动补执行：自动加好友已过设定点且今日未做，立即触发")
                    doAddFriend(today)
                }

                val sbOn = isSpaceBrowseEnabled()
                MoodConfig.putBoolean(KEY_SPACE_BROWSE_LAST_SWITCH, sbOn)
                if (sbOn && !isSpaceBrowseDoneToday(today) && hm >= getSpaceBrowseTime()) {
                    FileLog.i("启动补执行：空间浏览已过设定点且今日未做，立即触发")
                    doSpaceBrowse(today)
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

    // ---- QQ 日签打卡 ----
    fun isDailySignEnabled(): Boolean = MoodConfig.getBoolean(KEY_DAILY_SIGN_ENABLED, false)

    fun getDailySignTime(): String =
        MoodConfig.getString(KEY_DAILY_SIGN_TIME, DEFAULT_DAILY_SIGN_TIME).trim()
            .takeIf { HH_MM_REGEX.matches(it) } ?: DEFAULT_DAILY_SIGN_TIME

    // ---- 大会员签到 ----
    fun isBigVipEnabled(): Boolean = MoodConfig.getBoolean(KEY_BIGVIP_ENABLED, false)

    fun getBigVipTime(): String =
        MoodConfig.getString(KEY_BIGVIP_TIME, DEFAULT_BIGVIP_TIME).trim()
            .takeIf { HH_MM_REGEX.matches(it) } ?: DEFAULT_BIGVIP_TIME

    // ---- 自动加好友 ----
    fun isAddFriendEnabled(): Boolean = MoodConfig.getBoolean(KEY_ADD_FRIEND_ENABLED, false)

    fun getAddFriendTime(): String =
        MoodConfig.getString(KEY_ADD_FRIEND_TIME, DEFAULT_ADD_FRIEND_TIME).trim()
            .takeIf { HH_MM_REGEX.matches(it) } ?: DEFAULT_ADD_FRIEND_TIME

    // ---- 空间浏览 ----
    fun isSpaceBrowseEnabled(): Boolean = MoodConfig.getBoolean(KEY_SPACE_BROWSE_ENABLED, false)

    fun getSpaceBrowseTime(): String =
        MoodConfig.getString(KEY_SPACE_BROWSE_TIME, DEFAULT_SPACE_BROWSE_TIME).trim()
            .takeIf { HH_MM_REGEX.matches(it) } ?: DEFAULT_SPACE_BROWSE_TIME

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

    private fun isDailySignDoneToday(today: String): Boolean =
        MoodConfig.getBoolean(PREFIX_DAILY_SIGN_DONE + today, false)

    private fun markDailySignDone(today: String) {
        MoodConfig.putBoolean(PREFIX_DAILY_SIGN_DONE + today, true)
        cleanupOldMarks(today)
    }

    private fun isBigVipDoneToday(today: String): Boolean =
        MoodConfig.getBoolean(PREFIX_BIGVIP_DONE + today, false)

    private fun markBigVipDone(today: String) {
        MoodConfig.putBoolean(PREFIX_BIGVIP_DONE + today, true)
        cleanupOldMarks(today)
    }

    private fun isAddFriendDoneToday(today: String): Boolean =
        MoodConfig.getBoolean(PREFIX_ADD_FRIEND_DONE + today, false)

    private fun markAddFriendDone(today: String) {
        MoodConfig.putBoolean(PREFIX_ADD_FRIEND_DONE + today, true)
        cleanupOldMarks(today)
    }

    private fun isSpaceBrowseDoneToday(today: String): Boolean =
        MoodConfig.getBoolean(PREFIX_SPACE_BROWSE_DONE + today, false)

    private fun markSpaceBrowseDone(today: String) {
        MoodConfig.putBoolean(PREFIX_SPACE_BROWSE_DONE + today, true)
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
            val day = out.format(c.time)
            MoodConfig.remove(PREFIX_DONE + day)
            MoodConfig.remove(PREFIX_CHECKIN_DONE + day)
            MoodConfig.remove(PREFIX_DAILY_SIGN_DONE + day)
            MoodConfig.remove(PREFIX_BIGVIP_DONE + day)
            MoodConfig.remove(PREFIX_ADD_FRIEND_DONE + day)
            MoodConfig.remove(PREFIX_SPACE_BROWSE_DONE + day)
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

            // ============ 日签打卡：开关关→开立即执行 + 到点触发 ============
            val dsOn = isDailySignEnabled()
            val dsLast = MoodConfig.getBoolean(KEY_DAILY_SIGN_LAST_SWITCH, false)
            if (dsOn && !dsLast && !isDailySignDoneToday(today)) {
                FileLog.i("日签打卡开关由关变开，立即触发")
                doDailySign(today)
            }
            if (dsOn != dsLast) MoodConfig.putBoolean(KEY_DAILY_SIGN_LAST_SWITCH, dsOn)
            if (dsOn && hitTime(hm, getDailySignTime()) && !isDailySignDoneToday(today)) {
                doDailySign(today)
            }

            // ============ 大会员签到：开关关→开立即执行 + 到点触发 ============
            val bvOn = isBigVipEnabled()
            val bvLast = MoodConfig.getBoolean(KEY_BIGVIP_LAST_SWITCH, false)
            if (bvOn && !bvLast && !isBigVipDoneToday(today)) {
                FileLog.i("大会员签到开关由关变开，立即触发")
                doBigVip(today)
            }
            if (bvOn != bvLast) MoodConfig.putBoolean(KEY_BIGVIP_LAST_SWITCH, bvOn)
            if (bvOn && hitTime(hm, getBigVipTime()) && !isBigVipDoneToday(today)) {
                doBigVip(today)
            }

            // ============ 自动加好友：开关关→开立即执行 + 到点触发 ============
            val afOn = isAddFriendEnabled()
            val afLast = MoodConfig.getBoolean(KEY_ADD_FRIEND_LAST_SWITCH, false)
            if (afOn && !afLast && !isAddFriendDoneToday(today)) {
                FileLog.i("自动加好友开关由关变开，立即触发")
                doAddFriend(today)
            }
            if (afOn != afLast) MoodConfig.putBoolean(KEY_ADD_FRIEND_LAST_SWITCH, afOn)
            if (afOn && hitTime(hm, getAddFriendTime()) && !isAddFriendDoneToday(today)) {
                doAddFriend(today)
            }

            // ============ 空间浏览：开关关→开立即执行 + 到点触发 ============
            val sbOn = isSpaceBrowseEnabled()
            val sbLast = MoodConfig.getBoolean(KEY_SPACE_BROWSE_LAST_SWITCH, false)
            if (sbOn && !sbLast && !isSpaceBrowseDoneToday(today)) {
                FileLog.i("空间浏览开关由关变开，立即触发")
                doSpaceBrowse(today)
            }
            if (sbOn != sbLast) MoodConfig.putBoolean(KEY_SPACE_BROWSE_LAST_SWITCH, sbOn)
            if (sbOn && hitTime(hm, getSpaceBrowseTime()) && !isSpaceBrowseDoneToday(today)) {
                doSpaceBrowse(today)
            }
        }.onFailure { FileLog.e("tick 异常", it) }
    }

    /** 发布定时说说（带运行锁，成功后才写幂等标记）。 */
    private fun doPublishMood(today: String) {
        if (!moodRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("定时说说：取不到 uin，跳过本次发布")
                    return@Thread
                }
                val tgt = getTime()
                val text = getText()
                val (ok, tid) = publishMood(text)
                if (ok) {
                    markDoneToday(today)
                    deleteAfterPublish(isDeleteAfter(), tid)
                }
                FileLog.i("定时说说发布 ok=$ok tid=$tid time=$tgt")
            } catch (t: Throwable) {
                FileLog.e("定时说说发布异常", t)
            } finally {
                moodRunning.set(false)
            }
        }.start()
    }

    /** 空间等级签到（带运行锁，成功后才写幂等标记）。 */
    private fun doCheckin(today: String) {
        if (!checkinRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("空间签到：取不到 uin，跳过本次签到")
                    return@Thread
                }
                val ckTgt = getCheckinTime()
                val (ok, tid) = qzoneClockIn(getCheckinText())
                if (ok) {
                    markCheckinDoneToday(today)
                    deleteAfterPublish(isCheckinDeleteAfter(), tid)
                }
                FileLog.i("空间等级签到 ok=$ok tid=$tid time=$ckTgt")
            } catch (t: Throwable) {
                FileLog.e("空间等级签到异常", t)
            } finally {
                checkinRunning.set(false)
            }
        }.start()
    }

    /** QQ 日签打卡（带运行锁，成功后才写幂等标记）。 */
    private fun doDailySign(today: String) {
        if (!dailySignRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("日签打卡：取不到 uin，跳过")
                    return@Thread
                }
                val (ok, msg) = dailySign()
                if (ok) markDailySignDone(today)
                FileLog.i("日签打卡 ok=$ok msg=$msg")
            } catch (t: Throwable) {
                FileLog.e("日签打卡异常", t)
            } finally {
                dailySignRunning.set(false)
            }
        }.start()
    }

    /** 大会员签到（带运行锁，成功后才写幂等标记）。 */
    private fun doBigVip(today: String) {
        if (!bigVipRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("大会员签到：取不到 uin，跳过")
                    return@Thread
                }
                val ok = bigVipClockIn()
                if (ok) markBigVipDone(today)
                FileLog.i("大会员签到 ok=$ok")
            } catch (t: Throwable) {
                FileLog.e("大会员签到异常", t)
            } finally {
                bigVipRunning.set(false)
            }
        }.start()
    }

    /** 自动加好友：向内置加速机器人申请，全部处理完即标记完成。 */
    private fun doAddFriend(today: String) {
        if (!addFriendRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("自动加好友：取不到 uin，跳过")
                    return@Thread
                }
                val ok = addFriendsUntil(3)
                markAddFriendDone(today)
                FileLog.i("自动加好友完成 ok=$ok/3")
            } catch (t: Throwable) {
                FileLog.e("自动加好友异常", t)
            } finally {
                addFriendRunning.set(false)
            }
        }.start()
    }

    /** 空间浏览：拉好友说说 mood 链接并逐条访问，触发浏览记录。 */
    private fun doSpaceBrowse(today: String) {
        if (!spaceBrowseRunning.compareAndSet(false, true)) return
        Thread {
            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    FileLog.e("空间浏览：取不到 uin，跳过")
                    return@Thread
                }
                val ok = spaceBrowse(uin)
                markSpaceBrowseDone(today)
                FileLog.i("空间浏览 ok=$ok")
            } catch (t: Throwable) {
                FileLog.e("空间浏览异常", t)
            } finally {
                spaceBrowseRunning.set(false)
            }
        }.start()
    }

    private val forceRunRunning = AtomicBoolean(false)

    /**
     * 手动一键执行：无视各功能开关状态与「当日已完成」标记，逐项强制执行，并把每一步结果回传给调用方。
     * 与定时任务共用同一组运行锁：某功能正在跑时该项跳过，避免并发重复发帖/重复加好友。
     */
    fun runAllNow(onStep: (String) -> Unit) {
        if (!forceRunRunning.compareAndSet(false, true)) {
            onStep("上一次手动执行还没结束，请稍候")
            return
        }
        Thread {
            val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())

            fun step(lock: AtomicBoolean, name: String, block: () -> Pair<Boolean, String>) {
                if (!lock.compareAndSet(false, true)) {
                    onStep("$name：跳过（该功能正在执行中）")
                    return
                }
                try {
                    onStep("$name：执行中…")
                    val (ok, msg) = runCatching(block)
                        .getOrElse { false to "异常 ${it.message ?: it.javaClass.simpleName}" }
                    FileLog.i("手动执行 $name ok=$ok msg=$msg")
                    onStep("$name：${if (ok) "成功" else "失败"}（$msg）")
                } finally {
                    lock.set(false)
                }
            }

            try {
                val uin = QQEnv.getCurrentUin()
                if (uin.isEmpty()) {
                    onStep("当前 QQ 未登录，无法执行")
                    return@Thread
                }
                step(moodRunning, "定时说说") {
                    val (ok, tid) = publishMood(getText())
                    if (ok) markDoneToday(today)
                    ok to if (ok) "tid=$tid" else "发布被拒绝"
                }

                step(checkinRunning, "空间等级签到") {
                    val (ok, tid) = qzoneClockIn(getCheckinText())
                    if (ok) markCheckinDoneToday(today)
                    ok to if (ok) "tid=$tid" else "签到未成功（已重试 $CHECKIN_ATTEMPTS 次）"
                }

                step(dailySignRunning, "QQ日签打卡") {
                    val (ok, msg) = dailySign()
                    if (ok) markDailySignDone(today)
                    ok to msg
                }

                step(bigVipRunning, "大会员签到") {
                    val ok = bigVipClockIn()
                    if (ok) markBigVipDone(today)
                    ok to if (ok) "签到成功" else "签到被拒绝"
                }

                step(addFriendRunning, "自动加好友") {
                    val ok = addFriendsUntil(3) { line -> onStep("  $line") }
                    markAddFriendDone(today)
                    (ok > 0) to "$ok/3 个申请已成功发出"
                }

                step(spaceBrowseRunning, "空间浏览") {
                    val ok = spaceBrowse(uin)
                    markSpaceBrowseDone(today)
                    ok to if (ok) "已访问说说列表" else "未取到可访问的说说"
                }

                onStep("全部执行结束")
            } catch (t: Throwable) {
                FileLog.e("手动执行异常", t)
                onStep("执行异常 ${t.message ?: t.javaClass.simpleName}")
            } finally {
                forceRunRunning.set(false)
            }
        }.start()
    }

    /**
     * QQ 日签打卡（等级加速）。
     * 接口：ti.qq.com daily_attendance/SignIn，用 ti.qq.com 域 p_skey，retCode=1 视为今日已打卡也算成功。
     * @return (是否成功, 结果描述)
     */
    fun dailySign(): Pair<Boolean, String> {
        return runCatching {
            val uin = QQEnv.getCurrentUin()
            if (uin.isEmpty()) return@runCatching false to "当前QQ未登录"
            val skey = CookieTool.getSkey() ?: return@runCatching false to "未获取到 skey"
            val tiPskey = CookieTool.getPskey("ti.qq.com")
                ?: return@runCatching false to "未获取到 ti.qq.com pskey"
            val cookieUin = QQEnv.getCookieUin()
            val cookie = "uin=$cookieUin; skey=$skey; p_uin=$cookieUin; p_skey=$tiPskey"

            val adsReq = """{"app":"QQ","os":"Android","version":"8.8.93","imei":""}"""
                .replace("\"", "\\\"")
            val body = """{"uin":"$uin","type":1,"sId":"","qua":"V1_AND_SQ_8.8.93_2862_HDBM_T","mpExtend":{"tianshuAdsReq":"$adsReq"}}"""

            val raw = httpPostJson(
                "https://ti.qq.com/hybrid-h5/api/json/daily_attendance/SignIn",
                body,
                cookie,
                "https://ti.qq.com/"
            )
            if (raw.isEmpty()) return@runCatching false to "网络异常"
            val root = runCatching { JSONObject(raw.trim()) }.getOrNull()
                ?: return@runCatching false to "接口返回异常: ${raw.take(100)}"
            val data = root.optJSONObject("data")
                ?: return@runCatching false to "接口返回异常: ${raw.take(100)}"
            when (data.optString("retCode", "-1").trim()) {
                "0" -> true to "日签打卡成功"
                "1" -> true to "今日已打过日签卡"
                else -> false to "日签失败 retCode=${data.optString("retCode")}"
            }
        }.onFailure { FileLog.e("dailySign 异常", it) }
            .getOrDefault(false to "日签异常")
    }

    /**
     * 大会员签到（等级加速）。
     * 接口：h5.qzone.qq.com fcg_vip_task_checkin op=CheckIn appid=qq_big_vip。
     * 注意 g_tk 必须用 qzone.qq.com 的 p_skey 计算，否则返回 g_tk invalid。
     */
    fun bigVipClockIn(): Boolean {
        return runCatching {
            val uin = QQEnv.getCurrentUin()
            if (uin.isEmpty()) return@runCatching false
            val skey = CookieTool.getSkey() ?: return@runCatching false
            val qzone = CookieTool.getPskey("qzone.qq.com") ?: return@runCatching false
            // 该 VIP 接口要求 cookie 里的 uin 带 "o" 前缀，否则返回 g_tk invalid
            val bkn = CookieTool.getBkn(qzone)
            val cookieUin = "o$uin"
            val cookie = "uin=$cookieUin; skey=$skey; p_uin=$cookieUin; p_skey=$qzone"

            val urlStr = "https://h5.qzone.qq.com/p/vip/fcg-bin/v2/fcg_vip_task_checkin?g_tk=$bkn"
            val postData = "appid=qq_big_vip&op=CheckIn&format=json&inCharset=utf-8&outCharset=utf-8"
            val result = httpPostForm(urlStr, postData, cookie)
            if (result.isEmpty()) return@runCatching false
            result.contains("\"ret\":0") || result.contains("\"code\":0") ||
                result.contains("\"msg\":\"成功\"") || result.contains("\"msg\":\"已签到\"") ||
                result.contains("CheckIn成功") || result.contains("success")
        }.onFailure { FileLog.e("bigVipClockIn 异常", it) }
            .getOrDefault(false)
    }

    /**
     * 发送好友申请（SSO 包 OidbSvcTrpcTcp.0x7c2_5）。
     * 已是好友时先删除再加，保证每次都是"新加好友"从而计入等级加速。
     *
     * @return (是否拿到响应, 服务端拒绝原因；成功时为空串)
     */
    private fun sendAddFriendRequest(targetUin: String): Pair<Boolean, String> {
        return runCatching {
            val myUin = QQEnv.getCurrentUin()
            if (myUin.isEmpty()) return@runCatching false to "未登录"

            // 已是好友则先删除，否则重复添加不会产生加速效果
            val wasFriend = isFriend(targetUin)
            if (wasFriend) {
                val deleted = deleteFriend(targetUin)
                Thread.sleep(2000)
                FileLog.i("加好友 $targetUin：已是好友，删除=$deleted")
            }

            val packet = JSONObject()
            packet.put("1", 1986)
            packet.put("2", 5)

            val obj32 = JSONObject()
            val obj1 = JSONObject()
            obj1.put("100", 0)
            obj1.put("101", 0)
            obj1.put("102", 0)
            obj32.put("1", obj1)

            val obj4 = JSONObject()
            obj4.put("32", obj32)
            obj4.put("1", myUin.toLong())
            obj4.put("2", targetUin.toLong())
            obj4.put("3", 1)
            obj4.put("4", 1)
            obj4.put("5", 0)
            obj4.put("7", "")
            obj4.put("9", 1)
            obj4.put("11", 3041)
            obj4.put("12", 12)
            obj4.put("18", "")
            obj4.put("20", 0)
            obj4.put("28", 1)
            obj4.put("29", 1)
            obj4.put("30", JSONObject())
            obj4.put("31", "")

            packet.put("4", obj4)
            packet.put("12", 1)

            // 服务端会回 4.6=错误标志、4.7=错误码、4.8=错误文案，
            // 例如 "该机器人暂不支持添加" 就属于被拒绝，不能算成功。
            val resp = PacketSender.sendSync("OidbSvcTrpcTcp.0x7c2_5", packet, 8000)
            if (resp == null) {
                FileLog.w("加好友 $targetUin：无响应")
                return@runCatching false to "无响应"
            }
            val body = resp.optJSONObject("4")
            val errFlag = body?.optInt("6", 0) ?: 0
            val errMsg = body?.optString("8").orEmpty()
            if (errFlag != 0 || errMsg.isNotEmpty()) {
                FileLog.w("加好友 $targetUin：被拒绝 flag=$errFlag msg=$errMsg")
                return@runCatching true to errMsg.ifEmpty { "code=$errFlag" }
            }
            FileLog.i("加好友 $targetUin：申请已发出（已是好友=$wasFriend）")
            true to ""
        }.onFailure { FileLog.e("sendAddFriendRequest 异常 $targetUin", it) }
            .getOrDefault(false to "异常")
    }

    /**
     * 空间浏览：通过 SSO 包 GetFriendFeeds 拉取好友说说，提取 15 条 mood 链接后逐条带 cookie 访问。
     */
    private fun spaceBrowse(uin: String): Boolean {
        return runCatching {
            val req = JSONObject()
            req.put("1", 20)
            req.put("2", 0)
            val inner = JSONObject()
            inner.put("1", uin)
            req.put("3", inner)

            val resp = PacketSender.sendSync(
                "QzoneV4Service.trpc.qzone.feeds_reader.FeedsReader.GetFriendFeeds", req, 15000
            )
            if (resp == null) {
                FileLog.e("空间浏览：GetFriendFeeds 无响应")
                return@runCatching false
            }

            val links = extractMoodLinks(resp.toString()).take(15)
            if (links.isEmpty()) {
                FileLog.e("空间浏览：未提取到 mood 链接")
                return@runCatching false
            }

            val skey = CookieTool.getSkey().orEmpty()
            val pSkey = CookieTool.getPskey("qzone.qq.com").orEmpty()
            val cookieUin = QQEnv.getCookieUin()
            val cookie = buildString {
                append("uin=$cookieUin; ")
                if (pSkey.isNotEmpty()) append("p_skey=$pSkey; ")
                if (skey.isNotEmpty()) append("skey=$skey; ")
            }

            var okCount = 0
            for (url in links) {
                runCatching {
                    if (httpGet(url, cookie).isNotEmpty()) okCount++
                }.onFailure { FileLog.e("空间浏览访问失败 $url", it) }
                Thread.sleep(800)
            }
            FileLog.i("空间浏览完成 $okCount/${links.size}")
            okCount > 0
        }.onFailure { FileLog.e("spaceBrowse 异常", it) }
            .getOrDefault(false)
    }

    private val MOOD_URL_PATTERN =
        Regex("""https?:\\?/\\?/user\.qzone\.qq\.com\\?/\d+\\?/mood\\?/[A-Za-z0-9]+""")

    /** 从响应里提取 mood 链接，去重保序。 */
    private fun extractMoodLinks(raw: String): List<String> {
        val result = ArrayList<String>()
        val seen = HashSet<String>()
        runCatching {
            for (m in MOOD_URL_PATTERN.findAll(raw)) {
                val url = m.value.replace("\\/", "/").replace("http://", "https://")
                if (seen.add(url)) result.add(url)
            }
        }.onFailure { FileLog.e("extractMoodLinks 异常", it) }
        return result
    }

    /**
     * 取 QQ 的接口实现（失败返回 null）。
     *
     * 依次尝试三条通道，兼容不同版本 QQ：
     * 1. `QRoute.api(Class)` —— 新版 QQ 的接口路由（绝大多数字符串接口都从这里取）；
     * 2. `QQServiceHelper.getApi(Class)` —— 旧版 QQ 的服务助手；
     * 3. `AppRuntime.getRuntimeService(Class, String)` —— 老式 IRuntimeService。
     * 任一通道拿到非空实现即返回，全部失败返回 null（调用方按"非好友/无接口"降级）。
     */
    private fun getQqApi(className: String): Any? {
        val loader = Env.hostClassLoader
        val apiClass = runCatching { loader.loadClass(className) }.getOrElse {
            FileLog.e("getQqApi 接口类不存在 $className", it)
            return null
        }

        // 1) QRoute.api(Class)
        runCatching {
            val qroute = loader.loadClass("com.tencent.mobileqq.qroute.QRoute")
            val method = qroute.methods.firstOrNull { m ->
                m.name == "api" && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == Class::class.java
            } ?: return@runCatching
            val api = method.invoke(null, apiClass)
            if (api != null) return api
        }.onFailure { FileLog.w("getQqApi QRoute 通道不可用 $className: ${it.message}") }

        // 2) QQServiceHelper.getApi(Class)
        runCatching {
            val helper = loader.loadClass("com.tencent.mobileqq.qfix.QQServiceHelper")
            val api = helper.getMethod("getApi", Class::class.java).invoke(null, apiClass)
            if (api != null) return api
        }.onFailure { FileLog.w("getQqApi QQServiceHelper 通道不可用 $className: ${it.message}") }

        // 3) AppRuntime.getRuntimeService(Class, String) —— IRuntimeService 只能走这条；
        //    命名空间多数服务用 "all"，个别老服务用空串，两个都试
        runCatching {
            val app = QQEnv.getAppRuntime() ?: return@runCatching
            val method = app.javaClass.getMethod("getRuntimeService", Class::class.java, String::class.java)
            for (ns in listOf("all", "")) {
                val api = method.invoke(app, apiClass, ns)
                if (api != null) return api
            }
        }.onFailure { FileLog.w("getQqApi getRuntimeService 通道不可用 $className: ${it.message}") }

        FileLog.w("getQqApi 三条通道都没取到实现 $className")
        return null
    }

    /** AppRuntime.getRuntimeService(Class, String)：拿 QQ 的 Manager（失败返回 null）。 */
    private fun getQqRuntimeService(className: String): Any? {
        return runCatching {
            val app = QQEnv.getAppRuntime() ?: return@runCatching null
            val serviceClass = Env.hostClassLoader.loadClass(className)
            app.javaClass.getMethod("getRuntimeService", Class::class.java, String::class.java)
                .invoke(app, serviceClass, "")
        }.onFailure { FileLog.e("getQqRuntimeService 失败 $className", it) }
            .getOrNull()
    }

    /** 已是 uid（u_ 开头）则原样返回。 */
    private fun isValidUid(s: String): Boolean = s.startsWith("u_") && s.length > 2

    /** QQ 号转 uid（好友服务接口按 uid 索引），失败回退原值。 */
    private fun getUidFromUin(uin: String): String {
        if (isValidUid(uin)) return uin
        return runCatching {
            val api = getQqApi("com.tencent.relation.common.api.IRelationNTUinAndUidApi")
                ?: return@runCatching uin
            api.javaClass.getMethod("getUidFromUin", String::class.java)
                .invoke(api, uin) as? String ?: uin
        }.getOrDefault(uin)
    }

    /** 判断目标是否已是好友（失败按非好友处理）。 */
    private fun isFriend(uin: String): Boolean {
        return runCatching {
            val api = getQqApi("com.tencent.qqnt.ntrelation.friendsinfo.api.IFriendsInfoService")
                ?: return@runCatching false
            val uid = getUidFromUin(uin)
            api.javaClass.getMethod("isFriend", String::class.java, String::class.java)
                .invoke(api, uid, "") as? Boolean ?: false
        }.onFailure { FileLog.e("isFriend 异常 $uin", it) }
            .getOrDefault(false)
    }

    /** 删除好友（按 QQ 号调用，QQ 内部自行转换 uid）。 */
    private fun deleteFriend(uin: String): Boolean {
        return runCatching {
            val api = getQqApi("com.tencent.mobileqq.addfriend.api.IDelFriendService")
                ?: return@runCatching false
            api.javaClass.getMethod(
                "delFriend", String::class.java, String::class.java,
                Byte::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(api, "FriendsManager_deleteFriend", uin, 0.toByte(), 0)
            // 删除是异步下发，稍等后回查真实结果
            Thread.sleep(1500)
            val still = isFriend(uin)
            if (still) FileLog.w("deleteFriend 未生效 $uin")
            !still
        }.onFailure { FileLog.e("deleteFriend 异常 $uin", it) }
            .getOrDefault(false)
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
            if (ok) lastMoodPublishMs = System.currentTimeMillis()
            ok to tid
        }.onFailure {
            FileLog.e("publishMood 异常", it)
        }.getOrDefault(false to "")
    }

    /**
     * 空间等级签到：走 h5.qzone.qq.com publishDiyMood/publishmood，
     * 发一条「每日签到」模板说说（miniprogram randomsignin 模板 + 模板图），
     * 观感就是 QQ 空间里常见的打卡/签到卡片，文字内容可自定义。
     *
     * 偶发的服务端限频/网络抖动会让单次发布失败（表现为"有概率成功"），
     * 因此最多尝试 [CHECKIN_ATTEMPTS] 次，且必须拿到非空 tid 才算成功。
     *
     * @return (是否成功, 签到说说 tid)
     */
    fun qzoneClockIn(content: String): Pair<Boolean, String> {
        // 刚发过说说时先等一会儿：服务端对同账号短时间连发两条说说会限频
        val elapsed = System.currentTimeMillis() - lastMoodPublishMs
        if (elapsed in 0 until MOOD_PUBLISH_GAP_MS) {
            Thread.sleep(MOOD_PUBLISH_GAP_MS - elapsed)
        }
        var reason = ""
        for (attempt in 1..CHECKIN_ATTEMPTS) {
            val (ok, tid, msg) = qzoneClockInOnce(content)
            if (ok && tid.isNotEmpty()) {
                if (attempt > 1) FileLog.i("空间等级签到第 $attempt 次尝试成功")
                return true to tid
            }
            reason = msg.ifEmpty { "未返回 tid" }
            FileLog.w("空间等级签到第 $attempt 次失败：$reason")
            if (attempt < CHECKIN_ATTEMPTS) Thread.sleep(CHECKIN_RETRY_GAP_MS)
        }
        FileLog.e("空间等级签到最终失败：$reason")
        return false to ""
    }

    /** 空间等级签到单次尝试。@return (是否成功, tid, 失败原因) */
    private fun qzoneClockInOnce(content: String): Triple<Boolean, String, String> {
        return runCatching {
            val uin = QQEnv.getCurrentUin()
            if (uin.isEmpty()) return@runCatching Triple(false, "", "uart 为空")
            val skey = CookieTool.getSkey()
                ?: return@runCatching Triple(false, "", "skey 为空")
            val pSkey = CookieTool.getPskey("qzone.qq.com")
                ?: return@runCatching Triple(false, "", "p_skey 为空")
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
                return@runCatching Triple(
                    false, "", "返回非 JSON（${result.take(60).ifEmpty { "空响应" }}）"
                )
            }
            val data = json.optJSONObject("data")
            val innerRet = data?.optInt("ret", 0) ?: 0
            val outerRet = json.optInt("ret", -1)
            val tid = data?.optString("tid").orEmpty()
            val ok = outerRet == 0 && innerRet == 0 && tid.isNotEmpty()
            FileLog.i(
                "qzoneClockIn ret=$outerRet dataRet=$innerRet tid=$tid resp=${result.take(200)}"
            )
            Triple(
                ok, tid,
                if (ok) "" else "ret=$outerRet dataRet=$innerRet msg=${json.optString("msg")}"
            )
        }.onFailure {
            FileLog.e("qzoneClockIn 异常", it)
        }.getOrDefault(Triple(false, "", "异常：网络请求失败"))
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
    private fun httpPostJson(
        urlStr: String,
        body: String,
        cookie: String,
        referer: String = "https://h5.qzone.qq.com/"
    ): String {
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
            conn.setRequestProperty("Referer", referer)

            val out = DataOutputStream(conn.outputStream)
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()

            // 服务端限频/风控时会返回 4xx/5xx，错误体里同样带 ret/msg，
            // 必须从 errorStream 读，否则只会看到一个空串。
            val code = conn.responseCode
            val stream = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            if (code !in 200..299) FileLog.w("httpPostJson HTTP $code: ${sb.take(200)}")
            sb.toString()
        } catch (e: Exception) {
            FileLog.e("httpPostJson 异常: ${e.message}")
            ""
        } finally {
            try { reader?.close() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    /** GET 请求（空间浏览用）。失败返回空串。 */
    private fun httpGet(urlStr: String, cookie: String): String {
        var conn: HttpURLConnection? = null
        var reader: BufferedReader? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doInput = true
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; V2166BA Build/TP1A.220624.014; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.193 Mobile Safari/537.36"
            )
            conn.setRequestProperty("Referer", "https://user.qzone.qq.com/")

            reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        } catch (e: Exception) {
            FileLog.e("httpGet 异常: ${e.message}")
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
