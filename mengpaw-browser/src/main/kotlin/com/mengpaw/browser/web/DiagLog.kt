// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.web

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 兼容性诊断日志双通道（累计修改）。
 *
 * 背景: 站点兼容问题（100vh 矮条 / 顶栏菜单点不开 / 局部 JS 失效）必须靠**实测数值**定论，
 * 但实测中发现 **Honor 设备会抑制第三方应用的 logcat 输出** —— `adb logcat -s MpWebConsole`
 * 一条都读不到（`isMinifyEnabled=false`，不是 R8 剥离；进程存在、系统日志正常）。
 *
 * 因此同时走两条通道:
 * 1. **logcat**（`Log.w`，部分设备可见，便于普适排查）；
 * 2. **文件**（`/sdcard/Android/data/<pkg>/files/MpDiag/probe.log`）—— 应用外部私有目录，
 *    **无需任何存储权限**，且 ADB shell 可直接 `cat`，在本设备上稳定可用。
 *
 * 纪律: 单文件上限 [MAX_BYTES]，超限先截断（保留最近一半）；全部 IO try/catch；
 * 写入走单线程执行器（不占主线程、顺序稳定）。
 */
internal object DiagLog {

    private const val MAX_BYTES = 256 * 1024L
    private const val DIR_NAME = "MpDiag"
    private const val FILE_NAME = "probe.log"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mp-diag-log").apply { isDaemon = true }
    }

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** logcat + 文件双写。tag 固定 `MpWebConsole`，便于 `adb logcat -s MpWebConsole`。 */
    fun append(ctx: Context?, tag: String, line: String) {
        try { Log.w("MpWebConsole", "[$tag] $line") } catch (_: Exception) { }
        val appCtx = ctx?.applicationContext ?: return
        io.execute {
            try {
                val dir = File(appCtx.getExternalFilesDir(null), DIR_NAME)
                if (!dir.exists() && !dir.mkdirs()) return@execute
                val f = File(dir, FILE_NAME)
                if (f.length() > MAX_BYTES) {
                    // 截断: 只保留后半段（简单可靠，避免无限增长）
                    val tail = try { f.readText().takeLast((MAX_BYTES / 2).toInt()) } catch (_: Exception) { "" }
                    f.writeText(tail)
                }
                f.appendText("${stamp.format(Date())} [$tag] $line\n")
            } catch (_: Exception) {
                // 诊断日志失败绝不影响浏览
            }
        }
    }

    /** 诊断文件在设备上的绝对路径（供文档/命令输出引用）。 */
    fun filePath(ctx: Context): String =
        File(File(ctx.getExternalFilesDir(null), DIR_NAME), FILE_NAME).absolutePath
}
