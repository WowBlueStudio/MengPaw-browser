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
    fun fetchPdfBytes(ctx: Context, source: String): ByteArray? {
        val lower = source.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            var conn: java.net.HttpURLConnection? = null
            return try {
                conn = java.net.URL(source).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 MengPawBrowser"
                )
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code !in 200..299) {
                    android.util.Log.w(TAG, "PDF 下载失败 HTTP $code: ${source.take(120)}")
                    null
                } else {
                    conn.inputStream.use { readLimited(it, source) }
                }
            } catch (e: Exception) {
                // v0.10.3: 非 2xx / 传输异常原本静默返回 null, 补日志便于排障
                android.util.Log.w(TAG, "PDF 下载异常: ${source.take(120)}", e)
                null
            } finally {
                // 统一释放底连接 (含提前 return 的路径)
                try { conn?.disconnect() } catch (_: Exception) { /* 释放失败不影响结果 */ }
            }
        }
        return try {
            val input = openLocalSource(ctx, source) ?: return null
            input.use { readLimited(it, source) }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "PDF 本地读取失败: ${source.take(120)}", e)
            null
        }
    }

    /**
     * 拉取并写缓存 PDF, 返回缓存文件绝对路径; 失败/超限返回 null。
     * IO 线程调用。调用方随后把路径交给预览对话框。
     * 文件名加入 `uniqueCacheName` 去重: 同一毫秒内重复预览不再互相覆盖。
     */
    fun stagePdfForPreview(ctx: Context, source: String): String? = try {
        val bytes = fetchPdfBytes(ctx, source) ?: return null
        val dir = File(ctx.cacheDir, CACHE_DIR).apply { mkdirs() }
        val f = File(dir, uniqueCacheName(dir, System.currentTimeMillis()))
        f.writeBytes(bytes)
        f.absolutePath
    } catch (e: Exception) {
        android.util.Log.w(TAG, "PDF 缓存写入失败: ${source.take(120)}", e)
        null
    }

    /**
     * 清理 pdf_viewer 缓存残留 (v0.10.3 P1)。
     *
     * 旧实现的 `doc_<ts>.pdf` 每次预览都新增、从不删除, 缓存目录只增不减
     * (系统清缓存前会一直堆积)。策略: **超过 1 小时且不在最近 3 个之内**的删除,
     * 既兜住时间维度, 也保证最近的预览不因时钟异常被误删。
     * 调用方放在 IO 线程 (在 `BrowserActivity.onCreate` 调用)。
     */
    fun cleanupStaleCache(ctx: Context) {
        try {
            val dir = File(ctx.cacheDir, CACHE_DIR)
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            if (files.isEmpty()) return
            val now = System.currentTimeMillis()
            val stale = pickStale(files.map { CacheFile(it, it.lastModified(), it.length()) }, now)
            var removed = 0
            var bytes = 0L
            for (item in stale) {
                if (item.file.delete()) { removed++; bytes += item.bytes }
            }
            if (removed > 0) {
                android.util.Log.i(TAG, "PDF 缓存清理 $removed 个残留文件 (${bytes / 1024} KB)")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "PDF 缓存清理失败", e)
        }
    }

    /** 打开本地输入流 (IO): content URI / file URI / 裸路径。 */
    private fun openLocalSource(ctx: Context, source: String): java.io.InputStream? =
        when {
            source.lowercase().startsWith("content:") ->
                ctx.contentResolver.openInputStream(Uri.parse(source))
            source.lowercase().startsWith("file:") -> {
                val path = Uri.parse(source).path
                if (path == null) null else {
                    val f = File(path)
                    if (f.exists() && f.canRead()) f.inputStream() else null
                }
            }
            // 裸绝对路径兜底 (部分文件管理器以 path 形式给出)
            else -> {
                val f = File(source)
                if (f.exists() && f.canRead()) f.inputStream() else null
            }
        }

    /** 读满但限 MAX_PDF_BYTES; 超出抛异常 (上层返回 null)。 */
    private fun readLimited(input: java.io.InputStream, source: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PDF_BYTES) {
                android.util.Log.w(TAG, "PDF 超过 ${MAX_PDF_BYTES / (1024 * 1024)}MB 上限: ${source.take(120)}")
                throw IllegalStateException("PDF too large")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ── 纯逻辑 (可单测, 不碰文件系统) ──────────────────────────────────

    /** 缓存文件元数据 (清理判定的输入)。 */
    class CacheFile(val file: File, val lastModified: Long, val bytes: Long)

    /**
     * 选出应删除的残留: 超过 1 小时**且**不在最近 [KEEP_NEWEST] 个之内。
     * 纯函数, 便于单测。
     */
    fun pickStale(files: List<CacheFile>, now: Long): List<CacheFile> {
        if (files.size <= KEEP_NEWEST) return emptyList()
        val protected: List<CacheFile> = files.sortedByDescending { it.lastModified }.take(KEEP_NEWEST)
        return files.filter { f ->
            val isProtected = protected.any { it === f }
            !isProtected && now - f.lastModified > STALE_AFTER_MS
        }
    }

    /** 在已占用的名字集合下生成 `doc_<ts>.pdf` / `doc_<ts>_1.pdf` ... 纯函数。 */
    fun uniqueCacheName(taken: Set<String>, ts: Long): String {
        val base = "doc_$ts"
        if (!taken.contains("$base.pdf")) return "$base.pdf"
        var i = 1
        while (taken.contains("${base}_$i.pdf")) i++
        return "${base}_$i.pdf"
    }

    private fun uniqueCacheName(dir: File, ts: Long): String =
        uniqueCacheName((dir.list() ?: emptyArray()).toSet(), ts)

    private const val TAG = "PdfUtil"
    private const val CACHE_DIR = "pdf_viewer"
    private const val KEEP_NEWEST = 3
    private const val STALE_AFTER_MS = 60L * 60 * 1000
}
