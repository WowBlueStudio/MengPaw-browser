// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * 标签页管理命令组（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 * 注册键: tabs / tab / tab.open / tab.close / tab.all（Agent 形态名为 browser.<键>）。
 *
 * v0.10.3 修复:
 * ① 标签上限与 UI 同源（[BrowserCommandContext.maxTabs]）— 此前硬编码 0..3，第 5 个标签
 *   对 Agent 不可见、不可切换，表头还写死 (n/4)；
 * ② tab.open / preload 如实回报（[BrowserCommandContext.tabOpener] 返回失败原因）—
 *   此前无论是否真的打开都回「正在打开」；
 * ③ tab.all 改为「切到该标签 → 等它加载完 → 读它自己的 WebView」，并只在未加载完时等待；
 *   此前用固定 300ms + 循环外的 wv，读到的是切换前那个标签的内容（张冠李戴）。
 */
internal class BrowserTabCommands(private val ctx: BrowserCommandContext) {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        "tabs" to ::tabs, "tab" to ::tab, "tab.open" to ::tabOpen,
        "tab.close" to ::tabClose, "tab.all" to ::tabAll
    )

    // ═══════════════════════════════════════════════════════════════════
    // Tab management
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun tabs(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val all = ctx.tabInfoProvider()
        if (all.isEmpty()) return ExecutionResult.ok("(无标签页)")
        return ExecutionResult.ok(buildString {
            appendLine("## 浏览器标签页 (${all.size}/${ctx.maxTabs})")
            appendLine("| ID | 状态 | URL | 标题 |")
            appendLine("|----|------|-----|------|")
            all.forEach { t ->
                val active = if (t.isActive) "▶" else " "
                val load = if (t.isLoading) "…" else "✓"
                appendLine("| $active ${t.id} | $load | ${t.url.take(50)} | ${t.title.take(30)} |")
            }
        })
    }

    private suspend fun tab(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab <N>  — 切换到标签页 N (0-${ctx.maxTabs - 1})\nbrowser.tabs  — 查看所有标签页",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull()
            ?: return ExecutionResult.fail("标签页ID必须是数字 0-${ctx.maxTabs - 1}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (id !in 0 until ctx.maxTabs) return ExecutionResult.fail(
            "标签页ID范围: 0-${ctx.maxTabs - 1}", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (ctx.tabInfoProvider().none { it.id == id }) return ExecutionResult.fail(
            "标签页 $id 不存在（用 browser.tabs 查看现有标签）", errorCode = ErrorCodes.ERR_NOT_FOUND)
        ctx.tabSwitcher(id)
        return ExecutionResult.ok("已切换到标签页 $id")
    }

    private suspend fun tabOpen(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab.open <N> <url>  — 在标签页N打开URL\nbrowser.tab.open 0 https://example.com",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull()
        if (id == null || args.size < 2) return ExecutionResult.fail(
            "Usage: browser.tab.open <N> <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args.drop(1).joinToString(" ")
        // v0.10.3: 如实回报 — 未真正打开（无 WebView / 达上限）时返回失败原因
        val err = ctx.tabOpener(id, url)
        return if (err == null) ExecutionResult.ok("标签页 $id 正在打开: $url")
        else ExecutionResult.fail(err, errorCode = ErrorCodes.ERR_INTERNAL)
    }

    private suspend fun tabClose(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail(
            "Usage: browser.tab.close <N>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull() ?: return ExecutionResult.fail("标签页ID必须是数字", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val tabs = ctx.tabInfoProvider()
        if (tabs.size <= 1) return ExecutionResult.fail("至少保留一个标签页")
        if (tabs.none { it.id == id }) return ExecutionResult.fail("标签页 $id 不存在", errorCode = ErrorCodes.ERR_NOT_FOUND)
        ctx.tabCloser(id)
        return ExecutionResult.ok("已关闭标签页 $id")
    }

    /** Extract content from ALL tabs — Agent's most efficient multi-source reading tool. */
    private suspend fun tabAll(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val all = ctx.tabInfoProvider()
        if (all.isEmpty()) return ctx.noBrowser()
        ctx.webViewProvider() ?: return ctx.noBrowser()
        val results = mutableListOf<String>()
        for (t in all) {
            // 切到该标签并等它加载完（最多 8s），再读**它自己**的 WebView
            if (!t.isActive) {
                ctx.tabSwitcher(t.id)
                val deadline = System.currentTimeMillis() + 8_000
                while (System.currentTimeMillis() < deadline) {
                    if (ctx.tabInfoProvider().firstOrNull { it.id == t.id }?.isLoading != true) break
                    kotlinx.coroutines.delay(120)
                }
            }
            val wv = ctx.webViewProvider()
            if (wv == null) {
                results.add("""{"tab":${t.id},"url":"${esc(t.url.take(80))}","error":"webview missing"}""")
                continue
            }
            val json = BrowserBridge(wv).content()
            results.add("""{"tab":${t.id},"url":"${esc(t.url.take(80))}",""" + extractContentFields(json) + "}")
        }
        return ExecutionResult.ok("## 全部标签页内容 (${all.size})\n\n" + results.joinToString("\n---\n"))
    }

    /** 从 page.content 的 JSON 里取 title/url 字段（JSON 解析，不再用正则抠引号）。 */
    private fun extractContentFields(json: String): String {
        return try {
            val o = org.json.JSONObject(json)
            """"title":"${esc(o.optString("title", ""))}","url":"${esc(o.optString("url", ""))}""""
        } catch (_: Exception) {
            """"error":"parse failed""""
        }
    }

    /** JSON 字符串字段转义（URL/标题可能含引号或反斜杠）。 */
    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
