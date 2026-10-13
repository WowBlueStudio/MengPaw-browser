// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
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
 *
 * v0.10.3 P1 修复 (四个问题):
 * ① 巨型图片无降采样直接解码 → OOM: 先 `inJustDecodeBounds` 取尺寸, 最长边超过
 *   [MAX_DIMENSION] 时按 `inSampleSize` 降采样再解码;
 * ② 异常路径不 `recycle()`: 位图在 `finally` 里统一回收; `compress` 返回 false 也不再假报成功;
 * ③ 同名文件静默覆盖: 落盘前经 [uniqueFileName] 追加 `_1/_2` 后缀;
 * ④ 固定 JPEG 丢透明度: `.png/.webp/.gif` 用 PNG 压缩, 其余仍用 JPEG (见 [compressFormatFor])。
 */
suspend fun downloadImageSuspending(ctx: Context, url: String): String? = withContext(Dispatchers.IO) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext null
    var conn: HttpURLConnection? = null
    var bmp: Bitmap? = null
    try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Chrome/120.0.0.0")
        conn.setRequestProperty("Referer", url)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.inputStream.use { bmp = decodeSampled(it, MAX_DIMENSION) }
        val bitmap = bmp ?: return@withContext null
        val name = targetName(url)
        val format = compressFormatFor(name)
        val dir = publicImageDir(ctx)
        dir.mkdirs()
        val file = File(dir, uniqueFileName(name) { candidate -> File(dir, candidate).exists() })
        val ok = file.outputStream().use { outs -> bitmap.compress(format, QUALITY, outs) }
        if (!ok) {
            android.util.Log.w(TAG, "图片压缩失败 (${format.name}): ${url.take(80)}")
            try { file.delete() } catch (_: Exception) { /* 清理失败不掩盖主因 */ }
            return@withContext null
        }
        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
        file.absolutePath
    } catch (e: Exception) {
        android.util.Log.e(TAG, "Image download failed: ${url.take(80)}", e)
        null
    } finally {
        bmp?.recycle() // v0.10.3: 成功/失败/提前 return 都回收
        conn?.disconnect()
    }
}

/** 公共相册目录 Pictures/MengPaw；不可写时回退应用私有 Pictures 目录。 */
private fun publicImageDir(ctx: Context): File {
    val pub = File(android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_PICTURES), "MengPaw")
    if (pub.exists() || pub.mkdirs()) return pub
    val fallback = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
    return if (fallback == null) File(ctx.filesDir, "Pictures/MengPaw") else File(fallback, "MengPaw")
}

// ── 纯逻辑 (可单测, 不碰文件系统/Android 运行时) ─────────────────────

/**
 * 从 URL 推导落盘文件名 (末段, 最长 100 字符); 无扩展名时补 `.jpg`。
 * 去 query/fragment, 空则按时间戳生成。
 */
fun targetName(url: String): String {
    val raw = url.substringAfterLast('/').substringBefore('?').substringBefore('#').take(100)
    val base = raw.ifBlank { "img_${System.currentTimeMillis()}" }
    return if (base.contains('.')) base else "$base.jpg"
}

/** 透明友好的扩展名走 PNG, 其余走 JPEG (v0.10.3: 固定 JPEG 会丢 alpha 通道)。 */
fun compressFormatFor(fileName: String): Bitmap.CompressFormat {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "webp", "gif" -> Bitmap.CompressFormat.PNG
        else -> Bitmap.CompressFormat.JPEG
    }
}

/** 同名时生成 `name` → `name_1` → `name_2` …; [taken] 判定名字是否已被占用。 */
fun uniqueFileName(name: String, taken: (String) -> Boolean): String {
    if (!taken(name)) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var i = 1
    while (taken("${stem}_$i$ext")) i++
    return "${stem}_$i$ext"
}

/**
 * 解码图片, 最长边限制为 [maxDim] (v0.10.3: 巨图先降采样再解码, 避免 OOM)。
 * 流不可回退 (mark/reset 不支持) 时退化为单次直接解码 — 宁可失败也不多读一遍。
 */
internal fun decodeSampled(input: InputStream, maxDim: Int): Bitmap? {
    if (!input.markSupported()) {
        return try { BitmapFactory.decodeStream(input) } catch (_: Exception) { null }
    }
    input.mark(MARK_LIMIT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        BitmapFactory.decodeStream(input, null, bounds)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "图片尺寸探测失败", e)
        return null
    } finally {
        try { input.reset() } catch (_: Exception) { /* reset 失败由下方 decode 失败兜底 */ }
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDim)
        inJustDecodeBounds = false
    }
    return try {
        BitmapFactory.decodeStream(input, null, opts)
    } catch (e: Exception) {
        android.util.Log.w(TAG, "图片解码失败", e)
        null
    }
}

/** 计算 2 的幂降采样系数, 使降采样后最长边不超过 [maxDim] (最长边已达标则返回 1)。 */
internal fun sampleSizeFor(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0 || maxDim <= 0) return 1
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= maxDim) {
        sample *= 2
        longest /= 2
    }
    return sample
}

/** 单边上限: 超过则降采样 (4096 足够屏幕展示, 又不至于 OOM)。 */
internal const val MAX_DIMENSION = 4096

/** 压缩质量统一 95 (JPEG 与 PNG 都接受该参数)。 */
private const val QUALITY = 95

/** mark/reset 缓冲上限 ~8MB, 超过则回退失败 (decode 返回 null, 不影响正确性)。 */
private const val MARK_LIMIT = 8 * 1024 * 1024

private const val TAG = "DownloadUtil"
