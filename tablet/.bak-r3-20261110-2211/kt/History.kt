package com.fnos.tablet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接历史（对齐 PC 端 settings.json 的 history 数组 + 独立 history 文件语义）。
 *
 * PC 端字段（login.js 直接消费）：
 *   { serverInput, origin, href, label, isFnId, fnId, partition, at }
 * login.js 的 displayValue / iconFor / typeLabel / findActiveIndex 都读这些键，
 * 所以这里一个都不改名。
 */
object History {

    private const val KEY = "history"
    private const val MAX = 20

    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "settings.json")

    fun list(ctx: Context): JSONArray {
        val o = Settings.raw(ctx)
        return o.optJSONArray(KEY) ?: JSONArray()
    }

    fun toList(ctx: Context): List<JSONObject> {
        val a = list(ctx)
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    /** 与 PC `partitionForServer` 同构：persist:nas-<清洗后的 key，截 24 字符> */
    private fun partitionOf(p: ServerAddr.Parsed): String {
        val key = if (p.isFnId && p.href.isNotEmpty()) p.href else p.origin.ifEmpty { p.href }
        val hash = key.replace(Regex("[^a-zA-Z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .take(24)
        return "persist:nas-$hash"
    }

    /** 连接成功后写入/刷新一条历史，并把 settings 的 server/origin/lastConnectHref 一起更新 */
    fun remember(ctx: Context, p: ServerAddr.Parsed) {
        try {
            val entry = JSONObject().apply {
                put("serverInput", p.serverInput)
                put("origin", p.origin)
                put("href", p.href)
                put("label", p.label.ifBlank { p.serverInput })
                put("isFnId", p.isFnId)
                put("fnId", p.fnId)
                put("partition", partitionOf(p))
                put("at", System.currentTimeMillis())
            }
            // 去重键：href（PC removeHistoryByKey 也用 href/origin）
            val kept = ArrayList<JSONObject>()
            for (e in toList(ctx)) {
                val h = e.optString("href")
                val o = e.optString("origin")
                if (h == p.href || o == p.origin) continue
                kept.add(e)
            }
            val arr = JSONArray()
            arr.put(entry)
            for (e in kept.take(MAX - 1)) arr.put(e)

            val raw = Settings.raw(ctx)
            raw.put(KEY, arr)
            raw.put("server", p.serverInput)
            raw.put("origin", p.origin)
            raw.put("lastConnectHref", p.href)
            raw.put("lastConnectAt", System.currentTimeMillis())
            file(ctx).writeText(raw.toString(2))
        } catch (e: Exception) {
            Log2.w(ctx, "history: remember failed ${e.message}")
        }
    }

    /** 等价 PC `removeHistoryByKey(href)`：返回删除后的历史数组 */
    fun remove(ctx: Context, hrefOrOrigin: String): JSONArray {
        try {
            val raw = Settings.raw(ctx)
            val kept = ArrayList<JSONObject>()
            for (e in toList(ctx)) {
                val h = e.optString("href")
                val o = e.optString("origin")
                if (h == hrefOrOrigin || o == hrefOrOrigin) continue
                kept.add(e)
            }
            val arr = JSONArray()
            for (e in kept) arr.put(e)
            raw.put(KEY, arr)
            if (raw.optString("lastConnectHref") == hrefOrOrigin || raw.optString("origin") == hrefOrOrigin) {
                raw.put("server", "")
                raw.put("origin", "")
                raw.put("lastConnectHref", "")
            }
            file(ctx).writeText(raw.toString(2))
            return arr
        } catch (e: Exception) {
            Log2.w(ctx, "history: remove failed ${e.message}")
            return list(ctx)
        }
    }

    /** 上次连接过的服务器（login.js init() 用它预填输入框） */
    fun lastServer(ctx: Context): String {
        val raw = Settings.raw(ctx)
        val s = raw.optString("server", "")
        if (s.isNotBlank()) return s
        val h = raw.optString("lastConnectHref", "")
        if (h.isNotBlank()) return h
        // 兜底：历史第一条
        return toList(ctx).firstOrNull()?.optString("serverInput", "") ?: ""
    }

    fun lastOrigin(ctx: Context): String = Settings.raw(ctx).optString("origin", "")
}
