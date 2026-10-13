// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.browser.data.PendingLoad
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.util.PdfUtil
import com.mengpaw.browser.ui.BrowserShortcutActions
import com.mengpaw.browser.ui.BrowserTopBar
import com.mengpaw.browser.ui.DesktopTabBar
import com.mengpaw.browser.ui.browserShortcuts
import com.mengpaw.design.theme.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 浏览器主 UI（自 BrowserActivity 拆出 — 400 行文件拆分批次的第 2/3 批）。
 * 状态 + Scaffold 骨架; 导航动作委托 [browserActions], 浮层状态委托 [BrowserOverlayState],
 * 对话框渲染委托 [BrowserAppDialogs], 内容区委托 [BrowserContentArea]。
 *
 * v0.10.2 导航/交互修复:
 * ① 用户导航走「状态更新 + 待办导航」双通道 — WebView 未就绪时不再静默丢失 (转不到);
 * ② 浮层统一逐层关闭语义 (返回键/Esc), 查找栏此前拦不住返回键;
 * ③ 桌面快捷键 (Ctrl+L/R/T/W/F/D、Alt+←/→、F5、Esc);
 * ④ 收藏夹改为 state (增删即时刷新), 去掉只写不读的 quickClick/截图质量开关。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun BrowserApp(
    initialUrl: String? = null,
    initialMdContent: String? = null,
    initialPdfSource: String? = null,
    initialPdfTitle: String = ""
) {
    val ctx = LocalContext.current
    val prefs = remember { BrowserPrefs(ctx) }
    val isWide = LocalConfiguration.current.screenWidthDp >= 600
    // 平板判据用**最小宽度**（Android 官方口径）: 手机横屏 screenWidthDp 也会 ≥600,
    // 按宽度判会把手机横屏当成平板 → 误请求桌面版网页。
    val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val maxTabs = 5
    // Scroll-aware toolbar animation
    var scrollOffset by remember { mutableStateOf(0) }
    val urlBarFocusRequester = remember { FocusRequester() }
    val overlays = remember { BrowserOverlayState() }

    // Restore tabs from previous session, or start fresh
    var tabs by remember {
        val savedUrls = prefs.savedTabUrls
        if (initialUrl == null && savedUrls.isNotEmpty()) {
            mutableStateOf(savedUrls.mapIndexed { i, url -> TabState(id = i, url = url) })
        } else {
            mutableStateOf(listOf(TabState(id = 0, url = initialUrl ?: "")))
        }
    }
    var activeTabId by remember {
        val savedUrls = prefs.savedTabUrls
        val savedActive = prefs.savedActiveTabId
        if (initialUrl == null && savedUrls.isNotEmpty() && savedActive in savedUrls.indices) {
            mutableStateOf(savedActive)
        } else {
            mutableStateOf(0)
        }
    }
    var isColdStart by remember { mutableStateOf(initialUrl == null && prefs.savedTabUrls.isEmpty()) }

    // Persist tab session on every change
    LaunchedEffect(tabs.map { it.url }, activeTabId) {
        prefs.savedTabUrls = tabs.filter { it.url.isNotBlank() }.map { it.url }
        prefs.savedActiveTabId = activeTabId
    }
    var searchQuery by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<DetectedImage>>(emptyList()) }
    var mdContent by remember { mutableStateOf("") }
    // PDF 预览: pending 源在 LaunchedEffect 中取数/写缓存后弹窗 (网络与本地 intent 共用)
    var pendingPdfSource by remember { mutableStateOf(initialPdfSource) }
    var pendingPdfTitle by remember { mutableStateOf(initialPdfTitle) }
    var pdfFile by remember { mutableStateOf<String?>(null) }
    var pdfTitle by remember { mutableStateOf("") }
    var historyEnabled by remember { mutableStateOf(prefs.historyEnabled) }
    // 收藏夹以 state 承载 — 增删后顶栏星标/收藏列表/新标签页快捷入口即时刷新
    var bookmarks by remember { mutableStateOf(prefs.bookmarks) }
    // 待办导航: 目标 WebView 尚未创建时先记下, 由内容区在视图就绪时消费 (见 PendingLoad)
    var pendingLoad by remember { mutableStateOf<PendingLoad?>(null) }
    var navSeq by remember { mutableStateOf(0L) }

    // Auto-open Markdown viewer if launched with .md file
    LaunchedEffect(initialMdContent) {
        if (!initialMdContent.isNullOrBlank()) {
            mdContent = initialMdContent
            overlays.showMdViewer = true
        }
    }
    // PDF 预览: 消费 pending 源 → IO 取数写缓存 → 弹窗 (超限/失败 Toast)
    LaunchedEffect(pendingPdfSource) {
        val src = pendingPdfSource
        if (src != null) {
            val file = withContext(Dispatchers.IO) { PdfUtil.stagePdfForPreview(ctx, src) }
            if (file != null) {
                pdfFile = file
                pdfTitle = pendingPdfTitle
                overlays.showPdfViewer = true
            } else {
                Toast.makeText(ctx, "无法打开 PDF (文件过大或读取失败)", Toast.LENGTH_SHORT).show()
            }
            pendingPdfSource = null
        }
    }
    val historyStore = remember { HistoryStore(ctx) }
    var searchEngine by remember { mutableStateOf(prefs.defaultEngine()) }
    var adBlockEnabled by remember { mutableStateOf(prefs.adBlockEnabled) }
    var darkMode by remember { mutableStateOf(prefs.darkMode) }
    var autoInjectBridge by remember { mutableStateOf(prefs.autoInjectBridge) }
    var screenshotMaxH by remember { mutableStateOf(prefs.screenshotMaxHeight) }
    val webViewMap = remember { mutableMapOf<Int, WebView>() }
    // Sync WebView map to Activity for system back-key navigation
    // P1 fix: 同步浏览器状态桥 — 内置 browser.* 命令 (BuiltinBrowserPlugin) 经它操作真实 tab 状态
    SideEffect {
        val activity = ctx as? BrowserActivity ?: return@SideEffect
        activity.webViewMapRef = webViewMap
        // 标签上限单一来源: 命令层 (browser.tab/tab.open 的范围校验与表头) 与 UI 同源
        com.mengpaw.browser.plugin.BuiltinBrowserPlugin.maxTabs = { maxTabs }
        activity.browserState = BrowserActivity.createStateBridge(
            tabs = { tabs }, setTabs = { tabs = it },
            activeTabId = { activeTabId }, setActiveTabId = { activeTabId = it },
            setIsColdStart = { isColdStart = it },
            webViewMap = webViewMap, maxTabs = maxTabs,
            setPendingLoad = { pendingLoad = it },
            nextSeq = { navSeq += 1; navSeq }
        )
    }

    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.first()

    /**
     * v0.10.4: 立即置加载态 —— 供刷新/前进/后退等"点击即应有反馈"的按钮使用。
     * 地址栏导航已由 [browserActions] 的 navigate 在同一帧置位；页面内点击由
     * WebViewFactory.shouldOverrideUrlLoading 置位。这里补顶栏按钮与快捷键路径。
     */
    val startLoading: (Int) -> Unit = { id ->
        tabs = tabs.map { if (it.id == id) it.copy(isLoading = true) else it }
    }

    // 导航/返回/标签/收藏动作 — 读取项以 getter 传入, 首帧实例也不会读到过期状态
    val actions = browserActions(
        ctx = ctx, prefs = prefs, webViewMap = webViewMap,
        tabs = { tabs }, setTabs = { tabs = it },
        activeTabId = { activeTabId }, setActiveTabId = { activeTabId = it },
        isColdStart = { isColdStart }, setIsColdStart = { isColdStart = it },
        overlays = overlays,
        searchEngine = { searchEngine }, historyEnabled = { historyEnabled }, historyStore = historyStore,
        maxTabs = maxTabs,
        bookmarks = { bookmarks }, setBookmarks = { bookmarks = it },
        setPendingLoad = { pendingLoad = it }, nextSeq = { navSeq += 1; navSeq }
    )

    // Inject dark mode CSS after page loads (URL change + not loading + darkMode on)
    LaunchedEffect(activeTab.url, activeTab.isLoading, darkMode) {
        if (darkMode && !activeTab.isLoading && activeTab.url.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            webViewMap[activeTabId]?.evaluateJavascript(DARK_MODE_CSS, null)
        }
    }

    // 新页面回到顶部: 滚动收起工具栏后, 导航到新页必须让地址栏重新出现 (否则手机端无从输入)
    LaunchedEffect(activeTab.url) { scrollOffset = 0 }

    DisposableEffect(Unit) {
        val activity = ctx as? BrowserActivity
        activity?.onSystemBack = actions.handleBack
        activity?.onOpenUrl = { url -> if (url != null) actions.navigate(url) }
        activity?.onOpenMd = { _, _, md ->
            mdContent = md
            overlays.showMdViewer = true
        }
        activity?.onOpenPdf = { src, t ->
            pendingPdfSource = src
            pendingPdfTitle = t
        }
        onDispose {
            activity?.onSystemBack = null
            activity?.onOpenUrl = null
            activity?.onOpenMd = null
            activity?.onOpenPdf = null
        }
    }

    val shortcuts = BrowserShortcutActions(
        onFocusUrlBar = { overlays.showUrlBar = true; urlBarFocusRequester.requestFocus() },
        onReload = { startLoading(activeTabId); webViewMap[activeTabId]?.reload() },
        onBack = actions.handleBack,
        onForward = { startLoading(activeTabId); webViewMap[activeTabId]?.goForward() },
        onNewTab = actions.openNewTab,
        onCloseTab = actions.closeTab,
        onFind = { overlays.showFind = true },
        onToggleBookmark = actions.toggleBookmark,
        onEscape = { overlays.dismissTop() }
    )

    Scaffold(
        modifier = Modifier.fillMaxSize().browserShortcuts(shortcuts),
        topBar = {
            if (!isColdStart) {
                BrowserTopBar(
                    // 手机滚动页面时收起工具栏; 编辑地址栏或宽屏时保持常驻
                    visible = isWide || overlays.showUrlBar || scrollOffset < 200,
                    showUrlBar = overlays.showUrlBar,
                    onShowUrlBarChange = { overlays.showUrlBar = it },
                    isWide = isWide,
                    activeTab = activeTab,
                    activeTabId = activeTabId,
                    tabs = tabs,
                    adBlockEnabled = adBlockEnabled,
                    isBookmarked = activeTab.url in bookmarks,
                    urlBarFocusRequester = urlBarFocusRequester,
                    homeUrl = prefs.homeUrl,
                    onNavigate = actions.navigate,
                    onBack = actions.handleBack,
                    onShowTabs = { overlays.showTabs = !overlays.showTabs },
                    onShowBookmarks = { overlays.showBookmarks = true },
                    onToggleBookmark = actions.toggleBookmark,
                    onRefresh = { startLoading(activeTabId); webViewMap[activeTabId]?.reload() },
                    onGoForward = { startLoading(activeTabId); webViewMap[activeTabId]?.goForward() },
                    onGoBack = { startLoading(activeTabId); webViewMap[activeTabId]?.goBack() },
                    onNewTab = actions.openNewTab,
                    onCloseTab = actions.closeTab,
                    onShowTranslate = { overlays.showTranslate = true },
                    onShowFind = { overlays.showFind = true },
                    onShowReader = { overlays.showReader = true },
                    onAdBlockToggle = {
                        adBlockEnabled = !adBlockEnabled
                        prefs.adBlockEnabled = adBlockEnabled
                        webViewMap[activeTabId]?.reload()
                    },
                    onShowHistory = { overlays.showHistory = true },
                    onShowPasswords = { overlays.showPasswords = true },
                    onShare = { url ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, url)
                        }
                        ctx.startActivity(Intent.createChooser(intent, "分享到"))
                    },
                    onSendToAgent = { url -> (ctx as? BrowserActivity)?.sendToAgent(url, activeTab.title, extract = false) },
                    onExtractToAgent = { url, title -> (ctx as? BrowserActivity)?.sendToAgent(url, title, extract = true) },
                    onShowSettings = { overlays.showSettings = true },
                    onShowAgentSettings = { overlays.showAgentSettings = true }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // ── Desktop tab bar ──
            if (isWide && !isColdStart) {
                DesktopTabBar(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    maxTabs = maxTabs,
                    webViewMap = webViewMap,
                    onAddBookmark = actions.addBookmark,
                    onTabSelected = { id ->
                        activeTabId = id
                        isColdStart = tabs.find { it.id == id }?.url.isNullOrBlank() ?: true
                    },
                    onTabClose = { id ->
                        destroyWebViewSafe(webViewMap.remove(id))
                        tabs = tabs.filter { it.id != id }
                        // v0.10.3: 空表兜底（旧实现直接 first()，只剩一个标签时该路径会抛异常）
                        if (tabs.isEmpty()) {
                            tabs = listOf(TabState(id = 0)); activeTabId = 0; isColdStart = true
                        } else if (activeTabId == id) activeTabId = tabs.first().id
                    },
                    onNewTab = actions.openNewTab
                )
            }
            // ── Loader (material-colored progress bar) ──
            if (activeTab.isLoading && !isColdStart) {
                LinearProgressIndicator(
                    { activeTab.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ThemeColors.brand,
                    trackColor = ThemeColors.brand.copy(alpha = 0.12f)
                )
            }
            // ── Content (NewTabPage / 多标签 WebView — 实现见 BrowserContentArea) ──
            BrowserContentArea(
                isColdStart = isColdStart,
                tabs = tabs,
                activeTab = activeTab,
                activeTabId = activeTabId,
                webViewMap = webViewMap,
                isWide = isWide,
                desktopSite = isTablet,
                adBlockEnabled = { adBlockEnabled },
                autoInjectBridge = { autoInjectBridge },
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                searchEngine = searchEngine,
                onSearchEngineCycle = {
                    val engines = prefs.enabledEngines()
                    if (engines.isNotEmpty()) {
                        val idx = engines.indexOfFirst { it.key == searchEngine.key }
                        searchEngine = engines.getOrElse((idx + 1) % engines.size) { engines.first() }
                        prefs.setDefaultEngine(searchEngine)
                    }
                },
                bookmarks = bookmarks,
                updateTab = actions.updateTab,
                onImagesDetected = { imgs -> images = imgs; overlays.showImages = true },
                onScroll = { dy -> scrollOffset = (scrollOffset + dy).coerceIn(0, 500) },
                onNavigate = actions.navigate,
                onShowBookmarks = { overlays.showBookmarks = true },
                onShowMarkdown = { text -> mdContent = text; overlays.showMdViewer = true },
                onShowPdf = { src, t -> pendingPdfSource = src; pendingPdfTitle = t },
                pendingLoad = pendingLoad,
                onPendingLoadConsumed = { pendingLoad = null }
            )
            // ── 全屏对话框层 (Settings/历史/密码/翻译/图片/查找/阅读/标签页/书签/Markdown) ──
            BrowserAppDialogs(
                prefs = prefs,
                webViewMap = webViewMap,
                activeTab = activeTab,
                activeTabId = activeTabId,
                tabs = tabs,
                isWide = isWide,
                isColdStart = isColdStart,
                maxTabs = maxTabs,
                historyStore = historyStore,
                historyEnabled = historyEnabled,
                images = images,
                bookmarks = bookmarks,
                onAddBookmark = actions.addBookmark,
                onRemoveBookmark = actions.removeBookmark,
                // Settings dialog
                showSettings = overlays.showSettings, onDismissSettings = { overlays.showSettings = false },
                adBlockEnabled = adBlockEnabled, onAdBlockToggled = { adBlockEnabled = it; prefs.adBlockEnabled = it },
                darkMode = darkMode, onDarkModeToggled = {
                    darkMode = it
                    prefs.darkMode = it
                    // v0.10.3: 关闭时显式移除已注入的暗色样式（此前只 reload，同页残留暗色）
                    val wv = webViewMap[activeTabId]
                    if (wv != null) {
                        if (it) wv.evaluateJavascript(DARK_MODE_CSS, null)
                        else wv.evaluateJavascript(DARK_MODE_REMOVE_JS, null)
                    }
                },
                searchEngine = searchEngine, onDefaultEngineChanged = { searchEngine = it },
                onOpenCoolApk = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.coolapk.com/apk/com.google.android.webview"))
                    try { ctx.startActivity(intent) } catch (_: Exception) { }
                },
                onOpenApkCombo = {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://apkcombo.com/zh/android-system-webview/com.google.android.webview/"))
                    try { ctx.startActivity(intent) } catch (_: Exception) { }
                },
                // Agent Collaboration Settings
                showAgentSettings = overlays.showAgentSettings, onDismissAgentSettings = { overlays.showAgentSettings = false },
                autoInjectBridge = autoInjectBridge, onAutoInjectToggled = { autoInjectBridge = it; prefs.autoInjectBridge = it },
                screenshotMaxH = screenshotMaxH, onScreenshotMaxHChanged = { screenshotMaxH = it; prefs.screenshotMaxHeight = it },
                // History dialog
                showHistory = overlays.showHistory, onDismissHistory = { overlays.showHistory = false },
                onHistoryEnabledToggle = { historyEnabled = it; prefs.historyEnabled = it },
                // Password dialog
                showPasswords = overlays.showPasswords, onDismissPasswords = { overlays.showPasswords = false },
                // Translate dialog
                showTranslate = overlays.showTranslate, onDismissTranslate = { overlays.showTranslate = false },
                // Image picker
                showImages = overlays.showImages, onDismissImages = { overlays.showImages = false },
                // Find / Reader
                showFind = overlays.showFind, onDismissFind = { overlays.showFind = false },
                showReader = overlays.showReader, onDismissReader = { overlays.showReader = false },
                // Tab dialog (phone)
                showTabs = overlays.showTabs, onDismissTabs = { overlays.showTabs = false },
                onTabSelected = { id, cold -> activeTabId = id; isColdStart = cold; overlays.showTabs = false },
                onTabClose = { id ->
                    // P1 fix: 先脱离视图树再 destroy (attached destroy 风险)
                    destroyWebViewSafe(webViewMap.remove(id))
                    tabs = tabs.filter { it.id != id }
                    if (tabs.isEmpty()) {
                        tabs = listOf(TabState(id = 0)); activeTabId = 0; isColdStart = true; overlays.showTabs = false
                    } else if (activeTabId == id) activeTabId = tabs.first().id
                },
                onNewTab = actions.openNewTab,
                // Bookmarks / Markdown viewer
                showBookmarks = overlays.showBookmarks, onDismissBookmarks = { overlays.showBookmarks = false },
                onNavigate = actions.navigate,
                showMdViewer = overlays.showMdViewer, mdContent = mdContent,
                onDismissMdViewer = { overlays.showMdViewer = false; mdContent = "" },
                // PDF viewer
                showPdfViewer = overlays.showPdfViewer, pdfFile = pdfFile, pdfTitle = pdfTitle,
                onDismissPdfViewer = { overlays.showPdfViewer = false; pdfFile = null }
            )
        }
    }
}
