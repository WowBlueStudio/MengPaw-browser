// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.mengpaw.browser.data.TabState

// ── 下载承接（自 WebViewFactory 拆出 — 400 行红线拆分批次） ──

private const val TAG = "BrowserActivity"

/** 文件名长度上限（畸形 `Content-Disposition` 可能给出超长名字 — P2-10）。 */
internal const val MAX_FILE_NAME_CHARS = 120

/** blob 分片读取粒度: 单次 `evaluateJavascript` 回包要过 Binder, 256K 字符(~340KB) 安全。 */
private const val BLOB_CHUNK_CHARS = 256 * 1024

/** blob 轮询: 每 250ms 查一次长度, 最多 40 次（≈10s）。 */
private const val BLOB_POLL_INTERVAL_MS = 250L
private const val BLOB_MAX_POLLS = 40

/** 单次下载内存上限: data URL 总字符数（≈18MB 文件），超出直接放弃并说明。 */
private const val MAX_BLOB_CHARS = 24_000_000

/**
 * 安装下载监听（原 `WebViewFactory` 内联 `setDownloadListener` 迁出）。
 *
 * 背景（累计修改 — 兼容性）: 之前不实现 ⇒ 站点的"下载尺寸表/说明书"等按钮
 * 点了毫无反应（且会把加载指示器留在转圈状态）。http/https 交给系统 DownloadManager
 * 落到公共下载目录（用户可见、可分享），并顺带清掉加载态。
 *
 * P1-5 修复: **按 scheme 分流** —— DownloadManager 只接受 http/https，
 * `blob:`（站点 canvas 导出/前端生成文件）交给它必抛 `IllegalArgumentException`，
 * 下载直接失败。现在 `blob:` 走 JS `fetch` + `FileReader.readAsDataURL` 取回内容再落盘，
 * 绝不交给 DownloadManager。P2-10 修复: 文件名先消毒（路径分隔符/控制字符/超长）。
 */
internal fun installDownloadListener(
    webView: WebView,
    ctx: Context,
    tabId: Int,
    updateTab: (Int, (TabState) -> TabState) -> Unit
) {
    webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        updateTab(tabId) { it.copy(isLoading = false) }
        val fileName = sanitizeFileName(
            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        )
        when {
            url.startsWith("blob:", ignoreCase = true) -> fetchBlobAndSave(webView, ctx, url, fileName)
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) ->
                enqueueSystemDownload(ctx, url, userAgent, mimeType, fileName)
            url.startsWith("data:", ignoreCase = true) -> saveDataUrlAsync(ctx, url, fileName)
            else -> toast(ctx, "不支持下载该地址: ${url.take(60)}")
        }
    }
}

/** 系统 DownloadManager 下载（仅 http/https）—— 迁移前原逻辑，逐字保留。 */
private fun enqueueSystemDownload(
    ctx: Context,
    url: String,
    userAgent: String?,
    mimeType: String?,
    fileName: String
) {
    try {
        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
            .setMimeType(mimeType)
            .setTitle(fileName)
            .setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        // 带上站点 Cookie（下载受保护文件需要）
        try {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
        } catch (_: Exception) { }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
        if (dm != null) {
            dm.enqueue(request)
            toast(ctx, "开始下载: $fileName")
        } else {
            toast(ctx, "系统下载服务不可用")
        }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "下载失败: ${url.take(120)}", e)
        toast(ctx, "下载失败: ${e.message}")
    }
}

// ── blob: / data: 前端生成内容 ─────────────────────────────────────

/**
 * `blob:` 下载（P1-5）: 页面内 `fetch(blobUrl)` → `blob()` → `FileReader.readAsDataURL`
 * 把内容留在页面上下文，再分片取回 Kotlin 侧落盘。
 */
private fun fetchBlobAndSave(wv: WebView, ctx: Context, blobUrl: String, fileName: String) {
    val kick = "(function(){try{window.__mpBlobData=null;window.__mpBlobLen=0;" +
        "fetch(" + org.json.JSONObject.quote(blobUrl) + ")" +
        ".then(function(r){return r.blob()})" +
        ".then(function(b){var fr=new FileReader();" +
        "fr.onload=function(){window.__mpBlobData=String(fr.result||'');" +
        "window.__mpBlobLen=window.__mpBlobData.length};" +
        "fr.onerror=function(){window.__mpBlobLen=-1};fr.readAsDataURL(b)})" +
        ".catch(function(){window.__mpBlobLen=-1})}catch(e){window.__mpBlobLen=-1}})();"
    try {
        wv.evaluateJavascript(kick, null)
    } catch (e: Exception) {
        toast(ctx, "下载失败(blob): ${e.message}")
        return
    }
    pollBlobLength(wv, ctx, fileName, 0)
}

/** 轮询页面侧 blob 长度（异步链式，避免阻塞 UI 线程）。 */
private fun pollBlobLength(wv: WebView, ctx: Context, fileName: String, attempt: Int) {
    if (attempt > BLOB_MAX_POLLS) {
        toast(ctx, "下载失败(blob): 读取超时")
        return
    }
    evalJs(wv, "String(window.__mpBlobLen||0)") { raw ->
        when (val len = parseJsString(raw)?.toLongOrNull()) {
            null, 0L -> try {
                if (!wv.postDelayed({ pollBlobLength(wv, ctx, fileName, attempt + 1) }, BLOB_POLL_INTERVAL_MS)) {
                    toast(ctx, "下载失败(blob): 页面已关闭")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "blob 轮询投递失败: ${e.message}")
            }
            -1L -> toast(ctx, "下载失败(blob): 无法读取内容")
            else -> readBlobChunks(wv, ctx, fileName, len, 0L, StringBuilder())
        }
    }
}

