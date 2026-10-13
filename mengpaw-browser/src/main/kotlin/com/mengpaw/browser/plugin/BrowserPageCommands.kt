// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import com.mengpaw.kernel.cli.ExecutionContext
import com.mengpaw.kernel.cli.ExecutionResult
import com.mengpaw.kernel.cli.ErrorCodes
import com.mengpaw.kernel.error.ErrorCollector

/**
 * 页面控制命令组（自 BuiltinBrowserPlugin 拆出 — 400 行文件拆分批次 1）。
 * 半自动武器 Phase 3 去重后保留: speed (inject/diff/preload) + wait 2 + cookies 3 + dialogs 2。
 * 被 page.* 覆盖的命令 (eval/click/type/scroll/content/screenshot/open/back/forward/
 * title/url/wait.selector) 已删除 (决策 #4)。
 */
internal class BrowserPageCommands(private val ctx: BrowserCommandContext) {

    val commands: Map<String, suspend (List<String>, ExecutionContext) -> ExecutionResult> = mapOf(
        // Speed-optimized
        "inject" to ::injectBridge, "diff" to ::diff, "preload" to ::preload,
        // Page wait
        "wait" to ::waitMs, "wait.nav" to ::waitNav,
        // Cookies
        "cookies" to ::cookiesGet, "cookies.set" to ::cookiesSet, "cookies.clear" to ::cookiesClear,
        // Dialogs
        "dialog.accept" to ::dialogAccept, "dialog.dismiss" to ::dialogDismiss
    )

    // ═══════════════════════════════════════════════════════════════════
    // Speed-optimized commands
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Inject persistent __mp bridge once per page.
     * After injection, all subsequent commands use ~15-char calls instead of ~500-char scripts.
     * Speed gain: ~33x less data over the Java↔JS bridge per call.
     */
    private suspend fun injectBridge(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try {
            val r = b.inject()
            ExecutionResult.ok(r + "\n\n后续命令将自动使用快速通道 (__mp.*)。")
        } catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.inject"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Get only changed content since last extraction — fraction of the data. */
    private suspend fun diff(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.diff()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.diff"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    /** Preload a URL in a background tab without switching focus. */
    private suspend fun preload(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.preload <url>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val url = args[0]
        val tabs = ctx.tabInfoProvider()
        // Find first non-active empty tab, or use the last allowed tab id
        val target = tabs.firstOrNull { !it.isActive && it.url.isBlank() }?.id
            ?: tabs.firstOrNull { !it.isActive }?.id
            ?: (ctx.maxTabs - 1)
        // v0.10.3: 如实回报（此前无论是否真的打开都回「后台预加载中」）
        val err = ctx.tabOpener(target, url)
        return if (err == null) ExecutionResult.ok(
            "后台预加载中 (标签页$target): $url\n使用 browser.tab $target 切换查看。")
        else ExecutionResult.fail(err, errorCode = ErrorCodes.ERR_INTERNAL)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Page wait commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun waitMs(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.isEmpty()) return ExecutionResult.fail("Usage: browser.wait <milliseconds>", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val ms = args[0].toLongOrNull() ?: return ExecutionResult.fail("Invalid milliseconds", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        if (ms > 30000) return ExecutionResult.fail("Max wait: 30000ms (30s)", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        kotlinx.coroutines.delay(ms)
        return ExecutionResult.ok("Waited ${ms}ms")
    }

    private suspend fun waitNav(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val timeout = args.firstOrNull()?.toIntOrNull() ?: 10000
        ctx.webViewProvider() ?: return ctx.noBrowser()
        val bridge = ctx.bridge ?: return ctx.noBrowser()
        // v0.10.3 P0 修复: 原实现先取 wv/bridge 却从不使用, 只 delay(timeout) 就回
        // 「Navigation wait completed」—— 不等待任何导航 (Agent 据此认为已跳转, 后续
        // 全在旧页面上操作)。改为真实等待加载状态 (domcontentloaded), 超时如实失败。
        return try {
            val r = bridge.waitForLoad("domcontentloaded", timeout)
            if (r.contains("\"ok\":true")) ExecutionResult.ok("Navigation wait completed\n$r")
            else ExecutionResult.fail("导航等待未满足: $r", errorCode = ErrorCodes.ERR_INTERNAL)
        } catch (e: Exception) {
            ErrorCollector.report(e, "BuiltinBrowser.waitNav")
            ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Cookie commands
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun cookiesGet(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.cookies()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.cookies"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun cookiesSet(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        if (args.size < 2) return ExecutionResult.fail("Usage: browser.cookies.set <name> <value> [domain]", errorCode = ErrorCodes.ERR_INVALID_INPUT)
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.cookieSet(args[0], args[1], args.getOrNull(2))) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.cookiesSet"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }
    private suspend fun cookiesClear(args: List<String>, exeCtx: ExecutionContext): ExecutionResult {
        val b = ctx.bridge ?: return ctx.noBrowser()
        return try { ExecutionResult.ok(b.cookieClear()) }
        catch (e: Exception) { ErrorCollector.report(e, "BuiltinBrowser.cookiesClear"); ExecutionResult.fail("${e.message}", errorCode = ErrorCodes.ERR_INTERNAL) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Dialog commands
    //
    // v0.10.3 诚实化: 旧实现注入 `if(window.__mpDialogCb){...}` —— 全仓库从未定义
    // `__mpDialogCb`，WebChromeClient 也未接管 onJsAlert/onJsConfirm/onJsPrompt，
    // 因此是**恒成功的空操作**（Agent 以为已确认，页面上的 confirm 仍挂着）。
    // 本应用不提供 JS 对话框队列（接管会吞掉人类用户看到的系统弹窗），故如实失败并给替代路径。
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun dialogAccept(args: List<String>, exeCtx: ExecutionContext): ExecutionResult =
        dialogUnsupported("accept")

    private suspend fun dialogDismiss(args: List<String>, exeCtx: ExecutionContext): ExecutionResult =
        dialogUnsupported("dismiss")

    private fun dialogUnsupported(action: String): ExecutionResult = ExecutionResult.fail(
        "本浏览器不支持自动应答 JS 对话框 (dialog.$action): 对话框由系统 UI 处理，脚本无法代为点击。" +
            "替代方案: 用 page.eval 预先覆盖 window.confirm/window.alert，或改走不触发对话框的交互路径。",
        errorCode = ErrorCodes.ERR_INVALID_INPUT
    )
}
