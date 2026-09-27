package com.textrelay.app.relay

import com.textrelay.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Peer(
    val ip: String,
    val id: String,
    val name: String,
    val port: Int,
    val latest: Long,
    val lastSeen: Long,
    val manual: Boolean,
    /** 已确认推送给对方的最晚消息时间戳（对方的信标/确认会推进它） */
    val pushedUntil: Long
) {
    fun isOnline(now: Long = System.currentTimeMillis()): Boolean =
        !manual && now - lastSeen < Protocol.PEER_TIMEOUT_MS
}

object PeerRegistry {
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers

    data class SelfInfo(val name: String, val id: String, val ips: List<String>, val port: Int)

    private val _self = MutableStateFlow(SelfInfo("", "", emptyList(), Protocol.HTTP_PORT))
    val self: StateFlow<SelfInfo> = _self

    @Synchronized
    fun upsert(ip: String, id: String, name: String, port: Int, latest: Long) {
        val old = _peers.value[ip]
        val now = System.currentTimeMillis()
        val peer = Peer(
            ip = ip,
            id = id.ifBlank { old?.id ?: "unknown" },
            name = name.ifBlank { old?.name ?: "未知设备" },
            port = if (port > 0) port else old?.port ?: Protocol.HTTP_PORT,
            latest = maxOf(latest, old?.latest ?: 0L),
            lastSeen = now,
            manual = old?.manual ?: false,
            pushedUntil = maxOf(old?.pushedUntil ?: latest, latest)
        )
        _peers.value = _peers.value + (ip to peer)
    }

    @Synchronized
    fun setPushedUntil(ip: String, ts: Long) {
        val old = _peers.value[ip] ?: return
        if (ts <= old.pushedUntil) return
        _peers.value = _peers.value + (ip to old.copy(pushedUntil = ts))
    }

    @Synchronized
    fun refreshManual() {
        val manuals = Prefs.manualPeers()
        val cur = _peers.value.toMutableMap()
        manuals.forEach { ip ->
            if (cur[ip] == null) {
                cur[ip] = Peer(ip, "manual", "手动添加", Protocol.HTTP_PORT, 0, 0, true, 0)
            }
        }
        cur.keys.filter { key -> cur[key]?.manual == true && key !in manuals }.forEach { cur.remove(it) }
        _peers.value = cur
    }

    @Synchronized
    fun updateSelf(ips: List<String>, port: Int) {
        val info = SelfInfo(Prefs.name, Prefs.deviceId, ips, port)
        val old = _self.value
        // 内容没变就不发新值，避免通知栏每 3 秒重发
        if (old.name == info.name && old.id == info.id && old.port == info.port && old.ips == info.ips) return
        _self.value = info
    }

    @Synchronized
    fun renameSelf(name: String) {
        _self.value = _self.value.copy(name = name)
    }
}
