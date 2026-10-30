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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.data.MeshController
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.ThemeMode
import chat.cypher.android.ui.theme.ThemePrefs

/** Profile tab — identity, appearance, settings, and the panic wipe. */
@Composable
fun ProfileScreen(
    controller: MeshController,
    onChangeNickname: (String) -> Unit,
    onAbout: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val palette = LocalCypherPalette.current
    val identity = controller.identity
    var showNicknameEditor by remember { mutableStateOf(false) }
    var editNickname by remember(identity.nickname) { mutableStateOf(identity.nickname) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var themeMode by remember { mutableStateOf(ThemePrefs.current) }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        Text(
            "Profile",
            color = palette.textPrimary,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        )
        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 28.dp, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---- identity card ----
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = palette.surfaceElevated,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                    ) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            PeerAvatar(
                                name = identity.nickname,
                                identityKey = identity.peerId,
                                size = 84.dp,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.clickable { showNicknameEditor = true },
                            ) {
                                Text(
                                    identity.nickname.ifEmpty { "unnamed" },
                                    color = palette.textPrimary,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Icon(
                                    Icons.Filled.Edit, "Edit nickname",
                                    tint = palette.textTertiary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "ID ${identity.peerId.take(8)}…",
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = palette.surfaceSearch,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Lock, null,
                                            tint = palette.online,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            "Identity fingerprint",
                                            color = palette.textSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        formatFingerprint(identity.fingerprint),
                                        color = palette.textPrimary,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Compare fingerprints out-of-band to verify peers. Keys are generated on-device and never leave it.",
                                        color = palette.textTertiary,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- appearance ----
                item {
                    SettingsCard("APPEARANCE") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsIcon(Icons.Filled.Palette)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Theme",
                                color = palette.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            ThemeMode.entries.forEach { mode ->
                                ThemeChip(
                                    selected = themeMode == mode,
                                    label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                ) {
                                    themeMode = mode
                                    onThemeChange(mode)
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                    }
                }

                // ---- settings ----
                item {
                    SettingsCard("SETTINGS") {
                        SettingsToggleRow(
                            icon = Icons.Filled.Bolt,
                            title = "Run in background",
                            subtitle = "Keep the mesh connected when the app is closed",
                            initial = true,
                        ) { enabled ->
                            // handled at service level in v0.5
                        }
                        SettingsToggleRow(
                            icon = Icons.Filled.Notifications,
                            title = "Private message alerts",
                            subtitle = "Notify for new direct messages",
                            initial = true,
                        ) { }
                    }
                }

                // ---- about ----
                item {
                    SettingsCard("MORE") {
                        SettingsActionRow(
                            icon = Icons.Filled.Info,
                            title = "About Cypher",
                            subtitle = "How the mesh works · open source",
                            onClick = onAbout,
                        )
                    }
                }

                // ---- danger zone ----
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = palette.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clickable { showPanicConfirm = true },
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            SettingsIcon(Icons.Filled.Warning, tint = palette.error)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Wipe all data",
                                    color = palette.error,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "New identity, new keys, empty history",
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Cypher v0.4 · open source · privacy first",
                        color = palette.textTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    // ---- nickname editor dialog ----
    if (showNicknameEditor) {
        AlertDialog(
            onDismissRequest = { showNicknameEditor = false },
            title = { Text("Nickname") },
            text = {
                Column {
                    androidx.compose.foundation.text.BasicTextField(
                        value = editNickname,
                        onValueChange = { if (it.length <= 24) editNickname = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = palette.textPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.surfaceSearch, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Announced to the mesh with a signed identity packet.",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChangeNickname(editNickname.trim())
                    showNicknameEditor = false
                }) { Text("Save", color = palette.primary, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showNicknameEditor = false }) { Text("Cancel") }
            },
        )
    }

    // ---- panic wipe confirm ----
    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            title = { Text("Wipe all data?") },
            text = {
                Text(
                    "This permanently deletes your identity, keys, messages, and history on this device and creates a new identity. It can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPanicConfirm = false
                    controller.panicWipe()
                }) { Text("Wipe", color = palette.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsCard(label: String, content: @Composable () -> Unit) {
    val palette = LocalCypherPalette.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                label,
                color = palette.textTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, tint: Color? = null) {
    val palette = LocalCypherPalette.current
    Box(
        Modifier
            .size(36.dp)
            .background(palette.primarySoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: palette.primary, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    initial: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = LocalCypherPalette.current
    var checked by remember { mutableStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.textSecondary, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedTrackColor = palette.primary,
                uncheckedTrackColor = palette.surfaceSearch,
            ),
        )
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = palette.textSecondary, fontSize = 12.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = palette.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ThemeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val palette = LocalCypherPalette.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.primary else palette.surfaceSearch,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
            Text(
                label,
                color = if (selected) Color.White else palette.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun formatFingerprint(hex: String): String {
    if (hex.isEmpty()) return "—"
    return hex.chunked(4).joinToString("  ")
}
