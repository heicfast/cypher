package chat.cypher.android.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.AppThemeMode
import chat.cypher.android.core.AutoDeleteTimer
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.onboarding.BatteryOptimizationHelper
import chat.cypher.android.ui.components.AboutCypherSheet
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.GeistMono

private enum class SettingsSubSheet {
    PROFILE,
    PRIVACY_SECURITY,
    MESH_CONNECTIVITY,
    NOTIFICATIONS,
    APPEARANCE,
    ABOUT,
}

private data class SettingsCategorySpec(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val testTag: String,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    onBack: () -> Unit,
    onOpenMeshNetwork: () -> Unit,
    onOpenSecurityVerification: () -> Unit,
    onOpenChannels: () -> Unit,
    onOpenPanicMode: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onEmergencyWipe: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val palette = CypherTheme.colors

    val nickname by MeshStore.nickname.collectAsState()
    val statusBio by MeshStore.statusBio.collectAsState()
    val localIdHex by MeshStore.localIdentityHex.collectAsState()
    val localFpHex by MeshStore.localFingerprintHex.collectAsState()
    val autoDeleteTimer by MeshStore.autoDeleteTimer.collectAsState()
    val themeMode by MeshStore.themeMode.collectAsState()
    val accentHex by MeshStore.accentHex.collectAsState()
    val compactMode by MeshStore.compactMode.collectAsState()
    val screenSecurity by MeshStore.screenSecurityEnabled.collectAsState()
    val hapticFeedback by MeshStore.hapticFeedbackEnabled.collectAsState()
    val autoRelay by MeshStore.autoRelayEnabled.collectAsState()
    val storeAndForward by MeshStore.storeAndForwardEnabled.collectAsState()
    val coverTraffic by MeshStore.coverTrafficEnabled.collectAsState()
    val maxTtlHops by MeshStore.maxTtlHops.collectAsState()
    val bleScanMode by MeshStore.bleScanMode.collectAsState()
    val notificationsEnabled by MeshStore.notificationsEnabled.collectAsState()
    val mentionAlertsEnabled by MeshStore.mentionAlertsEnabled.collectAsState()
    val nearbyAlertsEnabled by MeshStore.nearbyPeerAlertsEnabled.collectAsState()
    val soundVibrationEnabled by MeshStore.soundVibrationEnabled.collectAsState()
    val blockedPeers by MeshStore.blockedPeers.collectAsState()
    val pendingOutboxCount by MeshStore.pendingOutboxCount.collectAsState()

    var activeSheet by remember { mutableStateOf<SettingsSubSheet?>(null) }

    val categories = remember {
        listOf(
            SettingsCategorySpec(
                title = "Profile",
                subtitle = "Nickname, identity, avatar",
                icon = Icons.Rounded.Person,
                testTag = "settings_row_profile",
                onClick = { activeSheet = SettingsSubSheet.PROFILE },
            ),
            SettingsCategorySpec(
                title = "Privacy & Security",
                subtitle = "Encryption, verification, panic mode",
                icon = Icons.Rounded.Security,
                testTag = "settings_row_privacy",
                onClick = { activeSheet = SettingsSubSheet.PRIVACY_SECURITY },
            ),
            SettingsCategorySpec(
                title = "Mesh & Connectivity",
                subtitle = "Bluetooth, relay, hop limit",
                icon = Icons.Rounded.Hub,
                testTag = "settings_row_mesh",
                onClick = { activeSheet = SettingsSubSheet.MESH_CONNECTIVITY },
            ),
            SettingsCategorySpec(
                title = "Channels",
                subtitle = "Manage and join channels",
                icon = Icons.Rounded.Tag,
                testTag = "settings_row_channels",
                onClick = onOpenChannels,
            ),
            SettingsCategorySpec(
                title = "Notifications",
                subtitle = "Messages, mentions, nearby alerts",
                icon = Icons.Rounded.Notifications,
                testTag = "settings_row_notifications",
                onClick = { activeSheet = SettingsSubSheet.NOTIFICATIONS },
            ),
            SettingsCategorySpec(
                title = "Appearance",
                subtitle = "Theme, colors, dark mode",
                icon = Icons.Rounded.Palette,
                testTag = "settings_row_appearance",
                onClick = { activeSheet = SettingsSubSheet.APPEARANCE },
            ),
            SettingsCategorySpec(
                title = "About Cipher",
                subtitle = "Version 1.0.0 • Open Source",
                icon = Icons.Rounded.Info,
                testTag = "settings_row_about",
                onClick = { activeSheet = SettingsSubSheet.ABOUT },
            ),
        )
    }

    Scaffold(
        containerColor = palette.bgPrimary,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF131524), palette.bgPrimary),
                    ),
                )
                .padding(innerPadding),
        ) {
            // Top Bar: Back Arrow + "Settings"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("settings_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.textPrimary,
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                    ),
                    color = palette.textPrimary,
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp)),
                    ) {
                        categories.forEachIndexed { idx, item ->
                            SettingsMenuRow(
                                spec = item,
                            )
                            if (idx < categories.lastIndex) {
                                HorizontalDivider(
                                    color = palette.borderSubtle.copy(alpha = 0.7f),
                                    thickness = 0.8.dp,
                                    modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (activeSheet) {
        SettingsSubSheet.PROFILE -> {
            ProfileSettingsBottomSheet(
                nickname = nickname,
                statusBio = statusBio,
                localIdHex = localIdHex,
                localFingerprintHex = localFpHex,
                onDismiss = { activeSheet = null },
                onSaveNickname = { newNick, newBio ->
                    MeshController.updateNickname(context, newNick)
                    MeshStore.updateStatusBio(context, newBio)
                    activeSheet = null
                },
                onRotateIdentity = {
                    MeshController.rotateIdentity(context)
                },
            )
        }

        SettingsSubSheet.PRIVACY_SECURITY -> {
            PrivacySecurityBottomSheet(
                screenSecurity = screenSecurity,
                autoDeleteTimer = autoDeleteTimer,
                blockedPeers = blockedPeers,
                onDismiss = { activeSheet = null },
                onToggleScreenSecurity = { MeshStore.setScreenSecurityEnabled(it) },
                onSelectAutoDelete = { MeshStore.setAutoDeleteTimer(it) },
                onUnblockPeer = { MeshStore.toggleBlockPeer(it) },
                onOpenVerification = {
                    activeSheet = null
                    onOpenSecurityVerification()
                },
                onOpenPanicMode = {
                    activeSheet = null
                    onOpenPanicMode()
                },
            )
        }

        SettingsSubSheet.MESH_CONNECTIVITY -> {
            MeshConnectivityBottomSheet(
                autoRelay = autoRelay,
                storeAndForward = storeAndForward,
                coverTraffic = coverTraffic,
                maxTtlHops = maxTtlHops,
                bleScanMode = bleScanMode,
                pendingOutboxCount = pendingOutboxCount,
                isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context),
                onDismiss = { activeSheet = null },
                onToggleAutoRelay = { MeshStore.setAutoRelayEnabled(it) },
                onToggleStoreAndForward = { MeshStore.setStoreAndForwardEnabled(it) },
                onToggleCoverTraffic = { MeshStore.setCoverTrafficEnabled(it) },
                onUpdateMaxHops = { MeshStore.setMaxTtlHops(it) },
                onSelectScanMode = { MeshStore.setBleScanMode(it) },
                onOpenTopology = {
                    activeSheet = null
                    onOpenMeshNetwork()
                },
                onRequestBatteryExemption = {
                    BatteryOptimizationHelper.launchExemptionRequest(context)
                    onRequestBatteryExemption()
                },
            )
        }

        SettingsSubSheet.NOTIFICATIONS -> {
            NotificationsSettingsBottomSheet(
                notificationsEnabled = notificationsEnabled,
                mentionAlertsEnabled = mentionAlertsEnabled,
                nearbyAlertsEnabled = nearbyAlertsEnabled,
                soundVibrationEnabled = soundVibrationEnabled,
                onDismiss = { activeSheet = null },
                onToggleNotifications = { MeshStore.setNotificationsEnabled(it) },
                onToggleMentions = { MeshStore.setMentionAlertsEnabled(it) },
                onToggleNearby = { MeshStore.setNearbyPeerAlertsEnabled(it) },
                onToggleSoundVibration = { MeshStore.setSoundVibrationEnabled(it) },
            )
        }

        SettingsSubSheet.APPEARANCE -> {
            AppearanceSettingsBottomSheet(
                themeMode = themeMode,
                accentHex = accentHex,
                compactMode = compactMode,
                hapticFeedback = hapticFeedback,
                onDismiss = { activeSheet = null },
                onSelectTheme = { MeshStore.setThemeMode(it) },
                onSelectAccent = { MeshStore.setAccentHex(it) },
                onToggleCompact = { MeshStore.setCompactMode(it) },
                onToggleHaptics = { MeshStore.setHapticFeedbackEnabled(it) },
            )
        }

        SettingsSubSheet.ABOUT -> {
            AboutCypherSheet(onDismiss = { activeSheet = null })
        }

        null -> Unit
    }
}

@Composable
private fun SettingsMenuRow(
    spec: SettingsCategorySpec,
) {
    val palette = CypherTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = spec.onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp)
            .testTag(spec.testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF22253A))
                    .border(1.dp, palette.borderSubtle, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = spec.icon,
                    contentDescription = spec.title,
                    tint = palette.textPrimary.copy(alpha = 0.90f),
                    modifier = Modifier.size(19.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.5.sp,
                    ),
                    color = palette.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = spec.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.5.sp,
                    ),
                    color = palette.textSecondary,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.textMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSettingsBottomSheet(
    nickname: String,
    statusBio: String,
    localIdHex: String,
    localFingerprintHex: String,
    onDismiss: () -> Unit,
    onSaveNickname: (String, String) -> Unit,
    onRotateIdentity: () -> Unit,
) {
    val context = LocalContext.current
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var editNick by remember(nickname) { mutableStateOf(nickname) }
    var editBio by remember(statusBio) { mutableStateOf(statusBio) }
    var copiedFp by remember { mutableStateOf(false) }

    val displayFp = remember(localIdHex, localFingerprintHex) {
        localFingerprintHex.ifBlank {
            localIdHex.uppercase()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PeerAvatar(
                    peerKeyHex = localIdHex.ifEmpty { editNick },
                    nickname = editNick,
                    size = 56.dp,
                    connected = true,
                    sessionEstablished = true,
                )
                Column {
                    Text(
                        text = "Profile & Ephemeral Identity",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textPrimary,
                    )
                    Text(
                        text = "Ed25519 / Noise_XX_25519_ChaChaPoly_BLAKE2s",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                        color = palette.textSecondary,
                    )
                }
            }

            OutlinedTextField(
                value = editNick,
                onValueChange = { editNick = it.take(24) },
                singleLine = true,
                label = { Text("Nickname") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.borderSubtle,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_nickname_input"),
            )

            OutlinedTextField(
                value = editBio,
                onValueChange = { editBio = it.take(80) },
                singleLine = true,
                label = { Text("Mesh Status Note") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.borderSubtle,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Identity Fingerprint (${if (localIdHex.isNotBlank()) "Peer #${localIdHex.take(8)}" else "Local Seed"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                    Text(
                        text = displayFp.chunked(4).joinToString(" "),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono),
                        color = palette.textPrimary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("cipher_fp", displayFp))
                                copiedFp = true
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(
                                imageVector = if (copiedFp) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (copiedFp) "Copied" else "Copy")
                        }

                        OutlinedButton(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Cipher Mesh Identity (@$editNick): $displayFp")
                                }
                                context.startActivity(Intent.createChooser(share, "Share Identity"))
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(imageVector = Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share")
                        }

                        OutlinedButton(
                            onClick = onRotateIdentity,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Rotate ID")
                        }
                    }
                }
            }

            Button(
                onClick = { onSaveNickname(editNick, editBio) },
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("save_profile_button"),
            ) {
                Text("Save Profile", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySecurityBottomSheet(
    screenSecurity: Boolean,
    autoDeleteTimer: AutoDeleteTimer,
    blockedPeers: Set<String>,
    onDismiss: () -> Unit,
    onToggleScreenSecurity: (Boolean) -> Unit,
    onSelectAutoDelete: (AutoDeleteTimer) -> Unit,
    onUnblockPeer: (String) -> Unit,
    onOpenVerification: () -> Unit,
    onOpenPanicMode: () -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Privacy & Security",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )

            Surface(
                onClick = onOpenVerification,
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = palette.success)
                        Column {
                            Text("Security Verification", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                            Text("Verify peer fingerprints & QR codes", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.textMuted)
                }
            }

            ToggleSettingCard(
                title = "Block Screenshots (FLAG_SECURE)",
                subtitle = "Prevent screen capture and recents previews",
                checked = screenSecurity,
                onCheckedChange = onToggleScreenSecurity,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Disappearing Messages Timer (${autoDeleteTimer.label})",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AutoDeleteTimer.entries.forEach { timer ->
                        val selected = timer == autoDeleteTimer
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) palette.accent else palette.surfaceElevated)
                                .clickable { onSelectAutoDelete(timer) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = timer.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color.White else palette.textSecondary,
                            )
                        }
                    }
                }
            }

            if (blockedPeers.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surface)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Blocked Peers (${blockedPeers.size})", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                    blockedPeers.forEach { peerHex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(peerHex.take(16), style = MaterialTheme.typography.bodySmall.copy(fontFamily = GeistMono), color = palette.textSecondary)
                            OutlinedButton(onClick = { onUnblockPeer(peerHex) }) {
                                Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Unblock")
                            }
                        }
                    }
                }
            }

            Surface(
                onClick = onOpenPanicMode,
                shape = RoundedCornerShape(14.dp),
                color = palette.dangerSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.danger.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = palette.danger)
                        Column {
                            Text("Panic Mode", style = MaterialTheme.typography.titleSmall, color = palette.danger)
                            Text("Instantly wipe all local data, chats, and keys", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.danger)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshConnectivityBottomSheet(
    autoRelay: Boolean,
    storeAndForward: Boolean,
    coverTraffic: Boolean,
    maxTtlHops: Int,
    bleScanMode: String,
    pendingOutboxCount: Int,
    isBatteryExempt: Boolean,
    onDismiss: () -> Unit,
    onToggleAutoRelay: (Boolean) -> Unit,
    onToggleStoreAndForward: (Boolean) -> Unit,
    onToggleCoverTraffic: (Boolean) -> Unit,
    onUpdateMaxHops: (Int) -> Unit,
    onSelectScanMode: (String) -> Unit,
    onOpenTopology: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sliderVal by remember(maxTtlHops) { mutableFloatStateOf(maxTtlHops.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Mesh & Connectivity",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )

            Surface(
                onClick = onOpenTopology,
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, tint = palette.accentLight)
                        Column {
                            Text("Live Mesh Network Graph", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                            Text("View connected peers, hops, and signal strength", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.textMuted)
                }
            }

            ToggleSettingCard(
                title = "Multi-Hop Auto-Relay",
                subtitle = "Forward encrypted frames for nearby peers",
                checked = autoRelay,
                onCheckedChange = onToggleAutoRelay,
            )

            ToggleSettingCard(
                title = "Store & Forward Cache ($pendingOutboxCount queued)",
                subtitle = "Buffer encrypted messages in Room DB for offline peers",
                checked = storeAndForward,
                onCheckedChange = onToggleStoreAndForward,
            )

            ToggleSettingCard(
                title = "Chaff / Cover Traffic",
                subtitle = "Periodic dummy packets to resist traffic analysis",
                checked = coverTraffic,
                onCheckedChange = onToggleCoverTraffic,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .padding(14.dp),
            ) {
                Text(
                    text = "Max Relay Hop Limit (TTL): ${sliderVal.toInt()} hops",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                )
                Slider(
                    value = sliderVal,
                    onValueChange = {
                        sliderVal = it
                        onUpdateMaxHops(it.toInt())
                    },
                    valueRange = 1f..7f,
                    steps = 5,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accentLight,
                        activeTrackColor = palette.accent,
                    ),
                )
            }

            Surface(
                onClick = onRequestBatteryExemption,
                shape = RoundedCornerShape(14.dp),
                color = palette.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_battery_exemption_button"),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BatterySaver,
                            contentDescription = null,
                            tint = if (isBatteryExempt) palette.success else palette.warning,
                        )
                        Column {
                            Text(
                                text = if (isBatteryExempt) "Battery Exemption Active" else "Disable Battery Optimization",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.textPrimary,
                            )
                            Text(
                                text = "Keep background BLE mesh relay running reliably",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                        }
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = palette.textMuted)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsSettingsBottomSheet(
    notificationsEnabled: Boolean,
    mentionAlertsEnabled: Boolean,
    nearbyAlertsEnabled: Boolean,
    soundVibrationEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onToggleMentions: (Boolean) -> Unit,
    onToggleNearby: (Boolean) -> Unit,
    onToggleSoundVibration: (Boolean) -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )
            ToggleSettingCard(
                title = "Direct Message Notifications",
                subtitle = "Notify when encrypted messages arrive over BLE",
                checked = notificationsEnabled,
                onCheckedChange = onToggleNotifications,
            )
            ToggleSettingCard(
                title = "@Mention Alerts",
                subtitle = "Alert when someone mentions your @nickname in a channel",
                checked = mentionAlertsEnabled,
                onCheckedChange = onToggleMentions,
            )
            ToggleSettingCard(
                title = "Nearby Peer Discovery Alerts",
                subtitle = "Subtle alert when a new peer enters Bluetooth range",
                checked = nearbyAlertsEnabled,
                onCheckedChange = onToggleNearby,
            )
            ToggleSettingCard(
                title = "Sound & Haptic Vibration",
                subtitle = "Vibrate on incoming mesh messages",
                checked = soundVibrationEnabled,
                onCheckedChange = onToggleSoundVibration,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsBottomSheet(
    themeMode: AppThemeMode,
    accentHex: String,
    compactMode: Boolean,
    hapticFeedback: Boolean,
    onDismiss: () -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onSelectAccent: (String) -> Unit,
    onToggleCompact: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accents = listOf("#8352FD", "#6938EF", "#3B82F6", "#10B981", "#F43F5E")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surfaceElevated,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.headlineMedium,
                color = palette.textPrimary,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Theme Mode", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                AppThemeMode.entries.forEach { mode ->
                    val selected = mode == themeMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) palette.accentSubtle else Color.Transparent)
                            .clickable { onSelectTheme(mode) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(mode.label, color = palette.textPrimary, style = MaterialTheme.typography.bodyMedium)
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.accentLight)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.surface)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Accent Color", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    accents.forEach { hex ->
                        val col = Color(android.graphics.Color.parseColor(hex))
                        val isSelected = accentHex.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(col)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable { onSelectAccent(hex) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            ToggleSettingCard(
                title = "Compact Message Density",
                subtitle = "Fit more messages and channels on screen",
                checked = compactMode,
                onCheckedChange = onToggleCompact,
            )

            ToggleSettingCard(
                title = "Micro-Interaction Haptics",
                subtitle = "Subtle tactile feedback on actions and gestures",
                checked = hapticFeedback,
                onCheckedChange = onToggleHaptics,
            )
        }
    }
}

@Composable
private fun ToggleSettingCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = CypherTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.bgPrimary,
            ),
        )
    }
}
