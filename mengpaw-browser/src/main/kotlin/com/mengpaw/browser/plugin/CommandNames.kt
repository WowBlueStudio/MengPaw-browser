// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.plugin

/**
 * 命令命名解析（v0.10.3 修复 — Agent 侧 21 条命令此前完全不可达）。
 *
 * **背景**：注册表用的是短名（`tabs` / `tab.open` / `cookies.set` / `dialog.accept` …
 * 见 [BrowserTabCommands]/[BrowserPageCommands]/[BrowserQueryCommands]），而 am 桥与
 * 命令手册用的是带前缀的 Agent 形态（`browser.tabs` / `browser.tab.open` …）。
 * 两侧从未对齐，导致：
 *  - 手册形态 `browser.tabs` → 过白名单（前缀 `browser.`）但注册表查不到 → 「未知浏览器命令」；
 *  - 短名形态 `tabs` → 直接被白名单拒绝 → 「仅允许浏览器命令」。
 * 即 21 条 `browser.*` 命令对 Agent **完全不可达**（`page.*` 22 条不受影响）。
 *
 * **定案**：注册键保持短名（内部稳定、不迁移），解析层同时接受两种形态 —
 * 精确命中优先，其次剥掉 `browser.` 前缀再命中。手册/提示词继续教 Agent 形态。
 */
internal const val BROWSER_CMD_PREFIX = "browser."

/** Agent 形态名字（手册/提示词/命令行里用的名字）。 */
internal fun agentCommandName(registeredKey: String): String =
    if (registeredKey.startsWith("page.")) registeredKey else BROWSER_CMD_PREFIX + registeredKey

/**
 * 把命令行里写的名字解析为注册键：精确命中优先，其次按 Agent 形态剥前缀。
 * 返回 null 表示该名字不存在（调用方负责报错）。
 */
internal fun resolveCommandKey(commands: Map<String, *>, rawName: String): String? {
    if (commands.containsKey(rawName)) return rawName
    if (rawName.startsWith(BROWSER_CMD_PREFIX)) {
        val short = rawName.removePrefix(BROWSER_CMD_PREFIX)
        if (commands.containsKey(short)) return short
    }
    return null
}

/** 解析失败时给 Agent 的可用名字清单（一行一个 Agent 形态名字，便于照抄）。 */
internal fun commandNameHint(commands: Map<String, *>): String =
    commands.keys.sorted().joinToString("\n") { "  " + agentCommandName(it) }
