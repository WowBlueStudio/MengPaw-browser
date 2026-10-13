// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.util.Log
import android.view.ViewTreeObserver
import android.webkit.WebView

/** 兜底延时: 极端情况下(始终收不到 OnGlobalLayout)也必须在此时限后加载。 */
private const val LOAD_FALLBACK_DELAY_MS = 300L

/**
 * **布局就绪后再加载** —— 修复站点「100vh 首屏被挤成窄条」类缺陷（累计修改）。
 *
 * 事故背景: `AndroidView` 的 `factory` 阶段 WebView **尚未被测量/布局**（尺寸 0×0），
 * 此时 `loadUrl()` 会让站点 JS 在 `window.innerHeight == 0` 下完成首屏计算并缓存
 * （trekbikes.com 每个 `100vh` hero 段被算成一条窄条）；WebView 随后布局到全尺寸，
 * 但站点不在 `resize` 时重算 ⇒ 永久保持矮条。官方 Edge 无此问题（不在 0 尺寸下加载）。
 *
 * 策略:
 * - 已测得非 0 尺寸 → 立即加载（等价旧行为，零额外延迟）；
 * - 否则挂一次性 [ViewTreeObserver.OnGlobalLayoutListener]，**首次**测得非 0 尺寸即加载并注销；
 * - 同时挂 [LOAD_FALLBACK_DELAY_MS] 兜底，避免极端情况下永不加载；
 * - 同一次调用**幂等**（局部 flag，防"监听 + 兜底"双触发）；
 * - 已销毁/`post` 失败等异常路径只记日志，**不抛异常**。
 */
internal fun WebView.loadWhenLaidOut(url: String) {
    if (url.isBlank()) return
    if (width > 0 && height > 0) {
        safeLoadUrl(url, "already-laid-out")
        return
    }

    // 单次调用内共享的"已加载"标志 — 监听与兜底都在主线程回调，无需同步原语
    var fired = false
    var observer: ViewTreeObserver.OnGlobalLayoutListener? = null
    var fallback: Runnable? = null

    fun teardown() {
        observer?.let { l ->
            try {
                val vt = viewTreeObserver
                if (vt.isAlive) vt.removeOnGlobalLayoutListener(l)
            } catch (e: Exception) {
                Log.w("BrowserActivity", "注销布局监听失败: ${e.message}")
            }
        }
        fallback?.let { r ->
            try { removeCallbacks(r) } catch (_: Exception) { }
        }
        observer = null
        fallback = null
    }

    fun fire(reason: String) {
        if (fired) return
        fired = true
        teardown()
        safeLoadUrl(url, reason)
    }

    val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (fired) return@OnGlobalLayoutListener
        // 未测到尺寸就继续等下一次全局布局（例如父容器刚可见/键盘收起）
        if (width > 0 && height > 0) fire("laid-out")
    }
    observer = layoutListener

    val fallbackTask = Runnable { fire("fallback-${LOAD_FALLBACK_DELAY_MS}ms") }
    fallback = fallbackTask

    try {
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    } catch (e: Exception) {
        // View 未附着/已销毁 — 监听挂不上，仍靠兜底
        Log.w("BrowserActivity", "注册布局监听失败, 转兜底加载: ${e.message}")
    }
    val posted = try {
        postDelayed(fallbackTask, LOAD_FALLBACK_DELAY_MS)
    } catch (e: Exception) {
        Log.w("BrowserActivity", "兜底加载入队失败: ${e.message}")
        false
    }
    if (!posted) {
        Log.w("BrowserActivity", "兜底加载未入队(View 已脱离), 尝试直接加载: ${url.take(96)}")
        fire("post-failed")
    }
}

/** 加载失败不抛异常 — 只记日志（含 URL 摘要，避免日志刷屏）。 */
private fun WebView.safeLoadUrl(url: String, reason: String) {
    try {
        loadUrl(url)
        Log.i("BrowserActivity", "布局就绪后加载 ($reason): ${url.take(96)}")
    } catch (e: Exception) {
        Log.w("BrowserActivity", "布局就绪后加载失败 ($reason): ${url.take(96)}", e)
    }
}
