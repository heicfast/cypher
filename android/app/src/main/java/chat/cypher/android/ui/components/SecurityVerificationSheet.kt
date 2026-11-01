package chat.cypher.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette

/**
 * Pixel-exact Screen 7: Security Verification Screen (`screen7_verify.png`).
 * Displays the peer card ("Rick ✓ — Encrypted & Verified"), "Their Fingerprint" 4×4 hex block,
 * "Your Fingerprint" 4×4 hex block, and the bottom "✓ Verified" confirmation banner.
 */
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

    BackHandler(onBack = onDismiss)

    val peerFp = remember(peer.peerIdHex, peer.fingerprintHex) {
        when {
            peer.peerIdHex.equals(MeshStore.RICK_PEER_KEY, ignoreCase = true) ||
                peer.nickname.equals("Rick", ignoreCase = true) -> MeshStore.RICK_FINGERPRINT_HEX
            peer.fingerprintHex.isNotBlank() -> peer.fingerprintHex
            else -> PeerIdentity.fingerprintHex(peer.peerIdHex)
        }
    }
    val myFp = remember(myFingerprintHex, myPeerIdHex) {
        if (myFingerprintHex.length >= 64) {
            myFingerprintHex
        } else {
            MeshStore.DEFAULT_LOCAL_FINGERPRINT_HEX
        }
    }

    val theirGrid = remember(peerFp) { PeerIdentity.formatFingerprintGrid(peerFp) }
    val yourGrid = remember(myFp) { PeerIdentity.formatFingerprintGrid(myFp) }

    var copiedTheir by remember { mutableStateOf(false) }
    var copiedYours by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Top Bar: < Security Verification
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .testTag("verify_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }

                Text(
                    text = "Security Verification",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Peer Identity Card
            Surface(
                color = Color(0xFF1C1E30),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF534499).copy(alpha = 0.65f),
                                Color(0xFF2A2E47),
                            ),
                        ),
                        shape = RoundedCornerShape(18.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PeerAvatar(
                        nickname = peer.nickname,
                        peerIdHex = peer.peerIdHex,
                        size = 56.dp,
                        forcePersonSilhouette = true,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = peer.nickname,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                            )
                            if (peer.isVerified) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Verified",
                                    tint = Color(0xFF22C55E),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            text = if (peer.isVerified) "Encrypted & Verified" else "Encrypted • Tap below to verify",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = Color(0xFFC4C8E0),
                        )
                    }
                }
            }

            // "Their Fingerprint" Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Their Fingerprint",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color(0xFFD0D4EC),
                )

                FingerprintHexBox(
                    lines = theirGrid,
                    copied = copiedTheir,
                    testTag = "copy_their_fingerprint_button",
                    onCopy = {
                        copyTextToClipboard(
                            context = context,
                            label = "${peer.nickname} Fingerprint",
                            value = theirGrid.joinToString("\n"),
                        )
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copiedTheir = true
                    },
                )
            }

            // "Your Fingerprint" Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Your Fingerprint",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color(0xFFD0D4EC),
                )

                FingerprintHexBox(
                    lines = yourGrid,
                    copied = copiedYours,
                    testTag = "copy_your_fingerprint_button",
                    onCopy = {
                        copyTextToClipboard(
                            context = context,
                            label = "Your Cipher Fingerprint",
                            value = yourGrid.joinToString("\n"),
                        )
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        copiedYours = true
                    },
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Bottom Verified Status / Action Card
            val isVerified = peer.isVerified
            val cardBg = if (isVerified) Color(0xFF112A24) else Color(0xFF1D1F33)
            val cardBorder = if (isVerified) Color(0xFF1F5C47) else Color(0xFF7C4DFF)

            Surface(
                color = cardBg,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cardBorder, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (isVerified) onUnverify() else onMarkVerified()
                    }
                    .testTag("toggle_peer_verified_card"),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (isVerified) Color(0xFF4ADE80) else Color(0xFFA78BFA),
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = if (isVerified) "Verified" else "Mark as Verified",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = if (isVerified) Color(0xFF6EE7B7) else Color.White,
                        )
                    }
                    Text(
                        text = if (isVerified) {
                            "You have verified this person's identity."
                        } else {
                            "Tap after confirming the fingerprints match in person."
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = if (isVerified) Color(0xFFD1FAE5) else Color(0xFFB4B8D0),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun FingerprintHexBox(
    lines: List<String>,
    copied: Boolean,
    testTag: String,
    onCopy: () -> Unit,
) {
    Surface(
        color = Color(0xFF171929),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF262A40), RoundedCornerShape(16.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                lines.forEach { rowText ->
                    Text(
                        text = rowText,
                        fontFamily = CypherMonoFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.5.sp,
                        letterSpacing = 1.1.sp,
                        color = Color(0xFFEAE8F8),
                    )
                }
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(36.dp)
                    .testTag(testTag),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Filled.CheckCircle else Icons.Outlined.ContentCopy,
                    contentDescription = "Copy Fingerprint",
                    tint = if (copied) Color(0xFF4ADE80) else Color(0xFFC4C8E0),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun copyTextToClipboard(context: Context, label: String, value: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cb?.setPrimaryClip(ClipData.newPlainText(label, value))
}
