package com.thirdhub.app.data

import org.json.JSONObject

/* 云端同步（表结构与网页版完全一致：user_id / id / data / updated_at） */
object SyncRepo {

    data class ShelfItem(
        val id: String,
        val sourceId: String,
        val type: String,
        val title: String,
        val author: String,
        val bookUrl: String,
        val sourceName: String,
        val addedAt: Long,
    )

    /* ---------- 书架 ---------- */

    fun pullShelf(): List<ShelfItem> {
        if (!Supabase.isLoggedIn()) return emptyList()
        val arr = Supabase.select("th_bookshelf", "user_id=eq.${Prefs.userId}&select=id,data,updated_at")
        val out = ArrayList<ShelfItem>()
        for (i in 0 until arr.length()) {
            val row = arr.getJSONObject(i)
            val d = row.optJSONObject("data") ?: continue
            out.add(
                ShelfItem(
                    id = row.optString("id"),
                    sourceId = d.optString("sourceId"),
                    type = d.optString("type"),
                    title = d.optString("title"),
                    author = d.optString("author"),
                    bookUrl = d.optString("bookUrl"),
                    sourceName = d.optString("sourceName"),
                    addedAt = d.optLong("addedAt"),
                )
            )
        }
        return out.sortedByDescending { it.addedAt }
    }

    fun pushShelf(item: ShelfItem) {
        if (!Supabase.isLoggedIn()) return
        val data = JSONObject()
            .put("id", item.id)
            .put("sourceId", item.sourceId)
            .put("type", item.type)
            .put("title", item.title)
            .put("author", item.author)
            .put("bookUrl", item.bookUrl)
            .put("sourceName", item.sourceName)
            .put("addedAt", item.addedAt)
            .put("top", false)
        Supabase.upsert(
            "th_bookshelf",
            JSONObject()
                .put("user_id", Prefs.userId)
                .put("id", item.id)
                .put("data", data)
                .put("updated_at", Supabase.nowIso())
        )
    }

    fun removeShelf(id: String) {
        if (!Supabase.isLoggedIn()) return
        try { Supabase.delete("th_bookshelf", "user_id=eq.${Prefs.userId}&id=eq.$id") } catch (_: Exception) {}
    }

    /* ---------- 阅读进度 ---------- */

    fun pushProgress(itemId: String, chapterIndex: Int, offset: Int) {
        if (!Supabase.isLoggedIn()) return
        try {
            val data = JSONObject()
                .put("chapterIndex", chapterIndex)
                .put("offset", offset)
                .put("ts", System.currentTimeMillis())
            Supabase.upsert(
                "th_reading_progress",
                JSONObject()
                    .put("user_id", Prefs.userId)
                    .put("id", itemId)
                    .put("data", data)
                    .put("updated_at", Supabase.nowIso())
            )
        } catch (_: Exception) {}
    }

    fun pullProgress(itemId: String): JSONObject? {
        if (!Supabase.isLoggedIn()) return null
        return try {
            val row = Supabase.selectOne("th_reading_progress", "user_id=eq.${Prefs.userId}&id=eq.$itemId&select=data")
            row?.optJSONObject("data")
        } catch (_: Exception) { null }
    }

    /* ---------- 会员资料 ---------- */

    data class Profile(
        val level: String,
        val role: String,
        val nickname: String,
        val storageUsed: Long,
        val expireAt: String,
    )

    fun pullProfile(): Profile? {
        if (!Supabase.isLoggedIn()) return null
        val row = Supabase.selectOne("th_profiles", "id=eq.${Prefs.userId}&select=*") ?: return null
        return Profile(
            level = row.optString("level", "satellite"),
            role = row.optString("role", "user"),
            nickname = row.optString("nickname", ""),
            storageUsed = row.optLong("storage_used", 0),
            expireAt = row.optString("expire_at", ""),
        )
    }

    /* ---------- 卡密（与网页版同一 RPC） ---------- */

    fun redeemCard(card: String): String {
        if (!Supabase.isLoggedIn()) throw Supabase.ApiException("请先登录")
        val result = Supabase.rpc("th_redeem_card", JSONObject().put("p_card", card).put("p_user", Prefs.userId))
        if (result is JSONObject) {
            val err = result.optString("error", "")
            if (err.isNotEmpty()) throw Supabase.ApiException(err)
            val msg = result.optString("message", "")
            if (msg.isNotEmpty()) return msg
        }
        return "激活成功"
    }

    /* ---------- 统计（用于分类页展示） ---------- */

    fun counts(): Triple<Int, Int, Int> {
        if (!Supabase.isLoggedIn()) return Triple(0, 0, 0)
        val shelf = try { Supabase.select("th_bookshelf", "user_id=eq.${Prefs.userId}&select=id").length() } catch (_: Exception) { 0 }
        val history = try { Supabase.select("th_history", "user_id=eq.${Prefs.userId}&select=id").length() } catch (_: Exception) { 0 }
        val fav = try { Supabase.select("th_favorites", "user_id=eq.${Prefs.userId}&select=id").length() } catch (_: Exception) { 0 }
        return Triple(shelf, history, fav)
    }
}
