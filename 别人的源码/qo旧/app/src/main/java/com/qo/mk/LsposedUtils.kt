package com.qo.mk

/**
 * LSPosed 框架检测（QAuxiliary 风格「已激活」横幅的数据源）。
 * 应用没有宿主注入，只能通过 Root 读 /data/adb 下的 LSPosed 数据判断：
 *  - LSPosed 是否安装：/data/adb/lspd 目录或 /data/adb/modules 下的 lsposed 模块
 *  - LSPosed 版本：模块 module.prop 的 version / versionCode 字段
 *  - 本模块是否已启用：lspd 配置目录中出现本模块包名（与作用于哪个应用无关）
 */
object LsposedUtils {

    private const val OUR_PKG = "com.qo.mk"

    /** LSPosed 是否安装 */
    fun isLsposedPresent(): Boolean {
        if (runRootCommand("test -d /data/adb/lspd && echo OK").contains("OK")) return true
        // 兜底：Magisk 模块目录里找 lsposed
        val out = runRootCommand("ls -d /data/adb/modules/*lsposed* 2>/dev/null")
        return out.isNotBlank()
    }

    /**
     * 读 LSPosed 的 module.prop 内容，读不到返回 null。
     * 逐个候选路径尝试（不同安装方式目录名不同：zygisk_lsposed / riru_lsposed 等），
     * 避免一条 shell 里 glob 不展开或某个路径不存在导致整体拿不到。
     */
    private fun readModuleProp(): String? {
        // 先列出 LSPosed 模块目录，再逐个读 module.prop
        val dirs = runRootCommand("ls -1d /data/adb/modules/*lsposed* 2>/dev/null")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        for (dir in dirs) {
            val prop = runRootCommand("cat \"$dir/module.prop\" 2>/dev/null")
            if (prop.isNotBlank()) return prop
        }
        // 兜底：lspd 主目录下可能直接有 module.prop
        val fallback = runRootCommand("cat /data/adb/lspd/module.prop 2>/dev/null")
        return fallback.ifBlank { null }
    }

    /** 从 prop 内容里取 key= 行的值 */
    private fun propValue(prop: String?, key: String): String? {
        if (prop.isNullOrBlank()) return null
        val line = prop.lineSequence().firstOrNull { it.startsWith("$key=") } ?: return null
        val v = line.substringAfter('=').trim()
        return v.ifEmpty { null }
    }

    /**
     * LSPosed 版本名（module.prop 的 version，形如 "v2.21 (7936)"），读不到返回 null。
     */
    fun getLsposedVersion(): String? {
        val prop = readModuleProp()
        return propValue(prop, "version")
    }

    /** LSPosed 版本代号（versionCode，如 7936），读不到返回 null */
    fun getLsposedVersionCode(): String? {
        val prop = readModuleProp()
        return propValue(prop, "versionCode")
    }

    /**
     * 本模块是否已在 LSPosed 中启用。
     * 在 lspd 配置目录里找得到本模块包名即认为已启用（与作用于哪个应用无关）。
     */
    fun isModuleEnabled(): Boolean {
        val out = runRootCommand("grep -rqa \"$OUR_PKG\" /data/adb/lspd/config 2>/dev/null && echo OK")
        return out.contains("OK")
    }

    /** 综合判断：LSPosed 安装且本模块已启用 */
    fun isActivated(): Boolean = isLsposedPresent() && isModuleEnabled()
}
