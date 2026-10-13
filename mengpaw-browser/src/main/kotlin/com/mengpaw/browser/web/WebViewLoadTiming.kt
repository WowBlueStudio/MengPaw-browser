// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.util.Log
import android.view.ViewTreeObserver
import android.webkit.WebView

/** 兜底延时: 极端情况下(始终收不到 OnGlobalLayout)也按此间隔重试, 但**绝不**在 0 尺寸下加载。 */
private const val LOAD_FALLBACK_DELAY_MS = 300L

/** 兜底重试上限（10 × 300ms = 3s）。到顶仍无尺寸则放弃加载并记日志 —— 比加载出错误布局好。 */
private const val LOAD_FALLBACK_MAX_RETRY = 10

/**
 * **布局就绪后再加载** —— 修复站点「100vh 首屏被挤成窄条」类缺陷（累计修改）。
 *
 * 事故背景: `AndroidView` 的 `factory` 阶段 WebView **尚未被测量/布局**（尺寸 0×0），
 * 此时 `loadUrl()` 会让站点 JS 在 `window.innerHeight == 0` 下完成首屏计算并缓存
 * （trekbikes.com 每个 `100vh` hero 段被算成一条窄条）；WebView 随后布局到全尺寸，
 * 但站点不在 `resize` 时重算 ⇒ 永久保持矮条。官方 Edge 无此问题（不在 0 尺寸下加载）。
 *
 * 社区/官方证据（调研子代理取证）:
 * - Chromium CL 175043002: *"It is perfectly legal for the Android WebView to have a zero
 *   height, this is frequently how a new WebView is first laid out."* — 0 高度是合法初始状态，
 *   尺寸更新会被采纳，但站点若已缓存计算结果就不会自愈；
 * - StackOverflow 12304489（高票）: JS 早于 View 初始化执行时会拿到退化的 **320×240**；
 * - StackOverflow 9613591: `window.innerHeight` 早读为 0。
 *
 * 策略:
 * - 已测得非 0 尺寸 → 立即加载（等价旧行为，零额外延迟）；
 * - 否则挂一次性 [ViewTreeObserver.OnGlobalLayoutListener]，**首次**测得非 0 尺寸即加载并注销；
 * - 同时挂 [LOAD_FALLBACK_DELAY_MS] 兜底**重试**；**兜底到点时若尺寸仍为 0 就绝不加载**
 *   （这是 v0.10.5 首版的漏洞: 300ms 后无条件加载 ⇒ 等于旧行为），最多重试
 *   [LOAD_FALLBACK_MAX_RETRY] 次后放弃并记日志；
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
    var attempt = 0
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
        // 核心断言: 没有任何路径允许在 0 尺寸下加载
        if (width <= 0 || height <= 0) {
            Log.w("BrowserActivity", "跳过加载(尺寸仍为 0, $reason): ${url.take(96)}")
            return
        }
        fired = true
        teardown()
        safeLoadUrl(url, reason)
    }

    val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (fired) return@OnGlobalLayoutListener
        // 未测到尺寸就继续等下一次全局布局（例如父容器刚可见/键盘收起）
        if (width > 0 && height > 0) fire("laid-out")
    }

    val fallbackTask = object : Runnable {
        override fun run() {
            if (fired) return
            if (width > 0 && height > 0) { fire("fallback-${attempt}"); return }
            attempt++
            if (attempt >= LOAD_FALLBACK_MAX_RETRY) {
                teardown()
                Log.w(
                    "BrowserActivity",
                    "放弃在 0 尺寸下加载(重试 ${attempt} 次): ${url.take(96)} — 该标签可能始终不可见"
                )
                return
            }
            // 仍未布局: 继续等（重新挂监听 + 再延时），绝不在 0 尺寸下加载
            try { postDelayed(this, LOAD_FALLBACK_DELAY_MS) } catch (_: Exception) { }
        }
    }

    observer = layoutListener
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
        Log.w("BrowserActivity", "兜底加载未入队(View 已脱离), 尝试按当前尺寸加载: ${url.take(96)}")
        fire("post-failed")
    }
}

/**
 * hero 高度规则审计（累计修改 — 兼容性诊断，第三轮）。
 *
 * 实测: 站点 hero 实测高度 195px、**计算 min-height = 0px**（站点 CSS 里本应是
 * `min-height:calc(90vh - 60px)`）⇒ 不是 vh 不对，而是**那条规则没生效**。
 * 这里把"已应用样式表里所有含 fixed-overslider 的规则"连同样式表总数打出来，
 * 直接区分: ①样式表没加载 ②选择器不匹配 ③声明被覆盖/无效。
 */
