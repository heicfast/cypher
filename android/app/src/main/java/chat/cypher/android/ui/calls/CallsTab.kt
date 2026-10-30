package chat.cypher.android.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.chats.relativeTime
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherThemeDefaults

/**
 * The Calls tab: voice-message history (the mesh-native equivalent of a
 * call log — Cypher's voice notes are PTT bursts and audio files).
 */
@Composable
fun CallsTab(onOpenConversation: (String) -> Unit) {
    val colors = CypherThemeDefaults.colors

    // Voice notes across all conversations, newest first.
    val voiceMessages = MeshController.conversations.entries
        .flatMap { (peerId, messages) ->
            messages.filter { it.kind == MeshController.Kind.VOICE }.map { it to peerId }
        }
        .sortedByDescending { it.first.timestampMs }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding(),
    ) {
        Text(
            "Calls",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )

        if (voiceMessages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(colors.searchField)
                            .padding(20.dp),
                    ) {
                        Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = colors.textTertiary)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "No calls yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        "Voice messages you send and receive\nwill appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn {
                items(voiceMessages, key = { it.first.id }) { (message, peerId) ->
                    VoiceCallRow(message, peerId, onOpenConversation)
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
private fun VoiceCallRow(
    message: MeshController.UiMessage,
    peerId: String,
    onOpenConversation: (String) -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    var playing by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }

    val name = MeshController.peers.firstOrNull { it.peerId == peerId }?.nickname
        ?: message.senderNickname

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PeerAvatar(name = name, peerId = peerId, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
            )
            Text(
                text = buildString {
                    append(if (message.mine) "Outgoing" else "Incoming")
                    append(" · voice message")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        Text(
            relativeTime(message.timestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.14f))
                .clickable {
                    val path = message.localPath
                    if (path != null) {
                        if (playing) {
                            runCatching { player?.stop() }
                            player = null
                            playing = false
                        } else {
                            runCatching {
                                val p = android.media.MediaPlayer()
                                p.setDataSource(path)
                                p.prepare()
                                p.setOnCompletionListener { playing = false }
                                p.start()
                                player = p
                                playing = true
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Call else Icons.Filled.Mic,
                contentDescription = "Play voice message",
                tint = colors.accentDark,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
