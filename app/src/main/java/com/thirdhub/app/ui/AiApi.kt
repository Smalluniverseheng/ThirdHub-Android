package com.thirdhub.app.ui

import com.thirdhub.app.data.Prefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* OpenAI 兼容接口的 AI 客户端（SSE 流式） */
object AiApi {

    data class Vendor(val id: String, val label: String, val baseUrl: String, val defaultModel: String)

    val vendors = listOf(
        Vendor("deepseek", "DeepSeek 深度求索", "https://api.deepseek.com/v1", "deepseek-chat"),
        Vendor("moonshot", "Moonshot Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0905-preview"),
        Vendor("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        Vendor("aliyun", "阿里百炼 通义", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"),
        Vendor("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Vendor("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
        Vendor("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        Vendor("custom", "自定义（OpenAI 兼容）", "", ""),
    )

    fun vendor(id: String): Vendor = vendors.firstOrNull { it.id == id } ?: vendors[0]

    fun currentBaseUrl(): String {
        val v = vendor(Prefs.aiVendor)
        return if (v.id == "custom") Prefs.customBaseUrl.trimEnd('/') else v.baseUrl
    }

    fun currentModel(): String {
        val v = vendor(Prefs.aiVendor)
        val m = Prefs.aiModel(v.id)
        return m.ifEmpty { v.defaultModel }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    class ApiException(msg: String) : Exception(msg)

    /* 拉取可用模型列表 */
    fun listModels(): List<String> {
        val base = currentBaseUrl()
        val key = Prefs.aiKey(vendor(Prefs.aiVendor).id)
        if (base.isEmpty()) throw ApiException("请先设置自定义接口地址")
        if (key.isEmpty()) throw ApiException("请先设置 API 密钥")
        val req = Request.Builder()
            .url("$base/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException("获取模型失败（${resp.code}）")
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<String>()
            for (i in 0 until data.length()) out.add(data.getJSONObject(i).optString("id"))
            return out.filter { it.isNotEmpty() }
        }
    }

    /* 流式对话：onDelta 逐段回调，onDone 完成，onError 失败。本方法在 IO 线程调用。 */
    fun chatStream(
        history: JSONArray,
        onDelta: (String) -> Unit,
    ) {
        val base = currentBaseUrl()
        val v = vendor(Prefs.aiVendor)
        val key = Prefs.aiKey(v.id)
        if (base.isEmpty()) throw ApiException("请先在「密钥」中设置自定义接口地址")
        if (key.isEmpty()) throw ApiException("请先在「密钥」中设置 API 密钥")

        val body = JSONObject()
            .put("model", currentModel())
            .put("messages", history)
            .put("stream", true)

        val req = Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val t = resp.body?.string() ?: ""
                throw ApiException(parseErr(t, resp.code))
            }
            val source = resp.body?.source() ?: throw ApiException("响应为空")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                try {
                    val o = JSONObject(payload)
                    val choices = o.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) onDelta(content)
                } catch (_: Exception) {}
            }
        }
    }

    private fun parseErr(text: String, code: Int): String {
        return try {
            val e = JSONObject(text).optJSONObject("error")
            val m = e?.optString("message") ?: ""
            if (m.isNotEmpty()) m else "请求失败（$code）"
        } catch (_: Exception) { "请求失败（$code）" }
    }
}
