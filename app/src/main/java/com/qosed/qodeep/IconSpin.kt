package com.qosed.qodeep

import android.os.SystemClock

/**
 * 入口头像与模块主页头像的共享旋转基准。
 *
 * 两处头像运行在不同进程（入口在 QQ 宿主进程、模块主页在模块自身进程），
 * 内存里的角度变量无法互通。要让它们「同步转、方向一致」，必须锚定同一个
 * 系统绝对时钟：用 [SystemClock.elapsedRealtime] 对固定周期取模推算角度，
 * 只要两处用同一周期、同一公式，任意时刻角度天然相等、方向相同（都顺时针）。
 */
object IconSpin {
    /** 旋转一圈的时长（毫秒），两处头像必须一致才能同步。 */
    const val PERIOD_MS = 8000L

    /** 根据系统时钟推算当前应处的角度（0..360），两处同源、天然同步。 */
    fun currentDegrees(): Float {
        val t = SystemClock.elapsedRealtime() % PERIOD_MS
        return (t.toFloat() / PERIOD_MS) * 360f
    }
}
