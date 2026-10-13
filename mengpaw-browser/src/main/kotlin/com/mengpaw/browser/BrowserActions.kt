// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mengpaw.browser.data.BrowserPrefs
import com.mengpaw.browser.data.HistoryStore
import com.mengpaw.browser.data.PendingLoad
import com.mengpaw.browser.data.SearchEngine
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.util.smartNavigate

/**
 * 浮层可见性集中持有 (自 BrowserApp 抽出 — 400 行文件拆分批次 3)。
 *
 * 除「哪个浮层开着」外, 还承载返回键/Esc 的**逐层关闭**语义: 查找栏是内联布局而非 Dialog,
 * 旧实现按返回键会翻页而查找栏留在原地 (v0.10.2 修复)。
 */
internal class BrowserOverlayState {
    var showUrlBar by mutableStateOf(false)
    var showFind by mutableStateOf(false)
    var showReader by mutableStateOf(false)
    var showTranslate by mutableStateOf(false)
    var showImages by mutableStateOf(false)
    var showTabs by mutableStateOf(false)
    var showBookmarks by mutableStateOf(false)
    var showHistory by mutableStateOf(false)
    var showPasswords by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showAgentSettings by mutableStateOf(false)
    var showMdViewer by mutableStateOf(false)
    var showPdfViewer by mutableStateOf(false)

    /** 关闭最上层浮层; 返回 true 表示本次返回/Esc 已被消费。 */
    fun dismissTop(): Boolean = when {
        showFind -> { showFind = false; true }
        showReader -> { showReader = false; true }
        showTranslate -> { showTranslate = false; true }
        showImages -> { showImages = false; true }
        showTabs -> { showTabs = false; true }
        showBookmarks -> { showBookmarks = false; true }
        showHistory -> { showHistory = false; true }
        showPasswords -> { showPasswords = false; true }
        showSettings -> { showSettings = false; true }
        showAgentSettings -> { showAgentSettings = false; true }
        showMdViewer -> { showMdViewer = false; true }
        showPdfViewer -> { showPdfViewer = false; true }
        showUrlBar -> { showUrlBar = false; true }
        else -> false
    }
}

/** [browserActions] 的产物 — 顶层 UI 与快捷键共用的动作集合。 */
internal class BrowserActions(
    val navigate: (String) -> Unit,
    val handleBack: () -> Unit,
    val openNewTab: () -> Unit,
    val closeTab: () -> Unit,
    val updateTab: (Int, (TabState) -> TabState) -> Unit,
    val addBookmark: (String) -> Unit,
    val removeBookmark: (String) -> Unit,
    val toggleBookmark: () -> Unit
)

/** 安全销毁 WebView: 先脱离视图树再 destroy (attached 时 destroy 有崩溃风险)。 */
internal fun destroyWebViewSafe(wv: WebView?) {
    if (wv == null) return
    try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) {}
    try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
}

/**
 * 组装浏览器导航动作 (自 BrowserApp 抽出 — 400 行文件拆分批次 3)。
 *
 * 所有读取项一律以 **getter** 传入 (`tabs: () -> List<TabState>`) 而非快照值:
 * `DisposableEffect(Unit)` 持有的是首帧的动作实例, 值传递会让返回键/快捷键读到过期状态。
 * 每次重组重建本对象 (O(1) 分配) 比 remember 更不容易踩「闭包捕获旧值」的坑。
 */
