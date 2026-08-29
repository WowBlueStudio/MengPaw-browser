// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.browser.bridge.BrowserBridge
import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes

/**
 * 标签页管理命令组（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 * tabs/tab/tab.open/tab.close/tab.all。
 * 半自动武器 Phase 3 去重 (决策 #4): nav → page.goto 覆盖已删除; batch/q 过渡期保留后亦去重。
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
            appendLine("## 浏览器标签页 (${all.size}/4)")
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
            "Usage: browser.tab <N>  — 切换到标签页 N (0-3)\nbrowser.tabs  — 查看所有标签页",
            errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val id = args[0].toIntOrNull() ?: return ExecutionResult.fail("标签页ID必须是数字 0-3", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (id !in 0..3) return ExecutionResult.fail("标签页ID范围: 0-3", errorCode = ErrorCodes.ERR_INVALID_INPUT)
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
        ctx.tabOpener(id, url)
        return ExecutionResult.ok("标签页 $id 正在打开: $url")
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
        // Switch to each tab and extract content
        val results = mutableListOf<String>()
        val wv = ctx.webViewProvider() ?: return ctx.noBrowser()
        for (t in all) {
            if (!t.isActive) ctx.tabSwitcher(t.id)
            kotlinx.coroutines.delay(300) // brief settle
            results.add(BrowserBridge(wv).content().let { json ->
                try {
                    val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""
                    """{"tab":${t.id},"url":"${t.url.take(80)}","title":"$title"}"""
                } catch (_: Exception) { """{"tab":${t.id},"url":"${t.url}","error":"parse failed"}""" }
            })
        }
        return ExecutionResult.ok("## 全部标签页内容 (${all.size})\n\n" + results.joinToString("\n---\n"))
    }
}
