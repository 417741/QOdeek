package com.qosed.qodeep.chat.qq

import com.qosed.qodeep.Env
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog
import java.io.File
import java.lang.reflect.Proxy

/**
 * 消息发送入口。
 *
 * 宿主发送消息要过两层：
 * 1. IMsgService（qroute 提供的业务层，负责把内容转成 MsgElement）；
 * 2. IKernelMsgService（NT 内核，负责真正下发）。
 *
 * 这里优先走业务层的 createXxxElement，让宿主自己处理图片压缩、语音转码、视频上传；
 * 只有业务层不可用时才退回纯反射构造元素。
 */
object MsgSender {

    private const val TAG = "MsgSender"

    private const val IMSG_SERVICE = "com.tencent.qqnt.msg.api.IMsgService"
    private const val QROUTE = "com.tencent.mobileqq.qroute.QRoute"

    @Volatile
    private var msgService: Any? = null

    @Volatile
    private var msgUtil: Any? = null

    private fun msgServiceObj(): Any? {
        msgService?.let { return it }
        val v = runCatching {
            val app = HostEnv.appRuntime() ?: return@runCatching null
            val loader = app.javaClass.classLoader
            val qRoute = loader.loadClass(QROUTE)
            val api = qRoute.getDeclaredMethod("api", Class::class.java).apply { isAccessible = true }
            val iface = loader.loadClass(IMSG_SERVICE)
            api.invoke(null, iface)
        }.onFailure { FileLog.w("$TAG 取 IMsgService 失败: ${it.message}") }.getOrNull()
        if (v != null) msgService = v
        return v
    }

    /** 构造元素用的工具类（MsgUtilApiImpl），不是内核服务。 */
    private fun msgUtilObj(): Any? {
        msgUtil?.let { return it }
        val cls = ChatRef.classOrNull("com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl") ?: return null
        val inst = runCatching { cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }
            .getOrNull()
        if (inst != null) msgUtil = inst
        return inst
    }

    @Volatile
    private var kernelMsgService: Any? = null

    /** NT 内核消息服务（IKernelMsgService），负责取媒体文件路径等内核能力。 */
    private fun kernelMsgServiceObj(): Any? {
        kernelMsgService?.let { return it }
        val v = runCatching {
            val app = HostEnv.appRuntime() ?: return@runCatching null
            val kernel = runCatching {
                val m1 = ChatRef.methodByName(app.javaClass, "getRuntimeService", 1)
                ChatRef.invoke(app, m1, ChatRef.classOrNull("com.tencent.qqnt.kernel.api.IKernelService"))
            }.getOrNull()
            if (kernel == null) return@runCatching null
            ChatRef.invoke(kernel, ChatRef.methodByName(kernel.javaClass, "getMsgService", 0))
        }.onFailure { FileLog.w("$TAG 取内核消息服务失败: ${it.message}") }.getOrNull()
        if (v != null) kernelMsgService = v
        return v
    }