internal fun browserActions(
    ctx: Context,
    prefs: BrowserPrefs,
    webViewMap: MutableMap<Int, WebView>,
    tabs: () -> List<TabState>,
    setTabs: (List<TabState>) -> Unit,
    activeTabId: () -> Int,
    setActiveTabId: (Int) -> Unit,
    isColdStart: () -> Boolean,
    setIsColdStart: (Boolean) -> Unit,
    overlays: BrowserOverlayState,
    searchEngine: () -> SearchEngine,
    historyEnabled: () -> Boolean,
    historyStore: HistoryStore,
    maxTabs: Int,
    bookmarks: () -> List<String>,
    setBookmarks: (List<String>) -> Unit,
    setPendingLoad: (PendingLoad) -> Unit,
    nextSeq: () -> Long
): BrowserActions {
    /** 用户导航统一入口: 有 WebView 直接加载, 没有则登记待办导航 (不再静默丢失)。 */
    val loadInTab: (Int, String) -> Unit = { id, url ->
        val wv = webViewMap[id]
        if (wv != null) {
            try { wv.onResume() } catch (_: Exception) {}
            wv.loadUrl(url)
        } else {
            setPendingLoad(PendingLoad(id, url, nextSeq()))
        }
    }

    val navigate: (String) -> Unit = { input ->
        val final = smartNavigate(input, searchEngine())
        if (final.isBlank()) {
            Toast.makeText(ctx, "请输入网址或搜索关键词", Toast.LENGTH_SHORT).show()
        } else {
            val id = activeTabId()
            setTabs(tabs().map { if (it.id == id) it.copy(url = final, isLoading = true) else it })
            overlays.showUrlBar = false
            setIsColdStart(false)
            if (historyEnabled()) historyStore.record(final, final.take(60))
            loadInTab(id, final)
        }
    }

    val handleBack: () -> Unit = {
        val id = activeTabId()
        val current = tabs()
        val wv = webViewMap[id]
        if (overlays.dismissTop()) {
            // 浮层已关闭, 不再触发页面后退
        } else if (wv?.canGoBack() == true) {
            wv.goBack()
        } else {
            val remaining = current.filter { it.id != id }
            if (remaining.isNotEmpty()) {
                destroyWebViewSafe(wv); webViewMap.remove(id)
                setTabs(remaining); setActiveTabId(remaining.first().id); setIsColdStart(false)
            } else if (!isColdStart()) {
                webViewMap.values.forEach { destroyWebViewSafe(it) }; webViewMap.clear()
                setTabs(listOf(TabState(id = 0))); setActiveTabId(0); setIsColdStart(true)
            } else {
                try {
                    ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("com.mengpaw.shell"))
                    (ctx as? BrowserActivity)?.finish()
                } catch (_: Exception) {
                    (ctx as? BrowserActivity)?.finish()
                }
            }
        }
    }

    /**
     * P2 fix: 统一开新 tab 入口 — maxTabs 上限真正生效。
     * 此前 DesktopTabBar/BrowserTabDialog 只在 UI 上隐藏 "+" 按钮, 菜单「新标签页」与
     * Agent 桥可无限开 tab。达上限时提示, 不静默创建。
     */
    val openNewTab: () -> Unit = {
        val current = tabs()
        if (current.size >= maxTabs) {
            Toast.makeText(ctx, "标签页已达上限 ($maxTabs)，请先关闭部分标签页", Toast.LENGTH_SHORT).show()
        } else {
            val newId = (current.maxOfOrNull { it.id } ?: 0) + 1
            setTabs(current + TabState(id = newId))
            setActiveTabId(newId)
            setIsColdStart(true)
        }
    }

    /** 关闭当前标签 (菜单与 Ctrl+W 共用; 最后一个标签不被关闭)。 */
    val closeTab: () -> Unit = {
        val current = tabs()
        if (current.size > 1) {
            val closing = activeTabId()
            setTabs(current.filter { it.id != closing })
            destroyWebViewSafe(webViewMap.remove(closing))
            setActiveTabId(current.first { it.id != closing }.id)
        }
    }

    val updateTab: (Int, (TabState) -> TabState) -> Unit = { id, update ->
        setTabs(tabs().map { if (it.id == id) update(it) else it })
    }

    val addBookmark: (String) -> Unit = { url ->
        if (url.isNotBlank()) {
            prefs.addBookmark(url)
            setBookmarks(prefs.bookmarks)
            Toast.makeText(ctx, "已收藏", Toast.LENGTH_SHORT).show()
        }
    }

    val removeBookmark: (String) -> Unit = { url ->
        prefs.removeBookmark(url)
        setBookmarks(prefs.bookmarks)
    }

    val toggleBookmark: () -> Unit = {
        val url = tabs().find { it.id == activeTabId() }?.url ?: ""
        if (url.isNotBlank()) {
            if (url in bookmarks()) removeBookmark(url) else addBookmark(url)
        }
    }

    return BrowserActions(
        navigate = navigate,
        handleBack = handleBack,
        openNewTab = openNewTab,
        closeTab = closeTab,
        updateTab = updateTab,
        addBookmark = addBookmark,
        removeBookmark = removeBookmark,
        toggleBookmark = toggleBookmark
    )
}
