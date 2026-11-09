package com.qosed.qodeep.chat.item

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.view.View
import android.widget.LinearLayout
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.levelboost.FileLog
import de.robv.android.xposed.XposedBridge
import java.io.IOException
import java.lang.reflect.Modifier

/**
 * 语音强制免提：
 *
 * 宿主语音有两条播放链路——silk 走 AudioTrack、amr 走 MediaPlayer。
 * 走听筒时会把音频流指定成 VOICE_CALL，在 NORMAL 模式（免提）下该流音量既不可控又带通信增益。
 * 这里同时做三件事：
 * 1. 播放器准备完成时切回扬声器；
 * 2. 把 AudioTrack / MediaPlayer / AudioManager 的流类型重定向到 MUSIC；
 * 3. 把增益钳到 1.0，避免某些机型上外放音量异常放大。
 *
 * 隐藏音量键按钮的部分一并处理，否则用户会看到无效的听筒切换按钮。
 */
object ForceSpeaker {

    private const val TAG = "ForceSpeaker"

    private const val CLS_BASE = "com.tencent.mobileqq.qqaudio.audioplayer.AudioPlayerBase"
    private const val CLS_PLAYER_IFACE = "com.tencent.mobileqq.qqaudio.audioplayer.j"
    private const val CLS_AUDIO_BTN = "com.tencent.mobileqq.aio.reserve1.audio.AIOAudioBtnVB"

    private val MEDIA_ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun enabled(): Boolean = ChatConfig.getBoolean(ChatKeys.FORCE_SPEAKER, false)

    fun install() {
        hookPrepare()
        hookSpeakerToggle()
        hookAudioMode()
        hookAudioTrack()
        hookVolumeClamp()
        hookMediaPlayer()
        hookStreamVolume()
        hookAudioBtnVisibility()
        FileLog.i("$TAG 已安装")
    }

    /** 播放前准备完成时切扬声器（混淆方法名，按「无参 void 且抛 IOException」特征匹配）。 */
    private fun hookPrepare() {
        val cls = ChatRef.classOrNull(CLS_BASE) ?: run {
            FileLog.w("$TAG 找不到 AudioPlayerBase")
            return
        }
        var count = 0
        for (m in cls.declaredMethods) {
            val ex = m.exceptionTypes
            if (m.parameterTypes.isNotEmpty() || m.returnType != Void.TYPE) continue
            if (ex.size != 1 || ex[0] != IOException::class.java) continue
            m.isAccessible = true
            ChatRef.hookAfter(m) { param ->
                if (!enabled()) return@hookAfter
                runCatching {
                    audioManager()?.let {
                        it.mode = AudioManager.MODE_NORMAL
                        it.isSpeakerphoneOn = true
                    }
                    forcePlayerStreamMusic(param.thisObject)
                }.onFailure { FileLog.e("$TAG 切扬声器失败", it) }
            }
            count++
        }
        if (count == 0) FileLog.w("$TAG 未匹配到播放准备方法")
    }

    private fun hookSpeakerToggle() {
        runCatching {
            val m = AudioManager::class.java.getMethod("setSpeakerphoneOn", Boolean::class.javaPrimitiveType)
            ChatRef.hookBefore(m) { param ->
                if (!enabled()) return@hookBefore
                param.args[0] = true
            }
        }.onFailure { FileLog.w("$TAG hook setSpeakerphoneOn 失败: ${it.message}") }
    }

