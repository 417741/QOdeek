package com.qo.mk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(
    reloadKey: Int,
    onReload: () -> Unit,
    onUninstallSingle: (String) -> Unit,
    onUninstallBatch: (List<String>) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var themeKey by remember { mutableStateOf(loadThemeKey(context)) }

    val theme = getThemeByKey(themeKey)

    // 返回键：在"模块"或"设置"页时回到"管家"页，而不是退出应用
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    Scaffold(
        containerColor = Color.Transparent,
        // 系统栏 inset 交给内层页面自己处理，避免状态栏 padding 被叠加两次
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Surface(color = AppBarSurface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomTab(
                        selected = selectedTab == 0,
                        label = "管家",
                        icon = null,
                        accent = theme.accent,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.weight(1f)
                    )
                    BottomTab(
                        selected = selectedTab == 1,
                        label = "模块",
                        icon = Icons.Default.Menu,
                        accent = theme.accent,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.weight(1f)
                    )
                    BottomTab(
                        selected = selectedTab == 2,
                        label = "设置",
                        icon = Icons.Default.Settings,
                        accent = theme.accent,
                        onClick = { selectedTab = 2 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> AppManagerScreen(
                    reloadKey = reloadKey,
                    onUninstallSingle = onUninstallSingle,
                    onUninstallBatch = onUninstallBatch,
                    onReload = onReload,
                    onOpenModuleInfo = { selectedTab = 1 },
                    theme = theme
                )
                1 -> ModuleInfoScreen(
                    theme = theme,
                    showBack = false,
                    onBack = { selectedTab = 0 }
                )
                else -> SettingsScreen(
                    theme = theme,
                    currentKey = themeKey,
                    onThemeChange = { key ->
                        themeKey = key
                        saveThemeKey(context, key)
                    }
                )
            }
        }
    }
}

/** 紧凑底部标签：图标 + 文字横排一行，选中显示主题色 */
@Composable
private fun BottomTab(
    selected: Boolean,
    label: String,
    icon: ImageVector?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) accent else AppTextTertiary
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            fontSize = 12.sp,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
