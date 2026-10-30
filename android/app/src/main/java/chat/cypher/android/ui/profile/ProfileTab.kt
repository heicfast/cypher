package chat.cypher.android.ui.profile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherThemeDefaults
import chat.cypher.android.ui.theme.ThemePreference
import chat.cypher.android.ui.theme.ThemePreferenceManager

/**
 * The Profile tab: identity card (nickname, peer id, fingerprint), theme
 * setting, network status, blocked peers, about — and the panic wipe
 * (bitchat's triple-tap parity).
 */
@Composable
fun ProfileTab() {
    val colors = CypherThemeDefaults.colors
    val context = LocalContext.current
    var showNicknameEditor by remember { mutableStateOf(false) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var panicDone by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            "Profile",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )

        // ---- identity card ----
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                PeerAvatar(
                    name = MeshController.myNickname,
                    peerId = MeshController.myPeerId,
                    size = 88.dp,
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .clickable { showNicknameEditor = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit nickname",
                        tint = Color.White,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                MeshController.myNickname,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "ID ${MeshController.myPeerId.take(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.canvas)
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    MeshController.myFingerprint.chunked(4).joinToString(" ").take(140),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "Your fingerprint — share it so peers can verify you",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ---- network status ----
        SectionTitle("Network")
        SettingsCard {
            StatusRow(
                icon = Icons.Filled.CheckCircle,
                label = "Mesh",
                value = when (val n = MeshController.peers.count { it.connected }) {
                    0 -> "No peers nearby"
                    1 -> "1 peer connected"
                    else -> "$n peers connected"
                },
            )
            StatusRow(
                icon = Icons.Filled.Verified,
                label = "Verified peers",
                value = MeshController.peers.count { it.verified }.toString(),
            )
        }

        // ---- appearance ----
        SectionTitle("Appearance")
        SettingsCard {
            val theme by ThemePreferenceManager.themeFlow.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                ThemeChips(current = theme) { pref ->
                    ThemePreferenceManager.set(context, pref)
                }
            }
        }

        // ---- privacy ----
        SectionTitle("Privacy & Security")
        SettingsCard {
            val blocked = MeshController.peers.filter { it.blocked }
            Text(
                text = if (blocked.isEmpty()) "No blocked peers" else "Blocked peers",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            for (peer in blocked) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Block,
                        contentDescription = null,
                        tint = colors.danger,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        peer.nickname,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp),
                    )
                    TextButton(onClick = { MeshController.unblock(peer.peerId) }) {
                        Text("Unblock", color = colors.accentDark)
                    }
                }
            }
        }

        // ---- danger zone ----
        SectionTitle("Danger Zone")
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showPanicConfirm = true }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = colors.danger,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        "Panic wipe",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.danger,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Erase identity, keys, messages and settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                    )
                }
            }
        }

        // ---- about ----
        Text(
            "Cypher v0.3",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 22.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            "Open Source • Privacy First • Decentralized",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }

    // ---- dialogs ----
    if (showNicknameEditor) {
        var draft by remember { mutableStateOf(MeshController.myNickname) }
        AlertDialog(
            onDismissRequest = { showNicknameEditor = false },
            containerColor = colors.surface,
            title = { Text("Nickname", color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(15) },
                    singleLine = true,
                    label = { Text("Seen by peers nearby") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (draft.isNotBlank()) MeshController.setNickname(draft.trim())
                    showNicknameEditor = false
                }) { Text("Save", color = colors.accentDark, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameEditor = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }

    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            containerColor = colors.surface,
            title = { Text("Wipe all data?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This permanently deletes your identity, keys, messages, and media " +
                        "on this device and creates a new identity. It can't be undone.",
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPanicConfirm = false
                    MeshController.panicWipe { panicDone = true }
                }) { Text("Wipe", color = colors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            },
        )
    }

    if (panicDone) {
        AlertDialog(
            onDismissRequest = { panicDone = false },
            containerColor = colors.surface,
            title = { Text("All data wiped", color = colors.textPrimary) },
            text = { Text("A new identity was created.", color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = { panicDone = false }) {
                    Text("OK", color = colors.accentDark, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

// ---- pieces -----------------------------------------------------------------

@Composable
private fun SectionTitle(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = CypherThemeDefaults.colors.textTertiary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun StatusRow(icon: ImageVector, label: String, value: String) {
    val colors = CypherThemeDefaults.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
    }
}

@Composable
private fun ThemeChips(current: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    val colors = CypherThemeDefaults.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (option in listOf(ThemePreference.System, ThemePreference.Light, ThemePreference.Dark)) {
            val selected = option == current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.accent else colors.searchField)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    option.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Color.White else colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
