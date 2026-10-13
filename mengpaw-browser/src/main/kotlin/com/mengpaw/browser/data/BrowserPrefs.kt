// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

import android.content.Context

/** Persistent browser settings backed by SharedPreferences. */
class BrowserPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp_browser", Context.MODE_PRIVATE)

    /**
     * 广告拦截开关（**默认关闭** — 用户定案 2026-10-14）。
     *
     * 起因: trekbikes.com 的「顶栏分类菜单点不开」+「整屏 hero 挤成窄条」两项报障，
     * 现场实测（同规格模拟器 + 平板文件通道）显示**站点自身的接口/配置请求失败**
     * （`Failed to fetch toggle configuration … Error: Network Error`）导致站点降级，
     * 而干扰源无法在小成本内 100% 定位（跨域 XHR 失败不产生 WebView 级错误）。
     * 按用户口径：**兼容优先，广告拦截默认停用**，需要时在设置里手动开启。
     *
     * 存储键由 `adblock` 改为 `adblock_v2`：**不迁移旧值**，保证已装设备也拿到新默认
     * （用户若要拦截，设置里一键开启即可，开关本身不变）。
     */
    var adBlockEnabled: Boolean
        get() = p.getBoolean("adblock_v2", false)
        set(v) = p.edit().putBoolean("adblock_v2", v).apply()

    /** Ordered list of enabled engine keys (comma-separated). */
    var engineKeys: List<String>
        get() = (p.getString("engines", "bing,google,baidu,duckduckgo") ?: "bing,google,baidu,duckduckgo").split(",").filter { it.isNotBlank() }
        set(v) = p.edit().putString("engines", v.joinToString(",")).apply()

    /** Last-used engine key. */
    var lastEngineKey: String
        get() = p.getString("last_engine", "bing") ?: "bing"
        set(v) = p.edit().putString("last_engine", v).apply()

    /** Get the ordered list of enabled SearchEngine instances. */
    fun enabledEngines(): List<SearchEngine> = engineKeys.mapNotNull { SearchEngine.fromKey(it) }

    /** Current default engine (last used). */
    fun defaultEngine(): SearchEngine = SearchEngine.fromKey(lastEngineKey)

    /** Set a new default and persist. */
    fun setDefaultEngine(engine: SearchEngine) { lastEngineKey = engine.key }

    var historyEnabled: Boolean
        get() = p.getBoolean("history_enabled", true)
        set(v) = p.edit().putBoolean("history_enabled", v).apply()

    // ── 主页 ──

    /** 主页 URL (Home 按钮目标; P2 fix: 消除 BrowserTopBar 硬编码 baidu, 改为持久化设置)。 */
    var homeUrl: String
        get() = p.getString("home_url", "https://www.baidu.com") ?: "https://www.baidu.com"
        set(v) = p.edit().putString("home_url", v).apply()
    // 注意: savePasswords pref 已随 P2 修复移除 — WebView API 18+ 无密码保存能力, 假开关无意义

    // ── Agent Collaboration Settings ──

    /** Auto-inject the __mp bridge on every page load for faster commands */
    var autoInjectBridge: Boolean
        get() = p.getBoolean("auto_inject", true)
        set(v) = p.edit().putBoolean("auto_inject", v).apply()

    /** Max height (pixels) for full-page screenshots. Default 15000, range 5000-30000 */
    var screenshotMaxHeight: Int
        get() = p.getInt("screenshot_max_h", 15000).coerceIn(5000, 30000)
        set(v) = p.edit().putInt("screenshot_max_h", v.coerceIn(5000, 30000)).apply()
    // 注意 (v0.10.2): quickClickEnabled / screenshotQuality 两个 pref 已删除 —
    // 全仓库无读取点 (截图固定 PNG, quality 参数被忽略), 设置里的对应控件一并移除,
    // 避免出现「点了没反应」的假开关。

    var darkMode: Boolean
        get() = p.getBoolean("dark_mode", false)
        set(v) = p.edit().putBoolean("dark_mode", v).apply()

    /**
     * 站点形态偏好（累计修改）: auto = 平板请求桌面版 / 手机请求移动版；
     * 可强制 desktop / mobile —— 站点差异大（有的桌面版在平板上更糟/移动版缺功能），
     * 让用户一键覆盖，不必等我们改代码出包。
     */
    var uaMode: UaMode
        get() = UaMode.fromKey(p.getString("ua_mode", UaMode.AUTO.key))
        set(v) = p.edit().putString("ua_mode", v.key).apply()

    // ── Bookmarks ──

    /**
     * 书签 URL 列表。
     * v0.10.3 P0 修复: 旧实现用 `joinToString(",")` 落盘, URL 查询串里的 `,`
     * 会在下次读取时把一条书签拆成两条垃圾, `isBookmarked`/`removeBookmark` 永久失效。
     * 现读写两侧都走 [StringListCodec]; 读取对老逗号数据保持兼容 (legacySeparator = ',')。
     */
    var bookmarks: List<String>
        get() = StringListCodec.decode(p.getString("bookmarks", null), ',')
        set(v) = p.edit().putString("bookmarks", StringListCodec.encode(v)).apply()

    fun addBookmark(url: String) { val list = bookmarks.toMutableList(); if (url !in list) { list.add(0, url); bookmarks = list } }

    fun removeBookmark(url: String) { bookmarks = bookmarks.filter { it != url } }

    fun isBookmarked(url: String): Boolean = url in bookmarks

    // ── Tab session persistence ──

    /** 标签会话 URL 列表。v0.10.3 P0 修复: 同 [bookmarks], 旧竖线分隔读取兼容 (legacySeparator = '|')。 */
    var savedTabUrls: List<String>
        get() = StringListCodec.decode(p.getString("tab_urls", null), '|')
        set(v) = p.edit().putString("tab_urls", StringListCodec.encode(v)).apply()

    var savedActiveTabId: Int
        get() = p.getInt("active_tab_id", -1)
        set(v) = p.edit().putInt("active_tab_id", v).apply()
}
