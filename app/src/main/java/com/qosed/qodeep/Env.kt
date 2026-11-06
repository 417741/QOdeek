package com.qosed.qodeep

import android.content.Context

/** 运行时共享状态：宿主 ClassLoader / 宿主 Context / 模块自身目录。 */
object Env {
    /**
     * 不能静态引用 MainHook：MainHook 依赖 Xposed API（IXposedHookLoadPackage 等），
     * 在独立进程（桌面 App）里 Xposed 未注入、MainHook 类加载不到，静态引用会拖垮
     * Env 的 <clinit>，导致所有用 Env 的地方抛 NoClassDefFoundError。
     * 默认改用 Env 自己的 ClassLoader；真正的宿主 loader 由 MainHook 在注入时覆盖。
     */
    @Volatile
    var hostClassLoader: ClassLoader = Env::class.java.classLoader
        ?: ClassLoader.getSystemClassLoader()

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
