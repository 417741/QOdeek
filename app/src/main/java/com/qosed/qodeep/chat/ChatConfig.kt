package com.qosed.qodeep.chat

import com.qosed.qodeep.Env
import org.json.JSONObject
import java.io.File

/**
 * 聊天功能的配置存储。
 *
 * 持久化到 {Env.moduleDataDir}/settings/chat.json，与「等级加速」的 mood.json 分开：
 * 聊天功能读写频率高（每次消息回调都可能读开关），单文件损坏时也不会互相影响。
 *
 * 读写线程安全：内存持有 JSONObject 缓存，按文件 mtime 判断是否需要重新读盘，
 * 保证设置页写入后宿主进程的 hook 回调能感知变化。
 */
object ChatConfig {

    private const val FILE_NAME = "settings/chat.json"

    private val lock = Any()

    @Volatile
    private var cache: JSONObject? = null

    @Volatile
    private var cacheStamp = 0L

    private fun configFile(): File? {
        val dir = Env.moduleDataDir
        if (dir.isEmpty()) return null
        return File(File(dir), FILE_NAME)
    }

    private fun load(): JSONObject {
        val f = configFile()
        val stamp = f?.takeIf { it.exists() }?.lastModified() ?: 0L
        cache?.let { if (stamp == cacheStamp) return it }
        synchronized(lock) {
            cache?.let { if (stamp == cacheStamp) return it }
            val obj = JSONObject()
            if (f != null && f.exists()) {
                runCatching {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        val parsed = JSONObject(text)
                        val keys = parsed.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            obj.put(k, parsed.opt(k))
                        }
                    }
                }
            }
            cache = obj
            cacheStamp = stamp
            return obj
        }
    }

    private fun persist() {
        val obj = cache ?: return
        val f = configFile() ?: return
        synchronized(lock) {
            runCatching {
                f.parentFile?.let { if (!it.exists()) it.mkdirs() }
                f.writeText(obj.toString())
                cacheStamp = f.lastModified()
            }
        }
    }

    fun reload() {
        synchronized(lock) { cache = null }
    }

    fun getBoolean(key: String, def: Boolean): Boolean = synchronized(lock) {
        load().optBoolean(key, def)
    }

    fun putBoolean(key: String, value: Boolean) = synchronized(lock) {
        load().put(key, value)
        persist()
    }

    fun getString(key: String, def: String): String = synchronized(lock) {
        val v = load().optString(key, def)
        if (v == JSONObject.NULL.toString()) def else v
    }

    fun putString(key: String, value: String) = synchronized(lock) {
        load().put(key, value)
        persist()
    }

    fun getInt(key: String, def: Int): Int = synchronized(lock) {
        load().optInt(key, def)
    }

    fun putInt(key: String, value: Int) = synchronized(lock) {
        load().put(key, value)
        persist()
    }

    fun getLong(key: String, def: Long): Long = synchronized(lock) {
        load().optLong(key, def)
    }

    fun putLong(key: String, value: Long) = synchronized(lock) {
        load().put(key, value)
        persist()
    }

    fun remove(key: String) = synchronized(lock) {
        load().remove(key)
        persist()
    }

    /** 当前全部配置快照（设置页整页渲染用）。 */
    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(load().toString()) }

    fun configPath(): String = configFile()?.absolutePath ?: ""
}

/**
 * 聊天功能的配置 key 与默认值集中定义。
 *
 * 所有开关默认关闭：模块装好后不改动任何聊天行为，用户逐项自己打开。
 */
object ChatKeys {

    // ---- 消息类 ----
    const val FLASH_PIC_BYPASS = "flash_pic_bypass"
    const val PREVENT_RECALL = "prevent_recall"
    const val COPY_ARK_MESSAGE = "copy_ark_message"
    const val LONG_CLICK_SEND_CARD = "long_click_send_card"
    const val REPEAT_MSG = "repeat_msg"
    const val ANTI_POKE_DELAY = "anti_poke_delay"

    // ---- 媒体类 ----
    const val DOWNLOAD_EMOTION = "download_emotion"
    const val NATIVE_EMOTION_DOWNLOAD = "native_emotion_download"
    const val VIDEO_TO_BUBBLE = "video_to_bubble"
    const val VOICE_SPEED = "voice_speed"
    const val FORCE_SPEAKER = "force_speaker"

    // ---- 发送/显示类 ----
    const val EMOTION_AI_TAG = "emotion_ai_tag"
    const val IMAGE_RATIO_OVERRIDE = "image_ratio_override"
    const val IMAGE_SUMMARY = "image_summary"
    const val FORCE_INPUT_NO_LIMIT = "force_input_no_limit"
}

/**
 * 聊天功能的清单（设置页按此顺序渲染卡片，顺序与功能分组一致）。
 */
object ChatFeatures {

    /** 全部功能项（扁平，设置页按此顺序渲染卡片）。 */
    val ALL: List<Feature> = listOf(
        Feature(ChatKeys.FLASH_PIC_BYPASS, "闪照破解", "闪照不再阅后即焚，可直接查看"),
        Feature(ChatKeys.PREVENT_RECALL, "防撤回", "对方撤回的消息保留在聊天界面"),
        Feature(ChatKeys.COPY_ARK_MESSAGE, "复制卡片消息", "长按卡片消息可复制其 JSON 内容"),
        Feature(ChatKeys.LONG_CLICK_SEND_CARD, "长按发送发卡片", "输入框长按发送，按 JSON 内容发出卡片消息"),
        Feature(ChatKeys.REPEAT_MSG, "消息复读", "消息旁显示复读按钮，点击即复读；长按可复制链接、查看原始消息"),
        Feature(ChatKeys.ANTI_POKE_DELAY, "取消拍一拍时间限制", "解除拍一拍的操作冷却"),
        Feature(ChatKeys.DOWNLOAD_EMOTION, "表情/泡泡/视频/语音下载", "长按可保存对应媒体到本地"),
        Feature(ChatKeys.NATIVE_EMOTION_DOWNLOAD, "原生表情包下载", "长按原生表情包保存整套"),
        Feature(ChatKeys.VIDEO_TO_BUBBLE, "视频转泡泡消息", "把视频作为泡泡消息发送"),
        Feature(ChatKeys.VOICE_SPEED, "语音消息倍速播放", "语音播放支持调速"),
        Feature(ChatKeys.FORCE_SPEAKER, "语音强制免提", "语音消息一律外放"),
        Feature(ChatKeys.EMOTION_AI_TAG, "AI表情标签", "表情面板显示 AI 标签"),
        Feature(ChatKeys.IMAGE_RATIO_OVERRIDE, "篡改发送图片比例", "发送前按自定义比例裁剪图片"),
        Feature(ChatKeys.IMAGE_SUMMARY, "图片外显自定义", "自定义图片消息的外显文案"),
        Feature(ChatKeys.FORCE_INPUT_NO_LIMIT, "解除输入框字数上限", "可输入超长文本")
    )

    /**
     * 是否需要重启 QQ 才生效（结构性 hook，运行时无法回滚）。
     * 只有「解除输入框字数上限」是构造时读配置、改不了，才需重启；
     * 语音倍速 / 强制免提的 hook 都是运行时每次现读配置，改完即时生效，无需重启。
     */
    val NEEDS_RESTART: Set<String> = setOf(
        ChatKeys.FORCE_INPUT_NO_LIMIT
    )

    fun isEnabled(key: String): Boolean = ChatConfig.getBoolean(key, false)

    fun setEnabled(key: String, on: Boolean) = ChatConfig.putBoolean(key, on)

    data class Feature(val key: String, val title: String, val subtitle: String)
}
