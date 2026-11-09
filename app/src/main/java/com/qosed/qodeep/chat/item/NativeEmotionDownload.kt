package com.qosed.qodeep.chat.item

import com.qosed.qodeep.DexKitCache
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog
import java.lang.reflect.Method

/**
 * 原生表情包下载：
 * 表情包图片（PicElement）进入下载链路前会经过 com.tencent.qqnt.msg 包下一个判定方法
 * （(PicElement) -> boolean，方法体调用 IZplanOffLineApi.isZPlanMsgOffLine），
 * 返回 false 时原生下载通道被拦，hook 后强制返回 true 即放行。
 *
 * 类名/方法名都被混淆，不硬编码：靠 DexKit「包名 + 参数类型 + 返回类型 + 方法体调用」
 * 四重条件定位，结果缓存在 DexKitCache（key 见 [DEXKIT_KEY]）。
 */
object NativeEmotionDownload {

    private const val TAG = "NativeEmotionDownload"
    private const val DEXKIT_KEY = "SettingInject->pic_download_method"

    fun install() {
        val m = resolveMethod()
        if (m != null) {
            hookMethod(m)
            return
        }
        // 首次启动缓存未就绪：DexKit 扫描在另一后台线程进行，轮询等它把结果写进缓存
        Thread({
            val deadline = System.currentTimeMillis() + 15_000
            var method: Method? = null
            while (System.currentTimeMillis() < deadline) {
                method = resolveMethod()
                if (method != null) break
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                }
            }
            if (method != null) hookMethod(method)
            else FileLog.w("$TAG DexKit 未定位到下载判定方法，功能不启用")
        }, "QOdeep-NativeEmotion").start()
    }

    private fun resolveMethod(): Method? =
        runCatching { DexKitCache.getMethod(DEXKIT_KEY) }.getOrNull()

    private fun hookMethod(m: Method) {
        ChatRef.hookAfter(m) { param ->
            if (!ChatConfig.getBoolean(ChatKeys.NATIVE_EMOTION_DOWNLOAD, false)) return@hookAfter
            // 只把判定结果拉成 true，保留原方法自身的副作用
            if (param.result != true) param.result = true
        }
        FileLog.i("$TAG 已 hook ${m.declaringClass.name}.${m.name}")
    }
}
