// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.ui.BrowserAgentSettingsDialog
import com.mengpaw.browser.ui.BrowserBookmarkDialog
import com.mengpaw.browser.ui.BrowserFindBar
import com.mengpaw.browser.ui.BrowserHistoryDialog
import com.mengpaw.browser.ui.BrowserImagePickerDialog
import com.mengpaw.browser.ui.BrowserMarkdownViewerDialog
import com.mengpaw.browser.ui.BrowserPasswordDialog
import com.mengpaw.browser.ui.BrowserPdfViewerDialog
import com.mengpaw.browser.ui.BrowserReaderMode
import com.mengpaw.browser.ui.BrowserSettingsDialog
import com.mengpaw.browser.ui.BrowserTabDialog
import com.mengpaw.browser.ui.BrowserTranslateDialog
import com.mengpaw.browser.util.downloadImageSuspending
import kotlinx.coroutines.launch

/**
 * 浏览器全屏对话框渲染层（自 BrowserActivity 拆出 — 400 行文件拆分批次 2）。
 * Settings/Agent 设置/历史/密码/翻译/图片选择/查找/阅读模式/标签页/书签/Markdown 预览。
 * 全部参数显式传入 (状态提升) — 仅渲染, 不改任何状态, 回调由调用方注入。
 *
 * v0.10.2: 图片下载在此层用**长生命周期作用域**启动 (旧实现用弹窗自身作用域, 关闭即取消),
 * 成败都给出真实反馈; 收藏列表由 BrowserApp 以 state 传入, 增删即时刷新。
 */
