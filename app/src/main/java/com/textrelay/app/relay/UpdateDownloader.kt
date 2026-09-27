package com.textrelay.app.relay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateDownloader {
    suspend fun download(context: Context, url: String, progress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(URL(url).protocol == "https") { "下载地址必须使用 HTTPS" }
            val directory = File(context.cacheDir, "updates").apply { mkdirs() }
            val partial = File(directory, "update.part")
            val apk = File(directory, "update.apk")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "TextRelay-App")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                check(connection.responseCode == 200) { "下载失败：HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong
                var received = 0L
                var lastReport = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            received += count
                            check(received <= 200L * 1024 * 1024) { "安装包过大" }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastReport >= 150) {
                                progress(received, total)
                                lastReport = now
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                check(received > 0 && (total <= 0 || received == total)) { "下载不完整，请重试" }
                val info = context.packageManager.getPackageArchiveInfo(partial.path, 0)
                check(info?.packageName == context.packageName) { "下载文件不是文字互传安装包" }
                check(UpdateChecker.isNewer(info?.versionName.orEmpty())) { "安装包版本不是新版本" }
                check(!apk.exists() || apk.delete()) { "无法替换旧安装包" }
                check(partial.renameTo(apk)) { "无法保存安装包" }
                progress(received, total)
                apk
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }

    /** Android requires explicit user consent; installation itself belongs to the system UI. */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
            return false
        }
        check(apk.isFile) { "安装包已被清理，请重新下载" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return true
    }
}
