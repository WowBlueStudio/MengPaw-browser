// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * PDF 预览专用 WebView 工厂 (v0.10.0)。
 *
 * 不复用 createWebView (网页浏览) 与 createMdViewerWebView — 预览页经 loadUrl(file://cache)
 * 宿主, pdf.js 需经 XHR 读取 file:///android_asset 下的 worker / cmaps 子资源,
 * 故必须开 allowFileAccessFromFileURLs (file→file)。
 *
 * v0.10.3: `allowUniversalAccessFromFileURLs` 关掉 —— 它允许 file:// 页面读取**任意来源**
 * (http/https) 的响应, 对预览器毫无必要 (只需 file→file); 关闭后恶意 PDF 内脚本无法借
 * 文件宿主页跨源读取外部内容。注: targetSdk 30+ 上该设置已被 WebView 忽略, 此处只对
 * Android 10 及以下的设备生效, 属纵深防御。
 */
@SuppressLint("SetJavaScriptEnabled")
fun createPdfViewerWebView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = true
    // file:// 宿主页读取其他 file:// 子资源 (pdf.js worker/cmaps)
    try { settings.allowFileAccessFromFileURLs = true } catch (_: Exception) { }
    try { settings.allowUniversalAccessFromFileURLs = false } catch (_: Exception) { }
    // 缩放由 viewer.js 内部控制 (按钮/滚动窗口), 关闭 WebView 层 pinch 双重缩放
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.setSupportZoom(false)
    webViewClient = object : WebViewClient() {
        // 预览页内不响应任何链接/导航 — 一律吞掉
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
    }
}