@Composable
internal fun BrowserAppDialogs(
    prefs: BrowserPrefs,
    webViewMap: Map<Int, WebView>,
    activeTab: TabState,
    activeTabId: Int,
    tabs: List<TabState>,
    isWide: Boolean,
    isColdStart: Boolean,
    maxTabs: Int,
    historyStore: HistoryStore,
    historyEnabled: Boolean,
    images: List<DetectedImage>,
    bookmarks: List<String>,
    onAddBookmark: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    // ── Settings dialog ──
    showSettings: Boolean, onDismissSettings: () -> Unit,
    adBlockEnabled: Boolean, onAdBlockToggled: (Boolean) -> Unit,
    darkMode: Boolean, onDarkModeToggled: (Boolean) -> Unit,
    searchEngine: SearchEngine, onDefaultEngineChanged: (SearchEngine) -> Unit,
    onOpenCoolApk: () -> Unit, onOpenApkCombo: () -> Unit,
    // ── Agent Collaboration Settings ──
    showAgentSettings: Boolean, onDismissAgentSettings: () -> Unit,
    autoInjectBridge: Boolean, onAutoInjectToggled: (Boolean) -> Unit,
    screenshotMaxH: Int, onScreenshotMaxHChanged: (Int) -> Unit,
    // ── History dialog ──
    showHistory: Boolean, onDismissHistory: () -> Unit, onHistoryEnabledToggle: (Boolean) -> Unit,
    // ── Password dialog ──
    showPasswords: Boolean, onDismissPasswords: () -> Unit,
    // ── Translate dialog ──
    showTranslate: Boolean, onDismissTranslate: () -> Unit,
    // ── Image picker ──
    showImages: Boolean, onDismissImages: () -> Unit,
    // ── Find-in-page bar / Reader mode ──
    showFind: Boolean, onDismissFind: () -> Unit,
    showReader: Boolean, onDismissReader: () -> Unit,
    // ── Tab dialog (phone) ──
    showTabs: Boolean, onDismissTabs: () -> Unit,
    onTabSelected: (Int, Boolean) -> Unit, onTabClose: (Int) -> Unit, onNewTab: () -> Unit,
    // ── Bookmarks / Markdown viewer ──
    showBookmarks: Boolean, onDismissBookmarks: () -> Unit,
    onNavigate: (String) -> Unit,
    showMdViewer: Boolean, mdContent: String, onDismissMdViewer: () -> Unit,
    // ── PDF viewer ──
    showPdfViewer: Boolean, pdfFile: String?, pdfTitle: String, onDismissPdfViewer: () -> Unit
) {
    val ctx = LocalContext.current
    // 存活期 = BrowserApp 组合期 (远长于任一弹窗) — 下载不会被弹窗关闭取消
    val appScope = rememberCoroutineScope()

    // ── Settings dialog ──
    BrowserSettingsDialog(
        visible = showSettings,
        onDismiss = onDismissSettings,
        prefs = prefs,
        adBlockEnabled = adBlockEnabled,
        onAdBlockToggled = onAdBlockToggled,
        darkMode = darkMode,
        onDarkModeToggled = onDarkModeToggled,
        searchEngine = searchEngine,
        onDefaultEngineChanged = onDefaultEngineChanged,
        webViewVersion = remember {
            try { WebView.getCurrentWebViewPackage()?.versionName ?: "" } catch (_: Exception) { "" }
        },
        appVersion = BuildConfig.VERSION_NAME,
        onOpenCoolApk = onOpenCoolApk,
        onOpenApkCombo = onOpenApkCombo
    )

    // ── Agent Collaboration Settings ──
    BrowserAgentSettingsDialog(
        visible = showAgentSettings,
        onDismiss = onDismissAgentSettings,
        autoInjectBridge = autoInjectBridge,
        screenshotMaxH = screenshotMaxH,
        onAutoInjectToggled = onAutoInjectToggled,
        onScreenshotMaxHChanged = onScreenshotMaxHChanged
    )

    // ── History dialog ──
    BrowserHistoryDialog(
        visible = showHistory,
        onDismiss = onDismissHistory,
        historyStore = historyStore,
        historyEnabled = historyEnabled,
        onHistoryEnabledToggle = onHistoryEnabledToggle,
        onNavigate = onNavigate
    )

    // ── Password dialog ──
    BrowserPasswordDialog(
        visible = showPasswords,
        onDismiss = onDismissPasswords
    )

    // ── Translate dialog ──
    BrowserTranslateDialog(
        visible = showTranslate,
        onDismiss = onDismissTranslate,
        activeTab = activeTab,
        webView = webViewMap[activeTabId]
    )

    // ── Image picker (保存: 交给长生命周期作用域 + 真实成败反馈) ──
    BrowserImagePickerDialog(
        visible = showImages && images.isNotEmpty(),
        onDismiss = onDismissImages,
        images = images,
        onSave = { src ->
            appScope.launch {
                val path = downloadImageSuspending(ctx, src)
                Toast.makeText(
                    ctx,
                    if (path != null) "已保存: $path" else "保存失败 (网络或图片格式不支持)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    )

    // ── Find-in-page bar ──
    BrowserFindBar(
        webView = webViewMap[activeTabId],
        visible = showFind && !isColdStart,
        onDismiss = onDismissFind
    )

    // ── Reader mode dialog ──
    BrowserReaderMode(
        webView = webViewMap[activeTabId],
        pageTitle = activeTab.title.ifBlank { activeTab.url },
        visible = showReader,
        onDismiss = onDismissReader
    )

    // ── Tab dialog (phone) ──
    if (!isWide) {
        BrowserTabDialog(
            visible = showTabs,
            onDismiss = onDismissTabs,
            tabs = tabs,
            activeTabId = activeTabId,
            webViewMap = webViewMap,
            onAddBookmark = onAddBookmark,
            onTabSelected = onTabSelected,
            onTabClose = onTabClose,
            onNewTab = onNewTab,
            maxTabs = maxTabs
        )
    }

    // ── Bookmarks ──
    BrowserBookmarkDialog(
        visible = showBookmarks,
        onDismiss = onDismissBookmarks,
        bookmarks = bookmarks,
        currentUrl = activeTab.url,
        onAdd = onAddBookmark,
        onRemove = onRemoveBookmark,
        onNavigate = onNavigate
    )

    // ── Markdown viewer ──
    BrowserMarkdownViewerDialog(
        visible = showMdViewer && mdContent.isNotBlank(),
        onDismiss = onDismissMdViewer,
        content = mdContent
    )

    // ── PDF viewer ──
    BrowserPdfViewerDialog(
        visible = showPdfViewer,
        onDismiss = onDismissPdfViewer,
        filePath = pdfFile,
        title = pdfTitle
    )
}
