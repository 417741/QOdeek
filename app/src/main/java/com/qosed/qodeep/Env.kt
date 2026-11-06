package com.qosed.qodeep

import android.content.Context

/** 运行时共享状态：宿主 ClassLoader / 宿主 Context / 模块自身目录。 */
object Env {
    @Volatile
    var hostClassLoader: ClassLoader = MainHook::class.java.classLoader!!

    @Volatile
    var hostContext: Context? = null

    @Volatile
    var moduleDataDir: String = ""

    @Volatile
    var moduleNativeLibDir: String = ""

    @Volatile
    var moduleApkPath: String = ""

    @Volatile
    var hostApkPath: String = ""
}
