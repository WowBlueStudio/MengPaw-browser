// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ── Download ──────────────────────────────────────────────────────

/**
 * 下载单张图片并落盘，返回保存路径 (失败返回 null)。
 *
 * v0.10.2 修复 (两个问题):
 * ① 调用侧此前用对话框的 `rememberCoroutineScope()` 启动下载, 同一点击的
 *   `onDismiss()` 会把该作用域取消 → 下载被静默中断, 而 Toast 已经报「已保存」;
 *   现在改为可挂起的函数, 由**活得更久的作用域** (BrowserApp 的 rememberCoroutineScope) 调用。
 * ② 落盘目录改为公共相册 `Pictures/MengPaw` (图库可见, 亦为 Agent 可读的公共目录),
 *   失败才回退应用私有目录 — 旧实现固定写私有目录, 用户在图库里找不到。
 */
suspend fun downloadImageSuspending(ctx: Context, url: String): String? = withContext(Dispatchers.IO) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
    var conn: HttpURLConnection? = null
    try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Chrome/120.0.0.0")
        conn.setRequestProperty("Referer", url)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream) ?: return@withContext null
        val name = url.substringAfterLast('/').substringBefore('?').substringBefore('#').take(100)
            .ifBlank { "img_${System.currentTimeMillis()}" }
            .let { if (it.contains('.') && it.length <= 100) it else "$it.jpg" }
        val dir = publicImageDir(ctx)
        dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
        file.absolutePath
    } catch (e: Exception) {
        android.util.Log.e("BrowserActivity", "Image download failed: ${url.take(80)}", e)
        null
    } finally {
        conn?.disconnect()
    }
}

/** 公共相册目录 Pictures/MengPaw；不可写时回退应用私有 Pictures 目录。 */
private fun publicImageDir(ctx: Context): File {
    val pub = File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_PICTURES), "MengPaw")
    if (pub.exists() || pub.mkdirs()) return pub
    return File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "MengPaw")
}
