// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User-Agent 形态策略单测（累计修改: 平板请求桌面版 / 手机请求移动版）。
 *
 * 这些是纯函数（不碰 Android API），所以能在 JVM 上钉住口径:
 * 平板必须给**不含 Mobile** 的桌面 UA，手机（含横屏）必须原样保留系统默认（含 Mobile）。
 * 判定口径是设备类别（`smallestScreenWidthDp >= 600`）而非当前宽度 —— 手机横屏也走移动版。
 */
class UserAgentTest {

    private val systemDefault =
        "Mozilla/5.0 (Linux; Android 14; GDI-W09 Build/HONORGDI-W09; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/120.0.6099.230 Mobile Safari/537.36"

    @Test
    fun 平板使用桌面UA且不含Mobile标记() {
        val ua = userAgentFor(desktopSite = true, systemDefault = systemDefault, engineVersion = "120.0.6099.230")
        assertTrue("桌面 UA 应标识 Windows 平台: $ua", ua.contains("Windows NT"))
        assertFalse("桌面 UA 不能带 Mobile 标记(否则站点仍给移动版): $ua", ua.contains("Mobile"))
        assertTrue("桌面 UA 应含 Chrome 主版本: $ua", ua.contains("Chrome/120."))
        assertTrue("桌面 UA 应含 Safari 段: $ua", ua.contains("Safari/537.36"))
    }

    @Test
    fun 手机保持系统默认移动UA() {
        val ua = userAgentFor(desktopSite = false, systemDefault = systemDefault, engineVersion = "120.0.6099.230")
        assertEquals("手机不应改写 UA", systemDefault, ua)
        assertTrue("系统默认 UA 带 Mobile 标记: $ua", ua.contains("Mobile"))
    }

    @Test
    fun 桌面UA主版本跟随引擎版本() {
        assertTrue(desktopUserAgent("108.0.5359.128").contains("Chrome/108."))
        assertTrue(desktopUserAgent("131.0.0.0").contains("Chrome/131."))
        // 引擎版本缺失/异常 → 兜底主版本, 仍是不含 Mobile 的桌面 UA
        assertTrue(desktopUserAgent(null).contains("Chrome/"))
        assertTrue(desktopUserAgent("").contains("Chrome/"))
        assertTrue(desktopUserAgent("not-a-version").contains("Chrome/"))
        assertEquals("120", engineMajorOf(null))
        assertEquals("131", engineMajorOf("131.0.0.0"))
        assertEquals("120", engineMajorOf("  "))
    }

    @Test
    fun 桌面UA两端形态互不污染() {
        val desktop = userAgentFor(true, systemDefault, "120.0.6099.230")
        val mobile = userAgentFor(false, systemDefault, "120.0.6099.230")
        assertTrue(desktop != mobile)
        // 第二次调用仍返回同样的移动 UA（说明取的是系统原始默认值而非被改写后的值）
        assertEquals(mobile, userAgentFor(false, systemDefault, "120.0.6099.230"))
        assertEquals(desktop, userAgentFor(true, systemDefault, "120.0.6099.230"))
    }
}
