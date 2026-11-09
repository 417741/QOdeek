package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog

/**
 * 取消拍一拍时间限制：
 * 拍一拍处理器的冷却判定是它唯一返回 boolean 的方法，拦截它直接返回 true 即视为「可拍」。
 *
 * 返回 boolean 的唯一性在方法上成立（该处理器其余方法都是 void 或返回其他类型），
 * 若某版本出现多个候选则全部 hook，语义仍然一致（都放行）。
 */
object AntiPokeDelay {

    private const val TAG = "AntiPokeDelay"

    private val HANDLERS = listOf(
        "com.tencent.mobileqq.paiyipai.PaiYiPaiHandler",
        "com.tencent.mobileqq.paiyipai.PaiYiPaiHandler\$Companion"
    )

    fun install() {
        var hooked = 0
        for (name in HANDLERS) {
            val cls = ChatRef.classOrNull(name) ?: continue
            val targets = cls.declaredMethods.filter {
                it.returnType == Boolean::class.javaPrimitiveType
            }
            for (m in targets) {
                m.isAccessible = true
                if (ChatRef.hookBefore(m) { param ->
                        if (!ChatConfig.getBoolean(ChatKeys.ANTI_POKE_DELAY, false)) return@hookBefore
                        param.result = true
                    }
                ) hooked++
            }
        }
        if (hooked == 0) FileLog.w("$TAG 未找到冷却判定方法，功能不可用")
        else FileLog.i("$TAG 已 hook $hooked 个判定方法")
    }
}
