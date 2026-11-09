package com.qosed.qodeep.chat.item

import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.levelboost.FileLog
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * 解除输入框字数上限：
 * 输入框的 maxLength 来自 AIOEditText 里唯一的 static final int 字段（由资源读取赋值，非编译期常量）。
 *
 * 只在「唯一」时改值：出现第二个候选说明该版本结构变了，此时放弃而不是猜，
 * 避免误改其它常量导致输入框行为异常。
 */
object ForceInputNoLimit {

    private const val TAG = "ForceInputNoLimit"

    private const val MAX_LENGTH = 1_000_000

    private val TARGETS = listOf(
        "com.tencent.mobileqq.aio.input.edit.AIOEditText",
        "com.tencent.mobileqq.aio.input.AIOEditText"
    )

    fun install() {
        if (!ChatConfig.getBoolean(ChatKeys.FORCE_INPUT_NO_LIMIT, false)) return
        for (name in TARGETS) {
            val cls = ChatRef.classOrNull(name) ?: continue
            if (applyLimit(cls)) return
        }
        FileLog.w("$TAG 未找到可修改的输入框上限字段")
    }

    private fun applyLimit(cls: Class<*>): Boolean {
        var target: Field? = null
        for (f in cls.declaredFields) {
            if (f.type != Int::class.javaPrimitiveType || f.isSynthetic) continue
            val mod = f.modifiers
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod)) continue
            if (target != null) {
                FileLog.w("$TAG ${cls.name} 有多个 static final int，放弃修改")
                return false
            }
            target = f
        }
        val f = target ?: return false
        return runCatching {
            f.isAccessible = true
            // Android 高版本不再允许改 final 修饰位，失败无妨：显式赋值静态字段本身即可覆盖
            runCatching {
                val accessFlags = Field::class.java.getDeclaredField("accessFlags")
                accessFlags.isAccessible = true
                accessFlags.setInt(f, accessFlags.getInt(f) and Modifier.FINAL.inv())
            }
            f.setInt(null, MAX_LENGTH)
            FileLog.i("$TAG ${cls.simpleName}.${f.name} 已改为 $MAX_LENGTH")
            true
        }.onFailure {
            FileLog.e("$TAG 修改上限字段失败", it)
        }.getOrDefault(false)
    }
}
