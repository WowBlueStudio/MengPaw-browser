// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
