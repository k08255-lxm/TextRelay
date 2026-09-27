package com.textrelay.app

import androidx.lifecycle.ViewModel
import android.content.Context
import com.textrelay.app.relay.UpdateDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.File
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
    val reason: String? = null,
    val apkUrl: String? = null
)

data class UpdateDownload(
    val running: Boolean = false,
    val received: Long = 0,
    val total: Long = -1,
    val file: File? = null,
    val error: String? = null
)

class MainViewModel : ViewModel() {
    val draft = MutableStateFlow("")

    val messages: StateFlow<List<com.textrelay.app.data.Message>> = MessageStore.messages
    val peers: StateFlow<Map<String, com.textrelay.app.relay.Peer>> = PeerRegistry.peers
    val self: StateFlow<PeerRegistry.SelfInfo> = PeerRegistry.self

    private val _updateEvent = MutableStateFlow<UpdateEvent?>(null)
    val updateEvent: StateFlow<UpdateEvent?> = _updateEvent
    private val _download = MutableStateFlow(UpdateDownload())
    val download: StateFlow<UpdateDownload> = _download
    private var downloadJob: Job? = null
    private var checkJob: Job? = null

    fun downloadUpdate(context: Context) {
        if (downloadJob?.isCompleted == false) return
        val url = _updateEvent.value?.apkUrl ?: return
        val app = context.applicationContext
        _download.value = UpdateDownload(running = true)
        downloadJob = viewModelScope.launch {
            try {
                val file = UpdateDownloader.download(app, url) { received, total ->
                    _download.value = UpdateDownload(running = true, received = received, total = total)
                }
                _download.value = _download.value.copy(running = false, file = file)
            } catch (e: CancellationException) {
                _download.value = UpdateDownload()
                throw e
            } catch (e: Exception) {
                _download.value = UpdateDownload(error = e.message ?: "下载失败，请重试")
            }
        }
    }

    fun installUpdate(context: Context) {
        val file = _download.value.file ?: return
        try {
            val launched = UpdateDownloader.install(context, file)
            _download.value = _download.value.copy(error = if (launched) null else "允许此来源安装应用后，返回点击「安装」。")
        } catch (e: Exception) {
            _download.value = _download.value.copy(file = file.takeIf { it.exists() }, error = e.message ?: "无法打开安装界面")
        }
    }

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
        if (checkJob?.isActive == true || downloadJob?.isCompleted == false) return
        checkJob = viewModelScope.launch {
            _download.value = UpdateDownload()
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            when (val r = UpdateChecker.check()) {
                is UpdateResult.Update ->
                    _updateEvent.value = UpdateEvent(
                        r.version, r.pageUrl,
                        r.notes?.let { UpdateChecker.plainNotes(it) }, apkUrl = r.apkUrl
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
        downloadJob?.cancel()
        _updateEvent.value = null
    }
}
