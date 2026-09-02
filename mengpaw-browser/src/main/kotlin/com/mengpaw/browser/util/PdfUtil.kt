// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PDF 预览取数层 (v0.10.0)。
 *
 * 统一把「网络 .pdf URL / 本地 file / content URI」三类来源解析为字节,
 * 写缓存 PDF 文件后交由预览 WebView (pdf.js) 渲染。纯逻辑可测部分集中在 URL 判定。
 * 全部文件 IO 必须 try/catch; 调用方放在 IO 线程。
 */
object PdfUtil {

    /** 单文件上限: 超过不预览 (base64 内联会放大 ~1.33x, 过大撑爆内存)。 */
    const val MAX_PDF_BYTES = 30 * 1024 * 1024

    /** 判定 URL 是否指向 .pdf (去 query/fragment 后后缀匹配) — 与 .md 判定同构。 */
    fun isPdfUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').endsWith(".pdf", ignoreCase = true)

    /** 判定本地源 (file/content URI 字符串) 是否为 .pdf — 供 BrowserActivity intent 检测。 */
    fun isLocalPdf(uriString: String?, mime: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        if (isPdfUrl(uriString)) return true
        return mime.equals("application/pdf", ignoreCase = true) ||
            uriString.substringAfterLast('.', "").equals("pdf", ignoreCase = true)
    }

    /** 从 uri/path 提取可读显示名 (末段)。 */
    fun displayName(uriString: String): String =
        uriString.substringBefore('?').substringAfterLast('/')
            .takeIf { it.isNotBlank() } ?: "PDF 文档"

    /**
     * 拉取 PDF 字节 (IO 线程)。支持 http(s)/content/file/裸路径。
     * 读取失败或超限返回 null。
     */
    fun fetchPdfBytes(ctx: Context, source: String): ByteArray? = try {
        val input = openSource(ctx, source) ?: return null
        input.use { readLimited(it) }
    } catch (_: Exception) { null }

    /**
     * 拉取并写缓存 PDF, 返回缓存文件绝对路径; 失败/超限返回 null。
     * IO 线程调用。调用方随后把路径交给预览对话框。
     */
    fun stagePdfForPreview(ctx: Context, source: String): String? = try {
        val bytes = fetchPdfBytes(ctx, source) ?: return null
        val dir = File(ctx.cacheDir, "pdf_viewer").apply { mkdirs() }
        val f = File(dir, "doc_${System.currentTimeMillis()}.pdf")
        f.writeBytes(bytes)
        f.absolutePath
    } catch (_: Exception) { null }

    /** 打开输入流 (IO)。来源 scheme 各异, 逐类处理。 */
    private fun openSource(ctx: Context, source: String): java.io.InputStream? {
        val lower = source.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val conn = java.net.URL(source).openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MengPawBrowser"
                    )
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode !in 200..299) return null
                    conn.inputStream
                } catch (e: Exception) {
                    conn.disconnect()
                    null
                }
            }
            lower.startsWith("content:") ->
                ctx.contentResolver.openInputStream(Uri.parse(source))
            lower.startsWith("file:") -> {
                val path = Uri.parse(source).path ?: return null
                val f = File(path)
                if (f.exists() && f.canRead()) f.inputStream() else null
            }
            // 裸绝对路径兜底 (部分文件管理器以 path 形式给出)
            else -> {
                val f = File(source)
                if (f.exists() && f.canRead()) f.inputStream() else null
            }
        }
    }

    /** 读满但限 MAX_PDF_BYTES; 超出抛异常 (上层返回 null)。 */
    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PDF_BYTES) throw IllegalStateException("PDF too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