internal fun WebView.logHeroCssAudit() {
    val js = "(function(){try{var o={ss:document.styleSheets.length};" +
        "var el=document.querySelector('.fixed-overslider');" +
        "if(el){o.cls=(el.closest('[class*=trek-marquee]')||{}).className||'';" +
        "o.inline=el.getAttribute('style')||'';o.compMin=getComputedStyle(el).minHeight;" +
        "o.compPos=getComputedStyle(el).position;o.compH=Math.round(el.getBoundingClientRect().height)}" +
        "var f=[];try{for(var i=0;i<document.styleSheets.length;i++){var ss=document.styleSheets[i];" +
        "var href=ss.href||'(inline)';var rs=null;try{rs=ss.cssRules}catch(e){f.push('CORS-blocked:'+href);continue}" +
        "if(!rs)continue;for(var j=0;j<rs.length;j++){var r=rs[j];" +
        "if(r.selectorText&&r.selectorText.indexOf('fixed-overslider')>=0){" +
        "f.push(r.selectorText+' | minH='+r.style.minHeight+' h='+r.style.height)}" +
        "else if(r.cssRules){for(var k=0;k<r.cssRules.length;k++){var rr=r.cssRules[k];" +
        "if(rr.selectorText&&rr.selectorText.indexOf('fixed-overslider')>=0){" +
        "f.push('@'+(r.conditionText||'')+' | '+rr.selectorText+' | minH='+rr.style.minHeight+' h='+rr.style.height)}}}}}" +
        "}catch(e){f.push('scan-err:'+e.message)}o.rules=f.slice(0,8);" +
        "var mq=document.querySelector('[class*=trek-marquee]');" +
        "if(mq){o.mqCls=mq.className;o.mqH=Math.round(mq.getBoundingClientRect().height)}" +
        "return JSON.stringify(o)}catch(e){return ''}})()"
    try {
        evaluateJavascript(js) { raw ->
            if (!raw.isNullOrBlank() && raw != "null" && raw != "\"\"") {
                DiagLog.append(context, "hero-css", raw)
            }
        }
    } catch (_: Exception) { }
}

/**
 * 视口与关键元素自证日志（累计修改 — 兼容性诊断）。
 *
 * 站点「100vh 段被挤成窄条」「顶栏菜单点了不出现」这类问题，只有把**真实数值**打出来才能定论：
 * - 视口: `innerHeight` / `clientHeight` / `visualViewport` / `devicePixelRatio` / `scale`；
 * - 断点: 站点桌面布局用 `@media (min-width:1024px)`，必须知道我们落在哪一侧；
 * - hero: 站点用 `.trek-marquee .fixed-overslider{min-height:calc(90vh - 60px)}` 这类纯 CSS vh，
 *   打印它的**实测高度**与**计算出的 min-height**即可判断是 vh 不对还是规则没生效；
 * - 顶栏菜单: 触发元素的 `aria-expanded` 与弹层的 `visibility/height/display`，
 *   判断"点了没反应"是事件没绑上/没触发，还是弹层被隐藏/零高。
 *
 * 结果统一打到 `MpWebConsole`（`adb logcat -s MpWebConsole` 可直读）。
 */
internal fun WebView.logViewportMetrics(tag: String) {
    val probe = "(function(){try{var d=document.documentElement,v=window.visualViewport;" +
        "var o={ih:window.innerHeight,ch:d.clientHeight,iw:window.innerWidth," +
        "dpr:window.devicePixelRatio,vvh:v?Math.round(v.height):null,scale:v?v.scale:null," +
        "lg:window.matchMedia('(min-width:1024px)').matches," +
        "hover:window.matchMedia('(hover: hover)').matches," +
        "coarse:window.matchMedia('(pointer: coarse)').matches," +
        "touch:('ontouchstart' in window)};" +
        "var h=document.querySelector('.fixed-overslider');" +
        "if(h){var r=h.getBoundingClientRect();o.heroH=Math.round(r.height);" +
        "o.heroMin=getComputedStyle(h).minHeight;o.heroW=Math.round(r.width)}" +
        "var t=document.querySelector('[aria-expanded]');" +
        "if(t){o.navTrig=(t.getAttribute('aria-label')||t.textContent||'').trim().substring(0,20);" +
        "o.navExp=t.getAttribute('aria-expanded')}" +
        "var n=document.querySelector('.nav-scroll-wrapper');" +
        "if(n){var cs=getComputedStyle(n);var nr=n.getBoundingClientRect();" +
        "o.navVis=cs.visibility;o.navDisp=cs.display;o.navH=Math.round(nr.height);o.navW=Math.round(nr.width)}" +
        "return JSON.stringify(o)}catch(e){return ''}})()"
    try {
        evaluateJavascript(probe) { raw ->
            if (!raw.isNullOrBlank() && raw != "null" && raw != "\"\"") {
                // 双通道: logcat(Log.w, 本设备可能被抑制) + 文件(ADB 可直接 cat)
                DiagLog.append(context, "probe:$tag", raw)
            }
        }
    } catch (_: Exception) { }
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
