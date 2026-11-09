package com.qosed.qodeep.chat.item

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.qosed.qodeep.R
import com.qosed.qodeep.chat.ChatConfig
import com.qosed.qodeep.chat.ChatKeys
import com.qosed.qodeep.chat.ChatRef
import com.qosed.qodeep.chat.api.MsgData
import com.qosed.qodeep.chat.qq.HostEnv
import com.qosed.qodeep.chat.qq.MsgSender
import com.qosed.qodeep.levelboost.FileLog
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息复读。
 *
 * 做法：hook `AIOMsgFollowComponent` 里更新气泡跟随栏的方法，反射取该组件内的
 * `kotlin.Lazy` 字段拿到复读 ImageView（纸飞机图标，fd.png），设为可见并挂
 * 点击->直接复读、长按->动作菜单。
 *
 * 复读不是简单发文本：直接把原消息的 `MsgElement` 列表重新下发，这样图片/视频/语音/卡片/文件
 * 都能原样复制；文本消息需把 atType 归零，否则会把 @ 指向错误的人。
 *
 * 动作菜单（长按）按消息内容动态生成：
 * 图片->复制链接，视频/语音/文件->复制在线地址，另始终提供 复制文本 / 原始消息 / 重构消息。
 */
object RepeatMsg {

    private const val TAG = "RepeatMsg"
    private const val SP_KEY = ChatKeys.REPEAT_MSG
    private const val ICON_SP_KEY = "repeat_msg_icon"
    private const val ICON_TAG_KEY = 0x7F293285

    /** 自定义图标路径/修改时间的校验间隔：高频回调里不每次都碰文件系统 */
    private const val ICON_CHECK_INTERVAL_MS = 1000L

    @Volatile
    private var cachedTargetMethod: Method? = null

    @Volatile
    private var hasSearched = false

    /** 按 Class 缓存 kotlin.Lazy 字段，避免每次气泡更新都扫全部字段 */
    private val lazyFieldCache = ConcurrentHashMap<Class<*>, Any>()
    private val FIELD_NOT_FOUND = Any()

    // 自定义图标按 路径+修改时间 缓存：换图时文件被原地覆盖（路径不变），靠 lastModified 失效
    @Volatile
    private var cachedIconKey: String? = null

    @Volatile
    private var cachedIconBitmap: Bitmap? = null

    @Volatile
    private var lastIconCheckMs = 0L

    /** 默认纸飞机图标（fd.png）的位图与是否已尝试解码。 */
    @Volatile
    private var defaultIconBitmap: Bitmap? = null

    @Volatile
    private var defaultIconLoaded = false

    /** 诊断日志节流与 hook 触发标记。 */
    @Volatile
    private var lastDiagMs = 0L

    @Volatile
    private var hookFiredLogged = false

    private var lastClickTime = 0L

    fun install() {
        val hostCL = ChatRef.loader()
        val targetMethod = getTargetMethod(hostCL)
        if (targetMethod == null) {
            FileLog.e("$TAG 未找到 AIOMsgFollowComponent 目标方法")
            return
        }
        val ok = ChatRef.hookAfter(targetMethod) { param -> onHandleIntent(param) }
        FileLog.i("$TAG 已 hook ${targetMethod.name} -> $ok")
    }

    fun isEnabled(): Boolean = ChatConfig.getBoolean(SP_KEY, false)

    @Synchronized
    private fun getTargetMethod(hostCL: ClassLoader): Method? {
        cachedTargetMethod?.let { return it }
        if (hasSearched) return null
        hasSearched = true

        return try {
            val componentClass = Class.forName(
                "com.tencent.mobileqq.aio.msglist.holder.component.msgfollow.AIOMsgFollowComponent",
                false,
                hostCL
            )
            // 原版签名：(int, AIOMsgItem, List)，返回 void
            val method = componentClass.declaredMethods.firstOrNull { m ->
                m.parameterCount == 3 &&
                    m.returnType == Void.TYPE &&
                    m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    List::class.java.isAssignableFrom(m.parameterTypes[2])
            }?.also { it.isAccessible = true }

            cachedTargetMethod = method
            if (method == null) FileLog.e("$TAG repeatMsgComponent 方法未匹配")
            method
        } catch (e: Exception) {
            FileLog.e("$TAG 查找目标方法失败: ${e.message}")
            null
        }
    }

