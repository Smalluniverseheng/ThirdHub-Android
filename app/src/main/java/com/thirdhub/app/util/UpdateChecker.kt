package com.thirdhub.app.util

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.thirdhub.app.BuildConfig
import com.thirdhub.app.data.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/* 应用内自动更新：检查清单 → 弹公告 → DownloadManager 后台下载 → 引导安装 */
object UpdateChecker {

    private const val MANIFEST_URL = "https://smalluniverseheng.github.io/ThirdHub/android-latest.json"
    private const val FALLBACK_URL = "https://raw.githubusercontent.com/Smalluniverseheng/ThirdHub-Update/main/latest.json"
    private const val APK_NAME = "ThirdHub-update.apk"

    data class Info(
        val version: String,
        val build: Int,
        val notes: String,
        val apkUrl: String,
        val releasePage: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /* IO 线程调用 */
    fun fetch(): Info? {
        for (url in listOf(MANIFEST_URL + "?t=" + System.currentTimeMillis(), FALLBACK_URL)) {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val o = JSONObject(resp.body?.string() ?: "")
                    val apks = o.optJSONObject("apks")
                    val apkUrl = apks?.optString("universal") ?: ""
                    if (apkUrl.isEmpty()) return@use
                    return Info(
                        version = o.optString("version"),
                        build = o.optInt("build", 0),
                        notes = o.optString("notes", ""),
                        apkUrl = apkUrl,
                        releasePage = o.optString("releasePage", ""),
                    )
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /* 有更新时弹公告（主线程调用，内部自行切线程） */
    fun checkAndPrompt(activity: Activity, manual: Boolean) {
        Thread {
            val info = fetch()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (info == null) {
                    if (manual) Toast.makeText(activity, "检查失败，请稍后再试", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (info.build > BuildConfig.VERSION_CODE) {
                    showUpdateDialog(activity, info)
                } else if (manual) {
                    Toast.makeText(activity, "当前已是最新版本（v${BuildConfig.VERSION_NAME}）", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showUpdateDialog(activity: Activity, info: Info) {
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(info.notes + "\n\n当前版本 v${BuildConfig.VERSION_NAME}，可在后台下载，完成后点击安装即可。")
            .setPositiveButton("后台下载") { _, _ -> startDownload(activity, info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    fun startDownload(ctx: Context, info: Info) {
        val f = apkFile(ctx)
        if (f.exists()) f.delete()
        val req = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("ThirdHub v${info.version} 更新包")
            .setDescription("正在后台下载，完成后点击通知即可安装")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, APK_NAME)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        Prefs.downloadId = id
        Toast.makeText(ctx, "已开始后台下载，可在通知栏查看进度", Toast.LENGTH_LONG).show()
    }

    /* 下载完成广播 → 安装 */
    fun onDownloadComplete(ctx: Context, id: Long) {
        if (id != Prefs.downloadId || id == 0L) return
        install(ctx)
    }

    fun install(ctx: Context) {
        val f = apkFile(ctx)
        if (!f.exists()) {
            Toast.makeText(ctx, "安装包不存在，请重新下载", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(ctx)
                .setTitle("需要授权")
                .setMessage("安装更新需要允许「安装未知来源应用」，请在下一步打开开关后返回。")
                .setPositiveButton("去授权") { _, _ ->
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(intent)
    }

    private fun apkFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), APK_NAME)
}
