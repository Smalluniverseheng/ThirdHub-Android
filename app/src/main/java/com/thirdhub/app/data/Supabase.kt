package com.thirdhub.app.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/* Supabase REST 客户端（与网页版共用同一项目与 th_ 数据表） */
object Supabase {
    const val URL = "https://mxvxlgjzeboktufumxbp.supabase.co"
    const val ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im14dnhsZ2p6ZWJva3R1ZnVteGJwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODQzODM5OTcsImV4cCI6MjA5OTk1OTk5N30.QjSLfYAFhwX72YSeAcbTN5O2_PDLaNcv76HhdGJsqpo"

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    class ApiException(msg: String) : Exception(msg)

    fun isLoggedIn(): Boolean = Prefs.accessToken.isNotEmpty()

    fun nowIso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    /* ---------- 认证 ---------- */

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email).put("password", password)
        val resp = authPost("token?grant_type=password", body)
        saveSession(resp)
    }

    fun signUp(email: String, password: String): Boolean {
        val body = JSONObject().put("email", email).put("password", password)
        val resp = authPost("signup", body)
        // 若项目关闭邮箱验证，注册即返回 session
        return if (resp.has("access_token")) { saveSession(resp); true } else false
    }

    fun signOut() {
        if (isLoggedIn()) {
            try {
                val req = Request.Builder()
                    .url("$URL/auth/v1/logout")
                    .header("apikey", ANON)
                    .header("Authorization", "Bearer " + Prefs.accessToken)
                    .post("{}".toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) {}
        }
        Prefs.clearAuth()
    }

    private fun authPost(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("$URL/auth/v1/$path")
            .header("apikey", ANON)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(errMsg(text, resp.code))
            return JSONObject(text)
        }
    }

    private fun saveSession(resp: JSONObject) {
        Prefs.accessToken = resp.optString("access_token", "")
        Prefs.refreshToken = resp.optString("refresh_token", "")
        val user = resp.optJSONObject("user")
        if (user != null) {
            Prefs.userId = user.optString("id", "")
            Prefs.userEmail = user.optString("email", "")
        }
        if (Prefs.accessToken.isEmpty()) throw ApiException("登录失败：未获取到会话")
    }

    private fun refreshSession(): Boolean {
        if (Prefs.refreshToken.isEmpty()) return false
        return try {
            val body = JSONObject().put("refresh_token", Prefs.refreshToken)
            val resp = authPost("token?grant_type=refresh_token", body)
            saveSession(resp)
            true
        } catch (_: Exception) { false }
    }

    /* ---------- PostgREST ---------- */

    fun select(table: String, query: String): JSONArray {
        return request("GET", "/rest/v1/$table?$query", null, null) as JSONArray
    }

    fun selectOne(table: String, query: String): JSONObject? {
        val arr = select(table, query)
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    fun upsert(table: String, row: JSONObject) {
        val arr = JSONArray().put(row)
        request("POST", "/rest/v1/$table", arr.toString(), "resolution=merge-duplicates")
    }

    fun delete(table: String, query: String) {
        request("DELETE", "/rest/v1/$table?$query", null, null)
    }

    fun rpc(name: String, params: JSONObject): Any {
        return request("POST", "/rest/v1/rpc/$name", params.toString(), null) ?: JSONObject()
    }

    private fun request(method: String, path: String, body: String?, prefer: String?, retried: Boolean = false): Any? {
        val builder = Request.Builder()
            .url(URL + path)
            .header("apikey", ANON)
            .header("Authorization", "Bearer " + Prefs.accessToken)
            .header("Content-Type", "application/json")
        if (prefer != null) builder.header("Prefer", prefer)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post((body ?: "{}").toRequestBody(JSON))
            else -> builder.method(method, (body ?: "{}").toRequestBody(JSON))
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401 && !retried && refreshSession()) {
                return request(method, path, body, prefer, true)
            }
            if (!resp.isSuccessful) throw ApiException(errMsg(text, resp.code))
            if (text.isEmpty()) return null
            return when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> JSONObject(text)
                else -> text
            }
        }
    }

    private fun errMsg(text: String, code: Int): String {
        return try {
            val o = JSONObject(text)
            val m = o.optString("msg").ifEmpty { o.optString("message").ifEmpty { o.optString("error_description") } }
            if (m.isNotEmpty()) m else "请求失败（$code）"
        } catch (_: Exception) {
            if (text.isNotEmpty() && text.length < 200) text else "请求失败（$code）"
        }
    }
}
