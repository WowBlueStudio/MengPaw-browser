// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.util.Log
import android.view.ViewTreeObserver
import android.webkit.WebView

/** 兜底延时: 始终收不到 OnGlobalLayout 时按此间隔重试, 但**绝不**在 0 尺寸下加载。 */
private const val LOAD_FALLBACK_DELAY_MS = 300L

/** 兜底重试上限（10 × 300ms = 3s）。到顶仍无尺寸则放弃加载并记日志。 */
private const val LOAD_FALLBACK_MAX_RETRY = 10

/**
 * **布局就绪后再加载** —— 修复站点「100vh 首屏被挤成窄条」类缺陷（累计修改）。
 *
 * 事故背景: `AndroidView` 的 `factory` 阶段 WebView **尚未被测量/布局**（尺寸 0×0），
 * 此时 `loadUrl()` 会让站点 JS 在 `window.innerHeight == 0` 下完成首屏计算并缓存；
 * WebView 随后布局到全尺寸，但站点不在 `resize` 时重算 ⇒ 永久保持矮条。
 *
 * 社区/官方证据（调研子代理取证）:
 * - Chromium CL 175043002: *"It is perfectly legal for the Android WebView to have a zero
 *   height, this is frequently how a new WebView is first laid out."*；
 * - StackOverflow 12304489（高票）: JS 早于 View 初始化执行时会拿到退化的 320×240；
 * - StackOverflow 9613591: `window.innerHeight` 早读为 0。
 *
 * 策略: 已测得非 0 尺寸 → 立即加载；否则挂一次性 OnGlobalLayoutListener（首次非 0 即加载）；
 * 兜底只**重试**、到点仍 0 尺寸**绝不加载**（重试 [LOAD_FALLBACK_MAX_RETRY] 次后放弃并记日志）；
 * 同一次调用幂等；异常路径只记日志不抛。
 */
