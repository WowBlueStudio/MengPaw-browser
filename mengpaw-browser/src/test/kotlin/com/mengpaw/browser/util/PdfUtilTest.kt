// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** PdfUtil 纯逻辑判定测试 — URL / 本地源识别与显示名。 */
class PdfUtilTest {

    @Test
    fun `网络 URL - pdf 后缀判定`() {
        assertTrue(PdfUtil.isPdfUrl("https://a.com/x/report.pdf"))
        assertTrue(PdfUtil.isPdfUrl("https://a.com/x/manual.PDF"))
        assertTrue(PdfUtil.isPdfUrl("https://a.com/doc.pdf?token=1&v=2#page=3"))
        assertTrue(PdfUtil.isPdfUrl("http://a.com/file.pdf"))
    }

    @Test
    fun `网络 URL - 非 pdf 判定`() {
        assertFalse(PdfUtil.isPdfUrl("https://a.com/page.html"))
        assertFalse(PdfUtil.isPdfUrl("https://a.com/readme.md"))
        assertFalse(PdfUtil.isPdfUrl("https://a.com/api/download?id=5"))
        assertFalse(PdfUtil.isPdfUrl(""))
        assertFalse(PdfUtil.isPdfUrl("https://a.com/paper.pdfx"))
    }

    @Test
    fun `本地源 - pdf 识别`() {
        assertTrue(PdfUtil.isLocalPdf("file:///sdcard/a.pdf", "application/pdf"))
        assertTrue(PdfUtil.isLocalPdf("file:///sdcard/a.pdf", "application/octet-stream"))
        assertTrue(PdfUtil.isLocalPdf("content://com.x.doc/spec", "application/pdf"))
        // 无 mime 时按扩展名
        assertTrue(PdfUtil.isLocalPdf("file:///sdcard/manual.PDF", null))
    }

    @Test
    fun `本地源 - 非 pdf`() {
        assertFalse(PdfUtil.isLocalPdf("content://com.x.doc/spec", "text/markdown"))
        assertFalse(PdfUtil.isLocalPdf("file:///sdcard/a.md", "text/markdown"))
        assertFalse(PdfUtil.isLocalPdf("content://com.x.img/photo", "image/png"))
        assertFalse(PdfUtil.isLocalPdf(null, "application/pdf"))
        assertFalse(PdfUtil.isLocalPdf("", "application/pdf"))
    }

    @Test
    fun `显示名提取`() {
        assertEquals("report.pdf", PdfUtil.displayName("https://a.com/x/report.pdf"))
        assertEquals("manual.pdf", PdfUtil.displayName("content://doc/manual.pdf"))
        assertEquals("PDF 文档", PdfUtil.displayName("https://a.com/"))
    }

    // ── v0.10.3 缓存清理 / 缓存去重 (纯逻辑) ──────────────────────────

    private fun cacheFile(name: String, ageMinutes: Long): PdfUtil.CacheFile =
        PdfUtil.CacheFile(File(name), NOW - ageMinutes * 60_000, 1024)

    @Test
    fun `缓存清理 - 最近3个不删`() {
        // 4 个文件全都很旧, 但最近的 3 个受保护 → 只删最旧的 1 个
        val files = listOf(
            cacheFile("doc_1.pdf", 600), cacheFile("doc_2.pdf", 500),
            cacheFile("doc_3.pdf", 400), cacheFile("doc_4.pdf", 300)
        )
        val stale = PdfUtil.pickStale(files, NOW)
        assertEquals(listOf("doc_1.pdf"), stale.map { it.file.name })
    }

    @Test
    fun `缓存清理 - 最近3个之内即使很旧也不删`() {
        // 只有 3 个文件: 都受保护, 即使全部超过 1 小时也不动
        val files = listOf(
            cacheFile("a.pdf", 600), cacheFile("b.pdf", 500), cacheFile("c.pdf", 400)
        )
        assertTrue(PdfUtil.pickStale(files, NOW).isEmpty())
    }

    @Test
    fun `缓存清理 - 未超1小时的过期位不删`() {
        // 4 个文件, 最旧的仅在 20 分钟前 → 未超 1 小时, 不删
        val files = listOf(
            cacheFile("a.pdf", 20), cacheFile("b.pdf", 15),
            cacheFile("c.pdf", 10), cacheFile("d.pdf", 5)
        )
        assertTrue(PdfUtil.pickStale(files, NOW).isEmpty())
    }

    @Test
    fun `缓存清理 - 超出保留数的旧文件全删`() {
        val files = (1..5).map { cacheFile("doc_$it.pdf", it * 100L) }
        val stale = PdfUtil.pickStale(files, NOW)
        // 最新 3 个 (doc_1/2/3) 受保护, 其余两个过期即删
        assertEquals(listOf("doc_4.pdf", "doc_5.pdf"), stale.map { it.file.name })
    }

    @Test
    fun `缓存清理 - 不足3个不删`() {
        assertTrue(PdfUtil.pickStale(emptyList(), NOW).isEmpty())
        assertTrue(PdfUtil.pickStale(listOf(cacheFile("a.pdf", 999)), NOW).isEmpty())
        assertTrue(PdfUtil.pickStale(listOf(cacheFile("a.pdf", 999), cacheFile("b.pdf", 998)), NOW).isEmpty())
    }

    @Test
    fun `缓存名去重 - 同毫秒不覆盖`() {
        assertEquals("doc_7.pdf", PdfUtil.uniqueCacheName(emptySet(), 7L))
        assertEquals("doc_7_1.pdf", PdfUtil.uniqueCacheName(setOf("doc_7.pdf"), 7L))
        assertEquals("doc_7_2.pdf", PdfUtil.uniqueCacheName(setOf("doc_7.pdf", "doc_7_1.pdf"), 7L))
        // 其它时间戳的文件名不影响
        assertEquals("doc_7.pdf", PdfUtil.uniqueCacheName(setOf("doc_6.pdf", "junk"), 7L))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
