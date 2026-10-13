// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import com.mengpaw.browser.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能导航 URL 判定单测 (browser 首测, 2026-08-07 Codex 交接补充)。
 *
 * 覆盖 P2 fix 语义: 完整 URL 原样返回 / 域名补 https / 纯数字与小数不误判为域名 /
 * 关键词走搜索引擎 / 空输入返回空串。
 */
class SmartNavigateTest {

    @Test
    fun 完整URL原样返回() {
        assertEquals("http://example.com", smartNavigate("http://example.com", SearchEngine.BING))
        assertEquals("https://example.com/x", smartNavigate("https://example.com/x", SearchEngine.BING))
    }

    @Test
    fun 纯域名补https前缀() {
        assertEquals("https://example.com", smartNavigate("example.com", SearchEngine.BING))
        assertEquals("https://www.mengpaw.dev", smartNavigate("www.mengpaw.dev", SearchEngine.BING))
    }

    @Test
    fun 子域与连字符域名合法() {
        assertEquals("https://sub.domain.org", smartNavigate("sub.domain.org", SearchEngine.BING))
        assertEquals("https://my-site.io", smartNavigate("my-site.io", SearchEngine.BING))
    }

    @Test
    fun 纯数字不误判为域名() {
        // P2 回归: "3.14" 末段无字母, 按搜索处理而非 https://3.14
        val r = smartNavigate("3.14", SearchEngine.BING)
        assertTrue(r.startsWith("https://www.bing.com/search?q=3.14"))
    }

    @Test
    fun 关键词走搜索引擎() {
        val r = smartNavigate("hello world", SearchEngine.BAIDU)
        assertEquals("https://www.baidu.com/s?wd=hello+world", r)
    }

    @Test
    fun 中文关键词URL编码() {
        val r = smartNavigate("檬爪框架", SearchEngine.GOOGLE)
        assertTrue(r.startsWith("https://www.google.com/search?q="))
        assertTrue(r.contains("%E6%AA%AC%E7%88%AA"))
    }

    @Test
    fun 空白输入返回空串() {
        assertEquals("", smartNavigate("   ", SearchEngine.BING))
        assertEquals("", smartNavigate("", SearchEngine.BING))
    }

    @Test
    fun 非法字符域名按搜索处理() {
        // 含空格不是域名 → 搜索; 含非法字符 (!) 也不是域名 → 搜索
        assertTrue(smartNavigate("exa mple.com", SearchEngine.BING).startsWith("https://www.bing.com/search?q="))
        assertTrue(smartNavigate("example!.com", SearchEngine.BING).startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun 中文路径URL识别为URL而非搜索() {
        // v0.8.0 中文支持: 中文路径跟在域名后 → 补 https 原样保留中文
        assertEquals(
            "https://zh.wikipedia.org/wiki/中文",
            smartNavigate("zh.wikipedia.org/wiki/中文", SearchEngine.BING)
        )
        assertEquals(
            "https://www.example.com/路径?q=测试&page=1",
            smartNavigate("www.example.com/路径?q=测试&page=1", SearchEngine.BING)
        )
    }

    @Test
    fun 带路径无协议URL识别为URL() {
        // host 段判定: 首个 / 之前的末段含字母即域名, 路径原样保留
        assertEquals("https://example.com/path?q=1", smartNavigate("example.com/path?q=1", SearchEngine.BING))
    }

    @Test
    fun 纯中文关键词仍走搜索() {
        // 无点 → 搜索 (与 中文关键词URL编码 一致, 不误判为 URL)
        val r = smartNavigate("中文关键词", SearchEngine.BING)
        assertTrue(r.startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun 百分号编码URL解码为中文显示() {
        // 维基百科中文词条 (百分号编码) → 解码为中文
        assertEquals(
            "https://zh.wikipedia.org/wiki/中文",
            decodeUrlForDisplay("https://zh.wikipedia.org/wiki/%E4%B8%AD%E6%96%87")
        )
        // query 的 + 保留字面, 不做空格语义转换
        assertEquals("https://x.com/q=a+b", decodeUrlForDisplay("https://x.com/q=a+b"))
        // 无百分号原样返回
        assertEquals("https://example.com/plain", decodeUrlForDisplay("https://example.com/plain"))
        // 非法编码回退原串
        assertEquals("https://x.com/%zz", decodeUrlForDisplay("https://x.com/%zz"))
    }

    @Test
    fun 全角句点域名按域名处理() {
        // v0.10.2 回归: 中文输入法下 "." 常打成 "。", 旧实现判成关键词 → 只能靠搜索到达
        assertEquals("https://github.com", smartNavigate("github。com", SearchEngine.BING))
        assertEquals("https://github.com", smartNavigate("ｇｉｔｈｕｂ．ｃｏｍ", SearchEngine.BING))
        assertEquals("https://www.example.com/x", smartNavigate("www.example.com／x", SearchEngine.BING))
    }

    @Test
    fun 裸IP按HTTP处理() {
        // 裸 IP 上的 https 需证书含 IP SAN — 一律 http:// (用户报障样例 http://47.119.135.155/home)
        assertEquals("http://47.119.135.155/home", smartNavigate("47.119.135.155/home", SearchEngine.BING))
        assertEquals("http://47.119.135.155", smartNavigate("47.119.135.155", SearchEngine.BING))
        assertEquals("http://192.168.1.10:8080/admin", smartNavigate("192.168.1.10:8080/admin", SearchEngine.BING))
        assertEquals("http://[::1]:8080/", smartNavigate("[::1]:8080/", SearchEngine.BING))
    }

    @Test
    fun 局域网主机按HTTP处理() {
        assertEquals("http://localhost:3000", smartNavigate("localhost:3000", SearchEngine.BING))
        // 无端口的 localhost 也按主机处理 (与 Chrome 一致: 指向本机)
        assertEquals("http://localhost", smartNavigate("localhost", SearchEngine.BING))
        assertEquals("http://nas:5000", smartNavigate("nas:5000", SearchEngine.BING))
        // 无端口的单标签主机名不是 URL (仍是关键词)
        assertTrue(smartNavigate("mynas", SearchEngine.BING).startsWith("https://www.bing.com/search?q="))
        // "3:14" 时刻不被当成主机:端口
        assertTrue(smartNavigate("3:14", SearchEngine.BING).startsWith("https://www.bing.com/search?q="))
    }

    @Test
    fun 域名尾点与零宽字符容错() {
        assertEquals("https://github.com", smartNavigate("github.com.", SearchEngine.BING))
        assertEquals("https://github.com", smartNavigate("\u200Bgithub.com\uFEFF", SearchEngine.BING))
        assertEquals("https://github.com", smartNavigate("  github.com  ", SearchEngine.BING))
    }

    @Test
    fun 协议相对与大小写协议() {
        assertEquals("https://example.com/a", smartNavigate("//example.com/a", SearchEngine.BING))
        assertEquals("HTTP://example.com", smartNavigate("HTTP://example.com", SearchEngine.BING))
    }

    @Test
    fun 带端口域名按HTTPS处理() {
        assertEquals("https://example.com:8443/x", smartNavigate("example.com:8443/x", SearchEngine.BING))
    }

    @Test
    fun fromKey未知键回退BING() {
        assertEquals(SearchEngine.BING, SearchEngine.fromKey("no-such-engine"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromKey("google"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromKey("duckduckgo"))
    }
}
