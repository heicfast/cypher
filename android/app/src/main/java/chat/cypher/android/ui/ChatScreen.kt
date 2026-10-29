package chat.cypher.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.ble.MeshEvents
import chat.cypher.android.core.CypherBridge
import chat.cypher.android.core.MeshRuntime
import chat.cypher.android.ui.theme.CypherTypography
import chat.cypher.android.ui.theme.BitchatFontFamily
import chat.cypher.android.ui.theme.colorForPeer
import chat.cypher.android.core.PeerIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A chat row (echoed sends + received messages). */
data class ChatMessage(
    val id: String,
    val sender: String,
    val senderId: String,
    val content: String,
    val timestampMs: Long,
    val isOwn: Boolean,
)

/** A peer row in the sheet. */
data class PeerRow(
    val peerId: String,
    val nickname: String,
    val connected: Boolean,
    val secureSession: Boolean,
)

/** Shared UI state fed by engine events. */
object MeshUiState {
    var messages by mutableStateOf(listOf<ChatMessage>())
    var peers by mutableStateOf(listOf<PeerRow>())
    var localPeerId by mutableStateOf("")
    var localNickname by mutableStateOf("cypher")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val snackbarHostState = remember { SnackbarHostState() }
    var showPeers by remember { mutableStateOf(false) }
    var composerText by remember { mutableStateOf("") }
    var selectedPeer by remember { mutableStateOf<PeerRow?>(null) }
    val listState = rememberLazyListState()

