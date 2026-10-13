// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 命令面守护测试（v0.10.3 新增 — 三层十二问 1.7-b / 九维「可测试性」）。
 *
 * 背景: 命令注册用短名（`tabs`/`tab.open`/…），而手册与 am 桥教的是 Agent 形态
 * （`browser.tabs`/`browser.tab.open`/…）。两侧此前从未被任何测试校验，结果 21 条
 * `browser.*` 命令在桥上**完全不可达**（v0.10.3 才修）。本测试把「文档教的命令名」
 * 与「注册表实际有的命令名」钉在一起：任何一侧漂移都会红。
 */
class CommandSurfaceTest {

    private val commands: Map<String, *> = BuiltinBrowserPlugin(webViewProvider = { null }).commands

    private fun repoFile(relative: String): File {
        val candidates = listOf(
            File(relative),                       // 单测工作目录 = 模块目录 (mengpaw-browser/)
            File("mengpaw-browser", relative),    // 兜底: 根目录
            File("..", relative)
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到文档: $relative (cwd=${File(".").absolutePath})")
    }

    @Test
    fun 命令总数为43条且两组齐备() {
        val page = commands.keys.filter { it.startsWith("page.") }
        val browser = commands.keys.filter { !it.startsWith("page.") }
        assertEquals("page.* 应为 22 条", 22, page.size)
        assertEquals("browser.* 应为 21 条", 21, browser.size)
        assertEquals("合计应为 43 条", 43, commands.size)
    }

    @Test
    fun 每个注册键都能被Agent形态名字解析() {
        commands.keys.forEach { key ->
            val agentName = agentCommandName(key)
            assertEquals(
                "Agent 形态名字应能解析回注册键: $agentName",
                key, resolveCommandKey(commands, agentName)
            )
            assertNotNull("Agent 形态名字应非空: $key", agentName)
        }
        // 短名形态（内部/兼容）也应可用
        commands.keys.forEach { key ->
            assertEquals(key, resolveCommandKey(commands, key))
        }
        // 不存在的名字必须解析失败（不能瞎命中）
        assertEquals(null, resolveCommandKey(commands, "browser.no_such_command"))
        assertEquals(null, resolveCommandKey(commands, "page.no_such_command"))
    }

    @Test
    fun 文档提到的命令名必须真实存在() {
        val docs = listOf(
            "docs/MengPaw_Browser_skills.md",
            "docs/MengPaw.Browser 开发文档.md"
        )
        // 已退役对象允许出现在「已删/已退役」说明行里；否则必须能解析到注册键
        val retiredMarkers = listOf("已退役", "已删", "不再", "移除", "退役", "retired")
        // 手册里的「Playwright 对照表」列的是 Playwright 官方 API 名（不是本应用命令），
        // 只作为映射参考 —— 显式白名单，避免把对照表误判成幽灵引用。
        val playwrightOnly = setOf(
            "page.selectOption", "page.evaluate", "page.waitForSelector",
            "page.keyboard.press", "page.mouse.wheel", "page.waitForLoadState",
            "page.textContent", "page.getAttribute", "page.reload", "page.goBack"
        )
        // 负向后视: 不匹配 `com.mengpaw.browser.RUN_COMMAND_ARGUMENTS` 这类全限定名里的片段
        val token = Regex("""(?<![\w.])(page|browser)\.[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)*""")
        val ghosts = mutableListOf<String>()
        docs.forEach { path ->
            val file = repoFile(path)
            file.readLines().forEachIndexed { idx, line ->
                if (retiredMarkers.any { line.contains(it) }) return@forEachIndexed
                token.findAll(line).forEach { m ->
                    val name = m.value
                    if (name in playwrightOnly) return@forEach
                    // page.* 与 browser.* 都可能指向真实键；解析不到才记为幽灵
                    if (resolveCommandKey(commands, name) == null) {
                        ghosts.add("$path:${idx + 1} → $name")
                    }
                }
            }
        }
        assertTrue(
            "文档里出现了解析不到的命令名（幽灵引用）:\n" + ghosts.joinToString("\n"),
            ghosts.isEmpty()
        )
    }

    @Test
    fun 文档覆盖全部注册命令() {
        val skills = repoFile("docs/MengPaw_Browser_skills.md").readText()
        val missing = commands.keys.filter { key ->
            val agentName = agentCommandName(key)
            // 手册可写 Agent 形态名，也可在该组小节里只写后缀（如 `tab.open`），两种都算覆盖
            !skills.contains(agentName) && !skills.contains("`$key`")
        }.map { agentCommandName(it) }
        assertTrue(
            "手册未覆盖以下命令（四源同步缺口）:\n" + missing.joinToString("\n"),
            missing.isEmpty()
        )
    }
}
