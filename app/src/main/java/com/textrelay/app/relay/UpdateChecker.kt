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
    private const val LATEST_PAGE = "https://github.com/k08255-lxm/TextRelay/releases/latest"
    private const val FALLBACK_URL = "https://github.com/k08255-lxm/TextRelay/releases"

    @Volatile private var lastError: String = ""

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        repeat(2) { attempt ->
            // 1) GitHub API：带更新日志，但未认证限流（按 IP 60 次/小时）下可能 403
            fetchLatest(API_URL)?.let { return@withContext evaluate(it) }
            // 2) 网页重定向探测：与浏览器同源，不受 API 限流影响（无更新日志）
            fetchViaRedirect()?.let { return@withContext evaluate(it) }
            if (attempt == 0) delay(1500)
        }
        UpdateResult.Failed(lastError.ifBlank { "未知错误" })
    }

    /** 访问 releases/latest 页面，从 302 的 Location 里解析最新版本号 */
    private fun fetchViaRedirect(): JSONObject? {
        val conn = URL(LATEST_PAGE).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "TextRelay-App")
        return try {
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")
            if (code !in 300..399 || loc.isNullOrBlank()) {
                lastError = "HTTP $code"
                null
            } else {
                val tag = loc.substringAfter("/tag/", "").removePrefix("v")
                if (tag.isEmpty()) {
                    lastError = "无法从重定向解析版本"
                    null
                } else {
                    JSONObject()
                        .put("tag_name", "v$tag")
                        .put("html_url", "https://github.com/k08255-lxm/TextRelay/releases/tag/v$tag")
                }
            }
        } catch (e: SocketTimeoutException) {
            lastError = "连接超时（10 秒）"
            null
        } catch (e: Exception) {
            lastError = "网络错误：${e.message ?: e.javaClass.simpleName}"
            null
        } finally {
            runCatching { conn.disconnect() }
        }
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

    /** 把 Release 的 Markdown 说明转成纯文本（弹窗展示用） */
    fun plainNotes(md: String): String = md
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace("**", "")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("\\[([^\\]]*)\\]\\(([^)]*)\\)"), "$1")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "· ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
