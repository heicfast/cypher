package chat.cypher.android.ui.profile

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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.onboarding.BatteryOptimizationHelper
import chat.cypher.android.ui.theme.ThemePreference

private enum class SettingsModalType {
    PROFILE,
    PRIVACY_SECURITY,
    MESH_CONNECTIVITY,
    NOTIFICATIONS,
    APPEARANCE,
}

private data class SettingsMenuRowSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Screen 8: Settings screen matching the Cipher design specification pixel-by-pixel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileTab(
    controller: MeshController,
    onBack: (() -> Unit)? = null,
    onOpenVerification: (String?) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenMeshNetwork: () -> Unit = {},
    onOpenChannels: () -> Unit = {},
    onOpenPanicMode: () -> Unit = {},
) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    var activeModal by remember { mutableStateOf<SettingsModalType?>(null) }

    val rows = listOf(
        SettingsMenuRowSpec(
            id = "profile",
            title = "Profile",
            subtitle = "Nickname, identity, avatar",
            icon = Icons.Outlined.PersonOutline,
            onClick = { activeModal = SettingsModalType.PROFILE },
        ),
        SettingsMenuRowSpec(
            id = "privacy_security",
            title = "Privacy & Security",
            subtitle = "Encryption, verification, panic mode",
            icon = Icons.Outlined.Shield,
            onClick = { activeModal = SettingsModalType.PRIVACY_SECURITY },
        ),
        SettingsMenuRowSpec(
            id = "mesh_connectivity",
            title = "Mesh & Connectivity",
            subtitle = "Bluetooth, relay, hop limit",
            icon = Icons.Outlined.Hub,
            onClick = { activeModal = SettingsModalType.MESH_CONNECTIVITY },
        ),
        SettingsMenuRowSpec(
            id = "channels",
            title = "Channels",
            subtitle = "Manage and join channels",
            icon = Icons.Outlined.Tag,
            onClick = onOpenChannels,
        ),
        SettingsMenuRowSpec(
            id = "notifications",
            title = "Notifications",
            subtitle = "Messages, mentions, nearby alerts",
            icon = Icons.Outlined.NotificationsNone,
            onClick = { activeModal = SettingsModalType.NOTIFICATIONS },
        ),
        SettingsMenuRowSpec(
            id = "appearance",
            title = "Appearance",
            subtitle = "Theme, colors, dark mode",
            icon = Icons.Outlined.Palette,
            onClick = { activeModal = SettingsModalType.APPEARANCE },
        ),
        SettingsMenuRowSpec(
            id = "about_cipher",
            title = "About Cipher",
            subtitle = "Version 1.0.0 • Open Source",
            icon = Icons.Outlined.Info,
            onClick = onOpenAbout,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E17))
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Top Header Bar: <- Settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("settings_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .testTag("settings_list"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF171928))
                        .border(1.dp, Color(0xFF25283D), RoundedCornerShape(22.dp)),
                ) {
                    rows.forEachIndexed { index, row ->
                        SettingsCardRow(
                            spec = row,
                        )
                        if (index < rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 68.dp, end = 16.dp),
                                thickness = 0.7.dp,
                                color = Color(0xFF23263A),
                            )
                        }
                    }
                }
            }
        }
    }

    when (activeModal) {
        SettingsModalType.PROFILE -> {
            ProfileSettingsModal(
                controller = controller,
                onDismiss = { activeModal = null },
            )
        }
        SettingsModalType.PRIVACY_SECURITY -> {
            PrivacySecurityModal(
                onDismiss = { activeModal = null },
                onOpenVerification = {
                    activeModal = null
                    onOpenVerification(MeshStore.RICK_PEER_KEY)
                },
                onOpenPanicMode = {
                    activeModal = null
                    onOpenPanicMode()
                },
            )
        }
        SettingsModalType.MESH_CONNECTIVITY -> {
            MeshConnectivityModal(
                controller = controller,
                onDismiss = { activeModal = null },
                onOpenMeshTopology = {
                    activeModal = null
                    onOpenMeshNetwork()
                },
            )
        }
        SettingsModalType.NOTIFICATIONS -> {
            NotificationsSettingsModal(
                controller = controller,
                onDismiss = { activeModal = null },
            )
        }
        SettingsModalType.APPEARANCE -> {
            AppearanceSettingsModal(
                controller = controller,
                onDismiss = { activeModal = null },
            )
        }
        null -> Unit
    }
}

