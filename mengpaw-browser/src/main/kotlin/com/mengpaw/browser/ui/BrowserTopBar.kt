// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.util.decodeUrlForDisplay
import com.mengpaw.design.theme.ThemeColors
import com.mengpaw.design.tokens.ArcoRadius

/**
 * Browser top app bar with animated visibility.
 *
 * Contains: URL bar (editable or display-only), navigation icons (home/back/forward/reload
 * on wide screens, tab-badge on phone), bookmark star, and the overflow menu.
 *
 * All state mutations are delegated via callbacks so the parent remains the owner.
 *
 * v0.10.2 地址栏三处修复 (根因: 转到按钮「没反应」):
 * ① **获焦全选** — 旧实现点进地址栏只落光标, 新输入的网址拼接在原 URL 后面,
 *   拼出的地址必然 DNS 失败 (一段时间后跳到「页面加载失败」)。浏览器惯例: 获焦即全选。
 * ② **编辑中不被页面 URL 覆盖** — 旧 `LaunchedEffect(activeTab.url)` 无条件回写,
 *   页面重定向/加载完成时会把用户正在输入的内容抹掉。
 * ③ **提交路径统一** — → 按钮 / 软键盘 Search/Go/Done / 物理回车 都走同一个 submit(),
 *   提交后清焦点收键盘; 空输入不再静默, 给出提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTopBar(
    visible: Boolean,
    showUrlBar: Boolean,
    onShowUrlBarChange: (Boolean) -> Unit,
    isWide: Boolean,
    activeTab: TabState,
    activeTabId: Int,
    tabs: List<TabState>,
    adBlockEnabled: Boolean,
    isBookmarked: Boolean,
    /** Ctrl+L / 点击地址栏时用于聚焦输入框 (由 BrowserApp 持有) */
    urlBarFocusRequester: FocusRequester,
    /** 主页 URL — 持久化设置 (BrowserPrefs.homeUrl), 不再硬编码 baidu */
    homeUrl: String = "https://www.baidu.com",
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    onShowTabs: () -> Unit,
    onShowBookmarks: () -> Unit,
    onToggleBookmark: () -> Unit,
    onRefresh: () -> Unit,
    onGoForward: () -> Unit,
    onGoBack: () -> Unit,
    onNewTab: () -> Unit,
    onCloseTab: () -> Unit,
    onShowTranslate: () -> Unit,
    onShowFind: () -> Unit,
    onShowReader: () -> Unit,
    onAdBlockToggle: () -> Unit,
    onShowHistory: () -> Unit,
    onShowPasswords: () -> Unit,
    onShare: (String) -> Unit,
    onSendToAgent: (String) -> Unit,
    onExtractToAgent: (String, String) -> Unit,
    onShowSettings: () -> Unit,
    onShowAgentSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(animationSpec = tween(200)),
        exit = fadeOut() + slideOutVertically(animationSpec = tween(200)),
        modifier = modifier
    ) {
        TopAppBar(
            title = {
                if (showUrlBar || isWide) {
                    var editUrl by remember(activeTabId) {
                        mutableStateOf(TextFieldValue(decodeUrlForDisplay(activeTab.url)))
                    }
                    // 是否处于编辑态 (输入框持有焦点)。编辑中不回写 — 否则页面重定向/加载完成
                    // 会把用户正在输入的网址替换掉 (P1 修复: 旧实现无条件 LaunchedEffect 回写)。
                    var editing by remember(activeTabId) { mutableStateOf(false) }
                    val focusManager = LocalFocusManager.current
                    LaunchedEffect(activeTab.url) {
                        if (!editing) editUrl = TextFieldValue(decodeUrlForDisplay(activeTab.url))
                    }
                    // 手机: 点地址栏展开输入框后自动聚焦 (省掉第二次点击/键盘自动弹出)
                    LaunchedEffect(showUrlBar) {
                        if (showUrlBar) urlBarFocusRequester.requestFocus()
                    }
                    val submit = {
                        if (editUrl.text.isBlank()) {
                            Toast.makeText(ctx, "请输入网址或搜索关键词", Toast.LENGTH_SHORT).show()
                        } else {
                            val target = editUrl.text
                            editing = false
                            focusManager.clearFocus()
                            onNavigate(target)
                        }
                    }
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .focusRequester(urlBarFocusRequester)
                            .onFocusChanged { state ->
                                editing = state.isFocused
                                // 获焦全选 (浏览器惯例): 否则新网址接在旧 URL 后面 → 必然加载失败
                                if (state.isFocused && editUrl.text.isNotEmpty()) {
                                    editUrl = editUrl.copy(
                                        selection = TextRange(0, editUrl.text.length)
                                    )
                                }
                            },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                        shape = RoundedCornerShape(ArcoRadius.round),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ThemeColors.brand,
                            unfocusedBorderColor = ThemeColors.border
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Search,
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false
                        ),
                        // 三种 IME 动作都归到 submit — 不同输入法给的是 搜索/前往/完成
                        keyboardActions = KeyboardActions(
                            onSearch = { submit() },
                            onGo = { submit() },
                            onDone = { submit() }
                        ),
                        trailingIcon = {
                            FilledIconButton(
                                onClick = { submit() },
                                enabled = editUrl.text.isNotBlank(),
                                modifier = Modifier.size(32.dp).offset(x = 1.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = ThemeColors.brand
                                )
                            ) {
                                Icon(
                                    Icons.Default.ArrowForward,
                                    "转到",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().clickable { onShowUrlBarChange(true) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            // P2 fix: title 为空时也渲染 URL 行 — 此前 title 空则整列不显示, 地址栏空白
                            if (activeTab.title.isNotBlank()) {
                                Text(
                                    activeTab.title,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                decodeUrlForDisplay(activeTab.url).take(60),
                                style = MaterialTheme.typography.labelSmall,
                                color = ThemeColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                Row {
                    // 宽屏常驻导航按钮 (实现见 BrowserNavIcons, 400 行拆分批次 3)
                    BrowserNavIcons(
                        isWide = isWide,
                        canGoBack = activeTab.canGoBack,
                        canGoForward = activeTab.canGoForward,
                        homeUrl = homeUrl,
                        onNavigate = onNavigate,
                        onBack = onBack,
                        onGoForward = onGoForward,
                        onRefresh = onRefresh
                    )
                    // Tab count badge (phone only, hidden when 1 tab)
                    if (!isWide) {
                        IconButton(
                            onClick = onShowTabs,
                            modifier = Modifier.size(40.dp)
                        ) {
                            if (tabs.size > 1) {
                                BadgedBox(
                                    badge = {
                                        Badge(containerColor = ThemeColors.textSecondary) {
                                            Text("${tabs.size}")
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.List, "标签页", tint = ThemeColors.brand)
                                }
                            } else {
                                Icon(Icons.Default.List, "标签页", tint = ThemeColors.brand)
                            }
                        }
                    }
                }
            },
            actions = {
                // Bookmark star — 打开收藏夹
                IconButton(
                    onClick = onShowBookmarks,
                    modifier = Modifier.size(36.dp)
                ) {
                    @Suppress("DEPRECATION")
                    Icon(
                        Icons.Default.Star,
                        "收藏夹",
                        tint = if (isBookmarked) ThemeColors.brand else ThemeColors.textSecondary
                    )
                }
                // Menu button with dropdown
                var menuExpanded by remember { mutableStateOf(false) }
                // 页面级功能 (翻译/查找/阅读) 的可用性只看「有没有页面」, 不看标题是否为空
                val hasPage = activeTab.url.isNotBlank() && !activeTab.url.startsWith("about:")
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, "菜单", tint = ThemeColors.brand)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("刷新") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick = { onRefresh(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("新标签页") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { onNewTab(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isBookmarked) "取消收藏当前页" else "收藏当前页") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            enabled = activeTab.url.isNotBlank(),
                            onClick = { onToggleBookmark(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("翻译页面") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            enabled = hasPage,
                            onClick = { onShowTranslate(); menuExpanded = false }
                        )
                        @Suppress("DEPRECATION")
                        DropdownMenuItem(
                            text = { Text("页面查找") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            enabled = hasPage,
                            onClick = { onShowFind(); menuExpanded = false }
                        )
                        @Suppress("DEPRECATION")
                        DropdownMenuItem(
                            text = { Text("阅读模式") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            enabled = hasPage,
                            onClick = { onShowReader(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (adBlockEnabled) "广告拦截: 开" else "广告拦截: 关"
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (adBlockEnabled) Icons.Default.Star
                                    else Icons.Default.Close,
                                    null
                                )
                            },
                            onClick = { onAdBlockToggle(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("后退") },
                            leadingIcon = { Icon(Icons.Default.ArrowBack, null) },
                            enabled = activeTab.canGoBack,
                            onClick = { onGoBack(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("前进") },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, null) },
                            enabled = activeTab.canGoForward,
                            onClick = { onGoForward(); menuExpanded = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("历史记录") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            onClick = { onShowHistory(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("密码管理") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            onClick = { onShowPasswords(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("分享链接") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = { onShare(activeTab.url); menuExpanded = false }
                        )
                        if (ctx.packageManager.getLaunchIntentForPackage("com.mengpaw.shell") != null) {
                            DropdownMenuItem(
                                text = { Text("发送给 MengPaw Agent") },
                                leadingIcon = { Icon(Icons.Default.Send, null) },
                                onClick = {
                                    onSendToAgent(activeTab.url)
                                    menuExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("提炼网页要点") },
                                leadingIcon = { Icon(Icons.Default.Star, null) },
                                onClick = {
                                    onExtractToAgent(activeTab.url, activeTab.title)
                                    menuExpanded = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("设置") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { onShowSettings(); menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("智能体协同") },
                            leadingIcon = { Icon(Icons.Default.Face, null) },
                            onClick = { onShowAgentSettings(); menuExpanded = false }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("关闭标签 Close Tab") },
                            leadingIcon = { Icon(Icons.Default.Close, null) },
                            enabled = tabs.size > 1,
                            onClick = { onCloseTab(); menuExpanded = false }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }
}
