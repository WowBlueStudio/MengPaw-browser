// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.MotionEvent
import android.net.http.SslError
import android.webkit.*
import android.webkit.WebResourceError
import android.widget.Toast
import com.mengpaw.browser.data.DetectedImage
import com.mengpaw.browser.data.TabState
import com.mengpaw.browser.ui.theme.BrowserThemeConfig
import com.mengpaw.browser.util.isAdRequest

// ── Block non-user-initiated popups ─────────────────────────────

// ── WebView Factory ──────────────────────────────────────────────

/**
 * 拆分说明（400 行红线）: 错误页/提示 helper 与两段 JS 常量已迁至 [WebErrorPage]，
 * `WebChromeClient` 匿名对象迁至 [createBrowserChromeClient]（BrowserChromeClient.kt），
 * 下载承接迁至 [installDownloadListener]（FileDownloader.kt）—— 均为逐字迁出，行为不变。
 */

/** .md URL 判定: 去 query/fragment 后后缀匹配 (导航级 O(len) 检查)。 */
private fun isMarkdownUrl(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').endsWith(".md", ignoreCase = true)

/** .pdf URL 判定 (与 .md 同构) — 触发 pdf.js 预览而非让 WebView 无法渲染的原始 PDF。 */
private fun isPdfUrl(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').endsWith(".pdf", ignoreCase = true)

// ── ComfyUI 判定: 精确 host/端口匹配 (P2 fix — 原子串匹配会误伤 comfygallery/notcomfy 等域名) ──

/**
 * ComfyUI 判定: host 精确匹配 (comfyui 或 *.comfyui 整段标签) 或端口 == ComfyUI 默认端口。
 * 原实现 `url.contains("comfy", ignoreCase=true)` 会把任意含 "comfy" 子串的域名都当 ComfyUI
 * (误注入主题 CSS); 改为解析 URI 后逐段精确判定, 自定义端口的 ComfyUI 可通过 host 匹配覆盖。
 */
private fun isComfyUiUrl(raw: String): Boolean {
    val uri = try { java.net.URI(raw) } catch (_: Exception) { return false }
    val host = uri.host?.lowercase() ?: return false
    val portOk = uri.port != -1 && uri.port == com.mengpaw.kernel.ports.Ports.COMFYUI
    val hostOk = host == "comfyui" || host.endsWith(".comfyui")
    return portOk || hostOk
}

/**
 * 形态 UA 策略（累计修改）: 平板 → 桌面版页面; 手机（含横屏）→ 移动版页面。
 *
 * [desktopSite] 由调用方按**设备类别**判定（`smallestScreenWidthDp >= 600`），不是当前宽度 ——
 * 手机横屏宽度也会 ≥600dp，按宽度判会误请求桌面版。
 *
 * 幂等且廉价: 只在目标值与当前值不同时才写（`update` 块每次重组都会调用它，
 * 因此旋转屏幕/折叠屏展开/分屏改变形态后也会自动纠正 UA）。
 * 注意: 已加载的页面需要**刷新**才按新 UA 重新请求（与桌面端浏览器切换 UA 行为一致）。
 */
internal fun applyUserAgent(wv: WebView, desktopSite: Boolean) {
    try {
        // 必须取系统原始默认值 —— settings.userAgentString 可能已被本策略改过
        val systemDefault = WebSettings.getDefaultUserAgent(wv.context)
        val engineVersion = try { WebView.getCurrentWebViewPackage()?.versionName } catch (_: Exception) { null }
        val target = userAgentFor(desktopSite, systemDefault, engineVersion)
        if (wv.settings.userAgentString != target) {
            wv.settings.userAgentString = target
            android.util.Log.i(
                "BrowserActivity",
                "UA 按形态切换 (desktopSite=$desktopSite): ${target.take(72)}"
            )
        }
    } catch (_: Exception) {
        // 设置失败不阻断加载 (保持系统默认 UA)
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
fun createWebView(
    ctx: android.content.Context, tab: TabState, isWide: Boolean,
    /** 广告拦截/桥接注入为动态读取 — 设置里切换后无需重建 WebView 即对下次加载生效 */
    adBlock: () -> Boolean,
    autoInject: () -> Boolean = { true },
    updateTab: (Int, (TabState) -> TabState) -> Unit,
    onMediaDetected: (List<DetectedImage>) -> Unit,
    onScroll: (Int) -> Unit = {},
    onMarkdownDetected: (String) -> Unit = {},
    onPdfDetected: (String) -> Unit = {},
    /** false 时不在构造期加载 tab.url — 由调用方 (待办导航) 统一加载, 避免重复导航 */
    loadInitialUrl: Boolean = true,
    /** 平板设备（`smallestScreenWidthDp >= 600`）→ 请求桌面版页面；手机（含横屏）→ 移动版 */
    desktopSite: Boolean = false,
    /**
     * 站点 `window.open` / `target=_blank` 弹窗的真实 URL 回调（累计修改）——
     * 由调用方开新标签承接；此前多窗口未开启，弹层全部静默消失。
     */
    onNewWindow: (String) -> Unit = {}
): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    // 累计修改（社区/官方口径）: Chromium 文档给"像浏览器一样配置 WebView"的三件套是
    // useWideViewPort(true) + loadWithOverviewMode(true) + layoutAlgorithm=TEXT_AUTOSIZING；
    // 缺第三项时排版宽度按 AOSP 缺省 NARROW_COLUMNS 走，与 Chrome/Edge 的字号排版不一致。
    // 注: 该项只影响排版/字号，**不影响 `vh` 高度**（100vh 矮条问题见 WebViewLoadTiming.kt）。
    try { settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING } catch (_: Exception) { }
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // Allow file:// access for local Markdown/HTML files
    settings.allowFileAccess = true
    // 累计修改: 平板请求桌面版页面, 手机请求移动版 (设备类别判定, 策略见 UserAgent.kt)
    applyUserAgent(this, desktopSite)
    // 累计修改（兼容性对齐）: 第三方 Cookie 改为**允许** —— Chromium/Edge 的默认行为。
    // 旧实现显式 false，比 Chrome/Edge 更严：依赖第三方 Cookie 的同意管理(CMP)/标签管理器/
    // 嵌入式登录会让站点局部 JS 初始化失败（分类菜单点不开、浮层弹窗不出现）。
    try { CookieManager.getInstance().setAcceptThirdPartyCookies(this, true) } catch (_: Exception) { }
    try { CookieManager.getInstance().setAcceptCookie(true) } catch (_: Exception) { }

    // SECURITY (P0 fix): Agent 控制浏览器完全走 Kotlin 侧直接方法调用 (BrowserBridge 由
    // BuiltinBrowserPlugin 持有), 网页 JS 从未引用 window.MengPaw — 原 addJavascriptInterface
    // 注册是纯暴露面: 任意网页一行 JS 即可截屏填盘/调协程 API/泄露内部路径。
    // 已移除。若未来需要页面主动回调, 必须按域名白名单 + 受限方法集重新设计。

    var lastScrollYLocal = 0
    setOnScrollChangeListener { _, _, scrollY, _, _ ->
        if (!isWide) {
            val delta = scrollY - lastScrollYLocal
            if (delta > 10) onScroll(delta)  // scrolling down
            else if (delta < -5) onScroll(delta)  // scrolling up
            lastScrollYLocal = scrollY
        }
    }

    var touchX = 0f; var touchY = 0f
    setOnTouchListener { _, event ->
        if (event.action == MotionEvent.ACTION_DOWN) { touchX = event.x; touchY = event.y }
        false
    }

    // ── Long press: detect images + videos at touch point ──
    setOnLongClickListener {
        val js = """
            (function(){
                var els=document.elementsFromPoint($touchX,$touchY);
                var r=[];
                for(var i=0;i<els.length;i++){
                    var el=els[i];
                    var src=el.src||el.getAttribute('src')||el.style.backgroundImage||el.getAttribute('poster')||'';
                    var tag=el.tagName||'';
                    var mt=tag==='VIDEO'?'video':'image';
                    if(tag==='IMG'||tag==='VIDEO'||tag==='SOURCE'||src){
                        src=src.replace(/url\(["']?/,'').replace(/["']?\)/,'');
                        if(src&&src!=='none'&&!src.startsWith('data:')){
                            r.push(JSON.stringify({
                                src:src,alt:el.alt||'',tag:tag,
                                width:el.naturalWidth||el.videoWidth||el.width||0,
                                height:el.naturalHeight||el.videoHeight||el.height||0,
                                z:i,mediaType:mt
                            }));
                        }
                    }
                }
                return '['+r.join(',')+']';
            })();
        """.trimIndent()
        evaluateJavascript(js) { json ->
            try {
                val arr = org.json.JSONArray(json)
                val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                    DetectedImage(it.getString("src"), it.optString("alt"), it.optInt("width"), it.optInt("height"), it.optInt("z"), it.optString("mediaType", "image"))
                }
                if (list.isNotEmpty()) {
                    onMediaDetected(list)
                }
            } catch (_: Exception) {}
        }
        true
    }

    webViewClient = object : WebViewClient() {
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            // SECURITY: Reject all SSL certificate errors — no bypass allowed
            handler?.cancel()
            android.util.Log.e("BrowserActivity", "SSL error: ${error?.primaryError} for ${error?.url}")
            val failing = error?.url ?: view?.url ?: ""
            val host = try { java.net.URI(failing).host } catch (_: Exception) { failing }
            updateTab(tab.id) { it.copy(isLoading = false) }
            // P1 修复: 此前只弹 Toast 不换页 — 证书错误时旧页面残留, 用户以为「点了没反应」
            view?.post {
                Toast.makeText(ctx, "SSL 证书错误，已阻止加载: $host", Toast.LENGTH_LONG).show()
                view.loadDataWithBaseURL(
                    null,
                    errorPageHtml("net::ERR_CERT_AUTHORITY_INVALID (SSL 证书不受信任)", failing),
                    "text/html", "UTF-8", null
                )
            }
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            val desc = error?.description?.toString() ?: "未知错误"
            val failingUrl = request?.url?.toString() ?: view?.url ?: ""
            // 累计修改（兼容性诊断）: 子资源失败此前被完全忽略 ⇒ 站点「接口 Network Error /
            // 菜单点不开 / 100vh 段不生效」现场无从取证。这里把**每个失败的子资源 URL + 错误码**
            // 落盘（logcat 被设备抑制时仍可 cat MpDiag/probe.log）。
            if (request?.isForMainFrame != true) {
                val code = try { error?.errorCode } catch (_: Exception) { null }
                DiagLog.append(ctx, "subres-fail", "code=$code $desc $failingUrl")
                return
            }
            // 主帧失败必须停掉加载态, 否则进度条永远转 (旧实现漏了这一步)
            updateTab(tab.id) { it.copy(isLoading = false) }
            android.util.Log.e("BrowserActivity", "Page load failed ($desc): $failingUrl")
            DiagLog.append(ctx, "main-fail", "$desc $failingUrl")
            view?.loadDataWithBaseURL(
                null, errorPageHtml(desc, failingUrl), "text/html", "UTF-8", null
            )
        }

        /**
         * 累计修改（兼容性诊断）: HTTP 错误（403/404/500…）此前完全不感知。
         * 站点接口被 WAF/鉴权拒绝时会走到这里，是判定"接口被拦还是 CORS 失败"的关键证据。
         */
        override fun onReceivedHttpError(
            view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
        ) {
            val url = request?.url?.toString() ?: return
            val status = try { errorResponse?.statusCode } catch (_: Exception) { null }
            val reason = try { errorResponse?.reasonPhrase } catch (_: Exception) { null }
            val main = request?.isForMainFrame == true
            DiagLog.append(ctx, if (main) "http-error-main" else "http-error", "$status $reason $url")
        }
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // SECURITY: Block dangerous URL schemes (javascript:, data:, content:, etc.)
            val url = request?.url?.toString() ?: return false
            val lower = url.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                if (lower.startsWith("file://") || lower.startsWith("about:")) return false
                // 累计修改（兼容性对齐）: blob: 是站点自己生成的内容（下载/预览/导出），
                // Chromium/Edge 允许在同一 WebView 内导航 —— 旧实现当危险协议拦掉，
                // 会让站点的「导出/预览/下载」类按钮点了没反应。危险协议仍拦：
                // javascript: / data: / content:
                if (lower.startsWith("blob:")) return false
                if (lower.startsWith("javascript:") || lower.startsWith("data:") ||
                    lower.startsWith("content:")) {
                    android.util.Log.w("BrowserActivity", "Blocked unsafe URL scheme: ${url.take(80)}")
                    return true
                }
                // P1 修复: 其余非 http(s) 协议 (tel/sms/mailto/intent/market/weixin/alipays/...)
                // 交给系统处理 — 旧实现只放行 4 种, 大量站点按钮 (拨号/唤起 App/支付) 点了没反应
                try {
                    ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {
                    android.util.Log.w("BrowserActivity", "No handler for scheme: ${url.take(80)}")
                }
                return true
            }
            // In-page .md URL → intercept + render preview (navigation-level O(len) check only;
            // no MIME probing in shouldInterceptRequest — that would slow every page load)
            if (isMarkdownUrl(url)) {
                onMarkdownDetected(url)
                return true
            }
            // .pdf URL → 走 pdf.js 预览 (WebView 原生无法渲染 PDF)
            if (isPdfUrl(url)) {
                onPdfDetected(url)
                return true
            }
            // v0.10.4: 页面内点击链接 = 用户已发起导航 → 立刻置加载态,
            // 让顶栏的旋转指示"点击即转"（旧版要等 onPageStarted/网络回包才亮）
            updateTab(tab.id) { it.copy(url = url, isLoading = true) }
            return false
        }
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            // 兜底: loadUrl() 等编程式导航不走 shouldOverrideUrlLoading — 这里对所有加载检测
            // (shouldOverrideUrlLoading 拦截时不会触发本回调, 不会重复拉取)
            if (url != null) {
                if (isMarkdownUrl(url)) {
                    view?.stopLoading()
                    // v0.10.3: 拦截分支必须收口加载态, 否则进度条永远在转
                    updateTab(tab.id) { it.copy(isLoading = false, url = url) }
                    onMarkdownDetected(url); return
                }
                if (isPdfUrl(url)) {
                    view?.stopLoading()
                    updateTab(tab.id) { it.copy(isLoading = false, url = url) }
                    onPdfDetected(url); return
                }
            }
            url?.let { u -> updateTab(tab.id) { it.copy(url = u, isLoading = true) } }
            // 尽早注入 JS 错误钩子（onPageFinished 会再补一次，覆盖晚绑定错误）
            evaluateJavascript(MP_ERROR_HOOK_JS, null)
        }
        override fun onPageFinished(view: WebView?, url: String?) {
            updateTab(tab.id) { it.copy(isLoading = false, title = view?.title ?: "", canGoBack = view?.canGoBack() ?: false, canGoForward = view?.canGoForward() ?: false) }
            url?.let { u ->
                // 视口自证日志（累计修改 — 兼容性诊断）: 站点「100vh 矮条」到底是 0、
                // 退化默认值还是别的数值, 只有实测能钉死; 见 `adb logcat -s MpWebConsole`。
                logViewportMetrics("finished")
                postDelayed({ try { logViewportMetrics("finished+1s") } catch (_: Exception) { } }, 1000)
                // hero 高度规则审计（第三轮取证: 判断是 vh 不对还是规则没生效）
                postDelayed({ try { logHeroCssAudit() } catch (_: Exception) { } }, 2000)
                // Hide app-banner elements
                evaluateJavascript(HIDE_APP_BANNER_JS, null)
                // 站点 JS 错误钩子（诊断用；幂等）
                evaluateJavascript(MP_ERROR_HOOK_JS, null)
                // Auto-inject __mp bridge for faster Agent commands (if enabled)
                if (autoInject()) {
                    evaluateJavascript("(function(){if(!window.__mp||!window.__mp._v){" +
                        "window.__mp={_v:1,_cache:{}," +
                        "c:function(s){var e=document.querySelector(s);if(!e)return JSON.stringify({ok:false,error:'not found:'+s});e.click();return JSON.stringify({ok:true,tag:e.tagName})}," +
                        "t:function(s,v){var e=document.querySelector(s);if(!e)return JSON.stringify({ok:false});e.focus();var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;d.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));return JSON.stringify({ok:true})}," +
                        "sc:function(x,y){window.scrollBy(x,y);return JSON.stringify({ok:true,sx:window.scrollX,sy:window.scrollY})}," +
                        "ct:function(){try{var ls=[];document.querySelectorAll('a[href]').forEach(function(a){var t=(a.textContent||'').trim().substring(0,80);if(t&&a.href&&!a.href.startsWith('javascript:'))ls.push({text:t,href:a.href})});return JSON.stringify({title:document.title,url:location.href,links:ls.slice(0,50),text:(document.body?document.body.innerText:'').replace(/\\s+/g,' ').trim().substring(0,3000)})}catch(e){return JSON.stringify({error:e.message})}}," +
                        "df:function(){var cur=window.__mp._cache._content||'';var raw=document.body?document.body.innerText:'';var fresh=raw.replace(/\\s+/g,' ').trim().substring(0,1000);window.__mp._cache._content=fresh;if(cur===fresh)return JSON.stringify({changed:false});return JSON.stringify({changed:true,added:fresh.substring(cur.length>0?function(a,b){for(var i=0;i<Math.min(a.length,b.length)&&a[i]===b[i];i++);return i}(cur,fresh):0)})}" +
                        "};return JSON.stringify({ok:true,msg:'__mp injected (auto)'})" +
                        "}})()", null)
                }
                // ComfyUI theme following: inject MengPaw theme colors (host/端口精确匹配, 见 isComfyUiUrl)
                if (isComfyUiUrl(u)) {
                    val theme = BrowserThemeConfig.load(ctx)
                    val primary = "#" + java.lang.Long.toHexString(theme.primary).takeLast(6).uppercase()
                    evaluateJavascript("""
(function(){
	var s=document.createElement('style');
	s.textContent=`
:root{--comfy-primary:${primary};--comfy-bg:${if (theme.surface == 0xFFFFFFFFL) "#FFFFFF" else "#1A1A2E"}}
.comfy-menu, .comfy-topbar, .comfy-btn-primary{background:var(--comfy-primary)!important}
.comfy-multiline-input, .comfy-modal-content{background:var(--comfy-bg)!important}
.comfy-node{background:${primary}11!important;border-color:${primary}44!important}
.comfy-btn-primary:hover{filter:brightness(1.1)}
`;
	document.head.appendChild(s);
})();
""".trimIndent(), null)
                }
            }
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            // 主帧 .md → 空响应 + 回调预览: 资源请求级检测覆盖全部加载路径
            // (loadUrl/链接点击/刷新/重定向), 仅主帧一个请求做 O(len) 判断, 子资源零开销
            if (request?.isForMainFrame == true && request.url != null && isMarkdownUrl(request.url.toString())) {
                onMarkdownDetected(request.url.toString())
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            // .pdf 主帧 → 空响应 + 预览回调
            if (request?.isForMainFrame == true && request.url != null && isPdfUrl(request.url.toString())) {
                onPdfDetected(request.url.toString())
                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }
            // P1 修复: 广告拦截只作用于子资源 — 旧实现连主帧一起拦, 主帧 URL 命中
            // 广告特征 (如 /ad/ 路径段、utm 参数) 时整页被替换成空响应, 表现为「网站打不开」
            if (request?.isForMainFrame != true && adBlock() &&
                request?.url != null && isAdRequest(request.url.toString())
            ) {
                // 累计修改（兼容性诊断）: 被拦的**真实 URL** 必须落盘 —— 站点接口被
                // 关键词型规则误伤时，跨域 XHR 会因缺少 CORS 头变成 "Network Error"，
                // 表现为「菜单点不开 / 100vh 段不生效」，只有看到 URL 才能定位。
                DiagLog.append(ctx, "adblock", "blocked: ${request.url}")
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            return super.shouldInterceptRequest(view, request)
        }
    }
    // 站点进度/标题/console 与 window.open 承接 — 见 BrowserChromeClient.kt（逐字迁出）
    webChromeClient = createBrowserChromeClient(ctx, tab.id, updateTab, onNewWindow)
    // 下载承接（系统 DownloadManager） — 见 FileDownloader.kt（逐字迁出）
    installDownloadListener(this, ctx, tab.id, updateTab)

    // 弹窗（window.open）需要开启多窗口支持，否则 onCreateWindow 不会被回调
    settings.setSupportMultipleWindows(true)

    // 加载时机（累计修改）: **布局就绪后**再 loadUrl —— `factory` 阶段 WebView 尚未测量
    // （0×0），此时加载会让站点 JS 在 window.innerHeight==0 下算好并缓存首屏高度，
    // 布局到全尺寸后不再重算 ⇒ `100vh` hero 被永久挤成"窄条"（见 WebViewLoadTiming.kt）。
    if (loadInitialUrl && tab.url.isNotBlank()) loadWhenLaidOut(tab.url)
}
