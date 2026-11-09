package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.api.OnSendMsg
import com.qosed.qodeep.levelboost.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 图片外显自定义：
 * 发送图片时把 picElement.summary 改成用户自定义的一段文字，对端看到的预览文字就变了。
 */
object ImageSummary {

    private const val TAG = "ImageSummary"

    private const val KEY_MODE = "image_summary_mode"
    private const val KEY_TIPS = "image_summary_tips"
    private const val KEY_URL = "image_summary_url"
    private const val KEY_FORMAT = "image_summary_format"
    private const val KEY_FIELD = "image_summary_field"

    fun install() {
        OnSendMsg.install()
        OnSendMsg.registerListener { _, elements ->
            if (!ChatConfig.getBoolean(ChatKeys.IMAGE_SUMMARY, false)) return@registerListener
            applySummary(elements)
        }
        FileLog.i("$TAG 已注册发送监听")
    }

    private fun applySummary(elements: MutableList<Any?>) {
        for (el in elements) {
            val pic = OnSendMsg.elementField(el, "picElement") ?: continue
            val summary = pickTip() ?: continue
            if (summary.isEmpty()) continue
            com.qosed.qodeep.chat.ChatRef.setField(pic, "summary", summary)
        }
    }

    /** 取用户设置里写的那段文字，原样作为外显文案（不做随机）。 */
    private fun pickTip(): String? {
        val tip = ChatConfig.getString(KEY_TIPS, "").trim().trim('"', '\'')
        return if (tip.isEmpty()) null else tip
    }

    private fun fetchFromHttp(): String? {
        val url = ChatConfig.getString(KEY_URL, "").trim()
        if (url.isEmpty()) return null
        val raw = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }.onFailure { FileLog.e("$TAG 接口请求失败", it) }.getOrNull()
        if (raw.isNullOrBlank()) return null
        if (ChatConfig.getString(KEY_FORMAT, "text") != "json") return raw
        val field = ChatConfig.getString(KEY_FIELD, "").trim()
        if (field.isEmpty()) return null
        return extractByPath(raw, field)
    }

    /** 支持 data.msg、data.list[0].msg 这类路径。 */
    private fun extractByPath(json: String, path: String): String? = runCatching {
        var cur: Any? = JSONObject(json)
        for (seg in path.split('.')) {
            if (seg.isEmpty()) continue
            val name = seg.substringBefore('[').trim()
            if (name.isNotEmpty()) cur = (cur as? JSONObject)?.opt(name)
            val idx = seg.substringAfter('[', "").substringBefore(']').trim().toIntOrNull()
            if (idx != null) {
                val arr = cur as? JSONArray
                cur = if (arr != null && idx in 0 until arr.length()) arr.opt(idx) else null
            }
            if (cur == null) return null
        }
        when (val v = cur) {
            null, JSONObject.NULL -> null
            is String -> v
            else -> v.toString()
        }
    }.getOrNull()

    // 设置页读写
    fun mode(): String = ChatConfig.getString(KEY_MODE, "text")
    fun setMode(v: String) = ChatConfig.putString(KEY_MODE, v)
    fun tips(): String = ChatConfig.getString(KEY_TIPS, "")
    fun setTips(v: String) = ChatConfig.putString(KEY_TIPS, v)
    fun url(): String = ChatConfig.getString(KEY_URL, "")
    fun setUrl(v: String) = ChatConfig.putString(KEY_URL, v)
    fun format(): String = ChatConfig.getString(KEY_FORMAT, "text")
    fun setFormat(v: String) = ChatConfig.putString(KEY_FORMAT, v)
    fun field(): String = ChatConfig.getString(KEY_FIELD, "")
    fun setField(v: String) = ChatConfig.putString(KEY_FIELD, v)
}
