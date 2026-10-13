// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-Commercial

package com.mengpaw.browser.data

import android.content.Context
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 历史记录存储 — 30 天保留, 内存列表为准 (v0.10.3 P1 重构)。
 *
 * 旧实现 (v0.10.2 及以前) 的 `record()` 在主线程做全量读取 + 拼接 500 条 JSON +
 * 单 key 落盘, 且读侧被 5s TTL 缓存挡住 —— 既卡主线程, 又让「刚记录的历史」在
 * TTL 窗口内读到旧值。现改为:
 * - 内存里维护权威列表, 读路径 ([all]) 只读内存 (首次从磁盘惰性加载);
 * - 持久化交给单线程 [Executor], 写入串行且不再依赖 TTL 缓存 (写前以内存列表为准);
 * - 解码失败不再静默: `Log.w` 记录坏条目数, 保留其余可解析条目;
 * - 磁盘格式沿用「JSON 对象 + `|` 分隔」, 老数据可读。
 */
class HistoryStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("mp_history", Context.MODE_PRIVATE)
    private val cutoffTime: Long get() = System.currentTimeMillis() - RETENTION_MS

    /** 内存权威列表 (最新在前); null = 尚未从磁盘加载。 */
    private var mem: MutableList<Entry>? = null

    /** 写入代次: `clear()` 自增, 使已排队的旧快照写入作废, 避免清空后被回写。 */
    private var generation = 0L

    private val writeLock = Any()

    data class Entry(val url: String, val title: String, val time: Long) {
        val daysLeft: Int get() = maxOf(0, ((RETENTION_MS - (System.currentTimeMillis() - time)) / DAY_MS).toInt())
        val countdown: String get() = "${daysLeft}d"
    }

    /** 记录一条历史 (主线程安全): 内存即时可见, 落盘异步串行。 */
    fun record(url: String, title: String) {
        val list = synchronized(writeLock) {
            val current = ensureLoaded()
            current.add(0, Entry(url.take(500), title.take(100), System.currentTimeMillis()))
            pruneInPlace(current)
            current
        }
        persist(list)
    }

    /** 全部未过期历史 (最新在前)。只读内存, 首次调用从磁盘加载。 */
    fun all(): List<Entry> = synchronized(writeLock) { ensureLoaded().toList() }

    /** 清空历史 (内存立即生效, 磁盘异步落空)。 */
    fun clear() {
        synchronized(writeLock) {
            mem = mutableListOf()
            generation++
        }
        persist(emptyList())
    }

    /** 序列化快照并排队异步落盘。 */
    private fun persist(snapshot: List<Entry>) {
        val payload = snapshot.joinToString("|") { encode(it) }
        val genAtSubmit = genSnapshot()
        try {
            io.execute {
                try {
                    // 期间被 clear() 作废则丢弃过期快照, 避免清空后被回写。
                    // 代次经局部变量传入, 避免工作线程依赖字段的跨线程可见性。
                    val stillValid = genSnapshot() == genAtSubmit
                    if (stillValid) p.edit().putString("entries", payload).apply()
                } catch (e: Exception) {
                    // apply() 不回写磁盘异常, 这里兜住 Executor 层的意外失败
                    Log.w(TAG, "历史落盘失败 (${snapshot.size} 条)", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "历史落盘任务提交失败 (${snapshot.size} 条)", e)
        }
    }

    /**
     * 读取当前写入代次。工作线程与主线程都可能调用 —
     * [writeLock] 内只做字段读取, 无嵌套持锁路径。
     */
    private fun genSnapshot(): Long = synchronized(writeLock) { generation }

    private fun ensureLoaded(): MutableList<Entry> {
        mem?.let { return it }
        val loaded = readFromDisk().toMutableList()
        pruneInPlace(loaded)
        mem = loaded
        return loaded
    }

    /** 从 SharedPreferences 读取; 坏条目按条计数告警, 不静默丢弃整段。 */
    private fun readFromDisk(): List<Entry> {
        val raw = try {
            p.getString("entries", "") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "历史读取失败", e)
            return emptyList()
        }
        if (raw.isBlank()) return emptyList()
        val parts = raw.split("|").filter { it.isNotBlank() }
        val out = ArrayList<Entry>(parts.size)
        var bad = 0
        for (part in parts) {
            val e = decode(part)
            if (e == null) bad++ else out.add(e)
        }
        if (bad > 0) Log.w(TAG, "历史条目解码失败 $bad/${parts.size} 条, 保留 ${out.size} 条")
        return out
    }

    /** 就地剔除过期 + 截断到上限。 */
    private fun pruneInPlace(list: MutableList<Entry>) {
        val cutoff = cutoffTime
        list.removeAll { it.time <= cutoff }
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
    }

    companion object {
        private const val TAG = "HistoryStore"
        private const val MAX_ENTRIES = 500
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val DAY_MS = 24 * 60 * 60 * 1000

        /** 单线程落盘队列: 串行保证写顺序, 守护线程不阻塞进程退出。 */
        private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mp-history-writer").apply { isDaemon = true }
        }

        /** 等待落盘队列排空 (仅供仪器/集成测试使用; 主线程禁用)。 */
        internal fun awaitPendingWritesForTest() {
            try {
                io.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "等待历史落盘超时", e)
            }
        }
    }

    private fun encode(e: Entry): String {
        val obj = org.json.JSONObject()
        obj.put("u", e.url)
        obj.put("t", e.title)
        obj.put("ts", e.time)
        return obj.toString()
    }

    private fun decode(s: String): Entry? = try {
        val obj = org.json.JSONObject(s)
        Entry(obj.getString("u"), obj.optString("t", ""), obj.getLong("ts"))
    } catch (e: Exception) {
        Log.w(TAG, "历史条目解码失败: ${s.take(80)}", e)
        null
    }
}
