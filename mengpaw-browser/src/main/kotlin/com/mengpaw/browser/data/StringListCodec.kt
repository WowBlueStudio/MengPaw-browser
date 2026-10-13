// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

/**
 * 字符串列表 ⇄ 单条 pref 字符串的编解码器 (v0.10.3)。
 *
 * 背景 (数据丢失 P0): 书签与标签会话此前用 `joinToString(",")` / `joinToString("|")` 落盘,
 * 而 URL 里合法地含 `,` (查询串) 或 `|` — 往返一次即被拆成两条垃圾项,
 * `isBookmarked` / `removeBookmark` 因此永久失效。
 *
 * 修复口径:
 * - 写入统一为 JSON 数组 (`["https://a.com/x?a=1,2", ...]`), 任何字符都安全;
 * - 读取先按 JSON 解析; JSON 解析失败时按旧分隔符兼容拆分,
 *   保证老用户已存的逗号/竖线数据不丢 (宁可拆分也不整段丢弃)。
 *
 * 实现说明: 手写极小 JSON 编解码, 只用 Kotlin 标准库 —— JVM 单元测试拿到的
 * `android.jar` 里 `org.json` 全是抛异常的桩, 用桩实现会让本模块单测无法覆盖
 * 编码/解码往返 (而这是本次 P0 的回归面)。输出是标准 JSON, Android 运行时的
 * `org.json.JSONArray` / 任何合规解析器都能读。
 */
object StringListCodec {

    /** 编码为 JSON 数组字符串。空列表 → `[]`。 */
    fun encode(items: List<String>): String {
        val sb = StringBuilder(items.size * 24 + 2)
        sb.append('[')
        items.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append('"')
            appendEscaped(sb, s)
            sb.append('"')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * 解码: 先按 JSON 数组解析, 失败则按 [legacySeparator] 兼容拆分 (老数据)。
     * 空白输入返回空列表。
     */
    fun decode(raw: String?, legacySeparator: Char): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        if (text.startsWith("[")) parseJsonArray(text)?.let { return it }
        return text.split(legacySeparator).map { it.trim() }.filter { it.isNotBlank() }
    }

    // ── 编码 ────────────────────────────────────────────────────────

    private fun appendEscaped(sb: StringBuilder, s: String) {
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
    }

    // ── 解码 ────────────────────────────────────────────────────────

    /** 解析失败返回 null (调用方转旧分隔符兼容路径), 不抛异常。 */
    private fun parseJsonArray(text: String): List<String>? {
        val p = JsonCursor(text)
        return try {
            p.skipWs()
            if (p.next() != '[') return null
            val out = ArrayList<String>()
            p.skipWs()
            if (p.peek() == ']') { p.next(); p.skipWs(); return if (p.atEnd()) out else null }
            while (true) {
                p.skipWs()
                if (p.next() != '"') return null
                out.add(p.readString())
                p.skipWs()
                when (p.next()) {
                    ',' -> continue
                    ']' -> break
                    else -> return null
                }
            }
            p.skipWs()
            if (!p.atEnd()) return null
            out
        } catch (_: Exception) {
            null
        }
    }

    /** 极简 JSON 数组游标 (只认字符串元素)。 */
    private class JsonCursor(private val src: String) {
        private var i = 0
        fun atEnd(): Boolean = i >= src.length
        fun peek(): Char = if (i < src.length) src[i] else '\u0000'
        fun next(): Char {
            if (i >= src.length) throw IllegalStateException("unexpected end of json")
            return src[i++]
        }
        fun skipWs() {
            while (i < src.length && src[i] in " \t\r\n") i++
        }

        /** 读字符串体 (首个 `"` 已消费)。 */
        fun readString(): String {
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c != '\\' -> sb.append(c)
                    else -> readEscape(sb)
                }
            }
        }

        private fun readEscape(sb: StringBuilder) {
            when (val e = next()) {
                '"', '\\', '/' -> sb.append(e)
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> sb.append(readHex4())
                else -> throw IllegalStateException("bad escape \\$e")
            }
        }

        private fun readHex4(): Char {
            if (i + 4 > src.length) throw IllegalStateException("bad \\u escape")
            var v = 0
            repeat(4) {
                val d = Character.digit(src[i++], 16)
                if (d < 0) throw IllegalStateException("bad hex digit")
                v = v * 16 + d
            }
            return v.toChar()
        }
    }
}
