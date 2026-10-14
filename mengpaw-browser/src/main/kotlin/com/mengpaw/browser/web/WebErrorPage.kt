// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

// ── App-banner hiding CSS ───────────────────────────────────────

/**
 * 隐藏「打开 App / 下载 App」推广条（累计修改：**大幅收窄选择器**）。
 *
 * 事故背景: 旧列表含大量**通用类名子串**（`[class*=floatBtn]`、`[class*=bottom-bar]`、
 * `[class*=bottomBar]`、`[class*=openIn]`、`[class*=float-app]`、`[class*=native-app]`、
 * `[class*=mobile-app]`、`[class*=goToApp]`、`[class*=appLink]` …）并一律
 * `display:none!important`。这些子串在正常站点里被**正当浮层**大量使用
 * （浮动购物车/客服/底部操作条/弹窗容器内层），于是出现用户报障的
 * 「**浮层弹窗全部都没有加载**」「分类菜单点不开」——不是没加载，是被我们的 CSS 隐藏了。
 *
 * 现在只保留**语义明确指向 App 推广**的选择器（smartbanner / app-banner / 打开App /
 * 下载App / 应用内打开），不再碰通用布局类名。
 */
internal val HIDE_APP_BANNER_JS = """
(function(){if(window.__mpHideBanner)return;window.__mpHideBanner=true;
var s=document.createElement('style');s.id='mp-hide-banner';
s.textContent=[
'#smartbanner,.smartbanner,[class*=app-banner],[class*=app_banner]',
'[class*=open-app],[class*=openApp],[class*=OpenInApp],[class*=open_in_app]',
'[class*=app-download],[class*=appDownload],[class*=app-install],[class*=appInstall]',
'[id*=smartbanner],[id*=app-banner],[id*=app_banner]',
'.weibo-app-banner,.open-in-app,.open-in-app-btn,.openAppBtn',
'.downloadApp,.download-app,.download_app',
'.app-promotion,.appPromotion,.OpenInAppButton,.Button--openInApp',
'.MobileAppHeader__button,.sohu-app-bar'
].join(',')+'{display:none!important}';
document.head.appendChild(s)})();
""".trimIndent()

/**
 * 站点 JS 错误钩子（累计修改 — 兼容性诊断）。
 *
 * 页面报错默认只进 `chromium` tag，站点「菜单点不开/浮层不出/高度异常」时现场无从取证。
 * 这里捕获 `error` 与 `unhandledrejection` 并转成 `console.error`，
 * 由 [BrowserChromeClient] 的 `onConsoleMessage` 打到 `MpWebConsole` tag，可经
 * `adb logcat -s MpWebConsole` 直接读取。
 */
internal val MP_ERROR_HOOK_JS = "(function(){if(window.__mpErrHook)return;window.__mpErrHook=1;" +
    // 脚本错误: 有 message/filename/lineno
    "window.addEventListener('error',function(e){" +
    "try{" +
    // 资源(script/link/img)加载失败时 e.message 为空, 必须记录真实 URL(判定是否被我们拦掉)
    "var t=e.target||{};" +
    "var u=t.src||t.href||'';" +
    "if(!e.message&&u){console.error('[mp] resource error: '+u)}" +
    "else{console.error('[mp] JS error: '+(e.message||'')+' @ '+(e.filename||'')+':'+(e.lineno||0))}" +
    "}catch(x){}},true);" +
    "window.addEventListener('unhandledrejection',function(e){try{console.error('[mp] unhandled rejection: '+" +
    "((e.reason&&e.reason.message)||String(e.reason)))}catch(x){}});})();"

/**
 * `#criticalCSS` 缺失兜底（累计修改 — 站点兼容性，模拟器实测定位）。
 *
 * 事故与证据: trekbikes.com 首页的整屏 hero（`.trek-marquee .fixed-overslider`）在
 * Android WebView 里只有 195px 高、`getComputedStyle().minHeight = "0px"`，而探针证明
 * **两条命中该元素的规则**（`min-height:60vh` 与 `@media((min-width:640px)){…calc(-60px + 90vh)}`）
 * 都在已应用样式表里 —— 即 `min-height` 被当成**非替换 inline 盒**不适用（计算值回落 0），
 * 也就是"给它 `display:block` 的那份布局 CSS 没到"。
 *
 * 站点侧机制: 其非关键 CSS 由 `loadNonCriticalCSS()` 注入，而该函数依赖
 * `#criticalCSS` 元素存在（否则 `insertAdjacentElement` 抛错、后续样式永不注入）。
 * 实测每次采样 `critical:false`（页面里没有该元素）⇒ 注入链从未走通。
 *
 * 本兜底**不写死任何站点样式**，只是把站点自己要求的挂载点补齐（幂等、纯 try/catch），
 * 让站点自己的 CSS 注入链能继续；对不需要该元素的站点无副作用。
 */
