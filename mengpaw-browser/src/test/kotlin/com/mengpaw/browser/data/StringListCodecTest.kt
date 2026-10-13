// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StringListCodec 往返测试 — 锁死 v0.10.3 的 P0 数据丢失修复:
 * 含 `,` / `|` / 引号 / 反斜杠 / 中文的 URL 编解码后必须原样回到列表,
 * 且老用户遗留的逗号/竖线分隔数据读取时不丢。
 */
class StringListCodecTest {

    private fun roundTrip(items: List<String>, sep: Char = ','): List<String> =
        StringListCodec.decode(StringListCodec.encode(items), sep)

    @Test
    fun `空列表往返`() {
        assertEquals("[]", StringListCodec.encode(emptyList()))
        assertEquals(emptyList<String>(), roundTrip(emptyList()))
    }

    @Test
    fun `含逗号的 URL 不再被拆成两条`() {
        val url = "https://a.com/search?q=1,2,3&tags=x,y"
        val out = roundTrip(listOf(url))
        assertEquals(1, out.size)
        assertEquals(url, out[0])
        // 旧实现 (joinToString + split) 的失效证据: 往返后确实分裂
        assertNotEquals(1, url.split(",").size)
    }

    @Test
    fun `含竖线的 URL 不再被拆成两条`() {
        val url = "https://a.com/p?ids=1|2|3"
        val out = roundTrip(listOf(url), '|')
        assertEquals(1, out.size)
        assertEquals(url, out[0])
    }

    @Test
    fun `顺序与多条往返保持`() {
        val items = listOf("https://b.com/", "https://a.com/x,y", "https://c.com/z|w", "https://d.com/")
        assertEquals(items, roundTrip(items))
        assertEquals(items, roundTrip(items, '|'))
    }

    @Test
    fun `特殊字符转义往返`() {
        val items = listOf(
            "https://a.com/q?x=\"quoted\"",
            "https://a.com/path\\with\\backslash",
            "https://a.com/中文路径/搜索?q=标签",
            "https://a.com/tab\there",
            "https://a.com/emoji/😀",
            "https://a.com/brackets/[1]"
        )
        assertEquals(items, roundTrip(items))
    }

    @Test
    fun `编码结果是 JSON 数组形状`() {
        val s = StringListCodec.encode(listOf("https://a.com/", "https://b.com/"))
        assertTrue(s.startsWith("["))
        assertTrue(s.endsWith("]"))
        assertEquals(2, s.count { it == '"' } / 2)
    }

    @Test
    fun `老逗号分隔数据兼容读取`() {
        assertEquals(
            listOf("https://a.com/", "https://b.com/"),
            StringListCodec.decode("https://a.com/,https://b.com/", ',')
        )
        assertEquals(
            listOf("https://a.com/", "https://b.com/"),
            StringListCodec.decode("https://a.com/ , https://b.com/ ", ',')
        )
    }

    @Test
    fun `老竖线分隔数据兼容读取`() {
        assertEquals(
            listOf("https://a.com/", "https://b.com/"),
            StringListCodec.decode("https://a.com/|https://b.com/", '|')
        )
    }

    @Test
    fun `空与空白输入返回空列表`() {
        assertEquals(emptyList<String>(), StringListCodec.decode(null, ','))
        assertEquals(emptyList<String>(), StringListCodec.decode("", ','))
        assertEquals(emptyList<String>(), StringListCodec.decode("[]", ','))
        assertEquals(emptyList<String>(), StringListCodec.decode(" [ ] ", ','))
        assertEquals(emptyList<String>(), StringListCodec.decode("   ", ','))
        assertEquals(emptyList<String>(), StringListCodec.decode("|", '|'))
        assertEquals(emptyList<String>(), StringListCodec.decode(",", ','))
    }

    @Test
    fun `坏 JSON 降级到旧分隔符而不是整体丢弃`() {
        // 半个 JSON 数组: 解析失败 → 按旧分隔符兜底, 至少保住可识别片段
        assertEquals(listOf("[\"https://a.com/\"", "x"), StringListCodec.decode("[\"https://a.com/\",x", ','))
        // 非字符串元素的 JSON 数组同样降级
        assertEquals(listOf("[1", "2]"), StringListCodec.decode("[1,2]", ','))
    }

    @Test
    fun `trim 后以括号开头的旧数据仍可读`() {
        assertEquals(listOf("https://a.com/"), StringListCodec.decode("  https://a.com/  ", ','))
    }
}
