package com.qo.mk

import java.io.File

/** 执行 root 命令，返回合并后的 stdout+stderr 输出 */
internal fun runRootCommand(command: String): String {
    return try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output
    } catch (e: Exception) {
        ""
    }
}

/**
 * 检测设备是否已 Root。
 * 先检查常见 su 二进制路径，再用 PATH 兜底检测（不会触发授权弹窗）。
 */
fun isDeviceRooted(): Boolean {
    val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/vendor/bin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su"
    )
    if (suPaths.any { File(it).exists() }) return true
    return try {
        val process = ProcessBuilder("sh", "-c", "command -v su")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.isNotEmpty()
    } catch (e: Exception) {
        false
    }
}

/**
 * 请求 Root 授权并验证可用性（首次会弹出授权框，拒绝或无 root 返回 false）。
 */
fun checkRootAccess(): Boolean =
    runRootCommand("echo OK").contains("OK")

/**
 * 通过 Root 卸载（多级尝试，尽量做到"什么都能卸载"）。
 *
 * 1. `pm uninstall --user 0`：卸载 user 0 的预装应用/更新，覆盖大多数情况；
 * 2. 失败则定位 APK 并删除系统预装目录，针对受保护的系统应用（/system、/vendor 等分区）。
 *
 * 首次调用会触发 Root 授权框（Magisk/SuperSU）。
 */
fun uninstallPackageWithRoot(packageName: String): Boolean {
    // 方案 1：标准卸载（含 user 0）
    val result1 = runRootCommand("pm uninstall --user 0 $packageName")
    if (result1.contains("Success")) return true

    // 方案 2：直接删除系统预装应用的 APK 目录
    val pathLine = runRootCommand("pm path $packageName")
        .lineSequence()
        .firstOrNull { it.startsWith("package:") }
    val path = pathLine?.substringAfter("package:")?.trim().orEmpty()

    val isSystemPath = path.startsWith("/system") || path.startsWith("/vendor") ||
        path.startsWith("/product") || path.startsWith("/system_ext")
    if (path.isNotEmpty() && isSystemPath) {
        val dir = path.substringBeforeLast("/")
        // 尝试 remount 系统分区为可写
        runRootCommand("mount -o rw,remount /")
        runRootCommand("rm -rf \"$dir\"")
        runRootCommand("pm uninstall $packageName")
        // 再次确认 APK 是否已被删除
        return !runRootCommand("pm path $packageName").contains("package:")
    }
    return false
}

/** 返回上一级目录 */
fun parentOf(path: String): String {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty() || trimmed == "/") return "/"
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}

/** 用 root 列出目录下的所有子目录（返回绝对路径列表） */
fun listDirectories(path: String): List<String> {
    val output = runRootCommand("ls -1p \"$path\"")
    val base = path.trimEnd('/').ifEmpty { "/" }
    return output.lineSequence()
        .filter { it.endsWith("/") }
        .map { it.dropLast(1) }
        .filter { it.isNotBlank() }
        .map { name -> if (base == "/") "/$name" else "$base/$name" }
        .toList()
}

/** 用 root 复制文件到任意路径，返回目标文件是否成功生成 */
fun copyFileWithRoot(src: String, dest: String): Boolean {
    runRootCommand("cp -f \"$src\" \"$dest\"")
    return runRootCommand("test -f \"$dest\" && echo OK").contains("OK")
}

/** 用 root 读取任意路径文件内容（用于跨应用读取 QQ 模块数据），失败返回 null */
fun readFileWithRoot(path: String): String? {
    val out = runRootCommand("cat \"$path\" 2>/dev/null")
    if (out.isBlank()) return null
    return out
}
