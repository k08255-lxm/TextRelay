package com.textrelay.app.data

import android.content.Context
import com.textrelay.app.relay.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
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

    // 删除语义：清空时间点之前的消息不再接收（防止清空后被其他设备同步回来）；
    // 单条删除记录墓碑 id，同理
    private var clearedBefore = 0L
    private val tombstones = LinkedHashSet<String>()
    private var rev = 0L   // 存储修订号：任何增删都会 +1（网页轮询据此全量重拉）

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    fun init(context: Context) {
        file = File(context.filesDir, "messages.jsonl")
        synchronized(lock) {
            loadMeta()
            if (file.exists()) {
                runCatching {
                    file.readLines(Charsets.UTF_8).forEach { line ->
                        if (line.isNotBlank()) {
                            runCatching { Message.fromJson(JSONObject(line)) }
                                .getOrNull()?.let { put(it) }
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
            isNew = put(m)
            if (isNew) publishLocked()
        }
        if (isNew) {
            persistAppend(listOf(m))
            maybePrune()
        }
        return isNew
    }

    fun addAll(list: List<Message>): Int {
        val fresh = ArrayList<Message>()
        var pruned = 0
        synchronized(lock) {
            list.forEach { if (put(it)) fresh.add(it) }
            pruned = pruneLocked()
            if (fresh.isNotEmpty() || pruned > 0) publishLocked()
        }
        if (pruned > 0) rewrite() else if (fresh.isNotEmpty()) persistAppend(fresh)
        return fresh.size
    }

    /** 删除单条消息（本机），并记录墓碑防止被其他设备同步回来 */
    fun delete(id: String): Boolean {
        var removed = false
        synchronized(lock) {
            if (byId.remove(id) != null) {
                tombstones.add(id)
                if (tombstones.size > 500) tombstones.remove(tombstones.first())
                publishLocked()
                removed = true
            }
        }
        if (removed) {
            saveMeta()
            rewrite()
        }
        return removed
    }

    fun clear() {
        synchronized(lock) {
            byId.clear()
            tombstones.clear()
            clearedBefore = System.currentTimeMillis()
            publishLocked()
        }
        synchronized(fileLock) { runCatching { file.delete() } }
        saveMeta()
    }

    /** 写入去重 + 删除过滤：墓碑消息与清空时间点之前的消息不再接收 */
    private fun put(m: Message): Boolean {
        if (m.ts < System.currentTimeMillis() - MAX_AGE_MS) return false
        if (m.ts <= clearedBefore || m.id in tombstones || byId.containsKey(m.id)) return false
        byId[m.id] = m
        return true
    }

    private fun loadMeta() {
        runCatching {
            val meta = File(file.parentFile, "store_meta.json")
            if (!meta.exists()) return
            val o = JSONObject(meta.readText(Charsets.UTF_8))
            clearedBefore = o.optLong("clearedBefore", 0L)
            o.optJSONArray("tombstones")?.let { arr ->
                for (i in 0 until arr.length()) tombstones.add(arr.getString(i))
            }
        }
    }

    private fun saveMeta() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val o = JSONObject()
                    .put("clearedBefore", clearedBefore)
                    .put("tombstones", JSONArray(tombstones))
                File(file.parentFile, "store_meta.json").writeText(o.toString(), Charsets.UTF_8)
            }
        }
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
        rev += 1
        _messages.value = byId.values.sortedWith(compareBy<Message> { it.ts }.thenBy { it.id })
    }

    /** 存储修订号：网页端据此感知远端的增删并全量重拉 */
    fun revision(): Long = synchronized(lock) { rev }

    fun count(): Int = synchronized(lock) { byId.size }

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
