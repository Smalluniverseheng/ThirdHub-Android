package com.thirdhub.app.data

import android.content.Context
import android.content.SharedPreferences

/* SharedPreferences 统一封装 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("thirdhub", Context.MODE_PRIVATE)
    }

    var accessToken: String
        get() = sp.getString("access_token", "") ?: ""
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String
        get() = sp.getString("refresh_token", "") ?: ""
        set(v) = sp.edit().putString("refresh_token", v).apply()

    var userId: String
        get() = sp.getString("user_id", "") ?: ""
        set(v) = sp.edit().putString("user_id", v).apply()

    var userEmail: String
        get() = sp.getString("user_email", "") ?: ""
        set(v) = sp.edit().putString("user_email", v).apply()

    var themeMode: String
        get() = sp.getString("theme_mode", "dark") ?: "dark"
        set(v) = sp.edit().putString("theme_mode", v).apply()

    var aiVendor: String
        get() = sp.getString("ai_vendor", "deepseek") ?: "deepseek"
        set(v) = sp.edit().putString("ai_vendor", v).apply()

    var customBaseUrl: String
        get() = sp.getString("custom_base_url", "") ?: ""
        set(v) = sp.edit().putString("custom_base_url", v).apply()

    fun aiKey(vendor: String): String = sp.getString("ai_key_$vendor", "") ?: ""
    fun setAiKey(vendor: String, key: String) = sp.edit().putString("ai_key_$vendor", key).apply()

    fun aiModel(vendor: String): String = sp.getString("ai_model_$vendor", "") ?: ""
    fun setAiModel(vendor: String, model: String) = sp.edit().putString("ai_model_$vendor", model).apply()

    /* AI 多会话：[{id,title,vendor,model,msgs:[{role,content}],updatedAt}] */
    var aiSessions: String
        get() = sp.getString("ai_sessions", "") ?: ""
        set(v) = sp.edit().putString("ai_sessions", v).apply()

    var aiCurrent: String
        get() = sp.getString("ai_current", "") ?: ""
        set(v) = sp.edit().putString("ai_current", v).apply()

    /* 旧版单会话记录（仅用于迁移） */
    var aiHistory: String
        get() = sp.getString("ai_history", "[]") ?: "[]"
        set(v) = sp.edit().putString("ai_history", v).apply()

    var downloadId: Long
        get() = sp.getLong("download_id", 0L)
        set(v) = sp.edit().putLong("download_id", v).apply()

    var readerFont: Float
        get() = sp.getFloat("reader_font", 17f)
        set(v) = sp.edit().putFloat("reader_font", v).apply()

    var readerTheme: Int
        get() = sp.getInt("reader_theme", 0)
        set(v) = sp.edit().putInt("reader_theme", v).apply()

    fun progress(itemId: String): String = sp.getString("progress_$itemId", "") ?: ""
    fun setProgress(itemId: String, json: String) = sp.edit().putString("progress_$itemId", json).apply()

    fun clearAuth() {
        sp.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("user_id")
            .remove("user_email")
            .apply()
    }
}
