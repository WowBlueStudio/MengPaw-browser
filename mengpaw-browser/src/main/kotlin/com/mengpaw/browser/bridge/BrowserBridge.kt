// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mengpaw.browser.util.BrowserStorage
import java.io.File
import java.io.FileOutputStream

/**
 * P2 fix: 截图位图内存上限 — ARGB_8888 = 4B/px, 32MB ≈ 8.4M px。
 * 超过上限的截图 (超大页面缝合图 / 超宽视口) 等比缩小绘制 (canvas.scale),
 * 坐标空间随返回 JSON 尺寸同步缩放, 防止 Bitmap OOM。
 */
const val MAX_SCREENSHOT_PIXELS = 8 * 1024 * 1024

/**
 * Kotlin 侧浏览器操作门面（Agent 命令 → 真实 WebView）。
 *
 * v0.10.3 文档纠正: 本类**不再**经 `addJavascriptInterface` 暴露给网页 —— 该注册在
 * v0.10.2 已因安全原因移除（任意网页一行 JS 即可调内部能力），现在**只由 Kotlin 侧
 * （插件命令/状态桥）持有并调用**。`@JavascriptInterface` 注解为历史遗留，勿据此恢复注册。
 *
 * v0.32.x（400 行拆分批次 2）: JS 脚本常量拆至 [BrowserScripts.kt]，
 * 全页缝合截图/坐标交互拆至 [FullPageScreenshotter.kt]。
 * v0.10.3（批次 3）: JS 求值/转义管道拆至 [JsEvaluator.kt]。
 */
