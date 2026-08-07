package com.thirdhub.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.thirdhub.app.util.UpdateChecker
import java.net.HttpURLConnection
import java.net.URL

/* 主界面：WebView 承载 ThirdHub 网页版
   v2.0 起采用「内置包直答」架构：
   - 对 https://thirdhub.pages.dev 的页面/静态资源请求，直接用 APK 内置资产秒回，
     慢网 / 弱网 / 断网都能秒开，且源(origin)不变，登录态与本地数据完全保留；
   - 后台静默比对线上 version.json，发现新版本时切换为真实线上加载（热更新），
     线上 18 秒未就绪自动切回内置包直答；
   - 非页面类请求（Supabase、AI 接口等）始终走真实网络。 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ONLINE_URL = "https://thirdhub.pages.dev/"
        const val LOCAL_URL = "https://appassets.androidplatform.net/assets/web/index.html"
        const val ONLINE_HOST = "thirdhub.pages.dev"
        const val VERSION_URL = "https://thirdhub.pages.dev/version.json"
        const val TAG = "ThirdHub"
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /* true = pages.dev 的静态请求由内置包直答；false = 放行到线上（热更新窗口） */
    @Volatile private var useBundled = true

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileCallback?.onReceiveValue((uris ?: emptyList()).toTypedArray())
            fileCallback = null
        }

    private fun mimeFor(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json", "webmanifest", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "md", "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /* 用内置资产应答 pages.dev 的静态请求；资产不存在（如接口路径）则返回 null 走网络 */
    private fun bundledResponse(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != ONLINE_HOST) return null
        val path = url.path ?: "/"
        val rel = if (path.isEmpty() || path == "/") "index.html" else path.removePrefix("/")
        if (rel.endsWith("/")) return null
        return try {
            val stream = assets.open("web/$rel")
            val textLike = rel.substringAfterLast('.', "").lowercase() in
                setOf("html", "js", "mjs", "css", "json", "svg", "md", "txt", "webmanifest", "map")
            WebResourceResponse(mimeFor(rel), if (textLike) "utf-8" else null, stream)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        WebView.setWebContentsDebuggingEnabled(false)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                assetLoader.shouldInterceptRequest(request.url)?.let { return it }
                if (useBundled) bundledResponse(request)?.let { return it }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://thirdhub.pages.dev") || url.startsWith("https://appassets.androidplatform.net/")) return false
                // 外链交给系统浏览器
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) { true }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (!request.isForMainFrame) return
                val u = request.url.toString()
                if (!useBundled && u.startsWith("https://$ONLINE_HOST")) {
                    // 热更新的线上版加载失败 → 切回内置包直答
                    Log.w(TAG, "线上版加载失败，切回内置包: ${error.description}")
                    useBundled = true
                    view.loadUrl(ONLINE_URL)
                } else if (useBundled && u.startsWith("https://$ONLINE_HOST")) {
                    // 内置资产缺失导致的失败 → 终极兜底：本地域名加载
                    Log.w(TAG, "内置直答失败，回退本地域名: ${error.description}")
                    useBundled = true
                    view.loadUrl(LOCAL_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.e(TAG, "JS: ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
                }
                return false
            }

            /* 文件选择（头像上传 / 连接器导入 / 附件） */
            override fun onShowFileChooser(
                wv: WebView,
                cb: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val mime = params.acceptTypes.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                return try {
                    filePicker.launch(arrayOf(mime))
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }

            /* window.open（如管理后台）在当前 WebView 内打开 */
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val href = view.handler.obtainMessage()
                view.requestFocusNodeHref(href)
                val url = href.data.getString("url")
                if (url != null) view.loadUrl(url)
                return true
            }
        }

        webView.addJavascriptInterface(NativeBridge(), "ThirdHubNative")
        webView.loadUrl(ONLINE_URL)

        /* 后台静默检查线上版本：发现新版则切换到真实线上加载（热更新） */
        Thread {
            val newer = onlineHasNewer()
            if (newer && !isDestroyed && !isFinishing) {
                runOnUiThread {
                    Log.i(TAG, "发现线上新版本，切换热更新")
                    useBundled = false
                    webView.loadUrl(ONLINE_URL)
                    armOnlineWatchdog()
                }
            }
        }.start()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /* 拉取线上 version.json（原生直连，不经过 WebView 拦截），版本号不同则视为有新版 */
    private fun onlineHasNewer(): Boolean {
        return try {
            val conn = URL("$VERSION_URL?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val v = Regex("\"version\"\s*:\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            v != null && v != BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            false
        }
    }

    /* 热更新看门狗：线上版 18 秒内未完成启动（弱网请求挂起）→ 切回内置包直答 */
    private fun armOnlineWatchdog() {
        webView.postDelayed({
            if (!useBundled && !isDestroyed && !isFinishing) {
                webView.evaluateJavascript(
                    "(window.__TH_READY === true) || !!(window.__THIRDHUB__ && window.__THIRDHUB__.version)"
                ) { v ->
                    if (v != "true" && !useBundled) {
                        Log.w(TAG, "线上版启动超时，切回内置包")
                        useBundled = true
                        webView.loadUrl(ONLINE_URL)
                    }
                }
            }
        }, 18000)
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun isNative(): Boolean = true

        @JavascriptInterface
        fun platform(): String = "android"

        @JavascriptInterface
        fun versionName(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun versionCode(): Int = BuildConfig.VERSION_CODE

        @JavascriptInterface
        fun getDeviceModel(): String {
            val m = android.os.Build.MODEL ?: ""
            val mf = android.os.Build.MANUFACTURER ?: ""
            val name = if (m.isNotBlank() && mf.isNotBlank() && !m.startsWith(mf, ignoreCase = true))
                mf.replaceFirstChar { it.uppercase() } + " " + m else m.ifBlank { mf }
            return name.ifBlank { "Android 设备" }
        }

        @JavascriptInterface
        fun checkAppUpdate(manual: Boolean) {
            runOnUiThread { UpdateChecker.checkAndPrompt(this@MainActivity, manual) }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
