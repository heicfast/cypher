package chat.cypher.android.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.data.MeshController
import chat.cypher.android.data.PeerUi
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.LocalCypherPalette
import kotlinx.coroutines.launch

/**
 * Peer info + security verification sheet (bitchat `SecurityVerificationSheet`
 * parity): session state, fingerprints, verify/unverify, favorite, block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerInfoSheet(
    controller: MeshController,
    peer: PeerUi,
    onDismiss: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PeerAvatar(
                name = peer.nickname,
                identityKey = peer.peerId,
                size = 72.dp,
                online = peer.connected,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    peer.nickname,
                    color = palette.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (peer.verified) {
                    Icon(
                        Icons.Filled.Verified, "Verified",
                        tint = palette.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Text(
                "#${peer.suffix} · ${if (peer.connected) "in range" else "out of range"}",
                color = palette.textSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(18.dp))

            val (stateIcon, stateColor, stateLabel) = when {
                peer.verified -> Triple(Icons.Filled.Verified, palette.online, "Encrypted & verified")
                peer.secureSession -> Triple(Icons.Filled.Lock, palette.online, "End-to-end encrypted")
                peer.connected -> Triple(Icons.Filled.LockOpen, palette.warning, "Handshaking…")
                else -> Triple(Icons.Filled.LockOpen, palette.textTertiary, "No session — connects when in range")
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.surfaceElevated,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(stateIcon, null, tint = stateColor, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stateLabel,
                            color = stateColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Noise XX with mutual key authentication",
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            FingerprintBlock("THEIR FINGERPRINT", peer.fingerprint)
            Spacer(Modifier.height(12.dp))
            FingerprintBlock("YOUR FINGERPRINT", controller.identity.fingerprint)

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    icon = if (peer.verified) Icons.Filled.Check else Icons.Filled.Verified,
                    label = if (peer.verified) "Verified" else "Mark verified",
                    color = palette.online,
                    filled = peer.verified,
                    modifier = Modifier.weight(1f),
                ) {
                    controller.setVerified(peer.peerId, !peer.verified)
                }
                ActionButton(
                    icon = if (peer.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    label = if (peer.favorite) "Favorited" else "Favorite",
                    color = palette.warning,
                    filled = peer.favorite,
                    modifier = Modifier.weight(1f),
                ) {
                    controller.toggleFavorite(peer.peerId)
                }
                ActionButton(
                    icon = Icons.Filled.Block,
                    label = "Block",
                    color = palette.error,
                    filled = false,
                    modifier = Modifier.weight(1f),
                ) {
                    controller.blockPeer(peer.peerId)
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Compare fingerprints with ${peer.nickname} using a secure channel (in person, or another messenger) to confirm nobody is impersonating them.",
                color = palette.textTertiary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun FingerprintBlock(label: String, fingerprintHex: String) {
    val palette = LocalCypherPalette.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.surfaceSearch,
        modifier = Modifier.fillMaxWidth(),
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
            Text(
                if (fingerprintHex.isEmpty()) "—"
                else fingerprintHex.chunked(4).joinToString(" "),
                color = palette.textPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (filled) color.copy(alpha = 0.14f) else palette.surfaceSearch,
        modifier = modifier,
        onClick = onClick,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** About sheet (bitchat AboutSheet parity, premium skin). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val palette = LocalCypherPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(palette.primary, palette.primaryDark)
                        ),
                        RoundedCornerShape(20.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("C", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Cypher",
                color = palette.textPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Encrypted mesh messaging",
                color = palette.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "v0.4",
                color = palette.textTertiary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(22.dp))

            AboutCard(
                Icons.Filled.Lock, "End-to-end encrypted",
                "Every private chat runs the Noise XX protocol with mutual authentication. Keys never leave your device."
            )
            Spacer(Modifier.height(10.dp))
            AboutCard(
                Icons.Filled.Block, "No servers, no internet",
                "Messages hop directly between devices over Bluetooth Low Energy. Nothing to subpoena."
            )
            Spacer(Modifier.height(10.dp))
            AboutCard(
                Icons.Filled.Verified, "Verify in person",
                "Compare fingerprints in Profile and peer info to defeat man-in-the-middle attacks."
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "Open source • Privacy first • Decentralized",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun AboutCard(
    icon: ImageVector,
    title: String,
    body: String,
) {
    val palette = LocalCypherPalette.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.surfaceElevated,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = palette.primarySoft,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = palette.primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
