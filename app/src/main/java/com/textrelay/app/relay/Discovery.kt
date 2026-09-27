package com.textrelay.app.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * UDP 发现：
 * - 每 3 秒向 255.255.255.255 / 各子网广播地址 / 组播地址发信标
 * - 常驻收包线程解析对端信标（带设备名、HTTP 端口、最新消息时间戳）
 * - socket 异常自动重建，兼容 Wi-Fi 重连
 */
class Discovery(
    private val scope: CoroutineScope,
    private val beaconPort: Int
) {
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var running = false
    private val group: InetAddress by lazy { InetAddress.getByName(Protocol.MULTICAST_GROUP) }

    fun start(onBeacon: (ip: String, json: JSONObject) -> Unit, payload: () -> ByteArray) {
        running = true
        scope.launch(Dispatchers.IO) { receiveLoop(onBeacon) }
        scope.launch(Dispatchers.IO) { sendLoop(payload) }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
    }

    @Synchronized
    private fun openSocket(): MulticastSocket {
        socket?.takeUnless { it.isClosed }?.let { return it }
        return MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(beaconPort))
            broadcast = true
            runCatching {
                val nis = NetworkInterface.getNetworkInterfaces()
                if (nis != null) {
                    for (ni in nis) {
                        if (ni.isUp && !ni.isLoopback && ni.supportsMulticast()) {
                            runCatching { joinGroup(InetSocketAddress(group, beaconPort), ni) }
                        }
                    }
                }
            }
            socket = this
        }
    }

    private suspend fun receiveLoop(onBeacon: (String, JSONObject) -> Unit) {
        val buf = ByteArray(8192)
        while (running && scope.isActive) {
            try {
                if (socket == null || socket!!.isClosed) socket = openSocket()
                val packet = DatagramPacket(buf, buf.size)
                socket!!.receive(packet)
                val ip = packet.address?.hostAddress ?: continue
                val json = runCatching {
                    JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                }.getOrNull() ?: continue
                if (json.optString("app") == Protocol.APP_TAG) onBeacon(ip, json)
            } catch (e: Exception) {
                if (!running) break
                runCatching { socket?.close() }
                socket = null
                delay(2000)
            }
        }
    }

    private suspend fun sendLoop(payload: () -> ByteArray) {
        while (running && scope.isActive) {
            try {
                val s = socket ?: openSocket().also { socket = it }
                val data = payload()
                targets().forEach { t ->
                    runCatching {
                        s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), beaconPort))
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                runCatching { socket?.close() }
                socket = null
            }
            delay(Protocol.BEACON_INTERVAL_MS)
        }
    }

    private fun targets(): List<String> {
        val set = LinkedHashSet<String>()
        set.add("255.255.255.255")
        set.add(Protocol.MULTICAST_GROUP)
        runCatching {
            val nis = NetworkInterface.getNetworkInterfaces()
            if (nis != null) {
                for (ni in nis) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.inetAddresses) {
                        if (ia is Inet4Address && ia.isSiteLocalAddress) {
                            set.add(broadcastOf(ia, lastTwo = false))
                            set.add(broadcastOf(ia, lastTwo = true))
                        }
                    }
                }
            }
        }
        return set.toList()
    }

    /** /24 与 /16 两种广播地址都试 */
    private fun broadcastOf(ia: Inet4Address, lastTwo: Boolean): String {
        val b = ia.address.copyOf()
        b[3] = -1
        if (lastTwo) b[2] = -1
        return InetAddress.getByAddress(b).hostAddress ?: "255.255.255.255"
    }
}