internal val MP_CRITICAL_CSS_SHIM_JS = "(function(){try{" +
    "if(document.getElementById('criticalCSS'))return;" +
    "var s=document.createElement('style');s.id='criticalCSS';" +
    "(document.head||document.documentElement).appendChild(s);" +
    "}catch(e){}})();"

/** HTML 特殊字符转义 — 错误页等拼接场景防注入 (恶意 URL/错误描述可携带 HTML/JS)。 */
private fun escapeHtml(s: String): String = buildString(s.length) {
    for (ch in s) {
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(ch)
        }
    }
}

/**
 * 网络错误码 → 中文提示 + **可执行的下一步**。
 * v0.10.3: 旧文案只陈述原因（"网址可能拼错"），Agent/用户拿不到替代动作 →
 * 补「换协议重试 / 换 DNS / 用系统浏览器验证 / 该站可能被网络屏蔽」等落点。
 */
private fun errorHint(desc: String): String = when {
    desc.contains("CLEARTEXT", ignoreCase = true) ->
        "该站点是明文 http，被系统流量策略拦截。可改用 https:// 访问，或检查应用是否被限制了明文流量"
    desc.contains("NAME_NOT_RESOLVED", ignoreCase = true) ->
        "域名解析失败：网址可能拼错；请检查 WiFi/移动数据，或换用 IP 直连/更换 DNS"
    desc.contains("TIMED_OUT", ignoreCase = true) ->
        "连接超时：站点无响应或当前网络访问不到。可改用 http:// 与 https:// 互试、稍后重试，或复制地址到系统浏览器验证"
    desc.contains("CONNECTION_REFUSED", ignoreCase = true) ->
        "连接被拒绝：站点端口未开放。确认端口号，或换 http/https 再试"
    desc.contains("CONNECTION_RESET", ignoreCase = true) ||
        desc.contains("CONNECTION_CLOSED", ignoreCase = true) ->
        "连接被重置：链路中断（网络屏蔽或站点故障）。可在系统浏览器中验证同一地址，或稍后重试"
    desc.contains("SSL", ignoreCase = true) || desc.contains("CERT", ignoreCase = true) ->
        "证书校验失败：站点证书无效（本浏览器不允许绕过证书错误）。确认域名正确，或改用 http:// 再试"
    desc.contains("UNKNOWN_URL_SCHEME", ignoreCase = true) ->
        "不支持的地址协议：请使用 http:// 或 https://"
    desc.contains("INSUFFICIENT_RESOURCES", ignoreCase = true) ->
        "内存不足：页面过大，尝试关闭其他标签页后重试"
    else -> ""
}

/**
 * 内置错误页: 展示错误码 + 人话解释 + 重试入口。
 * 重试按钮用 <a href> 而非 JS — 无脚本、无注入面, 且链接交给
 * [android.webkit.WebViewClient.shouldOverrideUrlLoading] 正常放行。
 * 仅 http(s) 失败地址才渲染重试 (其余协议不提供可点击入口)。
 */
internal fun errorPageHtml(desc: String, failingUrl: String): String {
    val hint = errorHint(desc)
    val retry = if (failingUrl.startsWith("http://") || failingUrl.startsWith("https://")) {
        "<p><a href=\"${escapeHtml(failingUrl)}\" style=\"display:inline-block;padding:10px 22px;" +
            "background:#2F6FED;color:#fff;border-radius:22px;text-decoration:none\">重试</a></p>"
    } else ""
    return "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
        "<body style='padding:40px 24px;font-family:sans-serif;text-align:center'>" +
        "<h2 style='margin-bottom:8px'>页面加载失败</h2>" +
        (if (hint.isNotBlank()) "<p style='color:#444'>${escapeHtml(hint)}</p>" else "") +
        "<p style='color:#888;font-size:13px;word-break:break-all'>${escapeHtml(desc)}</p>" +
        "<p style='color:#aaa;font-size:12px;word-break:break-all'>${escapeHtml(failingUrl.take(160))}</p>" +
        retry +
        "</body></html>"
}
