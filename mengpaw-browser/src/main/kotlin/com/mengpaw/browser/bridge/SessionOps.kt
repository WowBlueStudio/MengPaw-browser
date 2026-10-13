// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.bridge

import android.webkit.WebView

/**
 * 会话数据操作（Cookie / Web Storage）— 自 [BrowserBridge] 抽出（400 行拆分批次 3）。
 *
 * 全部返回 JSON 字符串；JSON 用 [org.json.JSONObject] 构造（Cookie 值可含引号/反斜杠，
 * 手拼 JSON 会让 Agent 收到非法 JSON）。
 */
internal class SessionOps(private val webView: WebView, private val js: JsEvaluator) {

    /** 当前 URL 的全部 Cookie。 */
    fun cookies(): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val url = webView.url ?: ""
            val cookie = cm.getCookie(url) ?: ""
            org.json.JSONObject().put("ok", true).put("cookies", cookie).toString()
        } catch (e: Exception) {
            org.json.JSONObject().put("ok", false).put("error", e.message ?: "unknown").toString()
        }
    }

    /** 设置 Cookie（domain 省略时用当前页面 URL）。 */
    fun cookieSet(name: String, value: String, domain: String? = null): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            val url = domain ?: (webView.url ?: "")
            cm.setCookie(url, "$name=$value; Path=/")
            cm.flush()
            """{"ok":true}"""
        } catch (e: Exception) {
            org.json.JSONObject().put("ok", false).put("error", e.message ?: "unknown").toString()
        }
    }

    /** 清空全部 Cookie（破坏性：会注销所有站点登录态）。 */
    fun cookieClear(): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
            """{"ok":true}"""
        } catch (e: Exception) {
            org.json.JSONObject().put("ok", false).put("error", e.message ?: "unknown").toString()
        }
    }

    /** localStorage / sessionStorage 的 get/set/clear。 */
    fun storage(type: String, op: String, key: String? = null, value: String? = null): String {
        val storageType = if (type == "session") "sessionStorage" else "localStorage"
        return when (op) {
            "get" -> js.eval(storageGetScript(storageType, js.escape(key ?: "")))
            "set" -> js.eval(storageSetScript(storageType, js.escape(key ?: ""), js.escape(value ?: "")))
            "clear" -> js.eval(storageClearScript(storageType))
            else -> org.json.JSONObject().put("ok", false)
                .put("error", "Unknown op: $op (use get/set/clear)").toString()
        }
    }
}
