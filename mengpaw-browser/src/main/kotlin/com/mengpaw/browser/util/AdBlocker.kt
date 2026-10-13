// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

// ── Ad Block List ──────────────────────────────────────────────────

private val AD_DOMAINS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
    "adservice.google.com", "adservice.google.nl", "pagead2.googlesyndication.com",
    "amazon-adsystem.com", "criteo.com", "criteo.net", "adsrvr.org", "adnxs.com",
    "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
    "smartadserver.com", "outbrain.com", "taboola.com", "moatads.com",
    "advertising.com", "serving-sys.com", "adsafeprotected.com", "yieldmo.com",
    "scorecardresearch.com", "quantserve.com", "bluekai.com", "exelator.com",
    "demdex.net", "ads.linkedin.com", "ads.twitter.com", "ads.yahoo.com",
    "analytics.google.com", "googletagmanager.com", "facebook.com/tr",
    "bat.bing.com", "clarity.ms", "hotjar.com", "mouseflow.com"
)

/** 路径模式 (v0.10.3): 只对 `host + path` 匹配, 不再扫 query/fragment。 */
private val AD_PATH_PATTERNS = listOf(
    Regex("[/.](?:ad|ads|advert|banner|popup|popunder|sponsor)[s]?[/.]", RegexOption.IGNORE_CASE),
    Regex("[/.](?:tracker|tracking|pixel|beacon|analytics|stat)[s]?[/.]", RegexOption.IGNORE_CASE)
)

/**
 * 查询参数模式（v0.10.4 收窄）: 只对 query 匹配，且**只保留纯追踪参数**。
 *
 * 旧规则含 `ref=` 与 `sponsored` —— 它们是**功能性参数**（`?ref=nav`、`?ref=header` 这类
 * 站点内部跳转/接口调用极常见），拦掉会让站点的导航/浮层数据取不到，表现为
 * 「顶栏分类菜单点不开、浮层弹窗全都不出现、100% 高容器异常」这类**局部 JS 初始化失败**。
 * 现在只拦真正无功能语义的追踪参数（utm_/gclid/fbclid/adid/msclkid/yclid）。
 */
private val AD_QUERY_PATTERNS = listOf(
    Regex("[?&]?(?:utm_[a-z]+|gclid|fbclid|msclkid|yclid|adid)=", RegexOption.IGNORE_CASE)
)

/**
 * P2 fix: 域名规则从子串匹配升级为 host 逐段精确匹配。
 * 原子串匹配 (`host.contains(rule)`) 会误拦合法域名 — 如 "doubleclick.net" 命中
 * "notdoubleclick.net"、"adservice.google.com" 命中 "adservice.google.com.cn"。
 * 规则语义: host == 规则 或 host 以 ".规则" 结尾 (子域继承), 规则带路径时 (如
 * "facebook.com/tr") 额外校验 URL 路径前缀。规则集合保持不变。
 */
private fun hostMatches(host: String, rule: String): Boolean {
    val h = host.lowercase().removeSuffix(".")
    val r = rule.lowercase().removeSuffix(".")
    return h == r || h.endsWith(".$r")
}

/**
 * v0.10.3 P1 fix: 路径模式只对 `host + path` 匹配, 查询参数模式只对 query 匹配。
 *
 * 旧实现对**整条 URL** (含 query 与 fragment) 逐条匹配, 于是子资源 URL 只要
 * query 里带了 `/ads/`、`analytics`、`?utm_` 之类字样就被整条拦掉 (第三方站点
 * 常用 `?ref=` / `utm_` 做跳转), 表现为页面功能被打断。现在:
 * - 前两条 (`[/.](ad|ads|…)`、`[/.](tracker|…)`) 只作用于 `host + path`;
 * - 第三条 (`[?&](utm_|ref=|…)`) 只作用于 query;
 * 规则集合未减少, 语义更精确。
 */
fun isAdRequest(url: String): Boolean {
    val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
    val host = uri.host ?: return false
    val path = uri.path ?: ""
    val domainHit = AD_DOMAINS.any { rule ->
        // 规则可含路径段 ("facebook.com/tr") — 域名与路径分开精确匹配
        val slash = rule.indexOf('/')
        val ruleHost = if (slash >= 0) rule.substring(0, slash) else rule
        val rulePath = if (slash >= 0) rule.substring(slash + 1) else null
        if (!hostMatches(host, ruleHost)) return@any false
        if (rulePath == null) return@any true
        path == "/$rulePath" || path.startsWith("/$rulePath/")
    }
    if (domainHit) return true
    val hostPath = host + path
    if (AD_PATH_PATTERNS.any { it.containsMatchIn(hostPath) }) return true
    val query = uri.rawQuery ?: return false
    return AD_QUERY_PATTERNS.any { it.containsMatchIn(query) }
}