internal fun WebView.loadWhenLaidOut(url: String) {
    if (url.isBlank()) return
    if (width > 0 && height > 0) {
        safeLoadUrl(url, "already-laid-out")
        return
    }

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
        if (width > 0 && height > 0) fire("laid-out")
    }

    val fallbackTask = object : Runnable {
        override fun run() {
            if (fired) return
            if (width > 0 && height > 0) { fire("fallback-$attempt"); return }
            attempt++
            if (attempt >= LOAD_FALLBACK_MAX_RETRY) {
                teardown()
                Log.w(
                    "BrowserActivity",
                    "放弃在 0 尺寸下加载(重试 $attempt 次): ${url.take(96)} — 该标签可能始终不可见"
                )
                return
            }
            try { postDelayed(this, LOAD_FALLBACK_DELAY_MS) } catch (_: Exception) { }
        }
    }

    observer = layoutListener
    fallback = fallbackTask

    try {
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    } catch (e: Exception) {
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

/** 加载失败不抛异常 — 只记日志（含 URL 摘要，避免日志刷屏）。 */
private fun WebView.safeLoadUrl(url: String, reason: String) {
    try {
        loadUrl(url)
        Log.i("BrowserActivity", "布局就绪后加载 ($reason): ${url.take(96)}")
    } catch (e: Exception) {
        Log.w("BrowserActivity", "布局就绪后加载失败 ($reason): ${url.take(96)}", e)
    }
}

/**
 * 视口与关键元素自证日志（累计修改 — 兼容性诊断）。
 *
 * 站点「100vh 段被挤成窄条」「顶栏菜单点了不出现」这类问题，只有把**真实数值**打出来才能定论：
 * 视口(innerHeight/clientHeight/visualViewport/scale)、断点(lg/hover/coarse)、
 * hero 实测高度与计算 min-height、菜单触发元素 aria-expanded 与弹层可见性。
 * 结果经 [DiagLog] 双通道输出（logcat + `MpDiag/probe.log`）。
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
                DiagLog.append(context, "probe:$tag", raw)
            }
        }
    } catch (_: Exception) { }
}

/**
 * hero 高度规则 + 站点 JS/CSS 生效链审计（累计修改 — 兼容性诊断，第三轮）。
 *
 * 实测: hero 实测 195px、**计算 min-height = 0px**（站点 CSS 本应 `min-height:calc(90vh - 60px)`）
 * ⇒ 不是 vh 不对，而是**那条规则没匹配**（多半是站点 JS 没给 `.trek-marquee` 加上
 * `.large/.medium/.small` 类型类）。本探针一次给出：
 * 祖先类名 / 所有 marquee 的类名 / 已应用样式表里所有 fixed-overslider 规则 /
 * `theme-trek/css` 链接的 `rel` 状态（`preload` vs `stylesheet`）/ `#criticalCSS` 是否存在 /
 * `window.isCSSLoaded` / 站点 JS 标签数 / 各资源的 `transferSize`（0 = 加载失败）/
 * 导航 tier-0/tier-1/aria-expanded 计数（判定站点导航数据是否到位）。
 *
 * ⚠ 该 JS 已用 `node --check` 离线校验（此前手拼字符串出过语法错，导致探针无输出）。
 */
internal fun WebView.logHeroCssAudit() {
    val js = """
(function(){try{
var o={ss:document.styleSheets.length,rs:document.readyState};
try{var el=document.querySelector('.fixed-overslider');
if(el){var p=el.parentNode;while(p&&!(p.className&&(''+p.className).indexOf('trek-marquee')>=0)){p=p.parentNode}
 o.mqAncestor=(p&&p.className)?(''+p.className).substring(0,80):null;
 o.inline=el.getAttribute('style')||'';o.minH=getComputedStyle(el).minHeight;
 o.h=Math.round(el.getBoundingClientRect().height);o.pos=getComputedStyle(el).position}}catch(e){o.e1=''+e.message}
try{var ms=document.querySelectorAll('[class*=trek-marquee]');o.mqCount=ms.length;o.mqClasses=[];
for(var i=0;i<ms.length&&i<6;i++){o.mqClasses.push((''+ms[i].className).substring(0,60))}}catch(e){o.e2=''+e.message}
try{var lk=document.querySelectorAll('link[href*="theme-trek/css"]');o.links=[];
for(var j=0;j<lk.length&&j<8;j++){o.links.push((lk[j].getAttribute('rel')||'?')+':'+(lk[j].getAttribute('href')||'').split('/').pop())}}catch(e){o.e3=''+e.message}
try{o.critical=!!document.getElementById('criticalCSS');
o.cssLoaded=(window.isCSSLoaded===undefined?null:window.isCSSLoaded);
o.mq640=window.matchMedia('(min-width:640px)').matches;
o.mq1440=window.matchMedia('(min-width:1440px)').matches;
o.mqDouble=window.matchMedia('((min-width:640px))').matches}catch(e){o.e4=''+e.message}
try{o.tier0=document.querySelectorAll('.nav-item__tier-0').length;
o.tier1=document.querySelectorAll('.nav__tier-1').length;
o.expanded=document.querySelectorAll('[aria-expanded]').length}catch(e){o.e5=''+e.message}
try{o.jsTags=document.querySelectorAll('script[src*="theme-trek/js"]').length}catch(e){o.e6=''+e.message}
try{var res=[];performance.getEntriesByType('resource').forEach(function(e){
if(e.name.indexOf('theme-trek')>=0){res.push((e.transferSize===0?'FAILED':'ok')+':'+e.name.split('/').pop())}});
o.res=res.slice(0,10)}catch(e){o.e7=''+e.message}
var rules=[];
function scan(list,prefix){for(var m=0;m<list.length;m++){var r=list[m];
 if(r.selectorText&&r.selectorText.indexOf('fixed-overslider')>=0){rules.push(prefix+r.selectorText+'|minH='+r.style.minHeight)}
 else if(r.cssRules){scan(r.cssRules,prefix+'@'+((r.conditionText||'').substring(0,30))+' ')}}}
try{for(var k=0;k<document.styleSheets.length;k++){var ss=document.styleSheets[k];var rr=null;
 try{rr=ss.cssRules}catch(e){rules.push('CORS:'+(ss.href||'').split('/').pop());continue}
 if(rr)scan(rr,'')}}catch(e){rules.push('scanerr:'+e.message)}
o.rules=rules.slice(0,8);
return JSON.stringify(o)
}catch(e){return 'ERR:'+e.message}})()
""".trimIndent()
    try {
        evaluateJavascript(js) { raw ->
            if (!raw.isNullOrBlank() && raw != "null" && raw != "\"\"") {
                DiagLog.append(context, "hero-css", raw)
            }
        }
    } catch (_: Exception) { }
}
