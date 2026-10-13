// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.theme.ThemeColors

/**
 * Agent 协同设置: 自动注入桥接 + 全页截图最大高度。
 *
 * v0.10.2 清理无效控件 (旧实为只写不读的假开关/假滑杆):
 * ① 「快速点击」——`BuiltinBrowserPlugin.quickClickEnabled` 全仓库无读取点, 开关无效果, 已删;
 * ② 「截图质量」——`prefs.screenshotQuality` 无读取点, 且截图固定 PNG (PNG 无损, quality 参数
 *   被忽略), 已删 (若将来支持 JPEG 再随实现一起恢复)。
 */
@Composable
fun BrowserAgentSettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    autoInjectBridge: Boolean,
    screenshotMaxH: Int,
    onAutoInjectToggled: (Boolean) -> Unit,
    onScreenshotMaxHChanged: (Int) -> Unit
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("智能体协同设置") },
        text = {
            LazyColumn {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Star, null, Modifier.size(16.dp), tint = ThemeColors.brand)
                        Spacer(Modifier.width(4.dp))
                        Text("Agent 控制", style = MaterialTheme.typography.labelLarge, color = ThemeColors.brand, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("使用全页截图 + 坐标点击替代 CSS 选择器。Vision 模型友好，对 Canvas/Shadow DOM/验证码有效。",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动注入桥接", fontWeight = FontWeight.Medium)
                            Text("每页自动注入 __mp 加速命令 (~33x)", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                        }
                        Switch(checked = autoInjectBridge, onCheckedChange = onAutoInjectToggled)
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // Section: Screenshot max height
                item {
                    Text("全页截图最大高度", fontWeight = FontWeight.Medium)
                    Text("${screenshotMaxH}px (更大=更完整, 更小=更快)", style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary)
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = screenshotMaxH.toFloat(),
                        onValueChange = { onScreenshotMaxHChanged(it.toInt()) },
                        valueRange = 5000f..30000f,
                        steps = 4,
                        colors = SliderDefaults.colors(thumbColor = ThemeColors.brand, activeTrackColor = ThemeColors.brand)
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

                // Workflow tips
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = ThemeColors.textSecondary)
                        Spacer(Modifier.width(4.dp))
                        Text("使用流程", fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("1. page.screenshot --full → 得到全页分段图\n2. Vision 模型识别目标坐标\n3. page.click <seg> <x> <y>\n4. page.scroll_by <dy> 验证位置",
                        style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary, lineHeight = 18.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {}
    )
}
