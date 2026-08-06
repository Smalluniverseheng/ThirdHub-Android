package com.thirdhub.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
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

/* AI 对话页：aiBeta 顶栏 + OmniHub 欢迎区 + 左滑会话抽屉 */
class AiFragment : Fragment() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var msgList: LinearLayout? = null
    private var msgScroll: ScrollView? = null
    private var welcomeScroll: ScrollView? = null
    private var drawer: DrawerLayout? = null
    private var sending = false

    /* 厂商一句话简介（OmniHub 式欢迎语） */
    private val vendorDesc = mapOf(
        "deepseek" to "DeepSeek 深度求索官方模型，推理与代码能力出色，性价比极高。",
        "moonshot" to "Moonshot Kimi 官方模型，超长上下文，中文理解与写作能力一流。",
        "zhipu" to "智谱 GLM 官方模型，清华系大模型，工具调用与多模态能力强。",
        "aliyun" to "阿里百炼通义千问官方模型，全能均衡，企业级稳定服务。",
        "openai" to "OpenAI 官方模型，GPT 系列，全球使用最广的大模型。",
        "openrouter" to "OpenRouter 聚合网关，一个密钥访问全球数百个模型。",
        "siliconflow" to "硅基流动聚合平台，国产开源模型高速推理，价格友好。",
        "custom" to "自定义 OpenAI 兼容接口，可接入任意第三方服务。",
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        msgList = view.findViewById(R.id.msgList)
        msgScroll = view.findViewById(R.id.msgScroll)
        welcomeScroll = view.findViewById(R.id.welcomeScroll)
        drawer = view.findViewById(R.id.aiDrawer)

        // OmniHub 式：抽屉跟手滑动，主内容随之变暗
        drawer?.setScrimColor(0x66000000)
        drawer?.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                val content = drawer?.getChildAt(0) ?: return
                content.alpha = 1f - slideOffset * 0.4f
            }
            override fun onDrawerClosed(drawerView: View) {
                drawer?.getChildAt(0)?.alpha = 1f
            }
        })

        view.findViewById<ImageButton>(R.id.btnDrawer).setOnClickListener {
            drawer?.openDrawer(GravityCompat.START)
        }
        view.findViewById<View>(R.id.modelPill).setOnClickListener { showModelPicker() }
        view.findViewById<ImageButton>(R.id.btnNewChatTop).setOnClickListener { newSession() }
        view.findViewById<View>(R.id.btnNewChat).setOnClickListener {
            newSession()
            drawer?.closeDrawer(GravityCompat.START)
        }
        view.findViewById<ImageButton>(R.id.btnPlus).setOnClickListener { showPlusPanel() }
        view.findViewById<ImageButton>(R.id.btnSend).setOnClickListener { send(view) }
        view.findViewById<EditText>(R.id.inputMsg).setOnEditorActionListener { _, _, _ -> send(view); true }

        // 今日推荐：点一下直接发问
        for (id in listOf(R.id.sug1, R.id.sug2, R.id.sug3)) {
            view.findViewById<TextView>(id).setOnClickListener { tv ->
                val text = (tv as TextView).text.toString()
                view.findViewById<EditText>(R.id.inputMsg).setText(text)
                send(view)
            }
        }

        migrateLegacy()
        ensureSession()
        refreshUi()
        renderMessages()
        renderSessions()
    }

    /* ================= 品牌图标绑定 ================= */

    private fun bindBrand(icon: ImageView, letter: TextView, vendorId: String) {
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

    /* ================= 顶栏 + 欢迎区 ================= */

    private fun refreshUi() {
        val v = view ?: return
        val vendor = AiApi.vendor(Prefs.aiVendor)
        val model = AiApi.currentModel()

        // 顶栏模型胶囊
        v.findViewById<TextView>(R.id.pillLabel).text = model.ifEmpty { "选择模型" }
        val pillIcon = v.findViewById<ImageView>(R.id.pillIcon)
        val res = BrandIcons.resFor(vendor.id)
        if (res != null) pillIcon.setImageResource(res)
        else pillIcon.setImageResource(R.drawable.ic_brand_openai) // 占位不会用到（8 厂商中 6 有图标）

        // 欢迎区
        v.findViewById<TextView>(R.id.welcomeTitle).text = "你好，我是 " + model.ifEmpty { vendor.label }
        v.findViewById<TextView>(R.id.welcomeDesc).text = vendorDesc[vendor.id] ?: ""
        v.findViewById<TextView>(R.id.welcomeChip).text = "正在使用 " + model.ifEmpty { vendor.label } + " · 聊天"
        bindBrand(v.findViewById(R.id.welcomeIcon), v.findViewById(R.id.welcomeLetter), vendor.id)
    }

    private fun syncWelcomeVisibility() {
        val empty = currentMsgs().length() == 0
        welcomeScroll?.visibility = if (empty) View.VISIBLE else View.GONE
        msgScroll?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /* ================= 模型选择弹窗（aiBeta 下拉式） ================= */

    private fun showModelPicker() {
        val ctx = context ?: return
        val dlgView = layoutInflater.inflate(R.layout.dialog_model_picker, null)
        val list = dlgView.findViewById<LinearLayout>(R.id.pickerList)
        val search = dlgView.findViewById<EditText>(R.id.pickerSearch)
        val dlg = AlertDialog.Builder(ctx).setView(dlgView).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun render(filter: String) {
            list.removeAllViews()
            val kw = filter.trim().lowercase()
            for (vendor in AiApi.vendors) {
                val model = Prefs.aiModel(vendor.id).ifEmpty { vendor.defaultModel }
                val hay = (vendor.label + " " + vendor.id + " " + model).lowercase()
                if (kw.isNotEmpty() && !hay.contains(kw)) continue
                val item = layoutInflater.inflate(R.layout.item_model_pick, list, false)
                bindBrand(item.findViewById(R.id.pickIcon), item.findViewById(R.id.pickLetter), vendor.id)
                item.findViewById<TextView>(R.id.pickTitle).text =
                    (if (vendor.id == Prefs.aiVendor) "● " else "") + model.ifEmpty { vendor.label }
                item.findViewById<TextView>(R.id.pickSub).text =
                    vendor.label + if (model.isNotEmpty() && model != vendor.label) " · " + vendor.defaultModel else ""
                if (Prefs.aiKey(vendor.id).isEmpty()) {
                    item.findViewById<TextView>(R.id.pickKeyTag).visibility = View.VISIBLE
                }
                item.setOnClickListener {
                    Prefs.aiVendor = vendor.id
                    val arr = sessionsArr()
                    val s = findSession(arr, currentId())
                    if (s != null) {
                        s.put("vendor", vendor.id)
                        s.put("model", AiApi.currentModel())
                        saveSessions(arr)
                    }
                    refreshUi()
                    renderMessages()
                    dlg.dismiss()
                    if (Prefs.aiKey(vendor.id).isEmpty()) {
                        toast("该厂商还没配置密钥，点输入框左侧 ＋ 设置")
                    }
                }
                item.setOnLongClickListener {
                    dlg.dismiss()
                    Prefs.aiVendor = vendor.id
                    showModelDialog()
                    true
                }
                list.addView(item)
            }
            if (list.childCount == 0) {
                val t = TextView(ctx)
                t.text = "没有匹配的模型"
                t.setTextColor(0xFF6B7186.toInt())
                t.setPadding(20, 30, 20, 30)
                list.addView(t)
            }
        }
        render("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        dlg.show()
    }

    /* ================= ＋ 功能面板 ================= */

    private fun showPlusPanel() {
        val ctx = context ?: return
        val items = arrayOf("密钥设置", "手动输入模型", "在线拉取模型", "清空当前会话")
        AlertDialog.Builder(ctx)
            .setTitle("更多功能")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showKeyDialog()
                    1 -> showModelDialog()
                    2 -> fetchModels(Prefs.aiVendor)
                    3 -> {
                        val arr = sessionsArr()
                        val s = findSession(arr, currentId())
                        if (s != null) {
                            s.put("msgs", JSONArray())
                            saveSessions(arr)
                        }
                        renderMessages()
                        toast("已清空当前会话")
                    }
                }
            }
            .show()
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
            arr = sortSessions(arr)
            saveSessions(arr)
            Prefs.aiCurrent = cur.optString("id")
        }
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
        refreshUi()
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
        refreshUi()
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
            saveSessions(JSONArray().put(s))
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
            time.text = AiApi.vendor(s.optString("vendor", "")).label + " · " + fmt.format(Date(s.optLong("updatedAt")))
            item.setOnClickListener {
                switchSession(s.optString("id"))
                drawer?.closeDrawer(GravityCompat.START)
            }
            item.setOnLongClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("删除会话")
                    .setMessage("删除「" + s.optString("title") + "」？")
                    .setPositiveButton("删除") { _, _ -> deleteSession(s.optString("id")) }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
            list.addView(item)
        }
    }

    /* ================= 模型 / 密钥设置 ================= */

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
                refreshUi()
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
                        refreshUi()
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
        syncWelcomeVisibility()
        scrollBottom()
    }

    private fun send(root: View) {
        if (sending) return
        val input = root.findViewById<EditText>(R.id.inputMsg)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return

        val vendor = AiApi.vendor(Prefs.aiVendor)
        if (Prefs.aiKey(vendor.id).isEmpty()) {
            toast("请先点输入框左侧 ＋ 设置 API 密钥")
            return
        }

        input.setText("")
        syncWelcomeVisibility()
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
            bindBrand(item.findViewById(R.id.aiBrandIcon), item.findViewById(R.id.aiBrandLetter), vendorId)
        }
        list.addView(item)
        msgScroll?.visibility = View.VISIBLE
        welcomeScroll?.visibility = View.GONE
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
