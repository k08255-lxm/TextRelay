package com.textrelay.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.textrelay.app.data.MessageStore
import com.textrelay.app.data.Prefs
import com.textrelay.app.relay.PeerRegistry
import com.textrelay.app.relay.RelayEngine
import com.textrelay.app.relay.UpdateChecker
import com.textrelay.app.relay.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 一次更新检查的结果事件；newVersion 为空表示没有新版本（或检查失败） */
data class UpdateEvent(
    val newVersion: String?,
    val pageUrl: String? = null,
    val notes: String? = null,
    val failed: Boolean = false,
    val reason: String? = null
)

class MainViewModel : ViewModel() {
    val draft = MutableStateFlow("")

    val messages: StateFlow<List<com.textrelay.app.data.Message>> = MessageStore.messages
    val peers: StateFlow<Map<String, com.textrelay.app.relay.Peer>> = PeerRegistry.peers
    val self: StateFlow<PeerRegistry.SelfInfo> = PeerRegistry.self

    private val _updateEvent = MutableStateFlow<UpdateEvent?>(null)
    val updateEvent: StateFlow<UpdateEvent?> = _updateEvent

    fun setDraft(text: String) {
        draft.value = text
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        RelayEngine.send(text)
    }

    /** 删除单条消息（本机），并防止被其他设备同步回来 */
    fun deleteMessage(id: String) {
        MessageStore.delete(id)
    }

    /** 清空本机全部消息，并防止被其他设备同步回来 */
    fun clearMessages() {
        MessageStore.clear()
    }

    /** 手动检查：无论结果如何都弹窗反馈 */
    fun checkUpdate(manual: Boolean) {
        viewModelScope.launch {
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            when (val r = UpdateChecker.check()) {
                is UpdateResult.Update ->
                    _updateEvent.value = UpdateEvent(
                        r.version, r.pageUrl,
                        r.notes?.let { UpdateChecker.plainNotes(it) }
                    )
                UpdateResult.UpToDate ->
                    if (manual) _updateEvent.value = UpdateEvent(null)
                is UpdateResult.Failed ->
                    if (manual) _updateEvent.value = UpdateEvent(null, failed = true, reason = r.reason)
            }
        }
    }

    /** 启动时静默检查，24 小时至多一次；只在发现新版本时提示 */
    fun autoCheckUpdate() {
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheck < 24L * 3600 * 1000) return
        checkUpdate(manual = false)
    }

    fun consumeUpdateEvent() {
        _updateEvent.value = null
    }
}
