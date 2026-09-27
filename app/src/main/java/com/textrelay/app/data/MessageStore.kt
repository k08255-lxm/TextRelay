package com.textrelay.app.data

import android.content.Context
import com.textrelay.app.relay.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 全部设备的消息都留在本地（JSONL 追加文件 + 内存去重索引），
 * 这样"发送时对方不在线、上线后也能自动收到"——双方按时间窗口互相对账补齐。
 */
object MessageStore {
    private val MAX_MESSAGES = Protocol.MAX_MESSAGES
    private val MAX_AGE_MS = Protocol.MAX_AGE_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val fileLock = Any()
    private val byId = LinkedHashMap<String, Message>()
    private lateinit var file: File

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    fun init(context: Context) {
        file = File(context.filesDir, "messages.jsonl")
        synchronized(lock) {
            if (file.exists()) {
                runCatching {
                    file.readLines(Charsets.UTF_8).forEach { line ->
                        if (line.isNotBlank()) {
                            runCatching { Message.fromJson(JSONObject(line)) }
                                .getOrNull()?.let { byId[it.id] = it }
                        }
                    }
                }
            }
            pruneLocked()
            publishLocked()
        }
    }

    fun latestTs(): Long = synchronized(lock) {
        byId.values.maxOfOrNull { it.ts } ?: 0L
    }

    fun since(ts: Long): List<Message> = synchronized(lock) {
        byId.values.filter { it.ts > ts }.sortedBy { it.ts }
    }

    fun create(text: String, senderId: String, senderName: String): Message {
        val m = Message(
            id = UUID.randomUUID().toString(),
            senderId = senderId,
            senderName = senderName,
            text = text,
            ts = System.currentTimeMillis()
        )
        add(m)
        return m
    }

    /** 按 id 去重写入；返回是否为新消息 */
    fun add(m: Message): Boolean {
        var isNew = false
        synchronized(lock) {
            if (!byId.containsKey(m.id)) {
                byId[m.id] = m
                publishLocked()
                isNew = true
            }
        }
        if (isNew) {
            persistAppend(listOf(m))
            maybePrune()
        }
        return isNew
    }

    fun addAll(list: List<Message>): Int {
        val fresh = ArrayList<Message>()
        synchronized(lock) {
            list.forEach {
                if (!byId.containsKey(it.id)) {
                    byId[it.id] = it
                    fresh.add(it)
                }
            }
            if (fresh.isNotEmpty()) publishLocked()
        }
        if (fresh.isNotEmpty()) persistAppend(fresh)
        return fresh.size
    }

    fun clear() {
        synchronized(lock) {
            byId.clear()
            publishLocked()
        }
        synchronized(fileLock) { runCatching { file.delete() } }
    }

    private fun maybePrune() {
        var removed = 0
        synchronized(lock) {
            removed = pruneLocked()
            if (removed > 0) publishLocked()
        }
        if (removed > 0) rewrite()
    }

    private fun pruneLocked(): Int {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        var removed = 0
        byId.entries.removeAll { if (it.value.ts < cutoff) { removed++; true } else false }
        if (byId.size > MAX_MESSAGES) {
            byId.values.sortedByDescending { it.ts }.drop(MAX_MESSAGES).forEach {
                byId.remove(it.id)
                removed++
            }
        }
        return removed
    }

    private fun publishLocked() {
        _messages.value = byId.values.sortedWith(compareBy<Message> { it.ts }.thenBy { it.id })
    }

    private fun persistAppend(list: List<Message>) {
        scope.launch(Dispatchers.IO) {
            synchronized(fileLock) {
                runCatching {
                    file.appendText(list.joinToString("") { it.toJson().toString() + "\n" }, Charsets.UTF_8)
                }
            }
        }
    }

    private fun rewrite() {
        scope.launch(Dispatchers.IO) {
            synchronized(fileLock) {
                runCatching {
                    val snapshot = synchronized(lock) { byId.values.sortedBy { it.ts } }
                    val tmp = File(file.parentFile, "messages.jsonl.tmp")
                    tmp.writeText(snapshot.joinToString("") { it.toJson().toString() + "\n" }, Charsets.UTF_8)
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }
}
