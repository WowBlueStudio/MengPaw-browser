// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import android.webkit.WebView
import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * 浏览器命令共享上下文（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 *
 * 21 条 browser.* 命令实现按组拆至 [BrowserTabCommands]/[BrowserPageCommands]/
 * [BrowserQueryCommands] — 各组类持有本 context, 公开 API 与行为零变化。
 *
 * v0.10.3: [tabOpener] 返回 null=成功 / 非 null=失败原因（此前返回 Unit，命令层
 * 无论是否真的打开都报成功 — 见 2.4 静默错位）；[maxTabs] 与 UI 同源。
 */
internal class BrowserCommandContext(
    val webViewProvider: () -> WebView?,
    val tabInfoProvider: () -> List<BrowserTab>,
    val tabSwitcher: (Int) -> Unit,
    val tabOpener: (Int, String) -> String?,
    val tabCloser: (Int) -> Unit
) {
    val bridge: BrowserBridge? get() {
        val wv = webViewProvider() ?: return null
        return BrowserBridge(wv)
    }

    /** 标签页上限（与 BrowserApp 的 maxTabs 同源，避免范围校验漂移）。 */
    val maxTabs: Int get() = BuiltinBrowserPlugin.maxTabs()

    fun noBrowser(): ExecutionResult =
        ExecutionResult.fail("浏览器未就绪，请先打开 MP 浏览器", errorCode = ErrorCodes.ERR_INTERNAL)
}
