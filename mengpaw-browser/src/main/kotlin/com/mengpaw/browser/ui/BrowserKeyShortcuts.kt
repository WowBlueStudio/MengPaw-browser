// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * 桌面式键盘快捷键 (v0.10.2 新增)。
 *
 * 本应用在宽屏/平板布局里明确面向「键盘 + 鼠标」场景 (见 BrowserTopBar 的常驻导航按钮、
 * DesktopTabBar)，但此前**全仓库没有任何按键处理**: 键盘用户只能打字 + 回车, 其它操作全靠点击。
 *
 * 绑定 (预览阶段拦截, 只吃自己认识组合键, 其余原样放行给输入框/页面):
 *   Ctrl/⌘ + L      聚焦地址栏 (并全选)
 *   Ctrl/⌘ + R / F5 刷新
 *   Alt + ←/→       后退 / 前进
 *   Ctrl/⌘ + T      新标签页
 *   Ctrl/⌘ + W      关闭当前标签
 *   Ctrl/⌘ + F      页面查找
 *   Ctrl/⌘ + D      收藏/取消收藏当前页
 *   Esc             逐层关闭: 查找 → 阅读 → 翻译 → 弹窗 → 收起地址栏
 *
 * 注: 普通回车不在此处理 — 单行输入框的物理回车由框架映射为 ImeAction.Search,
 * 与软键盘「搜索」键同路径 (避免重复触发导航)。
 */
internal data class BrowserShortcutActions(
    val onFocusUrlBar: () -> Unit,
    val onReload: () -> Unit,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onNewTab: () -> Unit,
    val onCloseTab: () -> Unit,
    val onFind: () -> Unit,
    val onToggleBookmark: () -> Unit,
    val onEscape: () -> Unit
)

/** 见 [BrowserShortcutActions] — 加在根布局上 (预览阶段先于焦点元素收到按键)。 */
internal fun Modifier.browserShortcuts(actions: BrowserShortcutActions): Modifier =
    onPreviewKeyEvent { e: KeyEvent ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val alt = e.isAltPressed
        when {
            ctrl && e.key == Key.L -> { actions.onFocusUrlBar(); true }
            ctrl && e.key == Key.R -> { actions.onReload(); true }
            e.key == Key.F5 -> { actions.onReload(); true }
            alt && e.key == Key.DirectionLeft -> { actions.onBack(); true }
            alt && e.key == Key.DirectionRight -> { actions.onForward(); true }
            ctrl && e.key == Key.T -> { actions.onNewTab(); true }
            ctrl && e.key == Key.W -> { actions.onCloseTab(); true }
            ctrl && e.key == Key.F -> { actions.onFind(); true }
            ctrl && e.key == Key.D -> { actions.onToggleBookmark(); true }
            e.key == Key.Escape -> { actions.onEscape(); true }
            else -> false
        }
    }
