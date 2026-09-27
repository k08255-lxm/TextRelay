package com.textrelay.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.textrelay.app.MainViewModel
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
    val ctx = LocalContext.current
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val self by vm.self.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showPeers by rememberSaveable { mutableStateOf(false) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Message?>(null) }
    var scrollAfterSend by remember { mutableStateOf(false) }
    val myId = Prefs.deviceId
    val online = peers.values.count { it.isOnline() }
    val cs = MaterialTheme.colorScheme

    fun notice(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        notice("已复制到剪贴板")
    }

    // Follow new messages only at the bottom, so incoming text never interrupts reading.
    var previousCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (previousCount == 0 || scrollAfterSend || lastVisible >= previousCount) {
                listState.scrollToItem(messages.size + 1) // connection + section heading
            }
        }
        previousCount = messages.size
        scrollAfterSend = false
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconTile(Icons.Outlined.SyncAlt, prominent = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("文字互传", style = MaterialTheme.typography.titleLarge)
                    Text("TextRelay · 随手传，自在用", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = { showPeers = true }) {
                    Icon(Icons.Outlined.Devices, "设备与连接")
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Outlined.MoreHoriz, "更多操作") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("检查更新") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                            onClick = { showMenu = false; vm.checkUpdate(manual = true) })
                        DropdownMenuItem(text = { Text("清空本机记录", color = cs.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = cs.error) },
                            enabled = messages.isNotEmpty(), onClick = { showMenu = false; showClearConfirm = true })
                    }
                }
            }
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
                SendComposer(
                    draft = draft, onDraftChange = vm::setDraft,
                    onPaste = {
                        val text = clipboard.getText()?.toString()
                        if (!text.isNullOrBlank()) vm.setDraft(text.trim()) else notice("剪贴板里还没有文字")
                    },
                    onSend = {
                        scrollAfterSend = true
                        vm.send()
                        notice("已发送，离线设备上线后自动接收")
                    },
                    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()
                )
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "connection") { ConnectionCard(self, online, onOpen = { showPeers = true }) }
                item(key = "heading") {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近的文字", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("保留 24 小时", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
                if (messages.isEmpty()) {
                    item(key = "empty") { EmptyState(onConnect = { showPeers = true }) }
                } else {
                    items(messages, key = { it.id }) { message ->
                        MessageCard(message, message.senderId == myId,
                            onCopy = { copy(message.text) }, onDelete = { pendingDelete = message })
                    }
                }
            }
        }
    }

    if (showPeers) PeersSheet(vm, onDismiss = { showPeers = false }, onCopy = ::copy)
    if (showClearConfirm || pendingDelete != null) {
        val all = showClearConfirm
        AlertDialog(
            onDismissRequest = { showClearConfirm = false; pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = cs.error) },
            title = { Text(if (all) "清空本机记录？" else "删除这条文字？") },
            text = { Text("仅删除本机内容，其他设备保留各自的记录。删除后不会再次同步到本机。") },
            confirmButton = {
                TextButton(onClick = {
                    if (all) vm.clearMessages() else pendingDelete?.let { vm.deleteMessage(it.id) }
                    showClearConfirm = false
                    pendingDelete = null
                    notice(if (all) "已清空本机记录" else "已删除")
                }, colors = ButtonDefaults.textButtonColors(contentColor = cs.error)) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false; pendingDelete = null }) { Text("保留") } }
        )
    }

    val updateEvent by vm.updateEvent.collectAsStateWithLifecycle()
    updateEvent?.let { ev ->
        AlertDialog(
            onDismissRequest = { vm.consumeUpdateEvent() },
            title = {
                Text(
                    when {
                        ev.newVersion != null -> "发现新版本 v${ev.newVersion}"
                        ev.failed -> "检查更新失败"
                        else -> "已是最新版本"
                    }
                )
            },
            text = {
                Text(
                    when {
                        ev.newVersion != null ->
                            (ev.notes?.take(600)?.trim() ?: "") +
                                "\n\n点击「打开下载页」前往浏览器下载新 APK。"
                        ev.failed -> "检查失败：${ev.reason ?: "未知原因"}。\n若使用了代理/VPN，请确认已开启后重试。"
                        else -> "当前已是最新版本。"
                    }
                )
            },
            confirmButton = {
                if (ev.newVersion != null) {
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ev.pageUrl)))
                        vm.consumeUpdateEvent()
                    }) { Text("打开下载页") }
                } else {
                    TextButton(onClick = { vm.consumeUpdateEvent() }) { Text("好的") }
                }
            },
            dismissButton = if (ev.newVersion != null) {
                {
                    TextButton(onClick = { vm.consumeUpdateEvent() }) { Text("下次再说") }
                }
            } else null
        )
    }
}


@Composable
private fun IconTile(icon: ImageVector, prominent: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
        .background(if (prominent) cs.primary else cs.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(23.dp), tint = if (prominent) cs.onPrimary else cs.onPrimaryContainer)
    }
}

@Composable
private fun ConnectionCard(self: PeerRegistry.SelfInfo, online: Int, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(onClick = onOpen, shape = MaterialTheme.shapes.large, color = cs.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(cs.onPrimaryContainer))
                    Spacer(Modifier.width(7.dp))
                    Text(if (self.ips.isEmpty()) "连接到同一 Wi-Fi 即可开始" else if (online == 0) "等待另一台设备" else "$online 台设备在线",
                        style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                }
                Text(if (self.ips.isEmpty()) "文字会先保存在这台设备上" else "${self.name.ifBlank { "本机" }} · ${self.ips.first()}",
                    style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.ChevronRight, null, tint = cs.onPrimaryContainer)
        }
    }
}

