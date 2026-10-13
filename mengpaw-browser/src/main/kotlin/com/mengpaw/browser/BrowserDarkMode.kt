// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser

val DARK_MODE_CSS = "(function(){if(window.__mpDark)return;window.__mpDark=true;var s=document.createElement('style');s.id='mp-dark';s.textContent='html{background:#1a1a1a!important}body{color:#ddd!important;background:#1a1a1a!important}img,video,canvas,svg{opacity:.92!important}input,textarea,select,button{background:#333!important;color:#ddd!important;border-color:#555!important}code,pre{background:#333!important;color:#ccc!important}table{background:#222!important;color:#ddd!important}a{color:#6cb6ff!important}';document.head.appendChild(s);document.body&&document.body.setAttribute('data-mp-dark','true')})();"

/**
 * 关闭暗色模式：移除已注入的样式与标记。
 *
 * v0.10.3 修复: 此前关闭开关只置 prefs + reload，已注入的 `#mp-dark` 样式仍在
 * （reload 后 `window.__mpDark` 归零但样式表已随文档销毁 — 然而**同页不 reload 的
 * 场景**（脚本注入后用户关开关）会残留暗色，开关与视觉不一致）。显式清理保证一致。
 */
val DARK_MODE_REMOVE_JS = "(function(){try{window.__mpDark=false;var s=document.getElementById('mp-dark');if(s&&s.parentNode)s.parentNode.removeChild(s);if(document.body)document.body.removeAttribute('data-mp-dark')}catch(e){}})();"