class BrowserBridge(
    private val webView: WebView,
    private val onScreenshot: ((Bitmap) -> String)? = null
) {

    /** JS 求值管道（eval/unquote/escape/jsonLiteral）。 */
    private val js = JsEvaluator(webView)

    /** 加载/等待语义（waitForSelector/waitForLoad/goto）。 */
    private val waits = LoadWaits(webView, js)

    /** 会话数据（Cookie / Web Storage）。 */
    private val session = SessionOps(webView, js)

    private val screenshotter = FullPageScreenshotter(
        webView = webView,
        onScreenshot = onScreenshot,
        unquoteJs = js::unquote,
        viewportFallback = ::screenshot
    )

    /**
     * Click the first element matching a CSS selector.
     * Returns JSON: {"ok":true} or {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun click(selector: String): String {
        return evalJs(clickScript(escapeJs(selector)))
    }

    /**
     * Type text into the first element matching a CSS selector.
     * Returns JSON: {"ok":true} or {"ok":false,"error":"..."}
     */
    @JavascriptInterface
    fun type(selector: String, text: String): String {
        return evalJs(typeScript(escapeJs(selector), escapeJs(text)))
    }

    /**
     * Scroll the page by (x, y) pixels.
     */
    @JavascriptInterface
    fun scroll(x: Float, y: Float): String {
        return evalJs(scrollScript(x, y))
    }

    /**
     * Extract structured page content as JSON.
     * Returns title, links, forms, headings, and visible text.
     * Text is capped at 3000 chars to keep Agent context lean.
     */
    @JavascriptInterface
    fun content(): String {
        return evalJs(contentScript())
    }

    /**
     * 等待元素出现（Kotlin 侧轮询，实现见 [LoadWaits.waitForSelector]）。
     * Returns JSON: {"ok":true,"found":true} or {"ok":false,"error":"timeout"}
     */
    @JavascriptInterface
    fun waitForSelector(selector: String, timeoutMs: Int = 5000): String =
        waits.waitForSelector(selector, timeoutMs)

    /** 当前 URL 的全部 Cookie（实现见 [SessionOps.cookies]，JSON 由 JSONObject 构造）。 */
    @JavascriptInterface
    fun cookies(): String = session.cookies()

    /** 设置 Cookie。Usage: browser.cookies.set <name> <value> [domain] */
    fun cookieSet(name: String, value: String, domain: String? = null): String =
        session.cookieSet(name, value, domain)

    /** 清空全部 Cookie（破坏性：注销所有站点登录态）。 */
    fun cookieClear(): String = session.cookieClear()

    /** Get/set/clear localStorage or sessionStorage（实现见 [SessionOps.storage]）。 */
    @JavascriptInterface
    fun storage(type: String, op: String, key: String? = null, value: String? = null): String =
        session.storage(type, op, key, value)

    /** Get element attribute value. */
    @JavascriptInterface
    fun attr(selector: String, attribute: String): String {
        return evalJs(attrScript(escapeJs(selector), escapeJs(attribute)))
    }

    /** Get element text content. */
    @JavascriptInterface
    fun text(selector: String): String {
        return evalJs(textScript(escapeJs(selector)))
    }

    /** Check if element is visible (has non-zero dimensions and is not hidden). */
    @JavascriptInterface
    fun visible(selector: String): String {
        return evalJs(visibleScript(escapeJs(selector)))
    }

    /** Check if element is enabled (not disabled). */
    @JavascriptInterface
    fun enabled(selector: String): String {
        return evalJs(enabledScript(escapeJs(selector)))
    }

    /** Select an option in a &lt;select&gt; element by value or visible text. */
    @JavascriptInterface
    fun select(selector: String, value: String): String {
        return evalJs(selectScript(escapeJs(selector), escapeJs(value)))
    }

    /** Submit a form. */
    @JavascriptInterface
    fun submit(selector: String): String {
        return evalJs(submitScript(escapeJs(selector)))
    }

    /** Check a checkbox or radio input. */
    @JavascriptInterface
    fun check(selector: String): String {
        return evalJs(checkScript(escapeJs(selector)))
    }

    /** Uncheck a checkbox. */
    @JavascriptInterface
    fun uncheck(selector: String): String {
        return evalJs(uncheckScript(escapeJs(selector)))
    }

    /**
     * Execute arbitrary JavaScript in the page and return the result.
     * Result is truncated to 5000 chars for safety.
     * SECURITY: NOT exposed via @JavascriptInterface — only callable from Kotlin (Agent).
     */
    fun eval(js: String): String {
        return evalJs("""
            (function() {
                try {
                    var result = eval(${js.toJsonLiteral()});
                    if (result === undefined) return 'undefined';
                    if (result === null) return 'null';
                    var s = typeof result === 'string' ? result : JSON.stringify(result);
                    return s.length > 5000 ? s.substring(0,5000)+'...[truncated]' : s;
                } catch(e) { return 'Error: '+e.message; }
            })()
        """.trimIndent())
    }

    // ── Internal (JS 求值管道见 JsEvaluator — 400 行拆分批次 3) ──────────

    /** 执行 JS 并返回结果（同步等待，上限 2s；见 [JsEvaluator.eval]）。 */
    private fun evalJs(script: String): String = js.eval(script)

    /** 去掉 evaluateJavascript 回调自带的一层 JSON 引号。 */
    private fun unquoteJs(raw: String): String = js.unquote(raw)

    /** 转义为 JS 字面量内容（含 U+2028/U+2029）。 */
    private fun escapeJs(s: String): String = js.escape(s)

    /** 转成可嵌入的 JS 字符串字面量。 */
    private fun String.toJsonLiteral(): String = js.jsonLiteral(this)

    // ── Persistent bridge (speed optimization) ──────────────────────────

    /**
     * Inject the persistent `__mp` helper object into the page.
     * After injection, all subsequent commands use tiny one-liners:
     *   __mp.c('#btn') instead of full click script (~500→15 chars, ~33x smaller)
     *
     * Call once per page load. Subsequent calls are no-ops.
     */
    @JavascriptInterface
    fun inject(): String {
        return evalJs(injectScript())
    }

    /**
     * Fast-path click using pre-injected __mp bridge.
     * Falls back to full script if __mp not available.
     */
    fun fastClick(selector: String): String {
        // 修复: 原实现只转义单引号 — '\' '"' 换行等均可注入。改用 escapeJs 完整转义
        // （转义后的字符串在单引号 JS 字面量中同样安全，双引号转义无害）。
        val s = escapeJs(selector)
        return evalJs(fastClickScript(s))
    }

    /** Fast-path type. */
    fun fastType(selector: String, text: String): String {
        val s = escapeJs(selector); val t = escapeJs(text)
        return evalJs(fastTypeScript(s, t))
    }

    /** Fast-path content. Returns diff if cached, full content otherwise. */
    fun fastContent(): String {
        return evalJs(fastContentScript())
    }

    /** Fast-path diff — returns only changed text since last extraction. */
    @JavascriptInterface
    fun diff(): String {
        return evalJs(diffScript())
    }

    /** 缓冲截图（page.screenshot 视口分支）。
     *
     * v0.10.3 修复:
     * ① **主线程约束**: 旧实现直接在命令线程读 `webView.width/height` 并 `draw()` —
     *    WebView 的这些调用必须在主线程（同文件 loadUrl 已 post，此处却漏了）；
     * ② **落盘校验**: 写盘后校验文件存在且非空，否则如实 ok:false —— 旧实现无条件回
     *    ok:true + 路径，Agent 读不到文件时无法区分「成功」与「文件丢了」。
     */
    @JavascriptInterface
    fun screenshot(): String {
        return try {
            var path = ""
            var w = 0
            var h = 0
            val latch = java.util.concurrent.CountDownLatch(1)
            val posted = webView.post {
                try {
                    w = webView.width
                    h = webView.height
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)
                    path = onScreenshot?.invoke(bitmap) ?: run {
                        val dir = File(BrowserStorage.screenshotsDir())
                        dir.mkdirs()
                        val file = File(dir, "browser_${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        file.absolutePath
                    }
                    bitmap.recycle()
                } finally {
                    latch.countDown()
                }
            }
            if (!posted) return """{"ok":false,"error":"webview detached"}"""
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return """{"ok":false,"error":"screenshot timeout (main thread busy)"}"""
            }
            verifySaved(path)?.let { return it }
            """{"ok":true,"path":"${esc(path)}","width":$w,"height":$h,"verified":true}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${esc(e.message ?: "unknown")}"}"""
        }
    }

    /** 产出文件校验：不存在/空文件视为失败（Agent 无法区分「成功」与「文件丢了」的根治）。 */
    private fun verifySaved(path: String): String? {
        return try {
            val f = File(path)
            if (!f.exists()) """{"ok":false,"error":"output file missing: ${esc(path)}"}"""
            else if (f.length() <= 0L) """{"ok":false,"error":"output file empty: ${esc(path)}"}"""
            else null
        } catch (e: Exception) {
            """{"ok":false,"error":"output verify failed: ${esc(e.message ?: "unknown")}"}"""
        }
    }

    /** JSON 字符串字段转义（路径/错误信息可能含引号或反斜杠）。 */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    /**
     * 精确等待页面加载（page.goto 语义）。实现见 [LoadWaits.waitForLoad]。
     */
    fun waitForLoad(mode: String, timeoutMs: Int = 30000): String = waits.waitForLoad(mode, timeoutMs)

    /** 导航 + 精确等待（page.goto）。实现见 [LoadWaits.goto]。 */
    fun goto(url: String, waitMode: String = "domcontentloaded", timeoutMs: Int = 30000): String =
        waits.goto(url, waitMode, timeoutMs)

    /** 派发按键事件（page.key）。特殊键走映射表，单字符按 ASCII。 */
    @JavascriptInterface
    fun key(keyName: String): String {
        return evalJs(keyScript(escapeJs(keyName)))
    }

    /** 原始页面文本（保留换行）— page.content 的 --grep/--head/--tail 过滤基础。 */
    @JavascriptInterface
    fun contentRaw(maxChars: Int = 50000): String {
        return evalJs(
            "(function(){var t=document.body?document.body.innerText:'';" +
                "return JSON.stringify({ok:true,title:document.title||'',url:location.href,text:t.substring(0,$maxChars)});})()"
        )
    }

    /** 分段全页截图（决策 #5）— 超长页按段返回，坐标按段拆分。 */
    @JavascriptInterface
    fun screenshotFullSegments(maxHeight: Int = 15000): String {
        return screenshotter.captureSegments(maxHeight)
    }

    /** 按段坐标点击（决策 #5）— 段号 + 段内截图坐标。 */
    @JavascriptInterface
    fun coordClickSegment(seg: Int, x: Int, y: Int): String {
        return screenshotter.tapSegment(seg, x, y)
    }

    /** 按段坐标滚动（决策 #5）。 */
    @JavascriptInterface
    fun coordScrollSegment(seg: Int, y: Int): String {
        return screenshotter.scrollToSegmentY(seg, y)
    }

    /** 元素截图 — 视口内滚动定位元素后裁剪（page.screenshot.element）。 */
    @JavascriptInterface
    fun screenshotElement(selector: String): String {
        val safe = escapeJs(selector)
        return try {
            // 元素可能存在视口外 — 先滚动到元素位置再取 rect (getBoundingClientRect 为视口坐标)
            val wv = webView
            val scrollLatch = java.util.concurrent.CountDownLatch(1)
            wv.post {
                wv.evaluateJavascript(
                    "(function(){var e=document.querySelector('$safe');if(!e)return 'not found';e.scrollIntoView({block:'center'});return 'ok';})()"
                ) { scrollLatch.countDown() }
            }
            scrollLatch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            val rectJson = eval(
                "(function(){var e=document.querySelector('$safe');if(!e)return JSON.stringify({ok:false,error:'not found'});var r=e.getBoundingClientRect();return JSON.stringify({ok:true,x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)});})()"
            )
            val json = org.json.JSONObject(rectJson)
            if (!json.optBoolean("ok", false)) {
                return """{"ok":false,"error":"not found: $safe"}"""
            }
            val vpW = wv.width.coerceAtLeast(1)
            val vpH = wv.height.coerceAtLeast(1)
            val scale = if (vpW.toLong() * vpH > MAX_SCREENSHOT_PIXELS)
                kotlin.math.sqrt(MAX_SCREENSHOT_PIXELS.toDouble() / (vpW.toLong() * vpH)).toFloat()
            else 1f
            val bmpW = (vpW * scale).toInt().coerceAtLeast(1)
            val bmpH = (vpH * scale).toInt().coerceAtLeast(1)
            val fullBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val drawLatch = java.util.concurrent.CountDownLatch(1)
            wv.post {
                val c = android.graphics.Canvas(fullBitmap)
                if (scale < 1f) c.scale(scale, scale)
                wv.draw(c)
                drawLatch.countDown()
            }
            drawLatch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            val x = (json.optInt("x", 0) * scale).toInt().coerceIn(0, bmpW - 1)
            val y = (json.optInt("y", 0) * scale).toInt().coerceIn(0, bmpH - 1)
            val w = minOf((json.optInt("w", fullBitmap.width) * scale).toInt().coerceAtLeast(1), fullBitmap.width - x)
            val h = minOf((json.optInt("h", fullBitmap.height) * scale).toInt().coerceAtLeast(1), fullBitmap.height - y)
            val cropped = Bitmap.createBitmap(fullBitmap, x, y, w, h)
            fullBitmap.recycle()
            val file = File(com.mengpaw.browser.util.BrowserStorage.screenshotsDir(), "element_${System.currentTimeMillis()}.png")
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
            cropped.recycle()
            """{"ok":true,"path":"${file.absolutePath}","rect":{"x":$x,"y":$y,"w":$w,"h":$h}}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
        }
    }

}
