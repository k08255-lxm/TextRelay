package com.textrelay.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.textrelay.app.relay.PeerRegistry
import java.util.UUID

object Prefs {
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"
    private const val KEY_MANUAL = "manual_peers"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.getSharedPreferences("textrelay", Context.MODE_PRIVATE)
        }
    }

    val deviceId: String
        get() = sp.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_ID, it).apply()
        }

    var name: String
        get() = sp.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() } ?: "安卓设备"
        set(value) {
            sp.edit().putString(KEY_NAME, value).apply()
            PeerRegistry.renameSelf(value)
        }

    fun manualPeers(): Set<String> = sp.getStringSet(KEY_MANUAL, emptySet()) ?: emptySet()

    fun addManualPeer(ip: String) {
        sp.edit().putStringSet(KEY_MANUAL, manualPeers() + ip).apply()
        PeerRegistry.refreshManual()
    }

    fun removeManualPeer(ip: String) {
        sp.edit().putStringSet(KEY_MANUAL, manualPeers() - ip).apply()
        PeerRegistry.refreshManual()
    }
}
