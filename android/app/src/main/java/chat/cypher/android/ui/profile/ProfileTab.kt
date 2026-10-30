package chat.cypher.android.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.onboarding.BatteryOptimizationHelper
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import chat.cypher.android.ui.theme.ThemePreference

@Composable
fun ProfileTab(
    nickname: String,
    myPeerIdHex: String,
    myStaticKeyHex: String,
    myFingerprintHex: String,
    coreVersion: String,
    isMeshRunning: Boolean,
    backgroundServiceEnabled: Boolean,
    themePreference: ThemePreference,
    blockedCount: Int,
    verifiedCount: Int,
    onUpdateNickname: (String) -> Unit,
    onSetBackgroundService: (Boolean) -> Unit,
    onSetThemePreference: (ThemePreference) -> Unit,
    onOpenGuide: () -> Unit,
    onPanicWipe: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = context as? LifecycleOwner

    var isEditingNickname by remember { mutableStateOf(false) }
    var draftNickname by remember(nickname) { mutableStateOf(nickname) }
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    var showPanicConfirm by remember { mutableStateOf(false) }
    var isBatteryUnrestricted by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        isBatteryUnrestricted = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryUnrestricted = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val fingerprintGrid = remember(myFingerprintHex, myPeerIdHex) {
        val fp = myFingerprintHex.ifBlank { PeerIdentity.fingerprintHex(myPeerIdHex, myStaticKeyHex) }
        PeerIdentity.formatFingerprintGrid(fp)
    }

    fun copyToClipboard(label: String, value: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cb?.setPrimaryClip(ClipData.newPlainText(label, value))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        copiedLabel = label
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .testTag("profile_settings_list"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Identity & Node",
                        style = MaterialTheme.typography.headlineLarge,
                        color = palette.textPrimary,
                    )
                    Text(
                        text = "Cryptographic keys, mesh relay & appearance",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                }

                IconButton(
                    onClick = onOpenGuide,
                    modifier = Modifier.testTag("settings_guide_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.HelpOutline,
                        contentDescription = "How Cypher works",
                        tint = palette.textSecondary,
                    )
                }
            }
        }

        // Cryptographic Identity Hero Card
        item(key = "identity_card") {
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(22.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PeerAvatar(
                            nickname = nickname,
                            peerIdHex = myPeerIdHex,
                            size = 58.dp,
                            isOnline = isMeshRunning,
                            isVerified = true,
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isEditingNickname) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(palette.canvas)
                                            .border(1.dp, palette.accent, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        BasicTextField(
                                            value = draftNickname,
                                            onValueChange = { draftNickname = it.take(24) },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                                color = palette.textPrimary,
                                            ),
                                            cursorBrush = SolidColor(palette.accent),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("nickname_edit_input"),
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            onUpdateNickname(draftNickname)
                                            isEditingNickname = false
                                        },
                                        modifier = Modifier.testTag("save_nickname_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Save nickname",
                                            tint = palette.encryptedGreen,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = nickname,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = palette.textPrimary,
                                    )
                                    IconButton(
                                        onClick = {
                                            draftNickname = nickname
                                            isEditingNickname = true
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .testTag("edit_nickname_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Edit nickname",
                                            tint = palette.accent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }

                            Text(
                                text = PeerIdentity.formatHandle(nickname, myPeerIdHex),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = CypherMonoFontFamily),
                                color = palette.accent,
                            )
                        }
                    }

                    // SHA-256 Key Fingerprint Block
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.canvas)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Key,
                                    contentDescription = null,
                                    tint = palette.encryptedGreen,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "STATIC NOISE XX SHA-256 FINGERPRINT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.encryptedGreen,
                                )
                            }
                            if (copiedLabel != null) {
                                Text(
                                    text = "Copied $copiedLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent,
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            fingerprintGrid.forEach { line ->
                                Text(
                                    text = line,
                                    fontFamily = CypherMonoFontFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = palette.textPrimary,
                                    letterSpacing = 0.8.sp,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { copyToClipboard("Peer ID", myPeerIdHex) },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("copy_peer_id_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Copy Peer ID",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        OutlinedButton(
                            onClick = { copyToClipboard("Fingerprint", myFingerprintHex) },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("copy_fingerprint_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Security,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Copy SHA-256",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }

        // Background Mesh & Battery Optimization Card
        item(key = "battery_and_service_card") {
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Radar,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Mesh Relay & Background Power",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                        )
                    }

                    // Foreground service toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Persistent Foreground Mesh Relay",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.textPrimary,
                            )
                            Text(
                                text = "Keep Bluetooth LE scanning & multi-hop routing alive when Cypher is minimized.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = backgroundServiceEnabled,
                            onCheckedChange = onSetBackgroundService,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.accentOnPrimary,
                                checkedTrackColor = palette.accent,
                            ),
                            modifier = Modifier.testTag("background_service_switch"),
                        )
                    }

                    // Battery Optimization Status & Action
                    Surface(
                        color = if (isBatteryUnrestricted) palette.encryptedGreenSoft else palette.warningAmberSoft,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = 1.dp,
                                color = if (isBatteryUnrestricted) {
                                    palette.encryptedGreen.copy(alpha = 0.35f)
                                } else {
                                    palette.warningAmber.copy(alpha = 0.4f)
                                },
                                shape = RoundedCornerShape(14.dp),
                            ),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = if (isBatteryUnrestricted) {
                                        Icons.Filled.CheckCircle
                                    } else {
                                        Icons.Filled.BatteryChargingFull
                                    },
                                    contentDescription = null,
                                    tint = if (isBatteryUnrestricted) palette.encryptedGreen else palette.warningAmber,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isBatteryUnrestricted) {
                                            "Battery Optimization Disabled (Unrestricted)"
                                        } else {
                                            "Battery Optimization Active"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = palette.textPrimary,
                                    )
                                    Text(
                                        text = if (isBatteryUnrestricted) {
                                            "Cypher is whitelisted from Android Doze throttling for uninterrupted mesh delivery."
                                        } else {
                                            "Android may suspend background BLE links. Grant unrestricted battery access for reliable mesh delivery."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.textSecondary,
                                    )
                                }
                            }

                            if (!isBatteryUnrestricted) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = {
                                            BatteryOptimizationHelper.launchExemptionRequest(
                                                context = context,
                                                launcher = batteryLauncher,
                                                forceListSettings = false,
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                            .testTag("profile_disable_battery_opt_button"),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = palette.warningAmber,
                                            contentColor = Color(0xFF1F1300),
                                        ),
                                    ) {
                                        Text(
                                            text = "Disable Optimization",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            BatteryOptimizationHelper.launchExemptionRequest(
                                                context = context,
                                                launcher = batteryLauncher,
                                                forceListSettings = true,
                                            )
                                        },
                                        modifier = Modifier
                                            .height(42.dp)
                                            .testTag("profile_open_battery_settings_button"),
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(
                                            text = "Settings",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = palette.textPrimary,
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Outlined.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Appearance & Theme Card
        item(key = "appearance_card") {
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Palette,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Appearance",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.textPrimary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ThemePreference.entries.forEach { pref ->
                            val selected = themePreference == pref
                            val bg by animateColorAsState(
                                targetValue = if (selected) palette.accentSoft else palette.canvas,
                                label = "theme_bg",
                            )
                            val border by animateColorAsState(
                                targetValue = if (selected) palette.accent else palette.borderSubtle,
                                label = "theme_border",
                            )
                            val fg by animateColorAsState(
                                targetValue = if (selected) palette.accent else palette.textSecondary,
                                label = "theme_fg",
                            )
                            Surface(
                                color = bg,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onSetThemePreference(pref) }
                                    .border(1.dp, border, RoundedCornerShape(12.dp))
                                    .testTag("theme_option_${pref.name.lowercase()}"),
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = pref.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = fg,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Security Stats & Protocol Reference
        item(key = "security_stats_card") {
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Cryptographic Engine",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.textPrimary,
                    )
                    Text(
                        text = "Rust cypher-core v$coreVersion · Noise_XX_25519_ChaChaPoly_SHA256 · Ed25519 Signing · PKCS#7 Block Padding",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                        color = palette.textSecondary,
                    )
                    Text(
                        text = "$verifiedCount verified peers · $blockedCount blocked peers",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textMuted,
                    )
                }
            }
        }

        // Emergency Panic Wipe Card
        item(key = "panic_wipe_card") {
            Surface(
                color = palette.dangerRedSoft,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.dangerRed.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = palette.dangerRed,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Emergency Panic Wipe",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.dangerRed,
                        )
                    }
                    Text(
                        text = "Immediately zeroizes your in-memory Rust Noise XX sessions, destroys your Android Keystore identity seed, deletes all stored messages, and generates a fresh unlinkable node identity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                    )
                    Button(
                        onClick = { showPanicConfirm = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("panic_wipe_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.dangerRed,
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Execute Panic Wipe",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }

    if (showPanicConfirm) {
        AlertDialog(
            onDismissRequest = { showPanicConfirm = false },
            containerColor = palette.surface1,
            titleContentColor = palette.textPrimary,
            textContentColor = palette.textSecondary,
            title = {
                Text(
                    text = "Destroy Node Identity & Messages?",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = "This action permanently erases your X25519/Ed25519 keys, verified peer trust records, and all conversation history. It cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showPanicConfirm = false
                        onPanicWipe()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.dangerRed,
                        contentColor = Color.White,
                    ),
                ) {
                    Text("Wipe Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirm = false }) {
                    Text("Cancel", color = palette.textSecondary)
                }
            },
        )
    }
}
