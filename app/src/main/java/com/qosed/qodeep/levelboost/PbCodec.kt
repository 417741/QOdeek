package com.qosed.qodeep.levelboost

import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 protobuf 编解码器（JSON <-> protobuf 字节）。
 *
 * 字段编号即 JSON 的 key（"1"、"2"、"4"…），值类型按内容自动判定：
 * - Int / Long  -> varint
 * - Boolean     -> varint
 * - String      -> length-delimited（UTF-8）
 * - JSONObject  -> length-delimited（嵌套 message）
 * - JSONArray   -> 同字段号重复写入（repeated）
 * - "hex->xxx"  -> length-delimited（hex 还原的原始字节）
 *
 * 解码侧对 length-delimited 字段做「字符串 or 嵌套 message」启发式判定，
 * 规则与 QQ 协议里常见的 pcbody 结构一致。
 */
object PbCodec {

    private const val HEX_PREFIX = "hex->"
    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LEN = 2
    private const val WIRE_32BIT = 5

    // ---------------- 编码 ----------------

    /** 把 JSONObject 描述的包体编码为 protobuf 字节。 */
    fun encode(json: JSONObject): ByteArray {
        val out = ArrayList<Byte>(64)
        writeMessage(out, json)
        return out.toByteArray()
    }

    private fun writeMessage(out: ArrayList<Byte>, obj: JSONObject) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val field = key.toIntOrNull() ?: continue
            when (val v = obj.opt(key)) {
                null, JSONObject.NULL -> {}
                is Boolean -> writeVarintField(out, field, if (v) 1L else 0L)
                is Int -> writeVarintField(out, field, v.toLong())
                is Long -> writeVarintField(out, field, v)
                is Double -> writeVarintField(out, field, v.toLong())
                is String -> writeLenField(out, field, encodeString(v))
                is JSONObject -> writeLenField(out, field, encode(v))
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        when (val item = v.opt(i)) {
                            null, JSONObject.NULL -> {}
                            is Boolean -> writeVarintField(out, field, if (item) 1L else 0L)
                            is Int -> writeVarintField(out, field, item.toLong())
                            is Long -> writeVarintField(out, field, item)
                            is String -> writeLenField(out, field, encodeString(item))
                            is JSONObject -> writeLenField(out, field, encode(item))
                        }
                    }
                }
            }
        }
    }

    private fun encodeString(s: String): ByteArray {
        return if (s.startsWith(HEX_PREFIX)) {
            hexToBytes(s.substring(HEX_PREFIX.length))
        } else {
            s.toByteArray(Charsets.UTF_8)
        }
    }

    private fun writeVarintField(out: ArrayList<Byte>, field: Int, value: Long) {
        writeVarint(out, ((field shl 3) or WIRE_VARINT).toLong())
        writeVarint(out, value)
    }

    private fun writeLenField(out: ArrayList<Byte>, field: Int, data: ByteArray) {
        writeVarint(out, ((field shl 3) or WIRE_LEN).toLong())
        writeVarint(out, data.size.toLong())
        for (b in data) out.add(b)
    }

    private fun writeVarint(out: ArrayList<Byte>, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.add(v.toByte())
                return
            }
            out.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
    }

    // ---------------- 解码 ----------------

    /** 把 protobuf 字节解码为 JSONObject（字段号字符串作 key）。 */
    fun decode(data: ByteArray): JSONObject = decodeMessage(data)

    private fun decodeMessage(data: ByteArray): JSONObject {
        val obj = JSONObject()
        val repeated = HashMap<Int, JSONArray>()
        var pos = 0
        val end = data.size

        while (pos < end) {
            val tagRes = readVarint(data, pos, end) ?: break
            val tag = tagRes.first
            pos = tagRes.second
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7L).toInt()
            if (field <= 0 || wire == 3 || wire == 4 || wire > 5) break

            when (wire) {
                WIRE_VARINT -> {
                    val r = readVarint(data, pos, end) ?: break
                    pos = r.second
                    putField(obj, repeated, field, r.first)
                }
                WIRE_64BIT -> {
                    if (pos + 8 > end) break
                    var v = 0L
                    for (i in 7 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
                    pos += 8
                    putField(obj, repeated, field, v)
                }
                WIRE_LEN -> {
                    val r = readVarint(data, pos, end) ?: break
                    val len = r.first.toInt()
                    pos = r.second
                    if (len < 0 || pos + len > end) break
                    val sub = data.copyOfRange(pos, pos + len)
                    pos += len
                    putLenField(obj, repeated, field, sub)
                }
                WIRE_32BIT -> {
                    if (pos + 4 > end) break
                    var v = 0L
                    for (i in 3 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
                    pos += 4
                    putField(obj, repeated, field, v)
                }
            }
        }

        for ((k, arr) in repeated) {
            if (obj.opt(k.toString()) !is JSONArray) {
                runCatching { obj.put(k.toString(), arr) }
            }
        }
        return obj
    }

    /**
     * length-delimited 字段：可能是 UTF-8 字符串，也可能是嵌套 message。
     * 判定策略（与 QQ 侧的常见做法一致）：
     *  1) 不是合法 UTF-8 往返 -> 嵌套 message
     *  2) 嵌套解析后没有任何字段 -> 字符串
     *  3) 嵌套解析结果单字段单值，且值为「超大数」或字段号是 14/7/2 -> 字符串
     *  4) 嵌套解析总字段数 <= 2 且字段密度 < 0.1 -> 字符串
     *  5) 其余 -> 嵌套 message
     */
    private fun putLenField(target: JSONObject, repeated: HashMap<Int, JSONArray>, field: Int, sub: ByteArray) {
        val text = String(sub, Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(sub)) {
            putField(target, repeated, field, decodeMessage(sub))
            return
        }
        if (sub.isEmpty()) {
            putField(target, repeated, field, "")
            return
        }
        // 尝试按 message 解析，失败则当字符串
        val nested = runCatching { decodeMessage(sub) }.getOrNull()
        if (nested == null) {
            putField(target, repeated, field, text)
            return
        }
        putField(target, repeated, field, if (isLikelyString(nested, sub)) text else nested)
    }

    private fun isLikelyString(parsed: JSONObject, original: ByteArray): Boolean {
        val keys = parsed.keys()
        val all = ArrayList<Pair<Int, Any>>()
        var total = 0
        while (keys.hasNext()) {
            val k = keys.next()
            val field = k.toIntOrNull() ?: continue
            when (val v = parsed.opt(k)) {
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        all.add(field to v.opt(i))
                        total++
                    }
                }
                else -> {
                    all.add(field to (v ?: ""))
                    total++
                }
            }
        }
        if (all.isEmpty()) return true

        if (all.size == 1) {
            val (field, value) = all[0]
            if (value is Number) {
                if (value.toLong() > 1000000000000000000L) return true
                if (field == 14 || field == 7 || field == 2) return true
            }
            if (value is JSONObject && value.length() == 0) return true
        }

        if (total <= 2 && original.isNotEmpty()) {
            val density = total.toDouble() / original.size
            if (density < 0.1) return true
        }
        return false
    }

    private fun putField(target: JSONObject, repeated: HashMap<Int, JSONArray>, field: Int, value: Any) {
        val key = field.toString()
        if (target.has(key)) {
            val arr = repeated.getOrPut(field) {
                JSONArray().also { a -> runCatching { a.put(target.opt(key)) } }
            }
            runCatching { arr.put(value) }
            runCatching { target.put(key, arr) }
        } else {
            runCatching {
                if (value is Long && value <= Int.MAX_VALUE && value >= Int.MIN_VALUE) {
                    target.put(key, value.toInt())
                } else {
                    target.put(key, value)
                }
            }
        }
    }

    private fun readVarint(data: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < end && shift < 64) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }

    // ---------------- 工具 ----------------

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i + 1 < len) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02X", b.toInt() and 0xFF))
        return sb.toString()
    }

    /** 请求包：前置 4 字节大端长度头（长度为 数据+4）。 */
    fun wrapLength(data: ByteArray): ByteArray {
        val out = ByteArray(data.size + 4)
        val len = data.size + 4
        out[0] = (len ushr 24 and 0xFF).toByte()
        out[1] = (len ushr 16 and 0xFF).toByte()
        out[2] = (len ushr 8 and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        System.arraycopy(data, 0, out, 4, data.size)
        return out
    }

    /** 响应包：首字节为 0 时说明带了 4 字节长度头，去掉；否则原样返回。 */
    fun unwrapLength(data: ByteArray): ByteArray {
        if (data.size < 4) return data
        return if (data[0].toInt() and 0xFF == 0) data.copyOfRange(4, data.size) else data
    }
}
