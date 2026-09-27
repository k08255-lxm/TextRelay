package com.textrelay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.textrelay.app.MainViewModel
import com.textrelay.app.R
import com.textrelay.app.data.Message
import com.textrelay.app.data.Prefs
import com.textrelay.app.relay.PeerRegistry
import com.textrelay.app.relay.RelayEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val self by vm.self.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showPeers by remember { mutableStateOf(false) }
    val myId = Prefs.deviceId

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbar.showSnackbar("已复制到剪贴板") }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= messages.size - 2) listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("文字互传") },
                actions = {
                    val online = peers.values.count { it.isOnline() }
                    BadgedBox(badge = { if (online > 0) Badge { Text(online.toString()) } }) {
                        IconButton(onClick = { showPeers = true }) {
                            Icon(Icons.Filled.Devices, contentDescription = "设备")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
        ) {
            StatusCard(
                self = self,
                onlineCount = peers.values.count { it.isOnline() },
                onCopyWeb = { ip -> copy("http://$ip:${self.port}") }
            )
            SendCard(
                draft = draft,
                onDraftChange = vm::setDraft,
                onPaste = {
                    val text = clipboard.getText()?.toString()
                    if (!text.isNullOrBlank()) vm.setDraft(text.trim())
                },
                onSend = {
                    vm.send()
                    scope.launch { snackbar.showSnackbar("已发送，离线设备上线后自动接收") }
                }
            )
            if (messages.isEmpty()) {
                EmptyState(self)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        MessageCard(
                            message = m,
                            mine = m.senderId == myId,
                            onCopy = { copy(m.text) }
                        )
                    }
                }
            }
        }
    }

    if (showPeers) {
        PeersDialog(vm = vm, onDismiss = { showPeers = false }, onCopy = { copy(it) })
    }
}

@Composable
private fun StatusCard(
    self: PeerRegistry.SelfInfo,
    onlineCount: Int,
    onCopyWeb: (String) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val ip = self.ips.firstOrNull()
    ElevatedCard(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("本机：${self.name}", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.size(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (ip != null) "网页版：http://$ip:${self.port}" else "未连接局域网",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (ip != null) {
                    IconButton(onClick = { onCopyWeb(ip) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.ContentCopy, "复制网址", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Text(
                text = if (onlineCount > 0) "局域网内 $onlineCount 台设备在线，新设备上线自动同步"
                else "暂未发现其他设备 · 对方上线后会自动收到消息",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SendCard(
    draft: String,
    onDraftChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSend: () -> Unit
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入要发送的文字…") },
                minLines = 2,
                maxLines = 6
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPaste) {
                    Icon(Icons.Filled.ContentPaste, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("粘贴")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("发送")
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: Message, mine: Boolean, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (mine) cs.secondaryContainer else cs.surfaceContainerHighest
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (mine) "我" else message.senderName,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (mine) cs.onSecondaryContainer else cs.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTime(message.ts),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(4.dp))
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (mine) cs.onSecondaryContainer else cs.onSurface
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCopy) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(self: PeerRegistry.SelfInfo) {
    val cs = MaterialTheme.colorScheme
    val ip = self.ips.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Devices, null,
            modifier = Modifier.size(48.dp),
            tint = cs.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        Text("还没有消息", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (ip != null)
                "在本机发送文字，局域网内设备都会收到；\n电脑也可用浏览器打开 http://$ip:${self.port} 发送与复制"
            else "连接到局域网后即可互传文字",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PeersDialog(vm: MainViewModel, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val self by vm.self.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(Prefs.name) }
    var ipInput by remember { mutableStateOf("") }
    val online = peers.values.filter { it.isOnline() }.sortedByDescending { it.lastSeen }
    val manual = peers.values.filter { it.manual }.sortedBy { it.ip }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设备") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "本机",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(6.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("设备名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) Prefs.name = n
                }) { Text("保存名称") }
                val ip = self.ips.firstOrNull()
                if (ip != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "网页版：http://$ip:${self.port}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onCopy("http://$ip:${self.port}") }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "局域网设备",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(4.dp))
                if (online.isEmpty()) {
                    Text(
                        "未发现设备 · 确保双方在同一 Wi-Fi，或手动添加 IP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                online.forEach { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${p.ip}:${p.port} · ${lastSeenText(p.lastSeen)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))
                Text(
                    "手动添加设备（UDP 被路由器屏蔽时）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        placeholder = { Text("对方 IP，如 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val ip = ipInput.trim()
                        if (ip.isNotEmpty()) {
                            Prefs.addManualPeer(ip)
                            RelayEngine.manualPoke(ip)
                            ipInput = ""
                        }
                    }) { Icon(Icons.Filled.Add, "添加") }
                }
                manual.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${p.ip}:${p.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { Prefs.removeManualPeer(p.ip) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, "移除", Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun lastSeenText(ts: Long): String {
    if (ts <= 0) return "未知"
    val d = System.currentTimeMillis() - ts
    return when {
        d < 10_000 -> "刚刚在线"
        d < 60_000 -> "${d / 1000}秒前在线"
        d < 3_600_000 -> "${d / 60_000}分钟前在线"
        else -> "更早在线"
    }
}

private fun formatTime(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val now = Calendar.getInstance()
    val pattern =
        if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        ) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}
