package com.textrelay.app.relay

import android.content.Context
import android.net.wifi.WifiManager
import com.textrelay.app.data.Message
import com.textrelay.app.data.MessageStore
import com.textrelay.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 同步引擎总控：
 * 1. 启动 HTTP 服务（网页版 + 同步接口）
 * 2. 启动 UDP 信标发现（周期广播自己的名字/端口/最新消息时间）
 * 3. 收到信标即登记设备，并按双方时间差决定「拉取」或「推送」补漏
 * 4. 发消息时先落库，再推给当前所有在线设备；没推到的设备上线后通过对账自动补齐
 */
object RelayEngine {

    @Volatile private var started = false
    private var scope: CoroutineScope? = null
    private var appContext: Context? = null
    private var httpServer: RelayHttpServer? = null
    private var discovery: Discovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile private var boundPort = Protocol.HTTP_PORT
    private val lastSyncAt = ConcurrentHashMap<String, Long>()
    private val syncLocks = ConcurrentHashMap<String, Mutex>()
    private val syncSlots = Semaphore(4)

    fun httpPort(): Int = boundPort

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val ctx = context.applicationContext
        appContext = ctx
        Prefs.init(ctx)
        PeerRegistry.refreshManual()

        // 组播锁：部分路由器/系统默认丢弃组播包
        multicastLock = (ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("TextRelay")
            ?.apply { setReferenceCounted(false); acquire() }

        // HTTP 服务：端口被占则向后顺延
        var port = Protocol.HTTP_PORT
        while (httpServer == null && port < Protocol.HTTP_PORT + 20) {
            try {
                val server = RelayHttpServer(port) { session -> HttpApi.handle(ctx, session) }
                server.start(5000, true)
                httpServer = server
                boundPort = port
            } catch (e: Exception) {
                port++
            }
        }

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        discovery = Discovery(newScope, Protocol.BEACON_PORT).also {
            it.start(::onBeacon, ::beaconPayload)
        }
        newScope.launch { syncLoop() }
        newScope.launch { selfLoop() }
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        discovery?.stop()
        discovery = null
        runCatching { httpServer?.stop() }
        httpServer = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        scope?.cancel()
        scope = null
    }

    /** 发送：先落本地库（离线设备以后对账补齐），再推给当前在线设备 */
    fun send(text: String) {
        scope?.launch { runCatching { sendSync(text) } }
    }

    /** 同步发送：落库并推送，返回送达台数（网页接口用，超时可控） */
    suspend fun sendSync(text: String, senderId: String? = null, senderName: String? = null): Int {
        val m = MessageStore.create(text, senderId ?: Prefs.deviceId, senderName ?: Prefs.name)
        return pushSync(m)
    }

    private suspend fun pushSync(m: Message): Int {
        val targets = PeerRegistry.peers.value.values
            .filter { it.isOnline() || it.manual }
            .filter { it.ip != localIpHint() }
        if (targets.isEmpty()) return 0
        val body = JSONArray().put(m.toJson()).toString()
        return coroutineScope {
            targets.map { peer ->
                async(Dispatchers.IO) {
                    runCatching { Http.post("http://${peer.ip}:${peer.port}/push", body) }
                        .getOrDefault(false)
                }
            }.awaitAll().count { it }
        }
    }

    fun manualPoke(ip: String) {
        scope?.launch {
            runCatching {
                pullFrom(Peer(ip, "manual", "手动添加", Protocol.HTTP_PORT, 0, 0, true, 0), full = true)
            }
        }
    }

    private fun beaconPayload(): ByteArray = JSONObject()
        .put("app", Protocol.APP_TAG)
        .put("v", 1)
        .put("id", Prefs.deviceId)
        .put("name", Prefs.name)
        .put("port", boundPort)
        .put("latest", MessageStore.latestTs())
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun onBeacon(ip: String, json: JSONObject) {
        if (json.optString("id") == Prefs.deviceId) return
        PeerRegistry.upsert(
            ip = ip,
            id = json.optString("id"),
            name = json.optString("name"),
            port = json.optInt("port", Protocol.HTTP_PORT),
            latest = json.optLong("latest", 0L)
        )
        maybeSync(ip)
    }

    private fun maybeSync(ip: String) {
        val now = System.currentTimeMillis()
        val last = lastSyncAt[ip] ?: 0L
        if (now - last < 2500) return
        lastSyncAt[ip] = now
        scope?.launch {
            val peer = PeerRegistry.peers.value[ip] ?: return@launch
            runCatching { syncPeer(peer, forced = false) }
        }
    }

    private suspend fun syncLoop() {
        val s = scope ?: return
        while (s.isActive) {
            delay(Protocol.SYNC_INTERVAL_MS)
            coroutineScope {
                PeerRegistry.peers.value.values.toList().map { peer ->
                    async { runCatching { syncPeer(peer, forced = true) } }
                }.awaitAll()
            }
        }
    }

    private suspend fun syncPeer(peer: Peer, forced: Boolean) {
        val mutex = syncLocks.getOrPut(peer.ip) { Mutex() }
        if (!mutex.tryLock()) return
        try {
            syncSlots.withPermit {
                val myLatest = MessageStore.latestTs()
                // A timestamp is not a multi-writer cursor. Periodically reconcile the
                // entire retained set so late messages from a third device cannot be lost.
                if (forced || peer.latest > myLatest) pullFrom(peer, full = forced)
                if (forced || myLatest > peer.latest) pushTo(peer)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun pullFrom(peer: Peer, full: Boolean = false) {
        val since = if (full) 0L else MessageStore.latestTs() - Protocol.OVERLAP_MS
        val body = Http.get("http://${peer.ip}:${peer.port}/api/messages?since=$since") ?: return
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return
        val msgs = (0 until arr.length()).mapNotNull { i ->
            runCatching { Message.fromJson(arr.getJSONObject(i)) }.getOrNull()
        }
        MessageStore.addAll(msgs)
    }

    private suspend fun pushTo(peer: Peer) {
        for (batch in MessageStore.since(0).chunked(100)) {
            val arr = JSONArray()
            batch.forEach { arr.put(it.toJson()) }
            if (!Http.post("http://${peer.ip}:${peer.port}/push", arr.toString())) break
        }
    }

    private fun localIpHint(): String? = appContext?.let { NetworkUtils.localIps().firstOrNull() }

    private suspend fun selfLoop() {
        val s = scope ?: return
        while (s.isActive) {
            PeerRegistry.updateSelf(NetworkUtils.localIps(), boundPort)
            delay(Protocol.BEACON_INTERVAL_MS)
        }
    }
}
