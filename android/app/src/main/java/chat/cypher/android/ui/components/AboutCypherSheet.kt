package chat.cypher.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette

/**
 * About Cipher & Protocol Overview Sheet matching the 7 core pillars shown in the left panel
 * of the design specification:
 * - Offline Communication (Works without internet)
 * - End-to-End Encryption (Keep your messages private)
 * - Mesh Networking (Multi-hop relay up to 7 hops)
 * - Local Channels (Nearby group conversations)
 * - No Tracking (No servers, accounts, or data collection)
 * - Ephemeral Identity (New peer ID generated regularly)
 * - Panic Mode (Triple-tap to instantly clear data)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutCypherSheet(
    coreVersion: String,
    onDismiss: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface0,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CipherChainLogo(size = 36.dp)
                    Column {
                        Text(
                            text = "Cipher",
                            style = MaterialTheme.typography.headlineMedium,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Offline Mesh Messenger",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }
                Surface(
                    color = palette.accentSoft,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "v$coreVersion • Open Source",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                        color = palette.accentSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            Text(
                text = "Private, secure, and internet-free messaging for real-world communication.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )

            GuideSectionCard(
                icon = Icons.Filled.Bluetooth,
                title = "Offline Communication",
                body = "Works without internet — connects directly over Bluetooth Low Energy.",
            )
            GuideSectionCard(
                icon = Icons.Filled.Lock,
                title = "End-to-End Encryption",
                body = "Keep your messages private with Noise XX mutual handshakes and ChaCha20-Poly1305.",
            )
            GuideSectionCard(
                icon = Icons.Filled.Hub,
                title = "Mesh Networking",
                body = "Multi-hop relay (up to 7 hops) extends range automatically across nearby peers.",
            )
            GuideSectionCard(
                icon = Icons.Filled.Groups,
                title = "Local Channels",
                body = "Nearby group conversations over broadcast mesh channels without servers.",
            )
            GuideSectionCard(
                icon = Icons.Filled.VisibilityOff,
                title = "No Tracking",
                body = "No servers, accounts, phone numbers, or telemetry data collection.",
            )
            GuideSectionCard(
                icon = Icons.Filled.Fingerprint,
                title = "Ephemeral Identity",
                body = "Cryptographic peer identity stored locally in Android Keystore.",
            )
            GuideSectionCard(
                icon = Icons.Filled.Bolt,
                title = "Panic Mode",
                body = "Triple-tap the Cipher logo on the home screen to instantly clear all data, chats, and keys.",
            )

            // Slash Commands Reference
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = null,
                            tint = palette.accentSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Slash Commands",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary,
                        )
                    }
                    MeshController.SLASH_COMMANDS.forEach { spec ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = spec.syntax,
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = CypherMonoFontFamily),
                                color = palette.accentSecondary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(0.38f),
                            )
                            Text(
                                text = spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                modifier = Modifier.weight(0.62f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSectionCard(
    icon: ImageVector,
    title: String,
    body: String,
) {
    val palette = LocalCypherPalette.current
    Surface(
        color = palette.surface1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF6D28D9), Color(0xFF4C1D95)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}
