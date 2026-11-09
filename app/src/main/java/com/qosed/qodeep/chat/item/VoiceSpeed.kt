package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog
import de.robv.android.xposed.XposedBridge

/**
 * 语音消息倍速播放：
 * 底层播放器（silk / amr 两条实现）都有 setPlaySpeed(float)，拦截后把入参改成配置倍速。
 *
 * 只在开关打开时改写；关闭时原样调用原方法，避免模块装上去就影响正常语速。
 */
object VoiceSpeed {

    private const val TAG = "VoiceSpeed"

    private const val KEY_VALUE = "voice_speed_value"

    private const val DEFAULT_SPEED = 1.5f

    /** 首次实际改写倍速时打一条日志，用于确认功能真的生效。 */
    @Volatile
    private var appliedLogged = false

    private val PLAYERS = listOf(
        "com.tencent.mobileqq.qqaudio.audioplayer.SilkPlayer",
        "com.tencent.mobileqq.qqaudio.audioplayer.AmrPlayer"
    )

    fun install() {
        var hooked = 0
        for (name in PLAYERS) {
            val cls = ChatRef.classOrNull(name) ?: continue
            val m = ChatRef.methodOrNull(
                cls, "setPlaySpeed", Float::class.javaPrimitiveType as Class<*>
            ) ?: continue
            runCatching {
                XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!ChatConfig.getBoolean(ChatKeys.VOICE_SPEED, false)) return
                        if (param.args.isNotEmpty()) {
                            val speed = currentSpeed()
                            param.args[0] = speed
                            if (!appliedLogged) {
                                appliedLogged = true
                                FileLog.i("$TAG 倍速生效: ${speed}x")
                            }
                        }
                    }
                })
                hooked++
            }.onFailure { FileLog.e("$TAG hook $name 失败", it) }
        }
        if (hooked == 0) FileLog.w("$TAG 未找到播放器 setPlaySpeed")
        else FileLog.i("$TAG 已 hook $hooked 个播放器")
    }

    fun currentSpeed(): Float {
        val v = ChatConfig.getString(KEY_VALUE, DEFAULT_SPEED.toString()).toFloatOrNull()
        return if (v != null && v > 0f && v <= 3f) v else DEFAULT_SPEED
    }

    fun setSpeed(v: Float) = ChatConfig.putString(KEY_VALUE, v.toString())
}
