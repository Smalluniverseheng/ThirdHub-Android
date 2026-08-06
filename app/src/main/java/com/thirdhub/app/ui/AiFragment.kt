package com.thirdhub.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.thirdhub.app.R
import com.thirdhub.app.data.BrandIcons
import com.thirdhub.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class AiFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var msgList: LinearLayout? = null
    private var msgScroll: ScrollView? = null
    private var drawer: DrawerLayout? = null
    private var sending = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        msgList = view.findViewById(R.id.msgList)
        msgScroll = view.findViewById(R.id.msgScroll)
        drawer = view.findViewById(R.id.aiDrawer)

        // OmniHub 式：抽屉滑动时主内容随之变暗，松手自动吸附（DrawerLayout 原生跟手）
        drawer?.setScrimColor(0x66000000)
        drawer?.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // 主内容随手指位移轻微缩放+变暗，跟手
                val content = drawer?.getChildAt(0) ?: return
                content.alpha = 1f - slideOffset * 0.4f
            }
            override fun onDrawerClosed(drawerView: View) {
                drawer?.getChildAt(0)?.alpha = 1f
            }
        })

        view.findViewById<Button>(R.id.btnSessions).setOnClickListener {
            drawer?.openDrawer(GravityCompat.START)
        }
        view.findViewById<Button>(R.id.btnVendor).setOnClickListener { showVendorDialog() }
        view.findViewById<Button>(R.id.btnModel).setOnClickListener { showModelDialog() }
        view.findViewById<Button>(R.id.btnKey).setOnClickListener { showKeyDialog() }
        view.findViewById<Button>(R.id.btnNewChat).setOnClickListener {
            newSession()
            drawer?.closeDrawer(GravityCompat.START)
        }
        view.findViewById<Button>(R.id.btnSend).setOnClickListener { send(view) }
        view.findViewById<EditText>(R.id.inputMsg).setOnEditorActionListener { _, _, _ -> send(view); true }

        migrateLegacy()
        ensureSession()
        refreshBar()
        renderMessages()
        renderSessions()
    }

    /* ================= 多会话存储 ================= */

    private fun sessionsArr(): JSONArray = try {
        val s = Prefs.aiSessions
        if (s.isEmpty()) JSONArray() else JSONArray(s)
    } catch (_: Exception) { JSONArray() }

    private fun saveSessions(arr: JSONArray) { Prefs.aiSessions = arr.toString() }

    private fun currentId(): String = Prefs.aiCurrent

    private fun findSession(arr: JSONArray, id: String): JSONObject? {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) return o
        }
        return null
    }

    private fun ensureSession() {
        var arr = sessionsArr()
        var cur = if (currentId().isEmpty()) null else findSession(arr, currentId())
        if (cur == null) {
            cur = JSONObject()
                .put("id", UUID.randomUUID().toString().substring(0, 8))
                .put("title", "新会话")
                .put("vendor", Prefs.aiVendor)
                .put("model", AiApi.currentModel())
                .put("msgs", JSONArray())
                .put("updatedAt", System.currentTimeMillis())
            arr.put(cur)
            // 按时间倒序
            arr = sortSessions(arr)
            saveSessions(arr)
            Prefs.aiCurrent = cur.optString("id")
        }
        // 会话绑定当前厂商/模型
        cur.put("vendor", Prefs.aiVendor)
        cur.put("model", AiApi.currentModel())
        saveSessions(arr)
    }

    private fun sortSessions(arr: JSONArray): JSONArray {
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
        list.sortByDescending { it.optLong("updatedAt") }
        val out = JSONArray()
        list.forEach { out.put(it) }
        return out
    }

    private fun newSession() {
        val arr = sessionsArr()
        val cur = JSONObject()
            .put("id", UUID.randomUUID().toString().substring(0, 8))
            .put("title", "新会话")
            .put("vendor", Prefs.aiVendor)
            .put("model", AiApi.currentModel())
            .put("msgs", JSONArray())
            .put("updatedAt", System.currentTimeMillis())
        arr.put(cur)
        saveSessions(sortSessions(arr))
        Prefs.aiCurrent = cur.optString("id")
        renderMessages()
        renderSessions()
        toast("已开始新会话")
    }

    private fun switchSession(id: String) {
        Prefs.aiCurrent = id
        val s = findSession(sessionsArr(), id)
        if (s != null) {
            Prefs.aiVendor = s.optString("vendor", Prefs.aiVendor)
            val m = s.optString("model")
            if (m.isNotEmpty()) Prefs.setAiModel(Prefs.aiVendor, m)
        }
        refreshBar()
        renderMessages()
        renderSessions()
    }

    private fun deleteSession(id: String) {
        val arr = sessionsArr()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != id) out.put(o)
        }
        saveSessions(out)
        if (currentId() == id) {
            Prefs.aiCurrent = ""
            if (out.length() > 0) Prefs.aiCurrent = out.getJSONObject(0).optString("id")
        }
        ensureSession()
        refreshBar()
        renderMessages()
        renderSessions()
    }

    private fun migrateLegacy() {
        if (Prefs.aiSessions.isNotEmpty()) return
        val legacy = Prefs.aiHistory
        if (legacy.isEmpty() || legacy == "[]") return
        try {
            val msgs = JSONArray(legacy)
            if (msgs.length() == 0) return
            val first = msgs.optJSONObject(0)?.optString("content") ?: ""
            val s = JSONObject()
                .put("id", UUID.randomUUID().toString().substring(0, 8))
                .put("title", if (first.length > 16) first.substring(0, 16) + "…" else first.ifEmpty { "导入的会话" })
                .put("vendor", Prefs.aiVendor)
                .put("model", AiApi.currentModel())
                .put("msgs", msgs)
                .put("updatedAt", System.currentTimeMillis())
            val arr = JSONArray().put(s)
            saveSessions(arr)
            Prefs.aiCurrent = s.optString("id")
            Prefs.aiHistory = "[]"
        } catch (_: Exception) {}
    }

    /* ================= 抽屉会话列表 ================= */

    private fun renderSessions() {
        val v = view ?: return
        val list = v.findViewById<LinearLayout>(R.id.sessionList) ?: return
        list.removeAllViews()
        val arr = sessionsArr()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val item = layoutInflater.inflate(R.layout.item_session, list, false)
            val title = item.findViewById<TextView>(R.id.txtSessionTitle)
            val time = item.findViewById<TextView>(R.id.txtSessionTime)
            val isCur = s.optString("id") == currentId()
            title.text = (if (isCur) "● " else "") + s.optString("title", "会话")
            val vid = s.optString("vendor", "")
            time.text = AiApi.vendor(vid).label + " · " + fmt.format(Date(s.optLong("updatedAt")))
            item.setOnClickListener {
                switchSession(s.optString("id"))
                drawer?.closeDrawer(GravityCompat.START)
            }
            item.setOnLongClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除会话")
                    .setMessage("删除「${s.optString("title")}」？")
                    .setPositiveButton("删除") { _, _ -> deleteSession(s.optString("id")) }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            list.addView(item)
        }
    }

    /* ================= 设置弹窗 ================= */

    private fun refreshBar() {
        val v = view ?: return
        val vendor = AiApi.vendor(Prefs.aiVendor)
        v.findViewById<Button>(R.id.btnVendor).text = vendor.label.substring(0, minOf(4, vendor.label.length))
        v.findViewById<Button>(R.id.btnModel).text = AiApi.currentModel().ifEmpty { "模型" }.let {
            if (it.length > 10) it.substring(0, 10) + "…" else it
        }
    }

    private fun showVendorDialog() {
        val ctx = context ?: return
        val names = AiApi.vendors.map { it.label }.toTypedArray()
        val cur = AiApi.vendors.indexOfFirst { it.id == Prefs.aiVendor }
        AlertDialog.Builder(ctx)
            .setTitle("选择厂商")
            .setSingleChoiceItems(names, cur) { d, which ->
                Prefs.aiVendor = AiApi.vendors[which].id
                val arr = sessionsArr()
                val s = findSession(arr, currentId())
                if (s != null) {
                    s.put("vendor", Prefs.aiVendor)
                    s.put("model", AiApi.currentModel())
                    saveSessions(arr)
                }
                refreshBar()
                renderMessages()
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

    /* ================= 对话 ================= */

    private fun currentMsgs(): JSONArray {
        val s = findSession(sessionsArr(), currentId()) ?: return JSONArray()
        return s.optJSONArray("msgs") ?: JSONArray()
    }

    private fun persistMsgs(msgs: JSONArray) {
        val arr = sessionsArr()
        val s = findSession(arr, currentId()) ?: return
        s.put("msgs", msgs)
        s.put("updatedAt", System.currentTimeMillis())
        // 用首条用户消息做标题
        if (s.optString("title") == "新会话") {
            for (i in 0 until msgs.length()) {
                val m = msgs.optJSONObject(i) ?: continue
                if (m.optString("role") == "user") {
                    val c = m.optString("content")
                    s.put("title", if (c.length > 16) c.substring(0, 16) + "…" else c)
                    break
                }
            }
        }
        saveSessions(sortSessions(arr))
    }

    private fun renderMessages() {
        val list = msgList ?: return
        list.removeAllViews()
        val msgs = currentMsgs()
        val vendor = AiApi.vendor(Prefs.aiVendor)
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "user" -> addBubble(m.optString("content"), true, vendor.id)
                "assistant" -> addBubble(m.optString("content"), false, vendor.id)
            }
        }
        scrollBottom()
    }

    private fun send(root: View) {
        if (sending) return
        val input = root.findViewById<EditText>(R.id.inputMsg)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val vendor = AiApi.vendor(Prefs.aiVendor)
        if (Prefs.aiKey(vendor.id).isEmpty()) {
            toast("请先点击「密钥」设置 API 密钥")
            return
        }

        input.setText("")
        addBubble(text, true, vendor.id)
        val aiBubble = addBubble("", false, vendor.id)
        sending = true

        val history = currentMsgs()
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
            while (history.length() > 50) history.remove(0)
            persistMsgs(history)
            renderSessions()
            scrollBottom()
        }
    }

    private fun addBubble(text: String, isUser: Boolean, vendorId: String): TextView {
        val list = msgList ?: return TextView(requireContext())
        val item = layoutInflater.inflate(R.layout.item_message, list, false)
        val user = item.findViewById<TextView>(R.id.bubbleUser)
        val aiRow = item.findViewById<View>(R.id.aiRow)
        val ai = item.findViewById<TextView>(R.id.bubbleAi)
        if (isUser) {
            user.visibility = View.VISIBLE
            user.text = text
        } else {
            aiRow.visibility = View.VISIBLE
            ai.text = text
            // 官方品牌图标头像
            val icon = item.findViewById<ImageView>(R.id.aiBrandIcon)
            val letter = item.findViewById<TextView>(R.id.aiBrandLetter)
            val res = BrandIcons.resFor(vendorId)
            if (res != null) {
                icon.setImageResource(res)
                icon.visibility = View.VISIBLE
                letter.visibility = View.GONE
            } else {
                icon.visibility = View.GONE
                letter.visibility = View.VISIBLE
                letter.text = BrandIcons.letterFor(vendorId)
                letter.setTextColor(BrandIcons.colorFor(vendorId))
            }
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