    /**
     * 构造泡泡视频元素（elementType=49 + FilterMsgElement，busiType=1/subBusiType=1/fileFormat=2），
     * 不发送，供发送前把普通视频元素原地替换成泡泡消息。
     * 宿主没有现成的 createBubbleVideoElement 可调，必须手工构造：算 MD5、取内核媒体路径、
     * 拷贝视频、生成缩略图、读时长，再拼 FilterMsgElement。
     */
    fun createBubbleVideoElement(path: String): Any? {
        return runCatching {
            val f = File(path)
            if (!f.exists()) {
                FileLog.e("$TAG 泡泡视频文件不存在: $path")
                return null
            }
            if (f.length() >= 200L * 1024 * 1024) {
                FileLog.e("$TAG 泡泡视频超 200MB，不转泡泡")
                return null
            }
            val svc = kernelMsgServiceObj() ?: run {
                FileLog.e("$TAG 泡泡视频: 内核消息服务为 null")
                return null
            }
            val cppProxy = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.QQNTWrapperUtil\$CppProxy")
                ?: run {
                    FileLog.e("$TAG 泡泡视频: QQNTWrapperUtil\$CppProxy 类不存在")
                    return null
                }

            val md5 = ChatRef.invoke(null, ChatRef.methodByName(cppProxy, "genFileMd5Hex", 1), path) as? String
            if (md5.isNullOrEmpty()) {
                FileLog.e("$TAG 泡泡视频: 计算 md5 失败")
                return null
            }
            val fileName = f.name

            val filePathInfoCls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.RichMediaFilePathInfo")
                ?: run {
                    FileLog.e("$TAG 泡泡视频: RichMediaFilePathInfo 类不存在")
                    return null
                }
            val pathInfoCtor = filePathInfoCls.constructors.firstOrNull { it.parameterTypes.size == 9 }
                ?: run {
                    FileLog.e("$TAG 泡泡视频: RichMediaFilePathInfo 9 参构造未找到")
                    return null
                }
            fun pathInfo(eleType: Int, streamType: Int): Any =
                pathInfoCtor.newInstance(5, streamType, md5, fileName, eleType, 0, null, "", true)

            val getPath = ChatRef.methodByName(svc.javaClass, "getRichMediaFilePathForMobileQQSend", 1)
            val destPath = ChatRef.invoke(svc, getPath, pathInfo(1, 2)) as? String
            if (destPath.isNullOrEmpty()) {
                FileLog.e("$TAG 泡泡视频: 获取视频目标路径失败")
                return null
            }
            val fileIsExist = ChatRef.methodByName(cppProxy, "fileIsExist", 1)
            val copyFile = ChatRef.methodByName(cppProxy, "copyFile", 2)
            if (!(ChatRef.invoke(null, fileIsExist, destPath) as? Boolean ?: false)) {
                if (!(ChatRef.invoke(null, copyFile, path, destPath) as? Boolean ?: false)) {
                    FileLog.e("$TAG 泡泡视频: 复制视频文件失败")
                    return null
                }
            }

            // 缩略图：目标路径已存在就直接用，否则从视频首帧生成
            var thumbPath: String? = null
            val thumbDestPath = ChatRef.invoke(svc, getPath, pathInfo(2, 1)) as? String
            if (!thumbDestPath.isNullOrEmpty()) {
                if (ChatRef.invoke(null, fileIsExist, thumbDestPath) as? Boolean ?: false) {
                    thumbPath = thumbDestPath
                } else {
                    thumbPath = runCatching {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(path)
                            val frame = retriever.getFrameAtTime()
                            if (frame != null) {
                                java.io.FileOutputStream(thumbDestPath).use { fos ->
                                    frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, fos)
                                }
                                thumbDestPath
                            } else null
                        } finally {
                            retriever.release()
                        }
                    }.onFailure { FileLog.w("$TAG 泡泡视频: 生成缩略图失败: ${it.message}") }.getOrNull()
                }
            }

            var thumbWidth = 720
            var thumbHeight = 720
            if (thumbPath != null) {
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(thumbPath, opts)
                if (opts.outWidth > 0) thumbWidth = opts.outWidth
                if (opts.outHeight > 0) thumbHeight = opts.outHeight
            }

