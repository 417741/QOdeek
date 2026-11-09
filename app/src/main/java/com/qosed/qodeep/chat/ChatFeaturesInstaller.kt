package com.qosed.qodeep.chat

import com.qosed.qodeep.chat.item.AntiPokeDelay
import com.qosed.qodeep.chat.item.CopyArkMessage
import com.qosed.qodeep.chat.item.DownloadEmotion
import com.qosed.qodeep.chat.item.EmotionAiTag
import com.qosed.qodeep.chat.item.FlashPicBypass
import com.qosed.qodeep.chat.item.ForceInputNoLimit
import com.qosed.qodeep.chat.item.ForceSpeaker
import com.qosed.qodeep.chat.item.ImageRatioOverride
import com.qosed.qodeep.chat.item.ImageSummary
import com.qosed.qodeep.chat.item.LongClickSendCard
import com.qosed.qodeep.chat.item.NativeEmotionDownload
import com.qosed.qodeep.chat.item.PreventRecall
import com.qosed.qodeep.chat.item.RepeatMsg
import com.qosed.qodeep.chat.item.VideoToBubble
import com.qosed.qodeep.chat.item.VoiceSpeed
import com.qosed.qodeep.levelboost.FileLog

/**
 * 聊天功能的统一安装入口。
 *
 * 每项独立 try-catch：宿主里某个类找不到、签名变了，只让那一项失效并记日志，
 * 不影响其余功能。整体在后台线程执行，不阻塞宿主启动。
 */
object ChatFeaturesInstaller {

    private const val TAG = "ChatFeatures"

    @Volatile
    private var installed = false

    fun install(hostPackage: String) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        Thread({
            step("闪照破解") { FlashPicBypass.install() }
            step("防撤回") { PreventRecall.install() }
            step("复制卡片消息") { CopyArkMessage.install() }
            step("长按发送发卡片") { LongClickSendCard.install() }
            step("消息复读") { RepeatMsg.install() }
            step("取消拍一拍时间限制") { AntiPokeDelay.install() }

            step("表情/泡泡/视频/语音下载") { DownloadEmotion.install() }
            step("原生表情包下载") { NativeEmotionDownload.install() }
            step("视频转泡泡消息") { VideoToBubble.install() }
            step("语音消息倍速播放") { VoiceSpeed.install() }
            step("语音强制免提") { ForceSpeaker.install() }

            step("AI表情标签") { EmotionAiTag.install() }
            step("篡改发送图片比例") { ImageRatioOverride.install() }
            step("图片外显自定义") { ImageSummary.install() }
            step("解除输入框字数上限") { ForceInputNoLimit.install() }

            FileLog.i("$TAG 全部聊天功能安装结束")
        }, "QOdeep-ChatInstall").start()
    }

    private inline fun step(name: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { FileLog.e("$TAG [$name] 安装失败", it) }
    }
}