@Composable
private fun SettingsCardRow(
    spec: SettingsMenuRowSpec,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = spec.onClick)
            .padding(horizontal = 16.dp, vertical = 14.5.dp)
            .testTag("settings_row_${spec.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF22253A))
                .border(1.dp, Color(0xFF2E324D), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = Color(0xFFD0D5DD),
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = spec.title,
                color = Color.White,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = spec.subtitle,
                color = Color(0xFF8E93AD),
                fontSize = 12.5.sp,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFF8E93AD),
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSettingsModal(
    controller: MeshController,
    onDismiss: () -> Unit,
) {
    val currentNick by controller.nickname.collectAsState()
    val myPeerId by controller.myPeerIdHex.collectAsState()
    var editedNick by remember(currentNick) { mutableStateOf(currentNick) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF151726),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Profile & Ephemeral Identity",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Your nickname is broadcasted to nearby Bluetooth mesh peers.",
                color = Color(0xFF8E93AD),
                fontSize = 13.5.sp,
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E2135))
                        .border(1.dp, Color(0xFF2D3250), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = editedNick,
                        onValueChange = { editedNick = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color(0xFF8B5CF6)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF8352FD), Color(0xFF6938EF)),
                            )
                        )
                        .clickable {
                            controller.updateNickname(editedNick)
                            onDismiss()
                        }
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Save",
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1B1E30))
                    .padding(14.dp),
            ) {
                Text(
                    text = "Ephemeral Peer ID",
                    color = Color(0xFF8E93AD),
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = myPeerId.ifBlank { "3501df1fc3eb113f" },
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacySecurityModal(
    onDismiss: () -> Unit,
    onOpenVerification: () -> Unit,
    onOpenPanicMode: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF151726),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Privacy & Security",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "End-to-end Noise XX encryption and emergency data wipe.",
                color = Color(0xFF8E93AD),
                fontSize = 13.5.sp,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C2034))
                    .border(1.dp, Color(0xFF2B314E), RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenVerification)
                    .padding(16.dp),
            ) {
                Column {
                    Text(
                        text = "Security Verification",
                        color = Color.White,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Inspect and compare 16-word hex fingerprints",
                        color = Color(0xFF8E93AD),
                        fontSize = 12.5.sp,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2B1722))
                    .border(1.dp, Color(0xFF562538), RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenPanicMode)
                    .padding(16.dp),
            ) {
                Column {
                    Text(
                        text = "Panic Mode",
                        color = Color(0xFFF87171),
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Triple-tap the Cipher logo or open Panic Mode to wipe all data",
                        color = Color(0xFFE5A4B4),
                        fontSize = 12.5.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeshConnectivityModal(
    controller: MeshController,
    onDismiss: () -> Unit,
    onOpenMeshTopology: () -> Unit,
) {
    val context = LocalContext.current
    val bgEnabled by controller.store.backgroundServiceEnabled.collectAsState()
    var isBatteryExempt by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF151726),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Mesh & Connectivity",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Multi-hop Bluetooth Low Energy relay (up to 7 hops).",
                color = Color(0xFF8E93AD),
                fontSize = 13.5.sp,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF8352FD), Color(0xFF6938EF)),
                        )
                    )
                    .clickable(onClick = onOpenMeshTopology)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Open Mesh Network Topology",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C2034))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Background Mesh Relay",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Keep BLE scanning and packet forwarding active",
                        color = Color(0xFF8E93AD),
                        fontSize = 12.5.sp,
                    )
                }
                Switch(
                    checked = bgEnabled,
                    onCheckedChange = { controller.setBackgroundServiceEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF7C4DFF),
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C2034))
                    .clickable {
                        BatteryOptimizationHelper.launchExemptionRequest(context)
                        isBatteryExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                    }
                    .padding(16.dp)
                    .testTag("settings_battery_optimization_row"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BatteryChargingFull,
                    contentDescription = null,
                    tint = if (isBatteryExempt) Color(0xFF4ADE80) else Color(0xFFA78BFA),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isBatteryExempt) {
                            "Unrestricted Background Battery Active"
                        } else {
                            "Disable Battery Optimization"
                        },
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Prevent Android from throttling multi-hop BLE relays",
                        color = Color(0xFF8E93AD),
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsSettingsModal(
    controller: MeshController,
    onDismiss: () -> Unit,
) {
    val bgEnabled by controller.store.backgroundServiceEnabled.collectAsState()
    val mutedSet by controller.store.mutedConversations.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF151726),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Notifications",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Direct messages, @mentions, and nearby mesh alerts.",
                color = Color(0xFF8E93AD),
                fontSize = 13.5.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1C2034))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Persistent Mesh Notification",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${mutedSet.size} conversation(s) currently muted",
                        color = Color(0xFF8E93AD),
                        fontSize = 12.5.sp,
                    )
                }
                Switch(
                    checked = bgEnabled,
                    onCheckedChange = { controller.setBackgroundServiceEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF7C4DFF),
                    ),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsModal(
    controller: MeshController,
    onDismiss: () -> Unit,
) {
    val currentTheme by controller.store.themePreference.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF151726),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Appearance",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Cipher is crafted for high-contrast dark mode OLED clarity.",
                color = Color(0xFF8E93AD),
                fontSize = 13.5.sp,
            )

            ThemePreference.entries.forEach { pref ->
                val isSelected = pref == currentTheme
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) Color(0xFF282048) else Color(0xFF1C2034))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) Color(0xFF8B5CF6) else Color(0xFF282D46),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable {
                            controller.setThemePreference(pref)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pref.name.lowercase().replaceFirstChar { it.uppercase() },
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (isSelected) {
                        Text(
                            text = "Active",
                            color = Color(0xFFA78BFA),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
