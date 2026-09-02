// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.content.Context

/**
 * PDF 预览 HTML 组装 (v0.10.0)。
 *
 * 读取 assets/pdf_viewer/viewer.html 模板 → 把 pdf 字节 base64 内联进
 * __MENGPAW_PDF_DATA__ 标记 → 把模板内相对资源改写为 file:///android_asset 绝对路径
 * (预览页经 loadUrl(file://cache) 宿主, 相对路径会失效 — 对齐 Markdown 大内容回退做法)。
 * worker / cmaps 由 viewer.js 内以绝对 asset 路径配置, 此处无需处理。
 */
object PdfViewerHtml {

    private const val TEMPLATE_PATH = "pdf_viewer/viewer.html"
    private const val DATA_MARKER = "<!--__MENGPAW_PDF_DATA__-->"
    private const val ASSET_PREFIX = "file:///android_asset/pdf_viewer/"

    /** 组装完整可加载 HTML; pdf 为空或模板缺失返回 null (后台线程调用)。 */
    fun render(context: Context, pdfBytes: ByteArray): String? {
        if (pdfBytes.isEmpty()) return null
        return try {
            val template = context.assets.open(TEMPLATE_PATH).readBytes().decodeToString()
            val b64 = java.util.Base64.getEncoder().encodeToString(pdfBytes)
            val dataScript = "<script>window.__MP_PDF_B64=\"$b64\";</script>"
            absolutize(template.replace(DATA_MARKER, dataScript))
        } catch (_: Exception) { null }
    }

    /** 模板相对资源 → asset 绝对路径 (缓存宿主页下相对引用失效)。 */
    private fun absolutize(html: String): String = html
        .replace("href=\"viewer.css\"", "href=\"${ASSET_PREFIX}viewer.css\"")
        .replace("src=\"pdf.min.js\"", "src=\"${ASSET_PREFIX}pdf.min.js\"")
        .replace("src=\"viewer.js\"", "src=\"${ASSET_PREFIX}viewer.js\"")
}
