package com.thirdhub.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.thirdhub.app.R
import com.thirdhub.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AiFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var msgList: LinearLayout? = null
    private var msgScroll: ScrollView? = null
    private var sending = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        msgList = view.findViewById(R.id.msgList)
        msgScroll = view.findViewById(R.id.msgScroll)

        view.findViewById<Button>(R.id.btnVendor).setOnClickListener { showVendorDialog() }
        view.findViewById<Button>(R.id.btnModel).setOnClickListener { showModelDialog() }
        view.findViewById<Button>(R.id.btnKey).setOnClickListener { showKeyDialog() }
        view.findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            Prefs.aiHistory = "[]"
            msgList?.removeAllViews()
            toast("已开始新会话")
        }
        view.findViewById<Button>(R.id.btnSend).setOnClickListener { send(view) }
        view.findViewById<EditText>(R.id.inputMsg).setOnEditorActionListener { _, _, _ -> send(view); true }

        refreshBar()
        restoreHistory()
    }

    private fun refreshBar() {
        val v = view ?: return
        val vendor = AiApi.vendor(Prefs.aiVendor)
        v.findViewById<Button>(R.id.btnVendor).text = vendor.label.substring(0, minOf(4, vendor.label.length))
        v.findViewById<Button>(R.id.btnModel).text = AiApi.currentModel().ifEmpty { "模型" }.let {
            if (it.length > 10) it.substring(0, 10) + "…" else it
        }
    }

    /* ---------- 设置弹窗 ---------- */

    private fun showVendorDialog() {
        val ctx = context ?: return
        val names = AiApi.vendors.map { it.label }.toTypedArray()
        val cur = AiApi.vendors.indexOfFirst { it.id == Prefs.aiVendor }
        AlertDialog.Builder(ctx)
            .setTitle("选择厂商")
            .setSingleChoiceItems(names, cur) { d, which ->
                Prefs.aiVendor = AiApi.vendors[which].id
                refreshBar()
                d.dismiss()
            }
            .show()
    }

    private fun showModelDialog() {
        val ctx = context ?: return
        val vendor = AiApi.vendor(Prefs.aiVendor)
        val input = EditText(ctx)
        input.setText(AiApi.currentModel())
        input.hint = "模型 ID，如 ${vendor.defaultModel}"
        AlertDialog.Builder(ctx)
            .setTitle("模型（${vendor.label}）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val m = input.text.toString().trim()
                if (m.isNotEmpty()) Prefs.setAiModel(vendor.id, m)
                refreshBar()
            }
            .setNeutralButton("在线拉取") { _, _ -> fetchModels(vendor.id) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun fetchModels(vendorId: String) {
        val ctx = context ?: return
        toast("正在拉取模型列表…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { AiApi.listModels() } catch (e: Exception) { e }
            }
            if (result is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val models = result as List<String>
                if (models.isEmpty()) { toast("未获取到模型"); return@launch }
                AlertDialog.Builder(ctx)
                    .setTitle("选择模型（${models.size}）")
                    .setItems(models.toTypedArray()) { _, which ->
                        Prefs.setAiModel(vendorId, models[which])
                        refreshBar()
                    }
                    .show()
            } else {
                toast("拉取失败：" + (result as Exception).message)
            }
        }
    }

    private fun showKeyDialog() {
        val ctx = context ?: return
        val vendor = AiApi.vendor(Prefs.aiVendor)
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        box.setPadding(pad, pad / 2, pad, 0)

        val keyInput = EditText(ctx)
        keyInput.hint = "API 密钥（仅保存在本机）"
        keyInput.setText(Prefs.aiKey(vendor.id))
        box.addView(keyInput)

        var baseInput: EditText? = null
        if (vendor.id == "custom") {
            val b = EditText(ctx)
            b.hint = "接口地址，如 https://api.example.com/v1"
            b.setText(Prefs.customBaseUrl)
            box.addView(b)
            baseInput = b
        }

        AlertDialog.Builder(ctx)
            .setTitle("密钥设置（${vendor.label}）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                Prefs.setAiKey(vendor.id, keyInput.text.toString().trim())
                if (baseInput != null) Prefs.customBaseUrl = baseInput.text.toString().trim()
                toast("已保存")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ---------- 会话 ---------- */

    private fun historyArr(): JSONArray = try { JSONArray(Prefs.aiHistory) } catch (_: Exception) { JSONArray() }

    private fun restoreHistory() {
        val arr = historyArr()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val role = m.optString("role")
            val content = m.optString("content")
            if (role == "user") addBubble(content, true) else if (role == "assistant") addBubble(content, false)
        }
        scrollBottom()
    }

    private fun send(root: View) {
        if (sending) return
        val ctx = context ?: return
        val input = root.findViewById<EditText>(R.id.inputMsg)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val vendor = AiApi.vendor(Prefs.aiVendor)
        if (Prefs.aiKey(vendor.id).isEmpty()) {
            toast("请先点击「密钥」设置 API 密钥")
            return
        }

        input.setText("")
        addBubble(text, true)
        val aiBubble = addBubble("", false)
        sending = true

        val history = historyArr()
        history.put(JSONObject().put("role", "user").put("content", text))
        val sb = StringBuilder()

        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    AiApi.chatStream(history) { delta ->
                        sb.append(delta)
                        val partial = sb.toString()
                        aiBubble.post { aiBubble.text = partial; scrollBottom() }
                    }
                    null
                } catch (e: Exception) { e }
            }
            sending = false
            if (err != null) {
                aiBubble.text = "⚠ " + (err.message ?: "请求失败")
                history.remove(history.length() - 1)
            } else {
                history.put(JSONObject().put("role", "assistant").put("content", sb.toString()))
            }
            // 只保留最近 50 条
            while (history.length() > 50) history.remove(0)
            Prefs.aiHistory = history.toString()
            scrollBottom()
        }
    }

    private fun addBubble(text: String, isUser: Boolean): TextView {
        val list = msgList ?: return TextView(requireContext())
        val item = layoutInflater.inflate(R.layout.item_message, list, false)
        val user = item.findViewById<TextView>(R.id.bubbleUser)
        val ai = item.findViewById<TextView>(R.id.bubbleAi)
        if (isUser) {
            user.visibility = View.VISIBLE
            user.text = text
        } else {
            ai.visibility = View.VISIBLE
            ai.text = text
        }
        list.addView(item)
        scrollBottom()
        return if (isUser) user else ai
    }

    private fun scrollBottom() {
        msgScroll?.post { msgScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
