package com.textrelay.app.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.textrelay.app.MainActivity
import com.textrelay.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 前台服务：保活 HTTP + UDP，让后台也能收消息；通知栏展示本机网址 */
class RelayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        RelayEngine.start(this)
        serviceScope.launch {
            PeerRegistry.self.collect { updateNotification(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        RelayEngine.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification("正在启动…")
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(self: PeerRegistry.SelfInfo) {
        val ip = self.ips.firstOrNull()
        val text = if (ip != null) "电脑浏览器打开 http://$ip:${self.port} 即可互发文字"
        else "等待局域网连接…"
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("文字互传运行中")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "运行状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "局域网文字互传的常驻状态" }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "relay_status"
        private const val NOTIF_ID = 1

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }
    }
}
