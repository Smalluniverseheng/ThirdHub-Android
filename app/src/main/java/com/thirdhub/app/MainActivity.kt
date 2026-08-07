package com.thirdhub.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
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

/* 主界面：WebView 承载 ThirdHub
   v2.1 起继续沿用「纯本地」架构：
   - 直接加载 APK 内置资源（appassets 本地域名），打开即显、与网络状况完全无关；
   - 历史版本注册的 Service Worker 会在首次启动时被注销并清空缓存，杜绝脏缓存白屏；
   - 应用更新由原生更新器负责（后台检查清单 → 下载新 APK → 提示安装）；
   - Supabase / AI 接口等数据请求正常走网络。 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val LOCAL_URL = "https://appassets.androidplatform.net/assets/web/index.html"
        const val TAG = "ThirdHub"
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var swPurged = false   /* 每个进程只清理一次旧 Service Worker 缓存 */

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileCallback?.onReceiveValue((uris ?: emptyList()).toTypedArray())
            fileCallback = null
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
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                /* 旧版本可能在 WebView 里注册过 Service Worker 并缓存了过期/损坏文件，
                   且 SW 的请求不经过本地资产拦截。首次加载时注销全部 SW 并清空其缓存，
                   清理干净后重载一次，彻底杜绝脏缓存导致的白屏 */
                if (!swPurged && url != null &&
                    (url.startsWith("https://appassets.androidplatform.net/") || url.startsWith("https://thirdhub.pages.dev"))
                ) {
                    swPurged = true
                    view.evaluateJavascript(
                        "(async()=>{let p=false;try{if('serviceWorker' in navigator){const rs=await navigator.serviceWorker.getRegistrations();if(rs.length)p=true;for(const r of rs){await r.unregister();}}if(window.caches){const ks=await caches.keys();if(ks.length)p=true;for(const k of ks){await caches.delete(k);}}}catch(e){}return p?'purged':'clean'})()"
                    ) { result -> if (result == "\"purged\"") view.reload() }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://appassets.androidplatform.net/") || url.startsWith("https://thirdhub.pages.dev")) return false
                // 外链交给系统浏览器
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) { true }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) Log.e(TAG, "页面加载失败: ${error.description}")
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
        webView.loadUrl(LOCAL_URL)

        /* 启动时静默检查应用更新（发现新 APK 弹窗提示，后台下载） */
        Thread {
            try {
                runOnUiThread { UpdateChecker.checkAndPrompt(this@MainActivity, false) }
            } catch (_: Exception) {}
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
