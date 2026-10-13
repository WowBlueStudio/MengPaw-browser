// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.PendingLoad
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.ui.NewTabPage
import com.mengpaw.browser.util.PdfUtil
import com.mengpaw.browser.web.applyUserAgent
import com.mengpaw.browser.web.createWebView
import com.mengpaw.browser.web.loadWhenLaidOut
import com.mengpaw.browser.web.loadWhenLaidOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浏览器主内容区（自 BrowserApp 拆出 — 400 行文件拆分批次 2）。
 * 冷启动 NewTabPage / 常态 WebView 多标签渲染 (pre-render + visibility-toggle)。
 *
 * v0.10.2 修复:
 * ① [pendingLoad] 消费 — 用户导航在 WebView 尚未就绪时不再静默丢失 (转不到的问题);
 * ② 广告拦截/桥接注入改为**动态读取** (lambda) — 设置里切换后无需重建 WebView;
 * ③ 激活标签的 WebView 恢复 resume 状态 (后台 onTrimMemory 曾整体 onPause, 不 resume 可能不再渲染)。
 *
 * @param onScroll 滚动偏移回调 (工具栏动画)
 * @param onShowMarkdown 站内 .md URL 拉取成功后的展示回调 (BrowserApp 注入 mdContent/showMdViewer)
 */
