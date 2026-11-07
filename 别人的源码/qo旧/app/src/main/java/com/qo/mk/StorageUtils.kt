package com.qo.mk

import android.net.Uri
import android.provider.DocumentsContract

/**
 * 把 SAF 的 content Uri 转换成真实文件系统路径。
 * 对主存储（primary）和 SD 卡等外部卷有效，root 环境下足够用。
 * 无法解析时返回 null。
 */
fun uriToRealPath(uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    if (uri.scheme != "content") return null
    return try {
        val docId = if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
        documentIdToPath(docId)
    } catch (e: Exception) {
        null
    }
}

private fun documentIdToPath(docId: String): String? {
    val idx = docId.indexOf(':')
    if (idx < 0) return null
    val volume = docId.substring(0, idx)
    val path = docId.substring(idx + 1).trim('/')
    return when (volume) {
        "primary" -> if (path.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$path"
        else -> if (path.isEmpty()) "/storage/$volume" else "/storage/$volume/$path"
    }
}
