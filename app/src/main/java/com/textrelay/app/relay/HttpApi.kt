package com.textrelay.app.relay

import android.content.Context
import com.textrelay.app.data.Message
import com.textrelay.app.data.MessageStore
import com.textrelay.app.data.Prefs
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

class RelayHttpServer(
    port: Int,
    private val handler: (NanoHTTPD.IHTTPSession) -> NanoHTTPD.Response
) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response = handler(session)
}

/**
 * 一个 HTTP 服务同时服务两类客户端：
 * - 电脑/平板浏览器（网页版：看消息、发送、复制）
 * - 局域网内其他本 APP 实例（/api/messages 拉取补漏、/push 实时推送）
 */
object HttpApi {

    fun handle(ctx: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response =
        try {
            route(ctx, session)
        } catch (e: Exception) {
            plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, "error: ${e.message}")
        }

    private fun route(ctx: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = (session.uri ?: "/").trimEnd('/')
        val method = session.method
        return when {
            (uri.isEmpty() || uri == "/index.html") && method == NanoHTTPD.Method.GET ->
                asset(ctx, "web/index.html")

            uri == "/api/info" ->
                json(
                    JSONObject()
                        .put("app", Protocol.APP_TAG)
                        .put("name", Prefs.name)
                        .put("id", Prefs.deviceId)
                        .put("port", RelayEngine.httpPort())
                        .put("ips", JSONArray(NetworkUtils.localIps()))
                )

            uri == "/api/messages/count" && method == NanoHTTPD.Method.GET ->
                json(JSONObject().put("rev", MessageStore.revision()).put("count", MessageStore.count()))

            uri == "/api/messages" && method == NanoHTTPD.Method.GET -> {
                val since = session.parameters["since"]?.firstOrNull()?.toLongOrNull() ?: 0L
                // 回看一个重叠窗口，容忍设备间时钟误差，接收方按 id 去重
                val msgs = MessageStore.since(since - Protocol.OVERLAP_MS)
                json(JSONArray().also { a -> msgs.forEach { a.put(it.toJson()) } })
            }

            uri == "/api/messages" && method == NanoHTTPD.Method.DELETE -> {
                MessageStore.clear()
                plain(NanoHTTPD.Response.Status.OK, "cleared")
            }

            uri.startsWith("/api/messages/") && method == NanoHTTPD.Method.DELETE -> {
                val id = uri.removePrefix("/api/messages/").takeIf { it.isNotEmpty() }
                    ?: return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "missing id")
                plain(
                    NanoHTTPD.Response.Status.OK,
                    if (MessageStore.delete(id)) "deleted" else "not found"
                )
            }

            uri == "/api/send" && method == NanoHTTPD.Method.POST -> {
                val body = runCatching { JSONObject(readBody(session)) }.getOrNull()
                    ?: return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad body")
                val text = body.optString("text").trim().takeUnless { it.isNullOrEmpty() }
                    ?: return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "empty text")
                // 允许调用方（如 PC 工具）声明发送者身份，缺省视为本机
                val senderId = body.optString("sid").takeUnless { it.isBlank() }
                val senderName = body.optString("name").takeUnless { it.isBlank() }
                // 阻塞 NanoHTTPD 工作线程是安全的：推送并行发出，单请求 4s 超时
                val delivered = runBlocking { RelayEngine.sendSync(text, senderId, senderName) }
                json(JSONObject().put("ok", true).put("delivered", delivered))
            }

            uri == "/push" && method == NanoHTTPD.Method.POST -> {
                val added = try {
                    val raw = readBody(session).trim()
                    // Accept old single-message senders as well as batched reconciliation.
                    val arr = if (raw.startsWith("{")) JSONArray().put(JSONObject(raw)) else JSONArray(raw)
                    val list = (0 until arr.length()).mapNotNull { i ->
                        runCatching { Message.fromJson(arr.getJSONObject(i)) }.getOrNull()
                    }
                    MessageStore.addAll(list)
                } catch (e: Exception) {
                    return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "bad message payload")
                }
                plain(NanoHTTPD.Response.Status.OK, added.toString())
            }

            else -> plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        }
    }

    private fun readBody(session: NanoHTTPD.IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun bytes(
        status: NanoHTTPD.Response.Status,
        data: ByteArray,
        mime: String
    ): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, mime, ByteArrayInputStream(data), data.size.toLong())

    private fun json(o: Any): NanoHTTPD.Response =
        bytes(NanoHTTPD.Response.Status.OK, o.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun plain(status: NanoHTTPD.Response.Status, s: String): NanoHTTPD.Response =
        bytes(status, s.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

    private fun asset(ctx: Context, path: String): NanoHTTPD.Response =
        bytes(NanoHTTPD.Response.Status.OK, ctx.assets.open(path).use { it.readBytes() }, "text/html; charset=utf-8")
}
