package com.qosed.qodeep.chat.api

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.qosed.qodeep.DexKitCache
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.levelboost.FileLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 聊天消息长按菜单的扩展点。
 *
 * 三层协作：
 * 1. setMenu 阶段把自定义项插进菜单列表（宿主按 item 自行渲染）；
 * 2. 宿主渲染某一项时替换成自己的布局，保证图标与文字在各版本一致；
 * 3. 点击回调统一转成 MsgData 交给业务。
 *
 * 菜单项靠内部存的字符串 key 识别（宿主在 CopyMenuItem 里用一个 String 字段存 key），
 * 因此不依赖菜单项的类名、方法名，换版本也不易失效。
 */
object OnMenuBuild {

    private const val TAG = "OnMenuBuild"

    /** 自定义菜单项的 key 前缀，用于区分宿主自带项。 */
    private const val PREFIX = "[QOdeep]"

    /** DexKit 任务名：按字符串特征定位长按菜单项类。 */
    const val DEX_KEY = "chat_menu_item"

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<MenuListener>()

    @Volatile
    private var installed = false

    interface MenuListener {
        /** 菜单项唯一 key（形如 [QOdeep]复制卡片）。 */
        val menuKey: String

        /** 当前是否显示这一项。 */
        fun isEnabled(): Boolean = true

        /** 对哪些 msgType 生效，空表示全部。 */
        fun targetMsgTypes(): List<String> = emptyList()

        fun onClick(msg: MsgData)
    }

    // ---------------- 对外注册 ----------------

    fun addMenu(
        title: String,
        enabled: () -> Boolean,
        msgTypes: List<String> = emptyList(),
        onClick: (MsgData) -> Unit
    ): String {
        val key = "$PREFIX$title"
        listeners.removeIf { it.menuKey == key }
        listeners.add(object : MenuListener {
            override val menuKey = key
            override fun isEnabled(): Boolean = runCatching { enabled() }.getOrDefault(false)
            override fun targetMsgTypes(): List<String> = msgTypes
            override fun onClick(msg: MsgData) = onClick(msg)
        })
        return key
    }

    fun removeMenu(title: String) {
        listeners.removeIf { it.menuKey == "$PREFIX$title" }
    }

    // ---------------- 安装 ----------------

    fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
            val cls = resolveMenuItemClass()
            if (cls == null) {
                FileLog.w("$TAG 未能定位菜单项类，长按菜单相关功能不可用")
                return
            }
            hookSetMenu(cls)
            hookItemRender(cls)
            FileLog.i("$TAG 长按菜单已接入: ${cls.name}")
        }
    }

    /** 优先用 DexKit 缓存结果，未命中时按常见命名直试。 */
    private fun resolveMenuItemClass(): Class<*>? {
        DexKitCache.getDescriptor("$CHAT_TAG->$DEX_KEY")
            ?.let { desc -> ChatRef.classOrNull(desc)?.let { return it } }
        listOf(
            "com.tencent.qqnt.aio.menu.item.CopyMenuItem",
            "com.tencent.qqnt.aio.menu.ui.CopyMenuItem",
            "com.tencent.qqnt.aio.menu.CopyMenuItem"
        ).forEach { name -> ChatRef.classOrNull(name)?.let { return it } }
        return null
    }

    private const val CHAT_TAG = "chat"

    /** 阶段一：插入自定义项。 */
    private fun hookSetMenu(itemClass: Class<*>) {
        val container = ChatRef.classOrNull("com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout")
            ?: return
        val setMenu = ChatRef.methodByName(container, "setMenu", 1) ?: run {
            FileLog.w("$TAG 未找到 setMenu")
            return
        }
        ChatRef.hookBefore(setMenu) { param ->
            if (listeners.isEmpty()) return@hookBefore
            runCatching { insertItems(param, itemClass) }
                .onFailure { FileLog.e("$TAG 插入菜单项异常", it) }
        }
    }

    private fun insertItems(param: XC_MethodHook.MethodHookParam, itemClass: Class<*>) {
        val menu = param.args.getOrNull(0) ?: return
        val items = findItemList(menu) ?: return
        if (items.isEmpty()) return
        val anchor = items[0] ?: return
        val msgRecord = findMsgRecord(anchor) ?: return
        val msgType = ChatRef.getInt(msgRecord, "msgType").toString()

        for (l in listeners) {
            if (l.menuKey.isEmpty() || !l.isEnabled()) continue
            if (items.any { itemKey(it) == l.menuKey }) continue
            val types = l.targetMsgTypes()
            if (types.isNotEmpty() && msgType !in types) continue
            runCatching { items.add(0, newItem(itemClass, anchor, l.menuKey)) }
                .onFailure { FileLog.e("$TAG 插入菜单项失败", it) }
        }
    }

    /** 构造菜单项实例：(Context, AIOMsgItem) 构造器优先。 */
    private fun newItem(itemClass: Class<*>, anchor: Any, key: String): Any {
        val ctx = HostEnv.activity() ?: HostEnv.appContext()
        val msgItem = findMsgItem(anchor)
        val twoArg = itemClass.constructors.firstOrNull {
            it.parameterTypes.size == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        val inst = if (twoArg != null) {
            twoArg.isAccessible = true
            twoArg.newInstance(ctx, msgItem)
        } else {
            val ctor = itemClass.constructors.first().apply { isAccessible = true }
            ctor.newInstance(ctx)
        }
        setItemKey(inst, key)
        return inst
    }

    /** 阶段二：渲染时段换成自己的布局。 */
    private fun hookItemRender(itemClass: Class<*>) {
        val container = ChatRef.classOrNull("com.tencent.qqnt.aio.menu.ui.QQCustomMenuExpandableLayout")
            ?: return
        val targets = container.declaredMethods.filter { m ->
            m.returnType == View::class.java &&
                m.parameterTypes.any { it.isAssignableFrom(itemClass) || itemClass.isAssignableFrom(it) }
        }.onEach { it.isAccessible = true }
        if (targets.isEmpty()) {
            FileLog.w("$TAG 未找到菜单项渲染方法")
            return
        }
        for (m in targets) {
            ChatRef.hookReplace(m) { param ->
                val item = param.args.firstOrNull { itemKey(it) != null }
                val key = itemKey(item)
                if (key == null || !key.startsWith(PREFIX)) {
                    return@hookReplace invokeOriginal(param)
                }
                val record = findMsgRecord(item) ?: return@hookReplace invokeOriginal(param)
                buildItemView(key, MsgData(record), param.thisObject)
            }
        }
    }

    private fun invokeOriginal(param: XC_MethodHook.MethodHookParam): Any? =
        runCatching { XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args) }
            .getOrNull()

    @SuppressLint("SetTextI18n")
    private fun buildItemView(key: String, msg: MsgData, container: Any?): View? {
        val ctx = HostEnv.activity() ?: HostEnv.appContext() ?: return null
        val density = ctx.resources.displayMetrics.density
        val itemSize = (72 * density + 0.5f).toInt()
        val iconSize = (24 * density + 0.5f).toInt()
        val pad = (6 * density + 0.5f).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(itemSize, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(ImageView(ctx).apply {
            val res = iconResOf(key.removePrefix(PREFIX))
            if (res != 0) setImageResource(res) else setImageResource(ctx.applicationInfo.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        })
        root.addView(TextView(ctx).apply {
            text = key.removePrefix(PREFIX)
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density + 0.5f).toInt() }
        })
        root.setOnClickListener {
            runCatching { listeners.firstOrNull { it.menuKey == key }?.onClick(msg) }
                .onFailure { FileLog.e("$TAG 菜单点击异常", it) }
            runCatching {
                if (container != null) {
                    ChatRef.invoke(container, ChatRef.methodByName(container.javaClass, "dismiss", 0))
                }
            }
        }
        return root
    }

    /**
     * 菜单项图标的资源映射（按菜单标题匹配）。
     * 找不到映射时返回 0，调用方回落到宿主应用图标。
     */
    private fun iconResOf(title: String): Int = 0

    // ---------------- 反射辅助 ----------------

    private fun findItemList(container: Any): MutableList<Any?>? {
        var c: Class<*>? = container.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (!List::class.java.isAssignableFrom(f.type)) continue
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val v = runCatching { f.get(container) }.getOrNull() as? MutableList<Any?>
                if (v != null && v.isNotEmpty()) return v
            }
            c = c.superclass
        }
        return null
    }

    private fun findMsgRecord(item: Any?): Any? =
        findMsgItem(item)?.let { ChatRef.getField(it, "msgRecord") }

    private fun findMsgItem(item: Any?): Any? {
        if (item == null) return null
        var c: Class<*>? = item.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type.simpleName != "AIOMsgItem") continue
                f.isAccessible = true
                runCatching { f.get(item) }.getOrNull()?.let { return it }
            }
            c = c.superclass
        }
        return null
    }

    private fun itemKey(item: Any?): String? {
        if (item == null) return null
        var c: Class<*>? = item.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java) continue
                f.isAccessible = true
                val v = runCatching { f.get(item) as? String }.getOrNull()
                if (v != null && v.startsWith(PREFIX)) return v
            }
            c = c.superclass
        }
        return null
    }

    private fun setItemKey(item: Any, key: String): Boolean {
        var c: Class<*>? = item.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.type != String::class.java || java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val cur = runCatching { f.get(item) as? String }.getOrNull()
                if (cur.isNullOrEmpty() || cur.startsWith(PREFIX)) {
                    if (runCatching { f.set(item, key) }.isSuccess) return true
                }
            }
            c = c.superclass
        }
        return false
    }
}
