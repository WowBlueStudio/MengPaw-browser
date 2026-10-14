// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

/**
 * User-Agent 选择策略（累计修改 — 平板请求桌面版 / 手机请求移动版）。
 *
 * 背景: Android WebView 的默认 UA 里带 **`Mobile`** 标记，即使平板屏很宽，站点也会
 * 优先返回移动版页面（窄栏、触屏样式）。因此平板需要显式换成桌面 UA；
 * 手机保持系统默认（含 `Mobile` → 站点给移动版）。
 *
 * ⚠️ 判定口径是**设备类别**不是当前宽度: 用 `smallestScreenWidthDp >= 600`（Android 官方的
 * 平板判据），而不是 `screenWidthDp >= 600`（当前宽度，手机横屏也会 ≥600）。否则手机横屏
 * 会误请求桌面版页面。
 *
 * 关键点: 桌面 UA 的 Chrome 主版本号取**当前 WebView 引擎**的主版本，避免
 * 「UA 说 Chrome 120、实际引擎是 Chrome 108」这类不一致触发站点的能力探测异常。
 */

/** 桌面 UA 模板（Windows 平台标识 + 与引擎同主版本）。 */
private const val DESKTOP_UA_TEMPLATE =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36"

/** 引擎版本号缺失时的兜底主版本（不影响功能，仅 UA 文本）。 */
private const val FALLBACK_ENGINE_MAJOR = "120"

/** 从 `WebView.getCurrentWebViewPackage()?.versionName`（如 `120.0.6099.230`）取主版本。 */
internal fun engineMajorOf(engineVersion: String?): String {
    val major = engineVersion?.trim()?.substringBefore('.')?.takeWhile { it.isDigit() }
    return if (major.isNullOrBlank()) FALLBACK_ENGINE_MAJOR else major
}

/** 桌面版 UA：Windows 标识 + 引擎同主版本（不含 `Mobile` 标记）。 */
internal fun desktopUserAgent(engineVersion: String?): String =
    String.format(DESKTOP_UA_TEMPLATE, engineMajorOf(engineVersion))

/**
 * 按形态选 UA：
 * - [desktopSite] == true（**平板设备**，调用方按 `smallestScreenWidthDp >= 600` 判定）
 *   → [desktopUserAgent]（请求桌面版页面）
 * - [desktopSite] == false（手机，含手机横屏）→ [systemDefault]（系统默认移动 UA，含 `Mobile`）
 *
 * [systemDefault] 必须是**系统原始默认值**（`WebSettings.getDefaultUserAgent(ctx)`），
 * 不能读 `settings.userAgentString` —— 后者一旦被本策略改成桌面 UA，再读就成了桌面 UA。
 */
internal fun userAgentFor(desktopSite: Boolean, systemDefault: String, engineVersion: String?): String =
    if (desktopSite) desktopUserAgent(engineVersion) else mobileUserAgent(systemDefault)

/**
 * 手机模式 UA 清洗（累计修改 — 参考开源 Android 浏览器共识）:
 * 系统默认 UA 里的 **`; wv`**（WebView 标记）与 **`Build/...`** 会让站点/登录风控把请求
 * 判成"App 内嵌 WebView"而降级或直接拒绝（Fulguris 注释原文:
 * *"WebSettings default identifies us as WebView and as WebView Google is preventing us to login"*；
 * SmartCookieWeb 在 `onPageStarted` 里 `replace("; wv","")`；Privacy Browser 直接内置一条无 `wv` 的
 * Chrome 移动 UA）。这里做同样的清洗，其余字段保持系统默认（不虚构设备信息）。
 */
internal fun mobileUserAgent(systemDefault: String): String =
    systemDefault
        .replace(Regex(" Build/[^;)]+"), "")
        .replace(Regex(";\\s*wv\\b"), "")
        .replace(Regex("\\s{2,}"), " ")
