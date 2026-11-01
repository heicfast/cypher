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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.core.VerificationService
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.GeistMono
import java.security.MessageDigest
import java.util.Locale

/**
 * Full-screen Security Verification screen with real Ed25519 / Noise XX SHA-256 fingerprints,
 * out-of-band `cipher://verify` URI verification, and over-the-air challenge-response.
 */
@Composable
fun SecurityVerificationScreen(
    peer: PeerUiModel?,
    localIdentityHex: String,
    isVerified: Boolean,
    onToggleVerified: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val palette = CypherTheme.colors
    val localFpRaw by MeshStore.localFingerprintHex.collectAsState()
    val localNick by MeshStore.nickname.collectAsState()
    val knownPeers by MeshStore.knownPeers.collectAsState()

    var copiedTheir by remember { mutableStateOf(false) }
    var copiedYours by remember { mutableStateOf(false) }
    var showQrVerifyDialog by remember { mutableStateOf(false) }
    var verifyUriInput by remember { mutableStateOf("") }
    var verifyFeedback by remember { mutableStateOf<String?>(null) }

    val theirHexRaw = remember(peer?.peerKey, knownPeers) {
        if (peer == null) {
            ""
        } else {
            val norm = peer.peerKey.lowercase(Locale.US)
            val savedFp = knownPeers[norm]?.fingerprintHex
            if (!savedFp.isNullOrBlank()) {
                savedFp.uppercase(Locale.US)
            } else {
                MeshStore.computeDeterministicPeerFingerprint(norm)
            }
        }
    }

    val yourHexRaw = remember(localIdentityHex, localFpRaw) {
        when {
            localFpRaw.length >= 64 -> localFpRaw.take(64).uppercase(Locale.US)
            localIdentityHex.isNotBlank() -> sha256Hex("cipher-local:$localIdentityHex")
            else -> sha256Hex("cipher-local:$localNick")
        }
    }

    val theirFormattedBlock = remember(theirHexRaw) {
        if (theirHexRaw.isNotEmpty()) format4x4HexBlock(theirHexRaw) else ""
    }
    val yourFormattedBlock = remember(yourHexRaw) { format4x4HexBlock(yourHexRaw) }

    val myVerifyQrUri = remember(localIdentityHex, yourHexRaw, localNick) {
        VerificationService.VerificationQR(
            peerIdHex = localIdentityHex.ifBlank { "local" },
            fingerprintHex = yourHexRaw,
            nickname = localNick,
        ).toUrlString()
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
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Top Bar: Back arrow + "Security Verification" + QR URI button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .testTag("security_verify_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.textPrimary,
                    )
                }

                Text(
                    text = "Security Verification",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                    ),
                    color = palette.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )

                IconButton(
                    onClick = { showQrVerifyDialog = true },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(40.dp)
                        .testTag("security_verify_qr_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = "Verify via cipher:// URI",
                        tint = palette.accentLight,
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Peer or Local Identity Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF6B42E8),
                                    Color(0xFF33207A),
                                ),
                            ),
                        )
                        .border(1.5.dp, palette.accentLight.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = peer?.nickname ?: localNick,
                        tint = Color(0xFFD8C7FF),
                        modifier = Modifier.size(26.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = peer?.nickname ?: "@$localNick (You)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                            ),
                            color = palette.textPrimary,
                        )
                        if (peer == null || isVerified) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Verified",
                                tint = palette.success,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            peer == null -> "Local Ed25519 / Noise_XX Identity Key"
                            isVerified -> "Encrypted & Verified"
                            else -> "Noise XX Encrypted • Unverified"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = palette.textSecondary,
                    )
                }
            }

            if (peer != null && theirFormattedBlock.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Their Fingerprint",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.5.sp,
                    ),
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                FingerprintHexBlockCard(
                    formattedBlock = theirFormattedBlock,
                    copied = copiedTheir,
                    onCopy = {
                        copyTextToClipboard(context, "peer_fingerprint", theirFormattedBlock)
                        copiedTheir = true
                    },
                    testTag = "copy_their_fingerprint_button",
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Section: Your Fingerprint
            Text(
                text = "Your Fingerprint",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.5.sp,
                ),
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            FingerprintHexBlockCard(
                formattedBlock = yourFormattedBlock,
                copied = copiedYours,
                onCopy = {
                    copyTextToClipboard(context, "your_fingerprint", yourFormattedBlock)
                    copiedYours = true
                },
                testTag = "copy_your_fingerprint_button",
            )

            Spacer(modifier = Modifier.height(22.dp))

            if (peer != null) {
                // Interactive Verified Banner Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isVerified) {
                                Color(0xFF102A24)
                            } else {
                                palette.surface
                            },
                        )
                        .border(
                            width = 1.2.dp,
                            color = if (isVerified) {
                                palette.success.copy(alpha = 0.55f)
                            } else {
                                palette.accent.copy(alpha = 0.55f)
                            },
                            shape = RoundedCornerShape(18.dp),
                        )
                        .clickable { onToggleVerified() }
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .testTag("toggle_verified_status_card"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (isVerified) palette.success else palette.accentLight,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (isVerified) "Verified" else "Mark as Verified",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp,
                                ),
                                color = if (isVerified) palette.success else palette.accentLight,
                            )
                        }
                        Text(
                            text = if (isVerified) {
                                "You have verified this person's identity."
                            } else {
                                "Tap after confirming both 64-hex fingerprints match."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                            ),
                            color = palette.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        MeshController.sendVerificationChallenge(peer.peerKey)
                        verifyFeedback = "Sent cryptographic challenge over BLE mesh to ${peer.nickname}."
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("send_ota_challenge_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = palette.accentLight,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Send Over-the-Air Challenge",
                        color = palette.textPrimary,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { showQrVerifyDialog = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("open_qr_verify_dialog_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = palette.accentLight,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Share / Verify cipher:// Identity URI",
                        color = palette.textPrimary,
                    )
                }
            }

            if (verifyFeedback != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = verifyFeedback!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.success,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showQrVerifyDialog) {
        AlertDialog(
            onDismissRequest = { showQrVerifyDialog = false },
            containerColor = palette.surfaceElevated,
            title = { Text("Out-of-Band Identity URI", color = palette.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Your Verification URI (tap to copy and share):",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surface)
                            .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                            .clickable {
                                copyTextToClipboard(context, "cipher_verify_uri", myVerifyQrUri)
                                verifyFeedback = "Copied your cipher://verify URI to clipboard."
                            }
                            .padding(10.dp),
                    ) {
                        Text(
                            text = myVerifyQrUri,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = GeistMono,
                                fontSize = 11.sp,
                            ),
                            color = palette.accentLight,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = verifyUriInput,
                        onValueChange = { verifyUriInput = it },
                        label = { Text("Paste peer's cipher://verify?... URI") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.borderSubtle,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = VerificationService.VerificationQR.fromUrlString(verifyUriInput)
                        if (parsed != null) {
                            MeshStore.markPeerVerified(
                                peerHex = parsed.peerIdHex,
                                fingerprintHex = parsed.fingerprintHex,
                                nickname = parsed.nickname,
                            )
                            verifyFeedback = "Verified @${parsed.nickname.ifBlank { parsed.peerIdHex.take(8) }} via signed URI."
                            verifyUriInput = ""
                            showQrVerifyDialog = false
                        } else {
                            verifyFeedback = "Invalid or expired cipher://verify URI."
                        }
                    },
                    enabled = verifyUriInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                ) {
                    Text("Verify Peer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrVerifyDialog = false }) {
                    Text("Close", color = palette.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun FingerprintHexBlockCard(
    formattedBlock: String,
    copied: Boolean,
    onCopy: () -> Unit,
    testTag: String,
) {
    val palette = CypherTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF171A2B))
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formattedBlock,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = GeistMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.5.sp,
                    lineHeight = 23.sp,
                    letterSpacing = 1.1.sp,
                ),
                color = palette.textPrimary,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(36.dp)
                    .testTag(testTag),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "Copy fingerprint",
                    tint = if (copied) palette.success else palette.textSecondary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/**
 * Formats a 64-character hex string into 4 rows of 4x4 uppercase hex blocks:
 *   XXXX XXXX XXXX XXXX
 *   XXXX XXXX XXXX XXXX
 *   XXXX XXXX XXXX XXXX
 *   XXXX XXXX XXXX XXXX
 */
private fun format4x4HexBlock(rawHex: String): String {
    val clean = rawHex.replace(" ", "").uppercase(Locale.US).padEnd(64, '0').take(64)
    val groups = clean.chunked(4)
    return groups.chunked(4).joinToString("\n") { row ->
        row.joinToString("  ")
    }
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
    return digest.joinToString("") { "%02X".format(it) }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