            // 时长（秒），取不到用 91 兜底
            var fileTime = 91
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    if (duration != null) fileTime = (duration.toLong() / 1000).toInt()
                } finally {
                    retriever.release()
                }
            }

            val filterCls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.FilterMsgElement")
                ?: run {
                    FileLog.e("$TAG 泡泡视频: FilterMsgElement 类不存在")
                    return null
                }
            val filter = filterCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            ChatRef.setField(filter, "filePath", destPath)
            ChatRef.setField(filter, "videoMd5", md5)
            val fileSize = (ChatRef.invoke(null, ChatRef.methodByName(cppProxy, "getFileSize", 1), destPath) as? Long)
                ?: f.length()
            ChatRef.setField(filter, "fileSize", fileSize)
            ChatRef.setField(filter, "fileName", fileName)
            ChatRef.setField(filter, "fileFormat", 2)
            ChatRef.setField(filter, "busiType", 1)
            ChatRef.setField(filter, "subBusiType", 1)
            ChatRef.setField(filter, "fileTime", fileTime)
            ChatRef.setField(filter, "thumbWidth", thumbWidth)
            ChatRef.setField(filter, "thumbHeight", thumbHeight)
            ChatRef.setField(filter, "original", true)
            ChatRef.setField(filter, "videoFrom", 1)
            if (thumbPath != null) {
                val thumbMd5 = ChatRef.invoke(null, ChatRef.methodByName(cppProxy, "genFileMd5Hex", 1), thumbPath) as? String
                if (!thumbMd5.isNullOrEmpty()) ChatRef.setField(filter, "thumbMd5", thumbMd5)
                val thumbSize = ChatRef.invoke(null, ChatRef.methodByName(cppProxy, "getFileSize", 1), thumbPath) as? Long
                if (thumbSize != null) ChatRef.setField(filter, "thumbSize", thumbSize.toInt())
                ChatRef.setField(filter, "thumbPath", HashMap<Int, String>().apply { put(0, thumbPath) })
            }

            val elCls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.MsgElement")
                ?: run {
                    FileLog.e("$TAG 泡泡视频: MsgElement 类不存在")
                    return null
                }
            val el = elCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            ChatRef.setField(el, "elementType", 49)
            ChatRef.setField(el, "filterMsgElement", filter)
            FileLog.i("$TAG 泡泡视频元素构造成功 时长=${fileTime}s 缩略图=${thumbWidth}x$thumbHeight")
            el
        }.onFailure { FileLog.e("$TAG 构造泡泡视频元素失败", it) }.getOrNull()
    }

    /** 构造 Contact（chatType: 1 好友 / 2 群）。 */
    fun contact(chatType: Int, peerUid: String): Any? {
        val cls = ChatRef.classOrNull("com.tencent.qqnt.kernelpublic.nativeinterface.Contact")
            ?: return null
        val ctor = cls.constructors.firstOrNull {
            it.parameterTypes.size == 3 && it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null
        return runCatching {
            ctor.isAccessible = true
            ctor.newInstance(chatType, peerUid, "")
        }.getOrNull()
    }

    fun sendText(chatType: Int, peerUid: String, text: String): Boolean {
        val util = msgUtilObj() ?: return false
        val el = ChatRef.invoke(util, ChatRef.methodByName(util.javaClass, "createTextElement", 1), text)
            ?: return false
        return sendElements(chatType, peerUid, mutableListOf(el))
    }

    /** 发卡片：直接构造 elementType=10 的 ArkElement。 */
    fun sendArk(chatType: Int, peerUid: String, json: String): Boolean {
        val elCls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.MsgElement") ?: return false
        val arkCls = ChatRef.classOrNull("com.tencent.qqnt.kernel.nativeinterface.ArkElement") ?: return false
        val ark = runCatching {
            arkCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return false
        ChatRef.setField(ark, "bytesData", json)
        val el = runCatching {
            elCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return false
        ChatRef.setField(el, "elementType", 10)
        ChatRef.setField(el, "arkElement", ark)
        return sendElements(chatType, peerUid, mutableListOf(el))
    }

    fun sendPic(chatType: Int, peerUid: String, path: String): Boolean {
        val util = msgUtilObj() ?: return false
        if (!File(path).exists()) {
            FileLog.w("$TAG 图片不存在: $path")
            return false
        }
        val el = ChatRef.invoke(util, ChatRef.methodByName(util.javaClass, "createPicElement", 3), path, true, 0)
            ?: return false
        return sendElements(chatType, peerUid, mutableListOf(el))
    }

    /** @param videoAsBubble true 时按「泡泡消息」发（elementType=49 的 FilterMsgElement 走视频上传） */
    fun sendVideo(chatType: Int, peerUid: String, path: String, videoAsBubble: Boolean = false): Boolean {
        val util = msgUtilObj() ?: return false
        val f = File(path)
        if (!f.exists()) {
            FileLog.w("$TAG 视频不存在: $path")
            return false
        }
        // 超过 200MB 走文件发送，视频通道会直接失败
        if (f.length() >= 200L * 1024 * 1024) {
            FileLog.d("$TAG 视频 ${f.length() / 1024 / 1024}MB 超限，改用文件发送")
            val el = ChatRef.invoke(util, ChatRef.methodByName(util.javaClass, "createFileElement", 1), path)
                ?: return false
            return sendElements(chatType, peerUid, mutableListOf(el))
        }
        val create = if (videoAsBubble) "createBubbleVideoElement" else "createVideoElement"
        val el = ChatRef.invoke(util, ChatRef.methodByName(util.javaClass, create, 1), path)
            ?: ChatRef.invoke(util, ChatRef.methodByName(util.javaClass, "createVideoElement", 1), path)
            ?: return false
        return sendElements(chatType, peerUid, mutableListOf(el))
    }

    /**
     * 转发下发：文件(3)/小灰条(7)/回复(19)/卡片(10) 等消息复读必须走 forwardMsg，
     * 走 sendMsg 会丢失元素或直接失败。
     */
    fun forwardElements(chatType: Int, peerUid: String, elements: MutableList<Any?>): Boolean {
        if (elements.isEmpty()) return false
        val svc = msgServiceObj()
        if (svc == null) {
            FileLog.e("$TAG 未取到 IMsgService，无法转发")
            return false
        }
        val contact = contact(chatType, peerUid) ?: run {
            FileLog.e("$TAG 构造 Contact 失败")
            return false
        }
        return runCatching {
            val gen = ChatRef.methodByName(svc.javaClass, "generateMsgUniqueId", 1)
            val msgId = (ChatRef.invoke(svc, gen, chatType) as? Long) ?: System.currentTimeMillis()

            val forwardIds = ArrayList<Long>().apply { add(msgId) }
            val contacts = ArrayList<Any?>().apply { add(contact) }

            // callback 必须是宿主接口类型，用动态代理生成，避免模块 stub 类进 JNI 崩溃
            val hostCL = svc.javaClass.classLoader
            val callback = runCatching {
                val cbClass = Class.forName(
                    "com.tencent.qqnt.kernel.nativeinterface.IForwardOperateCallback", false, hostCL
                )
                Proxy.newProxyInstance(hostCL, arrayOf(cbClass)) { _, m, _ ->
                    if (m.name == "onResult") FileLog.d("$TAG 转发回调 onResult")
                    null
                }
            }.getOrNull()

            val m5 = ChatRef.methodByName(svc.javaClass, "forwardMsg", 5)
            if (m5 != null) {
                ChatRef.invoke(svc, m5, forwardIds, contact, contacts, elements, callback)
                true
            } else {
                FileLog.e("$TAG 未找到 forwardMsg 方法")
                false
            }
        }.onFailure { FileLog.e("$TAG 转发异常", it) }.getOrDefault(false)
    }

    /** 真正下发到内核。 */
    fun sendElements(chatType: Int, peerUid: String, elements: MutableList<Any?>): Boolean {
        if (elements.isEmpty()) return false
        val svc = msgServiceObj()
        if (svc == null) {
            FileLog.e("$TAG 未取到 IMsgService，无法发送")
            return false
        }
        val contact = contact(chatType, peerUid)
        if (contact == null) {
            FileLog.e("$TAG 构造 Contact 失败")
            return false
        }
        return runCatching {
            val gen = ChatRef.methodByName(svc.javaClass, "generateMsgUniqueId", 1)
            val msgId = (ChatRef.invoke(svc, gen, chatType) as? Long) ?: System.currentTimeMillis()
            val attrMap = HashMap<Int, Any>()
            val m5 = ChatRef.methodByName(svc.javaClass, "sendMsg", 5)
            if (m5 != null) {
                ChatRef.invoke(svc, m5, msgId, contact, elements, attrMap, null)
                true
            } else {
                val m3 = ChatRef.methodByName(svc.javaClass, "sendMsg", 3)
                if (m3 != null) {
                    ChatRef.invoke(svc, m3, contact, elements, null)
                    true
                } else {
                    FileLog.e("$TAG 未找到 sendMsg 方法")
                    false
                }
            }
        }.onFailure { FileLog.e("$TAG 发送异常", it) }.getOrDefault(false)
    }
}
