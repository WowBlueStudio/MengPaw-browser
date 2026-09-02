// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mengpaw.browser.web.PdfViewerHtml
import com.mengpaw.browser.web.createPdfViewerWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF 预览对话框 (v0.10.0) — pdf.js WebView 渲染, 形态对齐 [BrowserMarkdownViewerDialog]。
 *
 * 内容以缓存 PDF 文件路径传入 (BrowserApp 已在 IO 线程 stage 好), 此处读字节 →
 * base64 内联进 assets/pdf_viewer/viewer.html → 写缓存 html 后 loadUrl(file)。
 * 不用 M3 AlertDialog (其 text 槽 verticalScroll 会压扁 WebView)。
 */
@Composable
fun BrowserPdfViewerDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    filePath: String?,
    title: String
) {
    if (!visible || filePath.isNullOrBlank()) return
    val ctx = LocalContext.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxSize().padding(5.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        title.ifBlank { "PDF 预览" },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                var html by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(filePath) {
                    html = null
                    error = null
                    val h = withContext(Dispatchers.Default) {
                        runCatching {
                            val bytes = File(filePath).readBytes()
                            PdfViewerHtml.render(ctx, bytes)
                        }.getOrNull()
                    }
                    if (h != null) html = h else error = "无法读取或渲染 PDF 文件"
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val h = html
                    val e = error
                    when {
                        e != null -> Text(
                            e, color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                        )
                        h == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else -> AndroidView(
                            factory = { createPdfViewerWebView(it) },
                            update = { wv ->
                                if (wv.tag != h) {
                                    wv.tag = h
                                    loadHtmlFile(wv, h)
                                }
                            },
                            onRelease = { wv ->
                                try { (wv.parent as? android.view.ViewGroup)?.removeView(wv) } catch (_: Exception) { }
                                try { wv.destroy() } catch (_: Exception) { }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/** 完整 HTML (含内联 base64) 写缓存文件后 loadUrl — 规避 data: URL 大内容截断。 */
private fun loadHtmlFile(wv: WebView, html: String) {
    try {
        val dir = File(wv.context.cacheDir, "pdf_viewer").apply { mkdirs() }
        val tmp = File(dir, "view_${System.currentTimeMillis()}.html")
        tmp.writeText(html)
        wv.loadUrl("file://${tmp.absolutePath}")
        tmp.deleteOnExit()
    } catch (_: Exception) {
        // 写缓存失败回退 loadDataWithBaseURL (小文件可用)
        wv.loadDataWithBaseURL(
            "file:///android_asset/pdf_viewer/", html, "text/html", "UTF-8", null
        )
    }
}
