package com.textrelay.app

import androidx.lifecycle.ViewModel
import com.textrelay.app.data.MessageStore
import com.textrelay.app.relay.PeerRegistry
import com.textrelay.app.relay.RelayEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainViewModel : ViewModel() {
    val draft = MutableStateFlow("")

    val messages: StateFlow<List<com.textrelay.app.data.Message>> = MessageStore.messages
    val peers: StateFlow<Map<String, com.textrelay.app.relay.Peer>> = PeerRegistry.peers
    val self: StateFlow<PeerRegistry.SelfInfo> = PeerRegistry.self

    fun setDraft(text: String) {
        draft.value = text
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        RelayEngine.send(text)
    }
}
