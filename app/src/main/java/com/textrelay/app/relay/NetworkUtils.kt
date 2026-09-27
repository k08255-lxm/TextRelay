package com.textrelay.app.relay

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    /** 本机所有局域网 IPv4，优先 192.168 段 */
    fun localIps(): List<String> {
        val out = ArrayList<String>()
        runCatching {
            val nis = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.inetAddresses) {
                    if (ia is Inet4Address && !ia.isLoopbackAddress && ia.isSiteLocalAddress) {
                        ia.hostAddress?.let { out.add(it) }
                    }
                }
            }
        }
        return out.distinct().sortedWith(compareBy { if (it.startsWith("192.168.")) 0 else 1 })
    }
}
