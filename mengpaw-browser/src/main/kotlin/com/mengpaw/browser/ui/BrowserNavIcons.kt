// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mengpaw.design.theme.ThemeColors

/**
 * 宽屏 (平板/折叠屏/横屏) 常驻导航按钮组: 主页 / 后退 / 前进 / 刷新。
 * 自 [BrowserTopBar] 抽出 (400 行文件拆分批次 3) — 手机端不渲染, 只有标签页角标。
 */
@Composable
internal fun BrowserNavIcons(
    isWide: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    homeUrl: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onGoForward: () -> Unit,
    onRefresh: () -> Unit
) {
    if (!isWide) return
    Row {
        IconButton(onClick = { onNavigate(homeUrl) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Home, "主页", tint = ThemeColors.brand)
        }
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            @Suppress("DEPRECATION")
            Icon(
                Icons.Default.ArrowBack,
                "后退",
                tint = if (canGoBack) ThemeColors.brand else ThemeColors.brand.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onGoForward, enabled = canGoForward, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.ArrowForward,
                "前进",
                tint = if (canGoForward) ThemeColors.brand else ThemeColors.brand.copy(alpha = 0.3f)
            )
        }
        IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Refresh, "刷新", tint = ThemeColors.brand)
        }
        Spacer(Modifier.width(4.dp))
    }
}
