// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mengpaw.browser.data.TabState

// ── WebChromeClient 工厂（自 WebViewFactory 拆出 — 400 行红线拆分批次） ──

/** 探针 WebView 兜底销毁时限: 超时仍未拿到可识别 URL 也必须释放（P1-1 泄漏修复）。 */
private const val PROBE_TIMEOUT_MS = 3000L

/**
 * 构建浏览器页面的 [WebChromeClient]（原 `WebViewFactory` 内联匿名对象迁出）。
 *
 * 拆分为逐字迁移；随后按并行审查落地了 5 处修复，均在下方逐条注明：
 * P1-1 探针 WebView 泄漏 + 未开 JS、P1-2 探针未过滤主帧、P1-3 `onConsoleMessage` 无条件吞日志、
 * P2-11 非摄像头/麦克风权限静默全授予、P2-12 定位 retain 每次重问。
 *
 * @param ctx WebView 的 Context（探针 WebView 与 `BrowserActivity` 授权桥都取自它）
 * @param tabId 目标标签 id（进度/标题/URL 变更经 [updateTab] 回写）
 * @param updateTab 标签状态更新入口 `(tabId, transform) -> Unit`
 * @param onNewWindow 站点 `window.open` / `target=_blank` 的真实 URL 回调（开新标签承接）
 * @param onConsole 站点 WARNING 以上 console 文本的可选旁路回调（默认空实现；日志行为不变）
 */
