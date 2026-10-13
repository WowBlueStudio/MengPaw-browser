// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.ui.components.SearchEngineLogo
import com.mengpaw.design.theme.ThemeColors

/** Settings dialog: search engine ordering, ad block toggle, dark mode. */
@Composable
fun BrowserSettingsDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    prefs: BrowserPrefs,
    adBlockEnabled: Boolean,
    onAdBlockToggled: (Boolean) -> Unit,
    darkMode: Boolean,
    onDarkModeToggled: (Boolean) -> Unit,
    searchEngine: SearchEngine,
    onDefaultEngineChanged: (SearchEngine) -> Unit,
    /** 站点形态偏好（累计修改）: auto / desktop / mobile */
    uaMode: com.mengpaw.browser.data.UaMode = com.mengpaw.browser.data.UaMode.AUTO,
    onUaModeChanged: (com.mengpaw.browser.data.UaMode) -> Unit = {},
    webViewVersion: String = "",
    /** 浏览器自身版本 — 报障/自测时需要, 旧版本无任何入口可见 */
    appVersion: String = "",
    onOpenCoolApk: (() -> Unit)? = null,
    onOpenApkCombo: (() -> Unit)? = null
) {
    if (!visible) return

    val engineKeys = remember { mutableStateListOf(*prefs.engineKeys.toTypedArray()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            LazyColumn {
                item { Text("搜索引擎 (勾选+拖动排序，首次为默认)", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary) }
                item { Spacer(Modifier.height(8.dp)) }
                items(engineKeys.size) { idx ->
                    val key = engineKeys[idx]
                    val eng = SearchEngine.fromKey(key)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = true, onCheckedChange = {
                            if (engineKeys.size > 1) engineKeys.removeAt(idx)
                        })
                        SearchEngineLogo(eng, size = 22, dimmed = false)
                        Spacer(Modifier.width(8.dp))
                        Text(eng.label, modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (idx > 0) { val t = engineKeys[idx]; engineKeys[idx] = engineKeys[idx-1]; engineKeys[idx-1] = t } },
                            enabled = idx > 0, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ArrowBack, "上移", Modifier.size(16.dp)) }
                        IconButton(onClick = { if (idx < engineKeys.size - 1) { val t = engineKeys[idx]; engineKeys[idx] = engineKeys[idx+1]; engineKeys[idx+1] = t } },
                            enabled = idx < engineKeys.size - 1, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ArrowForward, "下移", Modifier.size(16.dp)) }
                    }
                }
                // Add disabled engines
                val disabled = SearchEngine.entries.filter { it.key !in engineKeys }
                if (disabled.isNotEmpty()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item { Text("已关闭的引擎", style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary) }
                    items(disabled.size) { i ->
                        val eng = disabled[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = false, onCheckedChange = { engineKeys.add(eng.key) })
                            SearchEngineLogo(eng, size = 22, dimmed = true)
                            Spacer(Modifier.width(8.dp))
                            Text(eng.label, modifier = Modifier.weight(1f), color = ThemeColors.textSecondary)
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("广告拦截")
                        Switch(checked = adBlockEnabled, onCheckedChange = onAdBlockToggled)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("暗色模式")
                        Switch(checked = darkMode, onCheckedChange = onDarkModeToggled)
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                // 站点形态（累计修改）: 站点差异大，给用户一键覆盖；切换后刷新生效
                item {
                    Column {
                        Text("网站版本", fontWeight = FontWeight.Medium)
                        Text(
                            "默认：平板请求桌面版、手机请求移动版。站点不适配时可强制切换（刷新生效）。",
                            style = MaterialTheme.typography.labelSmall, color = ThemeColors.textSecondary
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            com.mengpaw.browser.data.UaMode.entries.forEach { mode ->
                                val selected = mode == uaMode
                                FilterChip(
                                    selected = selected,
                                    onClick = { if (!selected) onUaModeChanged(mode) },
                                    label = {
                                        Text(
                                            when (mode) {
                                                com.mengpaw.browser.data.UaMode.AUTO -> "自动"
                                                com.mengpaw.browser.data.UaMode.DESKTOP -> "桌面版"
                                                com.mengpaw.browser.data.UaMode.MOBILE -> "移动版"
                                            },
                                            fontSize = 12.sp
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("浏览器版本", style = MaterialTheme.typography.labelMedium)
                        Text(appVersion.ifBlank { "—" }, style = MaterialTheme.typography.labelMedium, color = ThemeColors.textSecondary)
                    }
                }
                if (webViewVersion.isNotBlank()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    item {
                        Column {
                            Text("WebView 引擎", style = MaterialTheme.typography.labelMedium)
                            Text(webViewVersion, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ThemeColors.brand)
                            if (onOpenCoolApk != null || onOpenApkCombo != null) {
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    onOpenCoolApk?.let { cb ->
                                        OutlinedButton(onClick = cb) { Text("酷安", fontSize = 12.sp) }
                                    }
                                    onOpenApkCombo?.let { cb ->
                                        OutlinedButton(onClick = cb) { Text("APKCombo", fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.engineKeys = engineKeys.toList()
                // P2 fix: 保存不再把默认引擎重置为列表首项 — 用户当前默认仍在列表中则保留;
                // 仅当当前默认被关闭 (移出列表) 时才顺延为列表首项。
                val newDefault = if (engineKeys.any { it == searchEngine.key }) searchEngine
                                 else SearchEngine.fromKey(engineKeys.firstOrNull() ?: searchEngine.key)
                prefs.setDefaultEngine(newDefault)
                onDefaultEngineChanged(newDefault)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
