// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors

/**
 * 密码说明对话框。
 *
 * v0.10.2: 仅在 WebView 密码库仍存在 (API < 33) 时才渲染「清除所有密码」按钮 —
 * 旧实现在 Android 13+ 点了只弹 Toast 不改任何状态, 属于无效控件。
 * WebView 自 API 18 移除 setSavePassword, 登录凭据统一由系统密码管理器负责。
 */
@Composable
fun BrowserPasswordDialog(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val ctx = LocalContext.current
    val pwdDb = remember { android.webkit.WebViewDatabase.getInstance(ctx) }
    val passwordStoreSupported = android.os.Build.VERSION.SDK_INT < 33

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("密码管理 Passwords") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("保存密码 — 已停用", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // 诚实说明: 本应用不实现密码自动填充/长按保存 — 登录凭据由系统密码管理器负责
                Text("登录凭据由系统密码管理器负责保存与填充，本应用不存储、不读取密码内容。", style = MaterialTheme.typography.bodySmall, color = ThemeColors.textSecondary)
                if (passwordStoreSupported) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        // clearUsernamePassword 在 API 33+ 已从框架移除，调用即抛 NoSuchMethodError (Error 非 Exception)
                        pwdDb.clearUsernamePassword()
                        Toast.makeText(ctx, "已清除 WebView 密码库", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("清除 WebView 密码库") }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android 13 起系统已移除 WebView 密码库，此处无需清理。",
                        style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
