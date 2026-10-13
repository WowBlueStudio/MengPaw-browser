// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

/**
 * 站点形态（桌面版/移动版）偏好（累计修改 — 站点兼容性开关）。
 *
 * 默认 [AUTO]：**平板请求桌面版、手机请求移动版**（设备类别判据 `smallestScreenWidthDp >= 600`，
 * 见 `web/UserAgent.kt`）。但站点差异很大 —— 有的桌面版布局在平板上更糟（例如按视口高
 * 计算整屏 hero 的站点），有的站点移动版缺功能 —— 所以给用户一个**一键覆盖**：
 * 不必等我们改代码/出包，设置里切一下、刷新即可。
 */
enum class UaMode(val key: String, val label: String) {
    AUTO("auto", "自动（平板桌面版 / 手机移动版）"),
    DESKTOP("desktop", "始终桌面版网站"),
    MOBILE("mobile", "始终移动版网站");

    /** true = 本次加载应按桌面版请求。 */
    fun resolveDesktopSite(isTablet: Boolean): Boolean = when (this) {
        AUTO -> isTablet
        DESKTOP -> true
        MOBILE -> false
    }

    companion object {
        fun fromKey(key: String?): UaMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
