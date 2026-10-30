package chat.cypher.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette

/**
 * Cryptographic Safety Number & SHA-256 Key Fingerprint verification bottom sheet,
 * modeled after bitchat-android's SecurityVerificationSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityVerificationSheet(
    peer: PeerUiModel,
    myPeerIdHex: String,
    myFingerprintHex: String,
    onMarkVerified: () -> Unit,
    onUnverify: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copiedFeedback by remember { mutableStateOf(false) }

    val peerFp = peer.fingerprintHex.ifBlank { PeerIdentity.fingerprintHex(peer.peerIdHex) }
    val myFp = myFingerprintHex.ifBlank { PeerIdentity.fingerprintHex(myPeerIdHex) }
    val safetyCode = remember(myFp, peerFp) {
        PeerIdentity.mutualSafetyNumber(myFp, peerFp)
    }
    val peerGrid = remember(peerFp) { PeerIdentity.formatFingerprintGrid(peerFp) }

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
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PeerAvatar(
                    nickname = peer.nickname,
                    peerIdHex = peer.peerIdHex,
                    size = 52.dp,
                    isOnline = peer.isDirectlyConnected,
                    isVerified = peer.isVerified,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = peer.nickname,
                            style = MaterialTheme.typography.titleLarge,
                            color = palette.textPrimary,
                        )
                        if (peer.isVerified) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = "Verified",
                                tint = palette.encryptedGreen,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = PeerIdentity.formatHandle(peer.nickname, peer.peerIdHex),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                }
                EncryptionStatusPill(
                    sessionState = peer.sessionState,
                    isVerified = peer.isVerified,
                    isBroadcast = false,
                )
            }

            Text(
                text = "Compare this 30-digit safety number or the SHA-256 static key fingerprint with ${peer.nickname} in person or over a trusted channel. If the numbers match, your Noise XX session is free from active man-in-the-middle interception.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
            )

            // Mutual Safety Number Card
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (peer.isVerified) palette.encryptedGreen.copy(alpha = 0.45f) else palette.borderSubtle,
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = if (peer.isVerified) palette.encryptedGreen else palette.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "MUTUAL SAFETY NUMBER",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (peer.isVerified) palette.encryptedGreen else palette.accent,
                        )
                    }

                    Text(
                        text = safetyCode,
                        fontFamily = CypherMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        lineHeight = 30.sp,
                        letterSpacing = 1.2.sp,
                        color = palette.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Peer SHA-256 Fingerprint Grid
            Surface(
                color = palette.surface1,
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = palette.textSecondary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "PEER SHA-256 FINGERPRINT",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.textSecondary,
                            )
                        }
                        Text(
                            text = PeerIdentity.shortId(peer.peerIdHex),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.textMuted,
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.canvas)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        peerGrid.forEach { line ->
                            Text(
                                text = line,
                                fontFamily = CypherMonoFontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = palette.textPrimary,
                                letterSpacing = 0.8.sp,
                            )
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val payload = "Cypher Safety Number (${peer.nickname}):\n$safetyCode\nSHA-256: $peerFp"
                        cb?.setPrimaryClip(ClipData.newPlainText("Cypher Safety Number", payload))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copiedFeedback = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("copy_safety_number_button"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = if (copiedFeedback) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = if (copiedFeedback) palette.encryptedGreen else palette.textPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (copiedFeedback) "Copied" else "Copy Code",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                }

                if (peer.isVerified) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onUnverify()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("unverify_peer_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.RemoveModerator,
                            contentDescription = null,
                            tint = palette.warningAmber,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear Verified",
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.warningAmber,
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onMarkVerified()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("mark_peer_verified_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.encryptedGreen,
                            contentColor = Color_OnEmerald,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Mark Verified",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private val Color_OnEmerald = androidx.compose.ui.graphics.Color(0xFF022C22)