    /** 按 Class 取缓存中的 kotlin.Lazy 字段，找不到返回 null（负结果同样缓存） */
    private fun getLazyField(clazz: Class<*>): Field? {
        val cached = lazyFieldCache[clazz]
        if (cached != null) return if (cached === FIELD_NOT_FOUND) null else cached as Field

        val found = clazz.declaredFields.firstOrNull { it.type.name == "kotlin.Lazy" }
            ?.apply { isAccessible = true }
        lazyFieldCache[clazz] = found ?: FIELD_NOT_FOUND
        return found
    }

    /**
     * 读取用户选择的自定义图标（路径存配置）。
     * 未设置、文件不存在或解码失败均返回 null，回退到默认图标。
     * 大图按 256px 采样解码，避免整张照片解进内存（按钮只有几十 dp）。
     */
    private fun loadCustomIcon(): Bitmap? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastIconCheckMs < ICON_CHECK_INTERVAL_MS) return cachedIconBitmap
        lastIconCheckMs = now

        val path = ChatConfig.getString(ICON_SP_KEY, "")
        if (path.isEmpty()) {
            cachedIconKey = null
            cachedIconBitmap = null
            return null
        }

        val file = java.io.File(path)
        if (!file.exists()) return null
        val key = "$path:${file.lastModified()}"
        if (key === cachedIconKey) return cachedIconBitmap

        val bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) {
            null
        }
        cachedIconKey = key
        cachedIconBitmap = bitmap
        return bitmap
    }

    /**
     * 默认纸飞机图标（fd.png）。默认图标不能走 setImageResource：那是模块资源 ID，
     * 在宿主进程里会被宿主的 Resources 解析成空（或 NotFound），气泡上就不显示；
     * 也不能按文件名去 APK 里找——打包时资源文件被混淆成了短哈希名。也不能用
     * createPackageContext：Android 11+ 包可见性限制下，QQ 进程的 PackageManager
     * 查不到模块包，会抛 NameNotFoundException。正确做法是拿模块 APK 路径直接构造
     * 模块资源表，再按资源 ID 解码，和自定义图标一样走 setImageBitmap。
     */
    private fun loadDefaultIcon(): Bitmap? {
        if (defaultIconLoaded) return defaultIconBitmap
        val app = HostEnv.appContext() ?: run {
            FileLog.w("$TAG 默认图标失败: appContext 为空")
            return null
        }
        defaultIconLoaded = true
        defaultIconBitmap = try {
            val apk = com.qosed.qodeep.Env.moduleApkPath
            if (apk.isEmpty()) {
                FileLog.w("$TAG 默认图标失败: 模块 APK 路径为空")
                return null
            }
            val res = buildModuleResources(apk, app.resources) ?: return null
            val bmp = BitmapFactory.decodeResource(res, R.drawable.fd)
            if (bmp == null) FileLog.w("$TAG 默认图标失败: fd 解码返回 null") else FileLog.i("$TAG 默认图标解码成功")
            bmp
        } catch (e: Throwable) {
            FileLog.w("$TAG 默认图标失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        return defaultIconBitmap
    }

    /**
     * 从模块 APK 路径构造只含模块资源的 Resources。
     * - Android 11+（API 30）：用公开的 ResourcesProvider/ResourcesLoader 加载 APK，
     *   不反射 hidden API addAssetPath（MIUI/Android 14 上会静默失败）。
     * - Android 10 及以下：回退反射 addAssetPath。
     */
    private fun buildModuleResources(apkPath: String, base: Resources): Resources? = try {
        if (Build.VERSION.SDK_INT >= 30) {
            val pfd = android.os.ParcelFileDescriptor.open(File(apkPath), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val provider = android.content.res.loader.ResourcesProvider.loadFromApk(pfd)
            val loader = android.content.res.loader.ResourcesLoader().apply { addProvider(provider) }
            val am = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            Resources(am, base.displayMetrics, base.configuration).apply { addLoaders(loader) }
        } else {
            val am = AssetManager::class.java.newInstance()
            AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }.invoke(am, apkPath)
            Resources(am, base.displayMetrics, base.configuration)
        }
    } catch (e: Throwable) {
        FileLog.w("$TAG 构造模块资源失败: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 诊断日志：3 秒节流，避免每条消息都刷屏。 */
    private fun diag(msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDiagMs < 3000) return
        lastDiagMs = now
        FileLog.w("$TAG [诊断] $msg")
    }

    private fun onHandleIntent(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam) {
        if (!hookFiredLogged) {
            hookFiredLogged = true
            FileLog.i("$TAG onHandleIntent 首次触发")
        }
        try {
            if (!isEnabled()) {
                diag("开关关闭，跳过")
                return
            }

            val lazyField = getLazyField(param.thisObject.javaClass) ?: run {
                diag("lazy 字段未找到 class=${param.thisObject.javaClass.name}")
                return
            }
            val lazy = lazyField.get(param.thisObject) ?: run {
                diag("lazy 字段值为 null")
                return
            }

            val repeatView = ChatRef.invoke(lazy, ChatRef.methodByName(lazy.javaClass, "getValue", 0)) as? ImageView
                ?: run {
                    diag("getValue 非 ImageView")
                    return
                }

            // 合并转发内不显示复读
            if (repeatView.context.javaClass.name.contains("MultiForwardActivity")) return

            val aioMsgItem = param.args.getOrNull(1) ?: run {
                diag("args[1] 为 null")
                return
            }
            val msgRecord = ChatRef.invoke(
                aioMsgItem, ChatRef.methodByName(aioMsgItem.javaClass, "getMsgRecord", 0)
            ) ?: run {
                diag("getMsgRecord 为 null")
                return
            }

            repeatView.apply {
                visibility = View.VISIBLE
                // 用户在首页选过图则用自定义图标，否则用默认图标；
                // 图标没变就跳过重设，避免每次 UI 更新都触发资源解析/重绘
                val customIcon = loadCustomIcon()
                val applied = getTag(ICON_TAG_KEY)
                if (applied == null || applied !== drawable) {
                    if (customIcon != null) setImageBitmap(customIcon) else loadDefaultIcon()?.let { setImageBitmap(it) }
                    setTag(ICON_TAG_KEY, drawable)
                }
                setOnClickListener {
                    if (isDoubleClick()) return@setOnClickListener
                    performRepeat(msgRecord)
                }
                setOnLongClickListener {
                    showActionDialog(msgRecord, repeatView.context)
                    true
                }
            }
        } catch (e: Exception) {
            FileLog.e("$TAG onHandleIntent: ${e.message}")
        }
    }

    /** 把原消息的 element 列表重新下发：能覆盖图片/视频/语音/卡片/文件/文本。 */
    private fun performRepeat(msgRecord: Any) {
        val data = MsgData(msgRecord)
        val elements = ChatRef.getField(msgRecord, "elements") as? List<*>
        if (elements.isNullOrEmpty()) {
            toast("这条消息没有可复读的内容")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val list = ArrayList(elements)

        // 文本消息把 atType==1 归零，否则重复发送会把 @ 指向错误的人
        if (data.msgType == 2) {
            for (el in list) {
                runCatching {
                    val t = ChatRef.getField(el, "textElement") ?: return@runCatching
                    if (ChatRef.getInt(t, "atType") == 1) ChatRef.setField(t, "atType", 0)
                }
            }
        }

        // 文件/小灰条/回复/卡片等走 forwardMsg，其余走 sendMsg
        val ok = if (isNeedForward(data.msgType, data.subMsgType)) {
            MsgSender.forwardElements(data.chatType, data.peerUid, list)
        } else {
            MsgSender.sendElements(data.chatType, data.peerUid, list)
        }
        if (!ok) toast("复读失败，详见日志")
    }

    private fun isNeedForward(msgType: Int, subMsgType: Int): Boolean =
        msgType == 3 || msgType == 7 || msgType == 19 || msgType == 10 ||
            subMsgType == 3 || subMsgType == 4096 || subMsgType == 2

    /** 长按复读按钮后的动作菜单：按消息内容动态生成操作项。 */
    private fun showActionDialog(msgRecord: Any, viewContext: Context) {
        try {
            val data = MsgData(msgRecord)
            val activity = HostEnv.activity() ?: (viewContext as? Activity)
            if (activity == null || activity.isFinishing) {
                toast("当前页面状态异常，无法打开菜单")
                return
            }

            val titles = ArrayList<String>()
            val subs = ArrayList<String>()
            val actions = ArrayList<() -> Unit>()

            if (data.picList.isNotEmpty()) {
                titles += "复制链接"; subs += "共 ${data.picList.size} 张图片"
                actions += { copyToClipboard(activity, data.picList.joinToString("\n")); toast("已复制图片链接") }
            }
            if (data.videoList.isNotEmpty()) {
                titles += "复制链接"; subs += "视频（本地/在线地址）"
                actions += { copyToClipboard(activity, data.videoList.joinToString("\n")); toast("已复制视频地址") }
            }
            if (data.pttList.isNotEmpty()) {
                titles += "复制链接"; subs += "语音（本地路径）"
                actions += { copyToClipboard(activity, data.pttList.joinToString("\n")); toast("已复制语音地址") }
            }
            if (data.msgType == 3 && data.path.isNotEmpty()) {
                titles += "复制链接"; subs += "文件（本地路径）"
                actions += { copyToClipboard(activity, data.path); toast("已复制文件路径") }
            }

            titles += "复制文本"; subs += ""
            actions += { copyToClipboard(activity, data.msg); toast("已复制消息到剪贴板") }

            titles += "原始消息"; subs += "查看 MsgRecord 原始内容"
            actions += { showRawText(activity, "原始消息 (MsgRecord)", msgRecord.toString()) }

            titles += "重构消息"; subs += "查看 MsgData 原始内容"
            actions += { showRawText(activity, "重构消息 (MsgData)", data.toString()) }

            showList(activity, titles, subs, actions)
        } catch (e: Exception) {
            FileLog.e("$TAG showActionDialog: ${e.message}")
            toast("打开菜单失败: ${e.message}")
        }
    }

    private fun showList(
        activity: Activity,
        titles: List<String>,
        subs: List<String>,
        actions: List<() -> Unit>
    ) {
        val builder = android.app.AlertDialog.Builder(activity)
        val labels = titles.mapIndexed { i, t ->
            if (subs.getOrNull(i).isNullOrEmpty()) t else "$t\n${subs[i]}"
        }.toTypedArray()
        builder.setTitle("消息操作")
        builder.setItems(labels) { _, which ->
            runCatching { actions.getOrNull(which)?.invoke() }
                .onFailure { FileLog.e("$TAG 菜单动作失败", it) }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showRawText(activity: Activity, title: String, text: String) {
        val tv = android.widget.TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(40, 40, 40, 40)
        }
        val sv = android.widget.ScrollView(activity).apply { addView(tv) }
        android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(sv)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("repeat_msg", text))
    }

    private fun isDoubleClick(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastClickTime
        lastClickTime = now
        return delta < 500
    }

    private fun toast(s: String) {
        runCatching {
            val ctx = HostEnv.activity() ?: HostEnv.appContext() ?: return
            Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
        }
    }
}
