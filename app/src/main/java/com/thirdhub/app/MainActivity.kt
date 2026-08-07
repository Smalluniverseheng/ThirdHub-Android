package com.thirdhub.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.thirdhub.app.util.UpdateChecker

/* 主界面：WebView 承载 ThirdHub 网页版
   优先加载线上版 https://thirdhub.pages.dev（与网页端 1:1 且永远最新）；
   断网 / 线上不可达时回退到内置包 assets/web（离线可用）；
   网页端「检查更新 / 自动检查更新」经 ThirdHubNative 委托给原生更新器（后台下载 APK → 完成后提示安装） */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ONLINE_URL = "https://thirdhub.pages.dev/"
        const val LOCAL_URL = "file:///android_asset/web/index.html"
        const val TAG = "ThirdHub"
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var fellBack = false

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
            allowFileAccess = true
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
        }
        WebView.setWebContentsDebuggingEnabled(false)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("https://thirdhub.pages.dev") || url.startsWith("file:///android_asset/")) return false
                // 外链交给系统浏览器
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) { true }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !fellBack && view.url?.startsWith("https://thirdhub.pages.dev") == true) {
                    // 线上不可达 → 内置离线包兜底
                    fellBack = true
                    Log.w(TAG, "线上版加载失败，回退内置包: ${error.description}")
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
        fun checkAppUpdate(manual: Boolean) {
            runOnUiThread { UpdateChecker.checkAndPrompt(this@MainActivity, manual) }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
