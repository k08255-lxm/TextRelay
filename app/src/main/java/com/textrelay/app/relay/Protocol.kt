package com.textrelay.app.relay

object Protocol {
    /** 本机 HTTP 服务端口（网页版 + 应用间同步共用） */
    const val HTTP_PORT = 24680

    /** UDP 广播/组播发现端口 */
    const val BEACON_PORT = 24681

    /** 组播地址 */
    const val MULTICAST_GROUP = "239.255.246.80"

    /** 信标里的应用标识，用于过滤无关 UDP 包 */
    const val APP_TAG = "textrelay"

    /** 同步时间重叠窗口：容忍设备间时钟误差 */
    const val OVERLAP_MS = 10L * 60 * 1000

    /** UDP 信标间隔 */
    const val BEACON_INTERVAL_MS = 3000L

    /** 周期性全量对账间隔 */
    const val SYNC_INTERVAL_MS = 20000L

    /** 超过该时长没有信标即视为离线 */
    const val PEER_TIMEOUT_MS = 12000L

    /** 消息保留策略 */
    const val MAX_MESSAGES = 1000
    const val MAX_AGE_MS = 24L * 3600 * 1000
}
