package com.textrelay.app.relay

import android.os.Build
import com.textrelay.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

sealed interface UpdateResult {
    data class Update(val version: String, val pageUrl: String, val notes: String?) : UpdateResult
    data object UpToDate : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

/** 通过 GitHub Releases 检查更新（走代理链路时延迟较高：超时 15 秒 + 自动重试一次） */
object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/k08255-lxm/TextRelay/releases/latest"
    private const val FALLBACK_URL = "https://github.com/k08255-lxm/TextRelay/releases"

    @Volatile private var lastError: String = ""

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            fetchLatest(API_URL)?.let { return@withContext evaluate(it) }
            if (attempt == 0) delay(1500)
        }
        UpdateResult.Failed(lastError.ifBlank { "未知错误" })
    }

    private fun fetchLatest(url: String): JSONObject? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "TextRelay-App")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                lastError = "HTTP $code"
                null
            } else {
                conn.inputStream.use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
            }
        } catch (e: SocketTimeoutException) {
            lastError = "连接超时（15 秒）"
            null
        } catch (e: Exception) {
            lastError = "网络错误：${e.message ?: e.javaClass.simpleName}"
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun evaluate(json: JSONObject): UpdateResult {
        val tagName = json.optString("tag_name", "").removePrefix("v")
        val pageUrl = json.optString("html_url", FALLBACK_URL)
        val notes = json.optString("body").takeIf { it.isNotBlank() }
        return if (isNewer(tagName, BuildConfig.VERSION_NAME)) {
            UpdateResult.Update(tagName, pageUrl, notes)
        } else {
            UpdateResult.UpToDate
        }
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
