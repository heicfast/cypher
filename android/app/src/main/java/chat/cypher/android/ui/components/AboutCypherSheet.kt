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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.CypherBridge
import chat.cypher.android.core.MeshController
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.GeistMono

private data class CipherFeaturePillar(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutCypherSheet(
    onDismiss: () -> Unit,
) {
    val palette = CypherTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val version = if (CypherBridge.isLoaded) {
        runCatching { CypherBridge.nativeVersion() }.getOrDefault("1.0.0")
    } else {
        "1.0.0"
    }

    val pillars = listOf(
        CipherFeaturePillar(
            title = "Offline Communication",
            subtitle = "Works without internet over Bluetooth Low Energy",
            icon = Icons.Rounded.Bluetooth,
        ),
        CipherFeaturePillar(
            title = "End-to-End Encryption",
            subtitle = "Noise_XX_25519_ChaChaPoly_BLAKE2s & PBKDF2 AES-256-GCM",
            icon = Icons.Rounded.Lock,
        ),
        CipherFeaturePillar(
            title = "Mesh Networking",
            subtitle = "Multi-hop relay (up to 7 hops) with Dijkstra route planning",
            icon = Icons.Rounded.Hub,
        ),
        CipherFeaturePillar(
            title = "Local Channels",
            subtitle = "GPS geohash channels & topic channels with zero servers",
            icon = Icons.Rounded.Groups,
        ),
        CipherFeaturePillar(
            title = "No Tracking",
            subtitle = "No servers, accounts, phone numbers, or data collection",
            icon = Icons.Rounded.VisibilityOff,
        ),
        CipherFeaturePillar(
            title = "Ephemeral Identity",
            subtitle = "Android Keystore AES-GCM protected identity seed",
            icon = Icons.Rounded.Fingerprint,
        ),
        CipherFeaturePillar(
            title = "Panic Mode",
            subtitle = "Triple-tap logo to instantly wipe Room DB, chats, and keys",
            icon = Icons.Rounded.Bolt,
        ),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bgSecondary,
        contentColor = palette.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CipherAppBadge(size = 72.dp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Cipher",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                ),
                color = palette.textPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Offline Mesh Messenger",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
                color = palette.accentLight,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Private, secure, and internet-free messaging\nfor real-world communication.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(22.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                pillars.forEach { pillar ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF422A8E), Color(0xFF281A5C)),
                                    ),
                                )
                                .border(
                                    1.dp,
                                    palette.accentLight.copy(alpha = 0.40f),
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = pillar.icon,
                                contentDescription = pillar.title,
                                tint = Color(0xFFC4B5FD),
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pillar.title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                ),
                                color = palette.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = pillar.subtitle,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.5.sp,
                                ),
                                color = palette.textSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slash Commands Reference Card
            Surface(
                color = palette.surface,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = palette.accentLight,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "IRC Slash Commands",
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
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = GeistMono),
                                color = palette.accentLight,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(0.42f),
                            )
                            Text(
                                text = spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                                modifier = Modifier.weight(0.58f),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Version $version • Pure Offline BLE Mesh • Open Source",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = GeistMono,
                ),
                color = palette.textMuted,
            )
        }
    }
}
