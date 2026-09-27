package com.textrelay.app.relay

import java.net.HttpURLConnection
import java.net.URL

/** 轻量 HTTP 客户端：仅同步用，不引第三方库 */
object Http {
    fun get(url: String, timeoutMs: Int = 4000): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "GET"
        try {
            if (c.responseCode in 200..299) {
                c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun post(url: String, body: String, timeoutMs: Int = 4000): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)
}