@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
internal fun ColumnScope.BrowserContentArea(
    isColdStart: Boolean,
    tabs: List<TabState>,
    activeTab: TabState,
    activeTabId: Int,
    webViewMap: MutableMap<Int, WebView>,
    isWide: Boolean,
    /** 平板设备（`smallestScreenWidthDp >= 600`）→ 请求桌面版网页；手机（含横屏）→ 移动版 */
    desktopSite: Boolean,
    adBlockEnabled: () -> Boolean,
    autoInjectBridge: () -> Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchEngine: SearchEngine,
    onSearchEngineCycle: () -> Unit,
    /** 收藏夹 state (新标签页快捷入口) */
    bookmarks: List<String>,
    updateTab: (Int, (TabState) -> TabState) -> Unit,
    onImagesDetected: (List<DetectedImage>) -> Unit,
    onScroll: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onShowBookmarks: () -> Unit,
    onShowMarkdown: (String) -> Unit,
    onShowPdf: (String, String) -> Unit,
    /** 站点 window.open 弹窗 URL → 由 BrowserApp 开新标签承接（累计修改） */
    onNewWindow: (String) -> Unit = {},
    pendingLoad: PendingLoad? = null,
    onPendingLoadConsumed: () -> Unit = {}
) {
    // v0.10.3: 非激活标签挂起 (此前只做 alpha(0)/height(0) 隐藏, WebView 仍处 resume —
    // 后台标签的音视频继续播放/持续渲染, 白耗内存与电量)。激活项恢复 resume。
    LaunchedEffect(activeTabId, tabs.size, isColdStart) {
        if (!isColdStart) {
            webViewMap.forEach { (id, wv) ->
                try { if (id == activeTabId) wv.onResume() else wv.onPause() } catch (_: Exception) { }
            }
        }
    }

    // 已消费的待办导航序号 — update 块可能因重组被多次调用, 用序号去重避免重复导航。
    // v0.10.3: 提升到分支之外 (旧实现放在 else 分支内, 冷启动↔常规渲染切换时去重基线丢失)
    val consumedSeq = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(-1L)
    }

    // 已"下发过首屏加载"的 tabId（累计修改）— update 块会因重组被反复调用,
    // 用集合保证每个标签的首屏加载只下发一次。放在分支之外与 consumedSeq 同理:
    // 冷启动↔常规渲染切换时不丢基线。
    val initialLoadIssued = androidx.compose.runtime.remember { mutableSetOf<Int>() }

    if (isColdStart) {
        NewTabPage(
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            searchEngine = searchEngine,
            onSearchEngineCycle = onSearchEngineCycle,
            isWide = isWide,
            bookmarks = bookmarks,
            onNavigate = onNavigate,
            onShowBookmarks = onShowBookmarks
        )
    } else {
        // WebView with pull-to-refresh
        val pullState = rememberPullRefreshState(
            refreshing = activeTab.isLoading,
            // v0.10.4: 下拉刷新同样"点击即转" — 先置加载态再 reload
            onRefresh = {
                updateTab(activeTabId) { it.copy(isLoading = true) }
                webViewMap[activeTabId]?.reload()
            }
        )
        // Pre-render: keep all WebViews alive, visibility-toggle instead of destroy
        Box(Modifier.weight(1f).pullRefresh(pullState)) {
            // 站内 .md URL → 拉取 → 预览 (与 OPEN_MD 通道共用 mdContent/showMdViewer 状态)
            val mdFetchScope = rememberCoroutineScope()
            val appCtx = LocalContext.current
            fun fetchMarkdownUrl(url: String) {
                mdFetchScope.launch(Dispatchers.IO) {
                    val text = runCatching { fetchUrlTextTop(url) }.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (text != null) {
                            onShowMarkdown(text)
                        } else {
                            Toast.makeText(appCtx, "无法加载 .md 文档", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            tabs.forEach { tab ->
                val isActive = tab.id == activeTabId
                androidx.compose.runtime.key(tab.id) {
                    AndroidView(
                        factory = { ctx ->
                            val wv = webViewMap[tab.id]
                            if (wv != null) wv
                            else {
                                // 构造期是否加载首屏:
                                // ① 有待办导航 → 不加载, 交给 update 统一消费 (避免重复导航);
                                // ② 非激活标签 → 不加载。它被排布在 alpha(0)+height(0) 下,
                                //    此时加载会让站点 JS 在 0 高度算好并缓存首屏,
                                //    切回激活后永久保持矮条 (见 WebViewLoadTiming.kt)。
                                val loadInitially = isActive && pendingLoad?.tabId != tab.id
                                createWebView(
                                    ctx, tab, isWide,
                                    adBlock = adBlockEnabled,
                                    autoInject = autoInjectBridge,
                                    updateTab = updateTab,
                                    onMediaDetected = onImagesDetected,
                                    onScroll = onScroll,
                                    onMarkdownDetected = { url -> fetchMarkdownUrl(url) },
                                    onPdfDetected = { url -> onShowPdf(url, PdfUtil.displayName(url)) },
                                    loadInitialUrl = loadInitially,
                                    // 平板请求桌面版页面, 手机请求移动版
                                    desktopSite = desktopSite,
                                    // 站点弹窗（window.open）→ 开新标签承接
                                    onNewWindow = onNewWindow
                                ).also { if (loadInitially) initialLoadIssued.add(tab.id) }
                            }
                        },
                        update = { wv ->
                            webViewMap[tab.id] = wv
                            // 累计修改: 形态变化(旋转/折叠展开/分屏)后纠正 UA —
                            // 平板请求桌面版, 手机请求移动版 (已加载页需刷新才重新请求)
                            applyUserAgent(wv, desktopSite)
                            val pending = pendingLoad
                            if (pending != null && pending.tabId == tab.id &&
                                consumedSeq.value != pending.seq && isActive
                            ) {
                                // 待办导航优先 —— 但**只在该标签已激活（有真实尺寸）时消费**：
                                // 否则会重演「0 高度下加载 ⇒ 站点首屏算成矮条」（含 browser.preload
                                // 指向后台标签的情形；此时请求保持待办，切到该标签时再真正加载）。
                                consumedSeq.value = pending.seq
                                initialLoadIssued.add(tab.id)
                                wv.loadWhenLaidOut(pending.url)
                                onPendingLoadConsumed()
                            } else if (isActive && tab.id !in initialLoadIssued &&
                                pending?.tabId != tab.id &&
                                wv.url.isNullOrBlank() && wv.originalUrl.isNullOrBlank()
                            ) {
                                // 该标签刚变为激活且从未加载过 → 补加载 (布局就绪时才真正发起)
                                initialLoadIssued.add(tab.id)
                                wv.loadWhenLaidOut(tab.url)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isActive) Modifier else Modifier.alpha(0f).height(0.dp))
                    )
                }
            }
            PullRefreshIndicator(activeTab.isLoading, pullState, Modifier.align(Alignment.TopCenter))
        }
    }
}
