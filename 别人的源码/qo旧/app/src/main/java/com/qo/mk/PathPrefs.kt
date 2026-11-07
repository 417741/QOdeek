package com.qo.mk

import android.content.Context

const val EXTRACT_MODE_DEFAULT = "default"
const val EXTRACT_MODE_CUSTOM = "custom"

private const val PREFS_PATH = "extract_path_prefs"
private const val KEY_MODE = "mode"
private const val KEY_PATH = "path"
private const val KEY_ROOT = "root_enabled"

/** 读取提取路径模式：default（Download/应用管家）或 custom（自定义文件夹） */
fun loadExtractMode(context: Context): String =
    context.getSharedPreferences(PREFS_PATH, Context.MODE_PRIVATE)
        .getString(KEY_MODE, EXTRACT_MODE_DEFAULT) ?: EXTRACT_MODE_DEFAULT

fun saveExtractMode(context: Context, mode: String) =
    context.getSharedPreferences(PREFS_PATH, Context.MODE_PRIVATE)
        .edit().putString(KEY_MODE, mode).apply()

/** 读取自定义文件夹的绝对路径，未设置返回 null */
fun loadExtractPath(context: Context): String? =
    context.getSharedPreferences(PREFS_PATH, Context.MODE_PRIVATE)
        .getString(KEY_PATH, null)

fun saveExtractPath(context: Context, path: String?) =
    context.getSharedPreferences(PREFS_PATH, Context.MODE_PRIVATE)
        .edit().putString(KEY_PATH, path).apply()

/** 读取 Root 权限开关状态 */
fun loadRootEnabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS_PATH, Context.MODE_PRIVATE)
        .getBoolean(KEY_ROOT, false)

fun saveRootEnabled(context: Context, enabled: Boolean) =
    context.getSharedPreferences(PREFS_PATH, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_ROOT, enabled).apply()
