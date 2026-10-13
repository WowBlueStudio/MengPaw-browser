// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 站点形态偏好单测（累计修改）——纯逻辑，钉住「默认按设备类别、可强制覆盖」的口径。
 */
class UaModeTest {

    @Test
    fun 自动模式按设备类别判定() {
        assertTrue("平板 → 桌面版", UaMode.AUTO.resolveDesktopSite(isTablet = true))
        assertFalse("手机(含横屏) → 移动版", UaMode.AUTO.resolveDesktopSite(isTablet = false))
    }

    @Test
    fun 强制模式忽略设备类别() {
        assertTrue(UaMode.DESKTOP.resolveDesktopSite(isTablet = false))
        assertTrue(UaMode.DESKTOP.resolveDesktopSite(isTablet = true))
        assertFalse(UaMode.MOBILE.resolveDesktopSite(isTablet = true))
        assertFalse(UaMode.MOBILE.resolveDesktopSite(isTablet = false))
    }

    @Test
    fun 未知或缺失键回退自动() {
        assertEquals(UaMode.AUTO, UaMode.fromKey(null))
        assertEquals(UaMode.AUTO, UaMode.fromKey(""))
        assertEquals(UaMode.AUTO, UaMode.fromKey("no-such-mode"))
        assertEquals(UaMode.DESKTOP, UaMode.fromKey("desktop"))
        assertEquals(UaMode.MOBILE, UaMode.fromKey("mobile"))
    }

    @Test
    fun 每个模式都有键与标签() {
        UaMode.entries.forEach {
            assertTrue("key 不能为空: $it", it.key.isNotBlank())
            assertTrue("label 不能为空: $it", it.label.isNotBlank())
        }
        // 键唯一（持久化用）
        assertEquals(UaMode.entries.size, UaMode.entries.map { it.key }.toSet().size)
    }
}
