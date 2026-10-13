// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import com.mengpaw.browser.data.SearchEngine
import java.net.URLEncoder

/**
 * 智能 URL 判定: 关键词 → 搜索 URL, 域名/IP/局域网主机 → 补协议。
 *
 * 判定顺序 (v0.10.2 重排):
 *  ① 归一化输入 (全角 → 半角 / 去零宽字符 / 折叠空白) — 中文输入法下 `.` 会打成 `。`,
 *    旧实现直接判成关键词走搜索, 是「域名输不进去、只能靠搜索绕」的根因;
 *  ② 带协议 http(s):// / 协议相对 // → 原样 (仅补协议);
 *  ③ 裸 IPv4 / IPv6 / localhost / 单标签内网主机 + 端口 → 补 http (明文)
 *    (裸 IP 上的 https 需证书含 IP SAN, 现实中绝大多数是明文 http);
 *  ④ 域名形态 (host 段末段为字母 TLD, 允许带路径/查询/中文/端口, 允许 FQDN 尾点) → https://;
 *  ⑤ 其余 → 搜索引擎。
 *
 * P2 修复保留: 纯数字/小数 (如 "3.14") 不误判为域名 — 末段无字母时按搜索处理。
 */
fun smartNavigate(input: String, engine: SearchEngine): String {
    val trimmed = normalizeInput(input)
    if (trimmed.isBlank()) return ""
    val lower = trimmed.lowercase()
    // ① 已带协议 (大小写不敏感)
    if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
    // ② 协议相对 (//example.com/x)
    if (trimmed.startsWith("//")) return "https:$trimmed"
    if (!trimmed.contains(' ')) {
        // ③ 裸 IP / 局域网主机 → 明文 http
        if (isHostWithLiteralAddress(trimmed)) return "http://$trimmed"
        // ④ 域名形态 → 补 https (FQDN 尾点如 "github.com." 去掉尾点)
        if (looksLikeDomain(trimmed)) return "https://${stripTrailingFqdnDot(trimmed)}"
    }
    // ⑤ 搜索引擎
    return engine.url + URLEncoder.encode(trimmed, "UTF-8")
}

/**
 * 输入归一化: 全角 ASCII (U+FF01..U+FF5E) 与常见中文标点折算为半角, 去零宽/软连字符,
 * 换行/制表符折算空格, 首尾去空白。URL 中对全角字面量一律按半角处理 (与系统浏览器一致)。
 */
private fun normalizeInput(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        when {
            ch.code == 0x3000 -> sb.append(' ')                              // 全角空格
            ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar()) // 全角 ASCII
            ch == '\u3002' -> sb.append('.')                                 // 。
            ch == '\u3001' -> sb.append(',')                                 // 、
            ch == '\u00B7' -> sb.append('.')                                 // · (域名误打)
            ch.code in 0x200B..0x200D || ch == '\uFEFF' || ch == '\u00AD' -> Unit // 零宽/软连字符
            ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
            else -> sb.append(ch)
        }
    }
    return sb.toString().trim()
}

private val IPV4_PATTERN =
    Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?:[/?#].*)?$")

/**
 * 主机是否为字面地址形态 (无需 DNS 或本身就是局域网名):
 * 裸 IPv4 (可带端口/路径) / IPv6 字面量 `[::1]:8080` / localhost / 单标签主机名 + 显式端口
 * (`nas:5000`, `router:8080`; 要求主机段含字母, 避免把 "3:14" 这类时刻误判成 URL)。
 * 这些一律按 http:// 补全 — 裸 IP 上的 https 需要证书含 IP SAN, 现实中罕见。
 */
private fun isHostWithLiteralAddress(s: String): Boolean {
    if (IPV4_PATTERN.matches(s)) return true
    if (s.startsWith("[")) return true
    val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringBefore(':')
    if (host.isEmpty()) return false
    if (host.equals("localhost", ignoreCase = true)) return true
    val port = authority.substringAfter(':', "")
    return port.toIntOrNull() != null &&
        host.all { it.isLetterOrDigit() || it == '-' } &&
        host.any { it.isLetter() }
}

/**
 * 域名形态判定: host 段 (首个 / ? # : 之前, 去 FQDN 尾点) 的末段须为字母 TLD
 * (允许 punycode 的字母数字与连字符, 但必须含字母), 且整串字符合法 (见 [isUrlLike])。
 * 路径/查询可含中文与 URL 字符 → `zh.wikipedia.org/wiki/中文` 不再误判为搜索。
 */
private fun looksLikeDomain(s: String): Boolean {
    val authority = s.substringBefore('/').substringBefore('?').substringBefore('#')
    val host = authority.substringBefore(':').removeSuffix(".")
    val lastDot = host.lastIndexOf('.')
    if (lastDot <= 0 || lastDot == host.length - 1) return false
    val tld = host.substring(lastDot + 1)
    val tldOk = tld.length in 2..24 &&
        tld.any { it.isLetter() } &&
        tld.all { it.isLetterOrDigit() || it == '-' }
    return tldOk && isUrlLike(s)
}

/** URL 合法字符 (RFC 3986 常用子集) + 非 ASCII (中文等)。! 排除 (沿用保守约定, 防误判)。 */
private fun isUrlLike(s: String): Boolean =
    s.all { c -> c.isLetterOrDigit() || c.code > 127 || c in ".-_/?:#=&%+~@$'()*," }

/** 去掉 authority 段的 FQDN 尾点 (`github.com.` → `github.com`, 路径/查询原样保留)。 */
private fun stripTrailingFqdnDot(s: String): String {
    val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val authority = if (cut < 0) s else s.substring(0, cut)
    val rest = if (cut < 0) "" else s.substring(cut)
    return authority.removeSuffix(".") + rest
}

/**
 * URL 显示解码 — 百分号编码 (UTF-8) 还原为中文等可读字符，供地址栏显示。
 * 仅解码 %XX；query 的 `+` 先转义保留字面（不做空格语义转换）。解码失败回退原串。
 */
fun decodeUrlForDisplay(url: String): String {
    if (url.indexOf('%') < 0) return url
    return try {
        java.net.URLDecoder.decode(url.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        url
    }
}
