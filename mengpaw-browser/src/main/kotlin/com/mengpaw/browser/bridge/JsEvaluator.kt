// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.webkit.WebView

/**
 * JS 求值管道（自 [BrowserBridge] 抽出 — 400 行文件拆分批次 3）。
 *
 * 负责三件事，全部与"命令线程 ↔ WebView 主线程"的边界有关：
 * 1. [eval] 把脚本 post 到主线程执行并同步等结果（上限 2s，避免拖垮 JavaBridge 线程池）；
 * 2. [unquote] 去掉 `evaluateJavascript` 回调自带的一层 JSON 引号；
 * 3. [escape] / [jsonLiteral] 把不可信入参安全嵌入 JS 字面量（含 U+2028/U+2029 —— JS 行终止符，
 *    漏转义会让整段脚本 SyntaxError）。
 */
internal class JsEvaluator(private val webView: WebView) {

    /**
     * 执行 JS 并返回结果（同步等待，最多 2s）。
     *
     * SAFETY: 可能被非主线程调用（命令线程）；脚本本身在主线程执行。
     * 长时间阻塞会耗尽 WebView 线程池 → 超时即返回错误 JSON，不无限等待。
     */
    fun eval(script: String): String {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = """{"ok":false,"error":"timeout"}"""
        try {
            val posted = webView.post {
                try {
                    webView.evaluateJavascript(script) { r ->
                        result = unquote(r)
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    result = """{"ok":false,"error":"${escape(e.message ?: "unknown")}"}"""
                    latch.countDown()
                }
            }
            if (!posted) {
                // WebView handler is gone (destroyed or shutting down)
                return """{"ok":false,"error":"webview detached"}"""
            }
            val ok = latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) {
                // Timeout — main thread is likely busy. Don't block longer.
                return """{"ok":false,"error":"evaluation timeout (main thread busy)"}"""
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            result = """{"ok":false,"error":"interrupted"}"""
        } catch (e: Exception) {
            result = """{"ok":false,"error":"${escape(e.message ?: "unknown")}"}"""
        }
        return result
    }

    /** 去掉 JSON 字符串引号（evaluateJavascript 回调会给字符串结果加一层引号）。 */
    fun unquote(raw: String): String {
        var s = raw.trim()
        if (s == "null") return """{"ok":false,"error":"JS returned null"}"""
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
            s = s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return s
    }

    /** 转义为 JS 单引号字面量内容。 */
    fun escape(s: String): String =
        s.replace("\\", "\\\\")
         .replace("'", "\\'")
         .replace("\"", "\\\"")
         .replace("\n", "\\n")
         .replace("\r", "")
         .replace("\u2028", "\\u2028")
         .replace("\u2029", "\\u2029")

    /** 转成可直接嵌入的 JS 字符串字面量（含引号）。 */
    fun jsonLiteral(s: String): String =
        "'" + s.replace("\\", "\\\\")
               .replace("'", "\\'")
               .replace("\n", "\\n") + "'"
}
