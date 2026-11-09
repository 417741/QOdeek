package com.qosed.qodeep.chat.item

import android.media.MediaScannerConnection
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.api.MsgData
import com.qosed.qodeep.chat.api.OnMenuBuild
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.chat.qq.HttpUtil
import com.qosed.qodeep.levelboost.FileLog
import java.io.File
import kotlin.concurrent.thread

/**
 * 表情/图片/视频/语音下载：
 * 长按含媒体的消息增加「保存」项，把图片下到本地、把已在缓存里的视频/语音复制出来。
 *
 * 保存目录放在宿主自己的 Android/data/{pkg}/Android/media 下，
 * 那里宿主有完整写权限，不受分区存储限制，也无需申请任何存储权限。
 */
object DownloadEmotion {

    private const val TAG = "DownloadEmotion"

    /** 支持保存的消息类型：图片 2、语音 7、视频 32、富媒体 6 */
    private val TYPES = listOf("2", "6", "7", "32")

    fun install() {
        OnMenuBuild.addMenu(
            title = "保存",
            enabled = { ChatConfig.getBoolean(ChatKeys.DOWNLOAD_EMOTION, false) },
            msgTypes = TYPES
        ) { msg ->
            if (msg.picList.isEmpty() && msg.videoList.isEmpty() && msg.pttList.isEmpty()) {
                toast("这条消息没有可保存的媒体")
                return@addMenu
            }
            download(msg)
        }
        FileLog.i("$TAG 已注册长按菜单")
    }

    private fun saveRoot(): File {
        val pkg = HostEnv.appContext()?.packageName ?: "com.tencent.mobileqq"
        return File("/sdcard/Android/media/$pkg/QOdeep")
    }

    private fun download(msg: MsgData) {
        thread(name = "QOdeep-Download") {
            runCatching {
                val root = saveRoot()
                val picDir = File(root, "Pictures").apply { mkdirs() }
                val videoDir = File(root, "Videos").apply { mkdirs() }
                val pttDir = File(root, "Ptts").apply { mkdirs() }

                val saved = mutableListOf<File>()
                val stamp = System.currentTimeMillis()

                msg.picList.forEachIndexed { i, url ->
                    if (url.isEmpty()) return@forEachIndexed
                    val dest = File(picDir, "pic_${stamp}_$i${HttpUtil.extOfImage(url)}")
                    if (HttpUtil.downloadSync(url, dest.absolutePath)) saved.add(dest)
                }

                msg.videoList.forEachIndexed { i, path ->
                    val src = File(path)
                    if (!src.exists()) {
                        toast("视频还没缓存，先点开播放一次再保存")
                        return@forEachIndexed
                    }
                    val dest = File(videoDir, "video_${stamp}_$i${HttpUtil.extOfVideo(path)}")
                    if (HttpUtil.copyFile(src, dest)) saved.add(dest)
                }

                msg.pttList.forEachIndexed { i, path ->
                    val src = File(path)
                    if (!src.exists()) {
                        toast("语音还没缓存，先点开播放一次再保存")
                        return@forEachIndexed
                    }
                    val dest = File(pttDir, "ptt_${stamp}_$i${HttpUtil.extOfPtt(path)}")
                    if (HttpUtil.copyFile(src, dest)) saved.add(dest)
                }

                if (saved.isEmpty()) {
                    toast("保存失败，详见日志")
                    return@runCatching
                }
                runCatching {
                    val ctx = HostEnv.appContext() ?: return@runCatching
                    MediaScannerConnection.scanFile(
                        ctx, saved.map { it.absolutePath }.toTypedArray(), null, null
                    )
                }
                toast("已保存 ${saved.size} 个文件到 ${root.absolutePath}")
                FileLog.i("$TAG 保存完成 ${saved.map { it.name }}")
            }.onFailure {
                FileLog.e("$TAG 保存异常", it)
                toast("保存失败: ${it.message}")
            }
        }
    }

    private fun toast(s: String) {
        runCatching {
            val ctx = HostEnv.activity() ?: HostEnv.appContext() ?: return
            android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
