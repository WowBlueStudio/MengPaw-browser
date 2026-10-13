// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.design.theme.ThemeColors

/**
 * Image picker dialog listing detected images with download-on-click.
 *
 * v0.10.2: 下载改为回调交给调用方 (存活更久的作用域 + 真实成败反馈), 且不再在点击后
 * 立即关闭对话框 — 旧实现用弹窗自身的作用域启动下载, `onDismiss()` 同帧取消协程,
 * 下载静默失败却提示「已保存」。
 */
@Composable
fun BrowserImagePickerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    images: List<DetectedImage>,
    onSave: (String) -> Unit
) {
    if (!visible || images.isEmpty()) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检测到 ${images.size} 个图片 (顶层→底层)") },
        text = {
            LazyColumn {
                items(images.size) { idx ->
                    val img = images[idx]
                    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                        onSave(img.src)
                    }, shape = RoundedCornerShape(8.dp), color = ThemeColors.bgCardHigh) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = ThemeColors.brand, modifier = Modifier.width(28.dp), fontSize = 12.sp)
                            Column(Modifier.weight(1f)) {
                                Text(img.alt.ifBlank { img.src.substringAfterLast('/').take(40) }, maxLines = 1, fontSize = 13.sp)
                                Text(img.src.take(50), style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary, maxLines = 1)
                            }
                            Icon(Icons.Default.Add, "保存", tint = ThemeColors.brand, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
