// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

// ── Types ──────────────────────────────────────────────────────────

data class TabState(
    val id: Int, val url: String = "", val title: String = "",
    val isLoading: Boolean = false, val progress: Int = 0,
    val canGoBack: Boolean = false, val canGoForward: Boolean = false
)

data class DetectedImage(
    val src: String, val alt: String, val width: Int = 0, val height: Int = 0, val z: Int = 0,
    val mediaType: String = "image" // "image" or "video"
)

/**
 * 待办导航请求 (v0.10.2): 地址栏回车/点击 → 时, 若该标签的 WebView 尚未在
 * `webViewMap` 中就绪 (冷启动首帧、刚切换标签), 直接 `map[id]?.loadUrl()` 会静默丢失
 * —— 表现为「转到按钮没反应」。改为记下请求, 由内容区在 WebView 就绪的同一帧消费。
 *
 * @param seq 单调递增序号 — 同一 URL 连续提交两次也能各触发一次。
 */
data class PendingLoad(val tabId: Int, val url: String, val seq: Long)

enum class SearchEngine(val label: String, val url: String, val key: String) {
    GOOGLE("Google", "https://www.google.com/search?q=", "google"),
    BING("Bing", "https://www.bing.com/search?q=", "bing"),
    BAIDU("百度", "https://www.baidu.com/s?wd=", "baidu"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=", "duckduckgo");

    companion object {
        fun fromKey(key: String) = entries.find { it.key == key } ?: BING
    }
}