/** 分片取回 data URL（每片过 Binder，避免超大字符串回包失败）。 */
private fun readBlobChunks(
    wv: WebView,
    ctx: Context,
    fileName: String,
    total: Long,
    offset: Long,
    acc: StringBuilder
) {
    if (offset >= total) {
        releaseBlobBuffer(wv)
        saveDataUrlAsync(ctx, acc.toString(), fileName)
        return
    }
    val take = minOf(BLOB_CHUNK_CHARS.toLong(), total - offset)
    evalJs(wv, "(window.__mpBlobData||'').substr($offset,$take)") { raw ->
        val chunk = parseJsString(raw)
        if (chunk.isNullOrEmpty()) {
            toast(ctx, "下载失败(blob): 分片读取中断")
            return@evalJs
        }
        acc.append(chunk)
        if (acc.length > MAX_BLOB_CHARS) {
            releaseBlobBuffer(wv)
            toast(ctx, "下载失败(blob): 文件过大")
            return@evalJs
        }
        readBlobChunks(wv, ctx, fileName, total, offset + take, acc)
    }
}

/** 取回后释放页面侧大字符串（否则驻留在页面堆里直到导航）。 */
private fun releaseBlobBuffer(wv: WebView) {
    evalJs(wv, "window.__mpBlobData=null;window.__mpBlobLen=0", null)
}

/** data URL（页面直接给出，或 blob 分片拼回）→ 解码落盘（后台线程，避免卡 UI）。 */
private fun saveDataUrlAsync(ctx: Context, dataUrl: String, fileName: String) {
    Thread {
        val bytes = decodeDataUrl(dataUrl)
        val message = when {
            bytes == null -> "下载失败: 无法解析文件内容"
            else -> writeToDownloads(ctx, fileName, bytes)
                ?.let { "已保存: $it" }
                ?: "下载失败: 写入下载目录失败"
        }
        Handler(Looper.getMainLooper()).post {
            try { android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) { }
        }
    }.start()
}

/** 解码 data URL（base64 或百分号编码文本）。 */
internal fun decodeDataUrl(dataUrl: String): ByteArray? {
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return null
    val meta = dataUrl.substring(0, comma)
    val payload = dataUrl.substring(comma + 1)
    return try {
        if (meta.contains(";base64", ignoreCase = true)) {
            android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "data URL 解码失败: ${e.message}")
        null
    }
}

/** 写公共下载目录；不可写时依次回退应用外部/内部目录（返回真实落盘路径）。 */
private fun writeToDownloads(ctx: Context, fileName: String, bytes: ByteArray): String? {
    val dirs = ArrayList<java.io.File>(3)
    publicDownloadsDir()?.let { dirs.add(it) }
    ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.let { dirs.add(it) }
    dirs.add(java.io.File(ctx.filesDir, "downloads"))
    for (dir in dirs) {
        val file = java.io.File(dir, fileName)
        try {
            if (!dir.exists() && !dir.mkdirs()) continue
            file.outputStream().use { it.write(bytes) }
            return file.absolutePath
        } catch (e: Exception) {
            android.util.Log.w(TAG, "写入下载目录失败 ${file.absolutePath}: ${e.message}")
        }
    }
    return null
}

@Suppress("DEPRECATION")
private fun publicDownloadsDir(): java.io.File? = try {
    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
} catch (_: Exception) {
    null
}

// ── 纯逻辑（可单测） ───────────────────────────────────────────────

/**
 * 文件名消毒（P2-10）: 只保留**末段**文件名，剔除路径分隔符/控制字符/Windows 保留字符，
 * 截断到 [MAX_FILE_NAME_CHARS]；结果为空时给带时间戳的兜底名。
 * 防的是畸形 `Content-Disposition`（`filename="../../x"`、含 `\0`、超长）。
 */
internal fun sanitizeFileName(raw: String): String {
    val tail = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = buildString(tail.length) {
        for (ch in tail) {
            append(if (ch.isISOControl() || ch in "\\/:*?\"<>|") '_' else ch)
        }
    }
    val trimmed = cleaned.trim().trimEnd('.').take(MAX_FILE_NAME_CHARS)
    return if (trimmed.isBlank()) "download_${System.currentTimeMillis()}" else trimmed
}

/** `evaluateJavascript` 的回包是 JSON 字面量 —— 解出真实字符串（`null` → null）。 */
internal fun parseJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return try {
        org.json.JSONTokener(raw).nextValue() as? String
    } catch (e: Exception) {
        android.util.Log.w(TAG, "JS 回包解析失败: ${e.message}")
        null
    }
}

// ── 小工具 ────────────────────────────────────────────────────────

private fun evalJs(wv: WebView, js: String, onResult: ((String?) -> Unit)?) {
    try {
        wv.evaluateJavascript(js) { value -> onResult?.invoke(value) }
    } catch (e: Exception) {
        android.util.Log.w(TAG, "JS 执行失败: ${e.message}")
        onResult?.invoke(null)
    }
}

private fun toast(ctx: Context, message: String) {
    try {
        android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_SHORT).show()
    } catch (_: Exception) { }
}
