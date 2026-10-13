// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.webkit.WebView

/**
 * 加载/等待语义（自 [BrowserBridge] 抽出 — 400 行文件拆分批次 3）。
 *
 * 三个 Playwright 语义等待原语：元素出现（[waitForSelector]）、加载完成（[waitForLoad]）、
 * 导航+等待（[goto]）。全部返回 JSON 字符串、超时如实失败（不抛异常）。
 */
internal class LoadWaits(private val webView: WebView, private val js: JsEvaluator) {

    /**
     * 等待元素出现（Kotlin 侧轮询，间隔 100ms，上限 30s）。
     *
     * 修复: 原实现 setTimeout 异步检查后立即返回 '__PENDING__' — evaluateJavascript
     * 回调拿到的永远是中间态，真实结果永久丢失。改用 Kotlin 侧轮询: 每次调用都是
     * 同步检查（JS 立即返回），间隔 100ms 重试；轮询间隙页面事件循环正常运转，
     * 异步渲染的元素（SPA/网络回包后出现的节点）能被观察到。JS 内忙等则会饿死
     * 页面自身 JS，异步出现的元素永远不会出现，故不采用。
     */
    fun waitForSelector(selector: String, timeoutMs: Int = 5000): String {
        val safe = js.escape(selector)
        val total = timeoutMs.coerceIn(0, 30000)
        val deadline = System.currentTimeMillis() + total
        while (true) {
            val r = js.eval(waitForSelectorCheckScript(safe))
            val obj = try { org.json.JSONObject(r) } catch (_: Exception) { null }
            if (obj?.optBoolean("found", false) == true) return r
            val err = obj?.optString("error")
            // 非"未找到"的错误（选择器非法/求值超时/webview 分离等）→ 如实返回，不再重试
            if (err != null && err != "not found yet") return r
            if (System.currentTimeMillis() >= deadline) {
                return """{"ok":false,"error":"timeout: selector not found after ${total}ms: $safe"}"""
            }
            try { Thread.sleep(100) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return """{"ok":false,"error":"interrupted"}"""
            }
        }
    }

    /**
     * 精确等待页面加载（page.goto 语义）。
     *
     * - `domcontentloaded`: progress==100 且 readyState interactive/complete；
     * - `networkidle`: 加载完成后额外 300ms 无新网络活动（近似实现，WebView 无标准 API）。
     * 超时返回错误 JSON（不抛异常，命令层如实转述）。
     */
    fun waitForLoad(mode: String, timeoutMs: Int = 30000): String {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1000, 60000)
        val networkIdle = mode == "networkidle"
        var progress = webView.progress
        var readyState = ""
        while (System.currentTimeMillis() < deadline) {
            progress = webView.progress
            val r = js.eval("(function(){return document.readyState;})()")
            readyState = r.trim().trim('"')
            val loaded = progress >= 100 && (readyState == "complete" || readyState == "interactive")
            if (loaded) {
                if (networkIdle) {
                    // 近似 networkidle: 加载完成后再观察 300ms 网络稳定
                    try { Thread.sleep(300) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    if (webView.progress >= 100) {
                        return """{"ok":true,"mode":"networkidle","progress":100,"readyState":"$readyState"}"""
                    }
                } else {
                    return """{"ok":true,"mode":"domcontentloaded","progress":100,"readyState":"$readyState"}"""
                }
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return """{"ok":false,"error":"load timeout after ${timeoutMs}ms","progress":$progress,"readyState":"$readyState"}"""
    }

    /** 导航 + 精确等待（page.goto）。loadUrl 必须主线程。 */
    fun goto(url: String, waitMode: String = "domcontentloaded", timeoutMs: Int = 30000): String {
        // v0.10.3: 校验 post 返回值 — 旧实现丢弃它，WebView 已销毁/脱离时 loadUrl 永不执行，
        // 却继续空轮询到超时，用「加载超时」掩盖真实原因（webview detached）。
        val posted = try {
            webView.post { webView.loadUrl(url) }
        } catch (e: Exception) {
            return """{"ok":false,"error":"${js.escape(e.message ?: "unknown")}"}"""
        }
        if (!posted) return """{"ok":false,"error":"webview detached"}"""
        return waitForLoad(waitMode, timeoutMs)
    }
}
