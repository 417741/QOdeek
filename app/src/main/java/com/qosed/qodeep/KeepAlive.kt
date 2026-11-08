package com.qosed.qodeep

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.qosed.qodeep.levelboost.MoodConfig

/**
 * QQ保活：总开关启用通知保活，悬浮窗另有独立开关（可单独关闭）。
 *
 *  1. 1x1 透明悬浮窗 —— 保持进程可见（TYPE_APPLICATION_OVERLAY），受 KEY_PIXEL_WINDOW 单独控制
 *  2. 前台通知 —— 高重要性 + setOngoing(true) 常驻通知
 *  3. 后台通知 —— 低重要性轻量通知
 *  4. 每 3 秒补发通知 —— QQ 主界面 onResume 会 cancelAll，必须定时补显
 *
 * 运行在 QQ 宿主进程（寄生设置页 / MainHook 注入），借用宿主的悬浮窗与通知权限，
 * 因此模块自身不需要声明 SYSTEM_ALERT_WINDOW / POST_NOTIFICATIONS；权限不足时
 * addView/notify 会抛异常，均被 catch 吞掉只记日志，不影响 QQ 主流程。
 *
 * 配置走 MoodConfig 的 settings/mood.json（跨进程 mtime 热加载），设置页写、宿主读。
 */
object KeepAlive {

    /** 总开关：开 = 前台通知 + 后台通知，并按悬浮窗独立开关决定是否起悬浮窗。 */
    const val KEY_ENABLED = "keep_alive_enabled"

    /** 悬浮窗独立开关：从总开关里拆出来，默认开，保证原有行为不变。 */
    const val KEY_PIXEL_WINDOW = "keep_alive_pixel_window"

    /** 用户可见的模块名统一取这里，避免各处硬编码漏改或混入别的名字。 */
    private const val MODULE_NAME = "QOdeep"

    private const val TAG = "QOdeep.KeepAlive"
    private const val CHANNEL_FG = "qodeep_keepalive_fg"
    private const val CHANNEL_BG = "qodeep_keepalive_bg"
    // 通知 ID 用独立号段，避免与其它常驻通知撞号被覆盖
    private const val NOTIF_ID_FG = 0x6A01
    private const val NOTIF_ID_BG = 0x6A02
    private const val NOTIF_RESHOW_INTERVAL = 3000L
    /** 开关关闭时的巡检间隔：仍能感知配置变化并自愈，但不必 3 秒一次地耗电。 */
    private const val SYNC_IDLE_INTERVAL = 15000L

