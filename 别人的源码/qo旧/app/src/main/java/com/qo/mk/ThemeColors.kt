package com.qo.mk

import android.content.Context
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 界面主题色（可切换的液态玻璃配色） */
data class ThemeOption(
    val key: String,
    val name: String,
    val gradient: List<Color>,
    val accent: Color
)

// ===== 全局统一 UI 语义色（所有界面共用，保证风格一致）=====

/** 主文字色（深紫灰） */
val AppTextPrimary = Color(0xFF2B2440)
/** 次要文字色（包名 / 大小等） */
val AppTextSecondary = Color(0xFF6E6A80)
/** 三级文字色（标签 / 说明） */
val AppTextTertiary = Color(0xFF8A8A8A)
/** 危险操作色（卸载） */
val AppDanger = Color(0xFFD32F2F)

/** 弹窗卡片底色（半透明白） */
val AppDialogSurface = Color.White.copy(alpha = 0.92f)
/** 普通卡片底色（半透明白） */
val AppCardSurface = Color.White.copy(alpha = 0.7f)
/** 顶栏 / 底栏玻璃底色（统一透明度，保证上下风格一致） */
val AppBarSurface = Color.White.copy(alpha = 0.55f)

/** 列表卡片玻璃背景渐变 */
val AppCardBackgroundBrush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.16f))
)
/** 列表卡片顶部镜面高光 */
val AppCardHighlightBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0f to Color.White.copy(alpha = 0.55f),
        0.5f to Color.White.copy(alpha = 0f),
        1f to Color.White.copy(alpha = 0f)
    )
)
/** 列表卡片描边渐变 */
val AppCardBorderBrush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.18f))
)
/** 弹窗专用玻璃背景渐变（与卡片同色，但透明度更高、更实，避免背景文字透出） */
val AppDialogBackgroundBrush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.97f), Color.White.copy(alpha = 0.88f))
)
/** 弹窗专用描边渐变（同色加深） */
val AppDialogBorderBrush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.55f))
)

/** 图标占位背景渐变 */
val AppIconPlaceholderBrush = Brush.verticalGradient(
    listOf(Color(0xFFE4E7FF), Color(0xFFCFD5FF))
)

/** 选中态叠加色：跟随主题 accent，统一半透明 */
fun selectionOverlay(accent: Color) = accent.copy(alpha = 0.28f)

val themeOptions = listOf(
    ThemeOption(
        key = "blue",
        name = "静谧蓝",
        gradient = listOf(Color(0xFFEAE6FF), Color(0xFFDCE8FF), Color(0xFFFFE4F0)),
        accent = Color(0xFF5B8DEF)
    ),
    ThemeOption(
        key = "purple",
        name = "梦幻紫",
        gradient = listOf(Color(0xFFF3E8FF), Color(0xFFE8E0FF), Color(0xFFFFE4F5)),
        accent = Color(0xFF9C6BEF)
    ),
    ThemeOption(
        key = "pink",
        name = "樱花粉",
        gradient = listOf(Color(0xFFFFE8F0), Color(0xFFFFE0EB), Color(0xFFFCE8FF)),
        accent = Color(0xFFEF6C9C)
    ),
    ThemeOption(
        key = "green",
        name = "薄荷绿",
        gradient = listOf(Color(0xFFE0F7EC), Color(0xFFDCF0FF), Color(0xFFF0FBE8)),
        accent = Color(0xFF4CAF7D)
    )
)

private const val PREFS_THEME = "theme_prefs"
private const val KEY_THEME = "theme_key"

fun loadThemeKey(context: Context): String? =
    context.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE).getString(KEY_THEME, null)

fun saveThemeKey(context: Context, key: String) =
    context.getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        .edit().putString(KEY_THEME, key).apply()

fun getThemeByKey(key: String?): ThemeOption =
    themeOptions.firstOrNull { it.key == key } ?: themeOptions.first()