@Composable
private fun SendComposer(draft: String, onDraftChange: (String) -> Unit, onPaste: () -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier.padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large,
        color = cs.surface, border = BorderStroke(1.dp, cs.outlineVariant), shadowElevation = 2.dp) {
        Column(Modifier.padding(8.dp)) {
            TextField(
                value = draft, onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("写点什么，传到另一台设备…", style = MaterialTheme.typography.bodyLarge) },
                minLines = 1, maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPaste) {
                    Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("粘贴")
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSend, enabled = draft.isNotBlank(), shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                    Text("发送")
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.Send, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: Message, mine: Boolean, onCopy: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surface, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f))) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mine) Icons.Outlined.NorthEast else Icons.Outlined.SouthWest, null,
                    Modifier.size(16.dp), tint = cs.primary)
                Spacer(Modifier.width(7.dp))
                Text(if (mine) "来自本机" else message.senderName, style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(formatTime(message.ts), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "文字操作", Modifier.size(20.dp), tint = cs.onSurfaceVariant) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("从本机删除", color = cs.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = cs.error) },
                            onClick = { menu = false; onDelete() })
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("复制文字")
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            IconTile(Icons.Outlined.Smartphone)
            Icon(Icons.Outlined.SyncAlt, null, tint = cs.primary, modifier = Modifier.size(24.dp))
            IconTile(Icons.Outlined.LaptopMac)
        }
        Spacer(Modifier.height(24.dp))
        Text("让文字，流动起来", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text("一段灵感，一个链接。\n在这里发送，在另一台设备继续。", style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onConnect) { Text("连接我的设备"); Spacer(Modifier.width(4.dp)); Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp)) }
        Spacer(Modifier.height(8.dp))
        Text("同一局域网 · 无需账号", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeersSheet(vm: MainViewModel, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val self by vm.self.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(Prefs.name) }
    var ipInput by rememberSaveable { mutableStateOf("") }
    var ipError by rememberSaveable { mutableStateOf(false) }
    var nameSaved by rememberSaveable { mutableStateOf(false) }
    val online = peers.values.filter { it.isOnline() }.sortedByDescending { it.lastSeen }
    val manual = peers.values.filter { it.manual }.sortedBy { it.ip }
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.background) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备与连接", style = MaterialTheme.typography.headlineMedium)
                    Text("让你的设备，在同一网络相遇", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭设备面板") }
            }
            DeviceSection("这台设备", Icons.Outlined.Smartphone) {
                OutlinedTextField(value = name, onValueChange = { name = it; nameSaved = false },
                    label = { Text("设备名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = name.isNotBlank() && name.trim() != self.name, onClick = { Prefs.name = name.trim(); nameSaved = true }) {
                        Text(if (nameSaved) "已保存" else "保存名称")
                    }
                }
                self.ips.firstOrNull()?.let { ip ->
                    HorizontalDivider(color = cs.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("电脑浏览器访问", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Text("http://$ip:${self.port}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { onCopy("http://$ip:${self.port}") }) { Icon(Icons.Outlined.ContentCopy, "复制网页版地址", Modifier.size(20.dp)) }
                    }
                }
            }
            DeviceSection("附近设备 · ${online.size}", Icons.Outlined.Devices) {
                if (online.isEmpty()) {
                    Text("还没有发现其他设备", style = MaterialTheme.typography.titleSmall)
                    Text("在另一台设备打开文字互传，并连接同一 Wi-Fi。上线后会自动同步。",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                online.forEachIndexed { index, peer ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp), color = cs.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, style = MaterialTheme.typography.titleSmall)
                            Text("${peer.ip}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text("在线", style = MaterialTheme.typography.labelMedium, color = cs.primary)
                    }
                }
            }
            DeviceSection("手动连接", Icons.Outlined.AddLink) {
                Text("找不到设备？输入对方的局域网 IP 地址。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                OutlinedTextField(value = ipInput, onValueChange = { ipInput = it; ipError = false },
                    label = { Text("IP 地址") }, placeholder = { Text("192.168.1.100") },
                    singleLine = true, isError = ipError,
                    supportingText = if (ipError) { { Text("请输入有效的 IPv4 地址") } } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Button(onClick = {
                    val ip = ipInput.trim()
                    val parts = ip.split('.')
                    val valid = parts.size == 4 && parts.all { part ->
                        part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
                    }
                    if (valid) { Prefs.addManualPeer(ip); RelayEngine.manualPoke(ip); ipInput = "" } else ipError = true
                }, enabled = ipInput.isNotBlank(), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("添加设备")
                }
                manual.forEach { peer ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${peer.ip}:${peer.port}", style = MaterialTheme.typography.bodyMedium)
                            Text("已添加 · 自动尝试连接", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = { Prefs.removeManualPeer(peer.ip) }) { Icon(Icons.Outlined.Close, "移除设备 ${peer.ip}", Modifier.size(18.dp)) }
                    }
                }
            }
            Text("设备离线也没关系，文字会在它上线后自动送达。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun DeviceSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = cs.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                Icon(icon, null, Modifier.size(19.dp), tint = cs.primary)
                Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
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
