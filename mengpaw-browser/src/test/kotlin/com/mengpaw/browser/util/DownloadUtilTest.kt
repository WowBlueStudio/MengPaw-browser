// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片下载落盘的纯逻辑测试 (v0.10.3 P1)。
 *
 * 覆盖: 文件名推导 / 同名不覆盖去重 / 透明格式走 PNG 而非 JPEG / 巨图降采样系数。
 */
class DownloadUtilTest {

    @Test
    fun `文件名取自 URL 末段`() {
        assertEquals("photo.png", targetName("https://a.com/img/photo.png"))
        assertEquals("photo.png", targetName("https://a.com/img/photo.png?w=100#x"))
        assertEquals("img_1.jpg", targetName("https://a.com/img_1.jpg"))
    }

    @Test
    fun `无扩展名补 jpg`() {
        assertEquals("photo.jpg", targetName("https://a.com/photo"))
        assertEquals("download.jpg", targetName("https://a.com/download?id=5"))
        assertTrue(targetName("https://a.com/").endsWith(".jpg"))
        assertTrue(targetName("").startsWith("img_"))
    }

    @Test
    fun `文件名末段截断到100字符后不再补扩展名`() {
        // 先取末段 100 字符, 再决定补不补扩展名:
        // 超过 100 字符的名字 (如 300 字符 + .png) 被截断且丢掉扩展名 → 补 .jpg, 总长 104
        val long = "a".repeat(300)
        assertEquals("a".repeat(100) + ".jpg", targetName("https://a.com/$long.png"))
        assertEquals(104, targetName("https://a.com/$long.png").length)
    }

    @Test
    fun `文件名不超过100时原样保留`() {
        assertEquals("x.png", targetName("https://a.com/x.png"))
        // 99 + ".png" = 103 > 100: 恰好被截成 100 字符 (与旧实现行为一致)
        assertEquals(100, targetName("https://a.com/" + "b".repeat(99) + ".png").length)
        // 96 + ".png" = 100: 边界内原样保留
        val fits = "c".repeat(96) + ".png"
        assertEquals(100, fits.length)
        assertEquals(fits, targetName("https://a.com/" + fits))
    }

    @Test
    fun `透明格式走 PNG`() {
        assertEquals(android.graphics.Bitmap.CompressFormat.PNG, compressFormatFor("a.png"))
        assertEquals(android.graphics.Bitmap.CompressFormat.PNG, compressFormatFor("a.PNG"))
        assertEquals(android.graphics.Bitmap.CompressFormat.PNG, compressFormatFor("a.webp"))
        assertEquals(android.graphics.Bitmap.CompressFormat.PNG, compressFormatFor("a.gif"))
        // 关键回归: 旧实现固定 JPEG, 透明通道被填黑
        assertNotEquals(android.graphics.Bitmap.CompressFormat.JPEG, compressFormatFor("shot.png"))
    }

    @Test
    fun `其余格式仍走 JPEG`() {
        assertEquals(android.graphics.Bitmap.CompressFormat.JPEG, compressFormatFor("a.jpg"))
        assertEquals(android.graphics.Bitmap.CompressFormat.JPEG, compressFormatFor("a.jpeg"))
        assertEquals(android.graphics.Bitmap.CompressFormat.JPEG, compressFormatFor("noext"))
    }

    @Test
    fun `同名文件追加序号不覆盖`() {
        val existing = mutableSetOf<String>()
        val taken: (String) -> Boolean = { existing.contains(it) }
        assertEquals("photo.png", uniqueFileName("photo.png", taken))
        existing.add("photo.png")
        assertEquals("photo_1.png", uniqueFileName("photo.png", taken))
        existing.add("photo_1.png")
        assertEquals("photo_2.png", uniqueFileName("photo.png", taken))
    }

    @Test
    fun `同名去重保留扩展名`() {
        val taken: (String) -> Boolean = { it == "a.jpg" || it == "a_1.jpg" }
        assertEquals("a_2.jpg", uniqueFileName("a.jpg", taken))
        // 无扩展名时序号直接追加在末尾
        val takenBare: (String) -> Boolean = { it == "shot" }
        assertEquals("shot_1", uniqueFileName("shot", takenBare))
    }

    @Test
    fun `降采样 - 最长边未超限不缩`() {
        assertEquals(1, sampleSizeFor(4096, 2048, 4096))
        assertEquals(1, sampleSizeFor(100, 100, 4096))
        assertEquals(1, sampleSizeFor(0, 0, 4096))
        assertEquals(1, sampleSizeFor(100, 100, 0))
    }

    @Test
    fun `降采样 - 超限按 2 的幂缩到限内`() {
        assertEquals(2, sampleSizeFor(8192, 4096, 4096))
        assertEquals(4, sampleSizeFor(16384, 100, 4096))
        assertEquals(8, sampleSizeFor(40000, 40000, 4096))
    }

    @Test
    fun `降采样 - 倍数是最小的 2 的幂`() {
        // 判定口径: s 为 2 的幂, 降采样后最长边 < 2*limit, 且再小一档就会 >= 2*limit
        val limit = MAX_DIMENSION
        for (w in listOf(5000, 9000, 20000, 60000)) {
            val s = sampleSizeFor(w, w / 2, limit)
            assertTrue("w=$w 应为 2 的幂, 实际 $s", s == 1 || s % 2 == 0)
            val after = w / s
            assertTrue("w=$w sample=$s after=$after", after < limit * 2)
            if (s > 1) {
                val coarser = w / (s / 2)
                assertTrue("w=$w sample=$s 再降一档=$coarser 不该仍小于 2*limit", coarser >= limit * 2)
            }
        }
    }
}
