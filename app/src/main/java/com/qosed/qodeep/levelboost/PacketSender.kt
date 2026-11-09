package com.qosed.qodeep.levelboost

import android.os.Bundle
import android.os.Parcelable
import com.qosed.qodeep.Env
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * QQ 内部 SSO 发包器（纯反射实现）。
 *
 * 用 QQ 自己的 mqq.app.NewIntent + SSOEasyServlet 发起 SSO 请求：
 * 请求包体由 [PbCodec] 从 JSONObject 编码成 protobuf 并加 4 字节长度头，
 * 响应包体去掉长度头后解码回 JSONObject。
 *
 * 不依赖任何编译期宿主类型，全部通过 [Env.hostClassLoader] 反射，
 * 因此不会因为 QQ 版本差异导致模块加载失败（找不到就降级为失败回调）。
 */
object PacketSender {

    /** 一次发包的结果回调。 */
    fun interface Callback {
        fun onResult(success: Boolean, json: JSONObject)
    }

    /**
     * 同步发送一个 SSO 包（带超时）。
     * @param serviceCmd 服务命令号，例如 "OidbSvcTrpcTcp.0x7c2_5"
     * @param pbData JSONObject 描述的包体
     * @return 成功时的响应 JSONObject；失败或超时为 null
     */
    fun sendSync(serviceCmd: String, pbData: JSONObject, timeoutMs: Long = 12000L): JSONObject? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<JSONObject>(1)
        send(serviceCmd, pbData) { ok, json ->
            if (ok) holder[0] = json
            latch.countDown()
        }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return holder[0]
    }

    /** 异步发送一个 SSO 包。 */
    fun send(serviceCmd: String, pbData: JSONObject, callback: Callback) {
        val err = sendInternal(serviceCmd, pbData, callback)
        if (err != null) {
            callback.onResult(false, errorJson(serviceCmd, err))
        }
    }

    private fun errorJson(cmd: String, msg: String): JSONObject {
        val js = JSONObject()
        runCatching {
            js.put("1", cmd)
            js.put("2", msg)
            js.put("12", 0)
        }
        return js
    }

    /** 返回 null 表示已成功发出；返回字符串表示失败原因。 */
    private fun sendInternal(serviceCmd: String, pbData: JSONObject, callback: Callback): String? {
        var stage = "start"
        return try {
            val loader = Env.hostClassLoader

            stage = "encode"
            val body = PbCodec.wrapLength(PbCodec.encode(pbData))

            stage = "load class"
            val newIntentClass = loader.loadClass("mqq.app.NewIntent")
            val servletClass = loader.loadClass("mqq.app.api.impl.SSOEasyServlet")
            val toServiceMsgClass = loader.loadClass("com.tencent.qphone.base.remote.ToServiceMsg")
            val observerClass = loader.loadClass("mqq.observer.BusinessObserver")

            // 优先用 QQ 自己的 Application 单例，与宿主内部创建 NewIntent 的方式一致
            stage = "get application"
            val baseAppClass = loader.loadClass("com.tencent.common.app.BaseApplicationImpl")
            val application = runCatching {
                baseAppClass.getMethod("getApplication").invoke(null)
            }.getOrNull() ?: Env.hostContext ?: return "application null"
            val context = application as android.content.Context
            val appRuntime = QQEnv.getAppRuntime() ?: return "app runtime null"

            stage = "new intent"
            val intentCtor = newIntentClass.constructors.firstOrNull { c ->
                c.parameterTypes.size == 2 &&
                    android.content.Context::class.java.isAssignableFrom(c.parameterTypes[0]) &&
                    c.parameterTypes[1] == Class::class.java
            } ?: return "NewIntent ctor not found"
            val intent = intentCtor.newInstance(context, servletClass)

            stage = "to service msg"
            val toServiceMsg = toServiceMsgClass
                .getConstructor(String::class.java, String::class.java, String::class.java)
                .newInstance("mobileqq.service", "0", serviceCmd)
            toServiceMsgClass.getMethod("putWupBuffer", ByteArray::class.java)
                .invoke(toServiceMsg, body)

            stage = "observer"
            val observer = java.lang.reflect.Proxy.newProxyInstance(
                loader, arrayOf(observerClass)
            ) { _, method, args ->
                if (method.name == "onReceive" && args != null && args.size >= 3) {
                    handleResponse(args[1] as? Boolean ?: false, args[2] as? Bundle, serviceCmd, callback)
                }
                null
            }

            stage = "set observer"
            newIntentClass.getMethod("setObserver", observerClass).invoke(intent, observer)

            stage = "put extra"
            val putExtra = newIntentClass.methods.firstOrNull { m ->
                m.name == "putExtra" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java &&
                    m.parameterTypes[1].isAssignableFrom(toServiceMsg.javaClass)
            } ?: newIntentClass.methods.firstOrNull { m ->
                m.name == "putExtra" && m.parameterTypes.size == 2 &&
                    m.parameterTypes[0] == String::class.java
            } ?: return "putExtra not found"
            putExtra.invoke(intent, "ToServiceMsg", toServiceMsg)

            stage = "start servlet"
            val startServlet = appRuntime.javaClass.methods.firstOrNull { m ->
                m.name == "startServlet" && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0].isAssignableFrom(intent.javaClass)
            } ?: return "startServlet not found"
            startServlet.invoke(appRuntime, intent)
            null
        } catch (t: Throwable) {
            FileLog.e("SSO 发包失败[$stage] $serviceCmd", t)
            "$stage: ${t.javaClass.simpleName} ${t.message ?: ""}".trim()
        }
    }

    private fun handleResponse(
        isSuccess: Boolean,
        bundle: Bundle?,
        serviceCmd: String,
        callback: Callback
    ) {
        if (!isSuccess || bundle == null) {
            callback.onResult(false, errorJson(serviceCmd, "request failed"))
            return
        }
        runCatching {
            val fromMsg = bundle.getParcelable<Parcelable>("FromServiceMsg")
                ?: return@runCatching callback.onResult(false, errorJson(serviceCmd, "FromServiceMsg null"))
            val wup = fromMsg.javaClass.getMethod("getWupBuffer").invoke(fromMsg) as? ByteArray
                ?: return@runCatching callback.onResult(false, errorJson(serviceCmd, "wupBuffer null"))
            if (wup.isEmpty()) {
                callback.onResult(false, errorJson(serviceCmd, "wupBuffer empty"))
                return@runCatching
            }
            callback.onResult(true, PbCodec.decode(PbCodec.unwrapLength(wup)))
        }.onFailure {
            FileLog.e("SSO 响应解析失败 $serviceCmd", it)
            callback.onResult(false, errorJson(serviceCmd, it.message ?: "decode error"))
        }
    }
}
