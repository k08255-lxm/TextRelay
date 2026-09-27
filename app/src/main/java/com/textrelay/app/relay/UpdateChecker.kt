package com.textrelay.app.relay

import android.os.Build
import com.textrelay.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateResult {
    data class Update(val version: String, val pageUrl: String, val notes: String?) : UpdateResult
    data object UpToDate : UpdateResult
    data object Failed : UpdateResult
}

/** 通过 GitHub Releases 检查更新（无第三方依赖） */
object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/k08255-lxm/TextRelay/releases/latest"
    private const val FALLBACK_URL = "https://github.com/k08255-lxm/TextRelay/releases"

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "TextRelay-App")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = try {
                if (conn.responseCode !in 200..299) return@runCatching UpdateResult.Failed
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            val pageUrl = json.optString("html_url", FALLBACK_URL)
            val notes = json.optString("body").takeIf { it.isNotBlank() }
            if (isNewer(tagName, BuildConfig.VERSION_NAME)) {
                UpdateResult.Update(tagName, pageUrl, notes)
            } else {
                UpdateResult.UpToDate
            }
        }.getOrDefault(UpdateResult.Failed)
    }

    /** 逐段比较版本号，支持 1.0.10 > 1.0.9 */
    fun isNewer(remote: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        val r = remote.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