    // Subscribe to engine events and fold them into the UI state.
    DisposableEffect(Unit) {
        val unsubscribe = MeshEvents.subscribe { eventsJson ->
            val events = try {
                JSONArray(eventsJson)
            } catch (_: Exception) {
                return@subscribe
            }
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val keys = event.keys()
                if (!keys.hasNext()) continue
                val variant = keys.next()
                val fields = event.optJSONObject(variant) ?: JSONObject()
                when (variant) {
                    "PublicMessageReceived" -> {
                        val message = ChatMessage(
                            id = fields.optString("message_id"),
                            sender = fields.optString("nickname"),
                            senderId = fields.optString("sender"),
                            content = fields.optString("content"),
                            timestampMs = fields.optLong("timestamp_ms"),
                            isOwn = false,
                        )
                        MeshUiState.messages = (MeshUiState.messages + message).takeLast(500)
                    }
                    "NoisePayloadReceived" -> {
                        val peer = fields.optString("peer")
                        // Private messages carry a typed encrypted payload; the
                        // full UI decodes per type — show a compact preview.
                        val message = ChatMessage(
                            id = "noise-${System.nanoTime()}",
                            sender = "noise:${peer.take(8)}",
                            senderId = peer,
                            content = "(encrypted message)",
                            timestampMs = System.currentTimeMillis(),
                            isOwn = false,
                        )
                        MeshUiState.messages = (MeshUiState.messages + message).takeLast(500)
                    }
                    "PeerConnected", "PeerDisconnected", "PeerListUpdated", "SessionEstablished" -> refreshState()
                    "MeshPingResult" -> {
                        // Surfaced through the peer sheet refresh.
                        refreshState()
                    }
                }
            }
        }
        refreshState()
        onDispose { unsubscribe() }
    }

    // Keep the transcript pinned to the newest message.
    LaunchedEffect(MeshUiState.messages.size) {
        if (MeshUiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(MeshUiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (selectedPeer != null) "@${selectedPeer!!.nickname}" else "#mesh",
                            style = CypherTypography.titleMedium,
                        )
                        Text(
                            if (selectedPeer != null && selectedPeer!!.secureSession) "end-to-end encrypted"
                            else "${MeshUiState.peers.count { it.connected }} peers nearby",
                            style = CypherTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPeers = true }) {
                        Icon(Icons.Default.People, contentDescription = "Peers")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(MeshUiState.messages, key = { it.id + it.timestampMs }) { message ->
                    MessageRow(message)
                }
                if (MeshUiState.messages.isEmpty()) {
                    item {
                        Text(
                            "No messages yet. Cypher peers within Bluetooth range appear here — " +
                                "messages relay across the mesh with Noise end-to-end encryption.",
                            style = CypherTypography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
            }

            // Composer.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    BasicTextField(
                        value = composerText,
                        onValueChange = { composerText = it },
                        textStyle = TextStyle(
                            fontFamily = BitchatFontFamily,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                if (composerText.isEmpty()) {
                                    Text(
                                        if (selectedPeer != null) "message @${selectedPeer!!.nickname}"
                                        else "message #mesh",
                                        style = CypherTypography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        val text = composerText.trim()
                        if (text.isEmpty()) return@IconButton
                        val handle = MeshRuntime.engineHandle()
                        if (handle != null) {
                            val response = if (selectedPeer != null) {
                                CypherBridge.nativeSendPrivateMessage(
                                    handle,
                                    selectedPeer!!.peerId,
                                    text,
                                    "dm-${System.currentTimeMillis()}",
                                )
                            } else {
                                CypherBridge.nativeSendPublicMessage(handle, text)
                            }
                            // Fold any commands/events from the send.
                            val parsed = CypherBridge.parseResponse(response)
                            if (parsed.eventsJson != "[]") MeshEvents.publish(parsed.eventsJson)
                            MeshUiState.messages = (
                                MeshUiState.messages + ChatMessage(
                                    id = "echo-${System.nanoTime()}",
                                    sender = MeshUiState.localNickname,
                                    senderId = MeshUiState.localPeerId,
                                    content = text,
                                    timestampMs = System.currentTimeMillis(),
                                    isOwn = true,
                                )
                                ).takeLast(500)
                        }
                        composerText = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    if (showPeers) {
        PeerListSheet(
            peers = MeshUiState.peers,
            localPeerId = MeshUiState.localPeerId,
            onDismiss = { showPeers = false },
            onSelect = { peer ->
                selectedPeer = peer
                showPeers = false
            },
            onDeselect = {
                selectedPeer = null
                showPeers = false
            },
        )
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val time = remember(message.timestampMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestampMs))
    }
    val senderColor = colorForPeer(
        if (message.isOwn) PeerIdentity.nickname(MeshUiState.localNickname)
        else PeerIdentity.mesh(message.senderId.ifBlank { message.sender }),
        chat.cypher.android.ui.theme.LocalBitchatPalette.current,
    )
    Row(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.sender,
                    style = CypherTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (message.isOwn) MaterialTheme.colorScheme.primary else senderColor,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    time,
                    style = CypherTypography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                message.content,
                style = CypherTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerListSheet(
    peers: List<PeerRow>,
    localPeerId: String,
    onDismiss: () -> Unit,
    onSelect: (PeerRow) -> Unit,
    onDeselect: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 32.dp)) {
            Text(
                "Peers in range",
                style = CypherTypography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                "You: ${localPeerId.take(16)}",
                style = CypherTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            ListItem(
                headlineContent = { Text("#mesh") },
                supportingContent = { Text("public channel") },
                leadingContent = {
                    Icon(Icons.Default.People, null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = onDeselect),
            )
            peers.forEach { peer ->
                ListItem(
                    headlineContent = { Text(peer.nickname) },
                    supportingContent = {
                        Text(
                            buildString {
                                append(peer.peerId.take(16))
                                if (peer.connected) append(" · connected")
                                if (peer.secureSession) append(" · encrypted")
                            }
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp).clickable(onClick = { onSelect(peer) }),
                )
            }
            if (peers.isEmpty()) {
                Text(
                    "No peers yet. Keep the app open — peers running Cypher or bitchat appear " +
                        "when they come within Bluetooth range.",
                    style = CypherTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun refreshState() {
    val handle = MeshRuntime.engineHandle() ?: return
    val json = CypherBridge.nativeStateJson(handle) ?: return
    try {
        val state = JSONObject(String(json, Charsets.UTF_8))
        MeshUiState.localPeerId = state.optString("peerId")
        MeshUiState.localNickname = state.optString("nickname")
        val peersArray = state.optJSONArray("peers") ?: JSONArray()
        MeshUiState.peers = (0 until peersArray.length()).mapNotNull { i ->
            val peer = peersArray.optJSONObject(i) ?: return@mapNotNull null
            PeerRow(
                peerId = peer.optString("peerId"),
                nickname = peer.optString("nickname"),
                connected = peer.optBoolean("connected"),
                secureSession = peer.optBoolean("secureSession"),
            )
        }
    } catch (_: Exception) {
    }
}
