package chat.cypher.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette

/**
 * Interactive architecture, protocol, gesture, and slash-command reference sheet
 * inspired by bitchat-android's AboutSheet.
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Text(
                        text = "CYPHER PROTOCOL GUIDE",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                    )
                    Text(
                        text = "How the Mesh Works",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textPrimary,
                    )
                }
                Surface(
                    color = palette.accentSoft,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = "core v$coreVersion",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CypherMonoFontFamily),
                        color = palette.accent,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            GuideSectionCard(
                icon = Icons.Filled.Hub,
                title = "Multi-Hop Bluetooth LE Mesh",
                body = "Devices advertise and scan simultaneously using GATT client/server links. Broadcast messages in #mesh and encrypted packets hop across intermediate nodes with bloom-filter deduplication and adaptive TTL.",
            )

            GuideSectionCard(
                icon = Icons.Filled.Lock,
                title = "Noise XX + ChaCha20-Poly1305",
                body = "Direct messages automatically negotiate a mutual Noise_XX_25519_ChaChaPoly_SHA256 session. Payloads are padded to PKCS#7 block boundaries (256 B – 2048 B) to defeat traffic-analysis size fingerprinting.",
            )

            GuideSectionCard(
                icon = Icons.Filled.Gesture,
                title = "Gesture Controls",
                body = "• Swipe right on a conversation row to pin or toggle unread\n• Swipe left on a conversation row to delete with instant Undo\n• Swipe right on any message bubble to quote & reply\n• Long-press any message for quick reactions, copy, mention, or verification\n• Hold or tap the microphone in the composer to record compact AAC voice notes",
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
                            tint = palette.accent,
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
                                color = palette.accent,
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
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(palette.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
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