    private fun hookAudioMode() {
        runCatching {
            val m = AudioManager::class.java.getMethod("setMode", Int::class.javaPrimitiveType)
            ChatRef.hookBefore(m) { param ->
                if (!enabled()) return@hookBefore
                val mode = param.args[0] as Int
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    param.args[0] = AudioManager.MODE_NORMAL
                }
            }
        }.onFailure { FileLog.w("$TAG hook setMode 失败: ${it.message}") }
    }

    private fun hookAudioTrack() {
        runCatching {
            for (c in AudioTrack::class.java.declaredConstructors) {
                val p = c.parameterTypes
                c.isAccessible = true
                val allInt = p.isNotEmpty() && p.all { it == Int::class.javaPrimitiveType }
                when {
                    allInt && (p.size == 6 || p.size == 7) -> ChatRef.hookBefore(c) { param ->
                        if (!enabled()) return@hookBefore
                        param.args[0] = AudioManager.STREAM_MUSIC
                    }
                    p[0] == AudioAttributes::class.java -> ChatRef.hookBefore(c) { param ->
                        if (!enabled()) return@hookBefore
                        param.args[0] = MEDIA_ATTRS
                    }
                }
            }
        }.onFailure { FileLog.w("$TAG hook AudioTrack 构造失败: ${it.message}") }

        // 播放器实例可能被复用，构造器不会再触发，play() 时再兜一次
        runCatching {
            val play = AudioTrack::class.java.getDeclaredMethod("play").apply { isAccessible = true }
            ChatRef.hookBefore(play) { param ->
                if (!enabled()) return@hookBefore
                runCatching {
                    val get = AudioTrack::class.java.getMethod("getStreamType")
                    val st = get.invoke(param.thisObject) as Int
                    if (st != AudioManager.STREAM_MUSIC) {
                        AudioTrack::class.java.getMethod("setStreamType", Int::class.javaPrimitiveType)
                            .invoke(param.thisObject, AudioManager.STREAM_MUSIC)
                    }
                }
            }
        }.onFailure { FileLog.d("$TAG hook AudioTrack.play 跳过: ${it.message}") }
    }

    /** 增益钳到 1.0。 */
    private fun hookVolumeClamp() {
        for (name in listOf("setVolume", "setStereoVolume")) {
            runCatching {
                for (m in AudioTrack::class.java.declaredMethods.filter { it.name == name }) {
                    m.isAccessible = true
                    ChatRef.hookBefore(m) { param ->
                        if (!enabled()) return@hookBefore
                        clampArgs(param.args)
                    }
                }
            }
        }
    }

    private fun clampArgs(args: Array<Any?>) {
        for (i in args.indices) {
            val v = args[i] as? Float ?: continue
            if (v > 1.0f) args[i] = 1.0f
        }
    }

    private fun hookMediaPlayer() {
        runCatching {
            val m = MediaPlayer::class.java.getMethod("setAudioStreamType", Int::class.javaPrimitiveType)
            ChatRef.hookBefore(m) { param ->
                if (!enabled()) return@hookBefore
                if (param.args[0] as Int != AudioManager.STREAM_MUSIC) {
                    param.args[0] = AudioManager.STREAM_MUSIC
                }
            }
        }.onFailure { FileLog.w("$TAG hook setAudioStreamType 失败: ${it.message}") }

        runCatching {
            for (m in MediaPlayer::class.java.declaredMethods.filter { it.name == "setVolume" }) {
                m.isAccessible = true
                ChatRef.hookBefore(m) { param ->
                    if (!enabled()) return@hookBefore
                    clampArgs(param.args)
                }
            }
        }
    }

    private fun hookStreamVolume() {
        runCatching {
            val set = AudioManager::class.java.getMethod(
                "setStreamVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            ChatRef.hookBefore(set) { param ->
                if (!enabled()) return@hookBefore
                if (param.args[0] as Int == AudioManager.STREAM_VOICE_CALL) {
                    param.args[0] = AudioManager.STREAM_MUSIC
                }
            }
        }
        runCatching {
            val adj = AudioManager::class.java.getMethod(
                "adjustStreamVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            ChatRef.hookBefore(adj) { param ->
                if (!enabled()) return@hookBefore
                if (param.args[0] as Int == AudioManager.STREAM_VOICE_CALL) {
                    param.args[0] = AudioManager.STREAM_MUSIC
                }
            }
        }
        // 音量键真实入口：按当前 Activity 绑定的流决定调整哪个
        runCatching {
            for (m in AudioManager::class.java.declaredMethods.filter { it.name == "adjustSuggestedStreamVolume" }) {
                m.isAccessible = true
                ChatRef.hookBefore(m) { param ->
                    if (!enabled()) return@hookBefore
                    if (param.args.size < 2) return@hookBefore
                    if (param.args[1] as? Int == AudioManager.STREAM_VOICE_CALL) {
                        param.args[1] = AudioManager.STREAM_MUSIC
                    }
                }
            }
        }
        runCatching {
            for (m in Activity::class.java.declaredMethods.filter { it.name == "setVolumeControlStream" }) {
                m.isAccessible = true
                ChatRef.hookBefore(m) { param ->
                    if (!enabled()) return@hookBefore
                    if (param.args.getOrNull(0) as? Int == AudioManager.STREAM_VOICE_CALL) {
                        param.args[0] = AudioManager.STREAM_MUSIC
                    }
                }
            }
        }
    }

    /** 隐藏听筒/扬声器切换按钮：状态更新回调直接短路，实例创建后把容器设为 GONE。 */
    private fun hookAudioBtnVisibility() {
        val cls = ChatRef.classOrNull(CLS_AUDIO_BTN) ?: return
        for (m in cls.declaredMethods) {
            val p = m.parameterTypes
            if (p.size != 1 || m.returnType != Void.TYPE) continue
            if (p[0].name != "com.tencent.mvi.base.mvi.MviUIState") continue
            m.isAccessible = true
            ChatRef.hookBefore(m) { param ->
                if (!enabled()) return@hookBefore
                param.result = null
            }
        }
        for (c in cls.declaredConstructors) {
            c.isAccessible = true
            ChatRef.hookAfter(c) { param ->
                if (!enabled()) return@hookAfter
                runCatching {
                    for (f in cls.declaredFields) {
                        if (Modifier.isStatic(f.modifiers)) continue
                        if (f.type.name != "kotlin.Lazy") continue
                        f.isAccessible = true
                        val lazy = f.get(param.thisObject) ?: continue
                        val v = ChatRef.invoke(lazy, ChatRef.methodByName(lazy.javaClass, "getValue", 0))
                        if (v is LinearLayout) {
                            v.visibility = View.GONE
                            return@runCatching
                        }
                    }
                }
            }
        }
    }

    /**
     * 把播放器实例的流类型改为 MUSIC。
     *
     * 播放器藏在 AudioPlayerBase 的字段里（字段名混淆），按「字段值实现了播放器接口」判断，
     * 而不是按声明类型——同包还有监听器接口，按类型前缀匹配会误判。
     */
    private fun forcePlayerStreamMusic(playerBase: Any?) {
        if (playerBase == null) return
        val iface = ChatRef.classOrNull(CLS_PLAYER_IFACE) ?: return
        var player: Any? = null
        var c: Class<*>? = playerBase.javaClass
        while (c != null && c != Any::class.java && player == null) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = runCatching { f.get(playerBase) }.getOrNull() ?: continue
                if (iface.isInstance(v)) {
                    player = v
                    break
                }
            }
            c = c.superclass
        }
        val p = player ?: return
        for (m in iface.declaredMethods) {
            if (m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.returnType == Void.TYPE
            ) {
                m.isAccessible = true
                runCatching { m.invoke(p, AudioManager.STREAM_MUSIC) }
                return
            }
        }
    }

    private fun audioManager(): AudioManager? = runCatching {
        val ctx: Context = HostEnv.appContext() ?: return null
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }.getOrNull()
}
