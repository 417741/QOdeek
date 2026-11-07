package com.qo.mk

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeOption,
    currentKey: String?,
    onThemeChange: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var extractMode by remember { mutableStateOf(loadExtractMode(context)) }
    var showInputDialog by remember { mutableStateOf(false) }
    var inputPath by remember { mutableStateOf(loadExtractPath(context) ?: "") }
    var rootEnabled by remember { mutableStateOf(loadRootEnabled(context)) }
    var rootChecking by remember { mutableStateOf(false) }

    // 系统文件夹选择器（SAF），结果转成真实路径
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val realPath = uriToRealPath(uri)
            if (realPath != null) {
                saveExtractPath(context, realPath)
                saveExtractMode(context, EXTRACT_MODE_CUSTOM)
                extractMode = EXTRACT_MODE_CUSTOM
            } else {
                Toast.makeText(context, "无法解析所选文件夹路径", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(theme.gradient))
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBarSurface
                )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    "界面颜色",
                    fontSize = 14.sp,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                themeOptions.forEach { option ->
                    ColorOptionCard(
                        option = option,
                        selected = option.key == currentKey,
                        onClick = { onThemeChange(option.key) }
                    )
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "提取安装包路径",
                    fontSize = 14.sp,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                ExtractPathCard(
                    mode = extractMode,
                    path = loadExtractPath(context),
                    accent = theme.accent,
                    // 点击直接打开系统 SAF 文件夹选择器
                    onClick = { treeLauncher.launch(null) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            saveExtractMode(context, EXTRACT_MODE_DEFAULT)
                            extractMode = EXTRACT_MODE_DEFAULT
                            Toast.makeText(context, "已恢复默认路径", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("恢复默认", fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            inputPath = loadExtractPath(context) ?: ""
                            showInputDialog = true
                        }
                    ) { Text("手动输入", fontSize = 12.sp) }
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    "Root 权限",
                    fontSize = 14.sp,
                    color = AppTextTertiary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
                RootSwitchCard(
                    enabled = rootEnabled,
                    checking = rootChecking,
                    onToggle = { want ->
                        if (want) {
                            // 开启：请求 Root 授权（首次弹授权框），失败自动弹回关闭
                            rootChecking = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { checkRootAccess() }
                                rootEnabled = ok
                                saveRootEnabled(context, ok)
                                rootChecking = false
                                if (!ok) {
                                    Toast.makeText(context, "没有 Root 权限", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            rootEnabled = false
                            saveRootEnabled(context, false)
                        }
                    }
                )
            }
        }
    }

    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            containerColor = AppDialogSurface,
            shape = RoundedCornerShape(24.dp),
            title = { Text("手动输入提取路径") },
            text = {
                Column {
                    OutlinedTextField(
                        value = inputPath,
                        onValueChange = { inputPath = it },
                        singleLine = true,
                        placeholder = { Text("/storage/emulated/0/Download/应用管家") },
                        label = { Text("文件夹绝对路径") }
                    )
                    Text(
                        "可在 MT 管理器等应用里复制文件夹路径后粘贴到这里",
                        fontSize = 12.sp,
                        color = AppTextTertiary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val p = inputPath.trim()
                        if (p.isEmpty()) {
                            Toast.makeText(context, "路径不能为空", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        saveExtractPath(context, p)
                        saveExtractMode(context, EXTRACT_MODE_CUSTOM)
                        extractMode = EXTRACT_MODE_CUSTOM
                        showInputDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showInputDialog = false }) { Text("取消") }
            }
        )
    }
}

/** Root 权限开关卡片 */
@Composable
private fun RootSwitchCard(enabled: Boolean, checking: Boolean, onToggle: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppCardBackgroundBrush)
            .border(1.dp, AppCardBorderBrush, RoundedCornerShape(20.dp))
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(AppCardHighlightBrush)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Root 权限",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTextPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        checking -> "正在请求 Root 授权…"
                        enabled -> "已开启，可统计数据占用"
                        else -> "未开启"
                    },
                    fontSize = 12.sp,
                    color = AppTextTertiary
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { if (!checking) onToggle(it) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorOptionCard(
    option: ThemeOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppCardBackgroundBrush)
            .border(1.dp, AppCardBorderBrush, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(AppCardHighlightBrush)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        Brush.linearGradient(option.gradient),
                        RoundedCornerShape(12.dp)
                    )
            )
            Spacer(Modifier.width(14.dp))
            Text(
                option.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AppTextPrimary,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = option.accent,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtractPathCard(mode: String, path: String?, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppCardBackgroundBrush)
            .border(1.dp, AppCardBorderBrush, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(AppCardHighlightBrush)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        accent.copy(alpha = 0.12f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("📁", fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (mode == EXTRACT_MODE_CUSTOM) (path ?: "未设置路径")
                    else "Download/应用管家",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "点击更改",
                    fontSize = 12.sp,
                    color = AppTextTertiary
                )
            }
            Text(
                "›",
                fontSize = 24.sp,
                color = AppTextTertiary
            )
        }
    }
}