internal fun createBrowserChromeClient(
    ctx: android.content.Context,
    tabId: Int,
    updateTab: (Int, (TabState) -> TabState) -> Unit,
    onNewWindow: (String) -> Unit,
    onConsole: (String) -> Unit = {}
): WebChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, p: Int) { updateTab(tabId) { it.copy(progress = p) } }
    override fun onReceivedTitle(view: WebView?, t: String?) { updateTab(tabId) { it.copy(title = t ?: "") } }

    /**
     * 站点 JS 报错/告警可见化（累计修改 — 兼容性诊断）。
     *
     * 背景: 站点「分类菜单点不开 / 浮层弹窗不出 / 100% 高容器异常」这类**局部功能失效**，
     * 现场只能靠猜；而 WebView 的 console 默认只进 `chromium` tag 且常被忽略。
     * 这里把 WARNING 以上（含页面注入的 `[mp] JS error` 钩子）打到 `MpWebConsole`，
     * 便于经 ADB `logcat -s MpWebConsole` 直接看到站点到底报了什么错。
     *
     * P1-3 修复: 原实现**无条件 `return true`** ⇒ 站点/第三方 SDK 的 `console.log/DEBUG`
     * 被我们全程吞掉（Chrome Remote Debugging 里也看不到）。现在 WARNING+ 才接管并返回 true，
     * 其余交回默认通道。
     */
    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
        val m = msg ?: return false
        if (m.messageLevel() >= ConsoleMessage.MessageLevel.WARNING) {
            val line = "[${m.sourceId()}:${m.lineNumber()}] ${m.message()?.take(400)}"
            // 双通道落盘: 站点 JS 报错必须能在 logcat 被抑制的设备上取证
            DiagLog.append(ctx, "console", line)
            onConsole(line)
            return true
        }
        return false
    }

    /**
     * 弹窗（`window.open` / `target=_blank`）承接（累计修改 — 兼容性修复）。
     *
     * 背景: `setSupportMultipleWindows(true)` 之前未开 → 站点用 window.open 打开的
     * **浮层弹窗/快捷查看/登录窗全部静默不出现**（用户报障「浮层弹窗全部没有加载」）。
     * 这里用一次性探针 WebView 拿到真实 URL，交给标签系统开新标签（与 Chrome 行为一致），
     * 再销毁探针。
     *
     * P1-1 修复: ① 探针**开 JS** —— 弹窗若靠 `location.href=…` 跳转，不开 JS 则
     * `shouldOverrideUrlLoading` 永不触发 ⇒ 探针既不销毁、弹窗也永久空白；
     * ② 3s 超时兜底销毁（未触发时释放 WebView，避免泄漏）；③ "已触发"布尔守卫，
     * 同一弹窗只开一个标签。P1-2 修复: 仅主帧放行 —— 子 iframe 的外链不应开标签（可被刷）。
     */
    override fun onCreateWindow(
        view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
    ): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val probe = WebView(ctx)
        try { probe.settings.javaScriptEnabled = true } catch (_: Exception) { }
        var opened = false
        var timeout: Runnable? = null
        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                // P1-2: 子资源/子 iframe 一律不接管（否则站内 iframe 外链会被当作弹窗开标签）
                if (request?.isForMainFrame != true) return false
                if (opened) return true
                val u = request.url?.toString()
                if (!u.isNullOrBlank() && !u.startsWith("about:") && !u.startsWith("data:")) {
                    opened = true
                    timeout?.let { t -> try { probe.removeCallbacks(t) } catch (_: Exception) { } }
                    onNewWindow(u)
                }
                try { v?.stopLoading(); v?.destroy() } catch (_: Exception) { }
                return true
            }
        }
        // 超时兜底: 未拿到可识别 URL（被站点脚本阻断/无跳转）也必须释放探针
        val task = Runnable {
            if (!opened) {
                try { probe.stopLoading(); probe.destroy() } catch (_: Exception) { }
            }
        }
        timeout = task
        try { probe.postDelayed(task, PROBE_TIMEOUT_MS) } catch (_: Exception) { }
        transport.webView = probe
        resultMsg.sendToTarget()
        return true
    }
    /**
     * 站点 `<input type=file>` / 上传按钮 → 系统文件选择器（累计修改 — 兼容性）。
     * 未实现时任何"上传图片/附件"按钮点了毫无反应（Edge/Chrome 均支持）。
     *
     * P2-7 修复: `launchFileChooser` 返回 false / 抛异常时（Activity 侧只清了自身的
     * `pendingFileCallback`）必须在此**显式 `onReceiveValue(null)`** —— 否则站点的 form
     * 永久 pending（"上传中"卡死，且无法再次触发选择器）。
     */
    override fun onShowFileChooser(
        view: WebView?,
        filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val cb = filePathCallback ?: return false
        val params = fileChooserParams ?: run {
            cb.onReceiveValue(null); return false
        }
        val activity = ctx as? com.mengpaw.browser.BrowserActivity
        if (activity == null) { cb.onReceiveValue(null); return false }
        val ok = try {
            activity.launchFileChooser(params, cb)
        } catch (e: Exception) {
            android.util.Log.w("BrowserActivity", "文件选择器启动异常: ${e.message}")
            false
        }
        if (!ok) {
            try { cb.onReceiveValue(null) } catch (_: Exception) { }
            return false
        }
        return true
    }

    /**
     * 摄像头/麦克风请求（getUserMedia）→ 系统授权（累计修改 — 兼容性）。
     * 之前不实现 ⇒ 站点任何"扫码/拍照/语音/视频通话"能力直接失败。
     *
     * P2-11 修复: 非摄像头/麦克风资源（MIDI / 受保护媒体等）此前**静默全部授予** ——
     * 与"最小权限"原则相悖，现改为一律 `deny()` 并记日志。
     */
    override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
        val req = request ?: return
        val want = req.resources.toList()
        val perms = buildList {
            if (want.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) add(android.Manifest.permission.CAMERA)
            if (want.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) add(android.Manifest.permission.RECORD_AUDIO)
        }
        val activity = ctx as? com.mengpaw.browser.BrowserActivity
        if (activity == null || perms.isEmpty()) {
            try { req.deny() } catch (_: Exception) { }
            android.util.Log.w(
                "BrowserActivity",
                "拒绝非摄像头/麦克风权限请求: ${want.joinToString(limit = 6)}"
            )
            return
        }
        activity.requestRuntimePermissions(perms.toTypedArray()) { granted ->
            try {
                if (granted) req.grant(want.toTypedArray()) else req.deny()
            } catch (_: Exception) { }
        }
    }

    /**
     * 地理位置请求 → 系统授权（累计修改 — 兼容性）。
     * 之前不实现 ⇒ 站点定位功能（门店/骑行路线等）直接失败且无任何提示。
     *
     * P2-12 修复: `retain` 由 false 改 **true** —— 站点 `watchPosition`/连续定位
     * 每次都回调本方法，retain=false 会导致系统授权框反复弹出（甚至被系统限流）。
     */
    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback?
    ) {
        val cb = callback ?: return
        val o = origin ?: return
        val activity = ctx as? com.mengpaw.browser.BrowserActivity
        if (activity == null) { cb.invoke(o, false, false); return }
        activity.requestRuntimePermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) { granted ->
            // retain=true: 本次会话内记住结果，避免站点连续定位请求反复弹系统框
            try { cb.invoke(o, granted, true) } catch (_: Exception) { }
        }
    }

    /**
     * 视频全屏（累计修改 — 兼容性）: 之前不实现 ⇒ 站点视频的"全屏"按钮无效。
     */
    override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
        val v = view ?: return
        val activity = ctx as? com.mengpaw.browser.BrowserActivity
        if (activity == null) { callback?.onCustomViewHidden(); return }
        if (activity.isFullscreenActive()) { callback?.onCustomViewHidden(); return }
        activity.enterFullscreen(v, callback)
    }

    override fun onHideCustomView() {
        (ctx as? com.mengpaw.browser.BrowserActivity)?.exitFullscreen()
    }
}