    /**
     * 当前是否在宿主进程。用进程名判断而非 Context.packageName：寄生页的壳 Context
     * 归属在不同 QQ 版本/ROM 下不稳定，而进程名跨 ClassLoader 完全一致、必然可靠。
     * 桌面独立 App 进程（com.qosed.qodeep）返回 false，那里只写配置不起保活。
     */
    fun isHostProcess(): Boolean {
        val name = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.os.Process.myProcessName()
            } else {
                java.io.File("/proc/self/cmdline").readBytes()
                    .takeWhile { it != 0.toByte() }.toByteArray()
                    .toString(Charsets.UTF_8)
            }
        }.getOrNull().orEmpty()
        // 主进程形如 com.tencent.mobileqq；QQ/TIM 各变体与子进程统一放行（本模块只在主进程注入）
        return name.startsWith("com.tencent.mobileqq") || name.startsWith("com.tencent.tim")
    }

    private var windowManager: WindowManager? = null
    private var pixelView: View? = null
    private var notificationManager: NotificationManager? = null
    private var channelCreated = false

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var hostAppContext: Context? = null

    @Volatile
    private var syncLoopRunning = false

    /**
     * 常驻自愈循环：开启态 3 秒一次、关闭态 15 秒一次，每轮完整同步三项状态。
     * 三个作用：
     *  1) QQ 主界面 onResume 会 cancelAll，必须定时补显通知；
     *  2) 设置页与 MainHook 是不同 ClassLoader（Env 靠 Intent extra 传递即是证据），
     *     MoodConfig/KeepAlive 各有独立静态副本，配置写进文件后 Hook 侧的 refresh()
     *     未必被调用到 —— 本循环让每一侧实例都能自行收敛，避免「关不掉」；
     *  3) 宿主进程重启后，即便 refresh 入口漏调也能自行恢复。
     * 关闭时同步逻辑会把悬浮窗移除、两条通知 cancel 掉，状态严格跟随总开关。
     */
    private val syncRunnable = object : Runnable {
        override fun run() {
            val enabled = try {
                // 强制丢内存缓存再读盘：仅靠文件 lastModified() 判断在部分设备（秒级精度）上
                // 会漏掉同一秒内的连续改写，导致关闭开关后状态收敛不了。reload() 只清缓存、
                // 不改数据，调度器下一次读会拿到同样的最新配置，无副作用。
                MoodConfig.reload()
                sync()
            } catch (t: Throwable) {
                log("sync loop: ${t.message}")
                false
            }
            // 开启态 3 秒补显（对抗 QQ 的 cancelAll）；关闭态降频巡检，省电且仍能自愈
            mainHandler.postDelayed(this, if (enabled) NOTIF_RESHOW_INTERVAL else SYNC_IDLE_INTERVAL)
        }
    }

    /**
     * 立即按总开关同步一次，并确保自愈循环已启动。
     * 可从任意线程调用（内部切主线程，WindowManager 必须主线程操作）。
     * @param context 宿主 QQ 的 Context；寄生设置页传自身 applicationContext 即可。
     */
    fun refresh(context: Context? = null) {
        if (!isHostProcess()) return
        if (context != null) {
            // 桌面独立 App 进程已被上面的门禁拦下；这里是 QQ 主进程，借用宿主悬浮窗/通知权限
            hostAppContext = context.applicationContext
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { refresh() }
            return
        }
        try {
            sync()
            if (!syncLoopRunning) {
                syncLoopRunning = true
                mainHandler.postDelayed(syncRunnable, NOTIF_RESHOW_INTERVAL)
            }
        } catch (t: Throwable) {
            log("refresh error: ${t.message}")
        }
    }

    /** 完整同步：总开关开则确保三项都在，关则确保三项都停（幂等，可反复调用）。
     *  @return 当前总开关状态，供循环决定下一轮间隔。 */
    private fun sync(): Boolean {
        val ctx = hostAppContext ?: return false
        if (windowManager == null) {
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }
        if (notificationManager == null) {
            notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }

        val enabled = MoodConfig.getBoolean(KEY_ENABLED, false)
        val pixelEnabled = MoodConfig.getBoolean(KEY_PIXEL_WINDOW, true)
        if (enabled) {
            // 悬浮窗可单独关闭；关掉时每轮巡检都会把它移除，不会残留
            if (pixelEnabled) startPixelWindow() else stopPixelWindow()
            startForegroundNotification()
            startBackgroundNotification()
        } else {
            stopPixelWindow()
            stopForegroundNotification()
            stopBackgroundNotification()
        }
        return enabled
    }

    private fun ensureChannels() {
        if (channelCreated) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager?.createNotificationChannel(
                NotificationChannel(CHANNEL_FG, "${MODULE_NAME}保活(前台)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "前台保活通知"
                    setShowBadge(false)
                }
            )
            notificationManager?.createNotificationChannel(
                NotificationChannel(CHANNEL_BG, "${MODULE_NAME}保活(后台)", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "后台保活通知"
                    setShowBadge(false)
                }
            )
        }
        channelCreated = true
    }

    /** 1x1 透明悬浮窗：保持进程可见。 */
    private fun startPixelWindow() {
        if (pixelView != null) return
        try {
            val ctx = hostAppContext ?: return
            val view = View(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
            val params = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 0
                y = 0
            }
            windowManager?.addView(view, params)
            pixelView = view
        } catch (t: Throwable) {
            log("startPixelWindow: ${t.message}")
        }
    }

    private fun stopPixelWindow() {
        pixelView?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (_: Throwable) {
            }
        }
        pixelView = null
    }

    private fun buildNotification(
        ctx: Context,
        channelId: String,
        title: String,
        text: String,
        ongoing: Boolean
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, channelId)
        else
            @Suppress("DEPRECATION") Notification.Builder(ctx)

        val iconRes = ctx.applicationInfo?.icon ?: android.R.drawable.ic_dialog_info
        builder.setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setShowWhen(false)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    ctx, 0,
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        ?: android.content.Intent(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        android.app.PendingIntent.FLAG_IMMUTABLE else 0
                )
            )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.setPriority(if (ongoing) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW)
        }
        return builder.build()
    }

    /** 高优先级常驻通知（前台策略）。 */
    private fun startForegroundNotification() {
        try {
            val ctx = hostAppContext ?: return
            ensureChannels()
            notificationManager?.notify(
                NOTIF_ID_FG,
                buildNotification(ctx, CHANNEL_FG, MODULE_NAME, "QQ正在后台运行", true)
            )
        } catch (t: Throwable) {
            log("startForegroundNotification: ${t.message}")
        }
    }

    private fun stopForegroundNotification() {
        try {
            notificationManager?.cancel(NOTIF_ID_FG)
        } catch (_: Throwable) {
        }
    }

    /** 低优先级轻量通知（后台策略）。 */
    private fun startBackgroundNotification() {
        try {
            val ctx = hostAppContext ?: return
            ensureChannels()
            notificationManager?.notify(
                NOTIF_ID_BG,
                buildNotification(ctx, CHANNEL_BG, MODULE_NAME, "保活服务运行中", false)
            )
        } catch (t: Throwable) {
            log("startBackgroundNotification: ${t.message}")
        }
    }

    private fun stopBackgroundNotification() {
        try {
            notificationManager?.cancel(NOTIF_ID_BG)
        } catch (_: Throwable) {
        }
    }

    private fun log(msg: String) {
        android.util.Log.w(TAG, msg)
    }
}
