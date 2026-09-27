package com.textrelay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.textrelay.app.relay.RelayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RelayService.start(context)
        }
    }
}
