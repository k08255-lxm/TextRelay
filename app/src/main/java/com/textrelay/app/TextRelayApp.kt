package com.textrelay.app

import android.app.Application
import com.textrelay.app.data.MessageStore
import com.textrelay.app.data.Prefs

class TextRelayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MessageStore.init(this)
    }
}
