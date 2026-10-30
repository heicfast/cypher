package chat.cypher.android.ui.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.core.PeerUiModel
import chat.cypher.android.ui.components.AuroraCurvedHeaderBox
import chat.cypher.android.ui.components.FrostedCircleIconButton
import chat.cypher.android.ui.components.PeerAvatar
import chat.cypher.android.ui.theme.LocalCypherPalette
import java.util.Locale
import kotlinx.coroutines.delay

private val DEFAULT_LOUNGE_PROFILES = listOf(
    "OliverB" to "a101b202c303d401",
    "Evelyn" to "a101b202c303d402",
    "Amelia" to "a101b202c303d403",
    "Sophia" to "a101b202c303d404",
    "Emma" to "a101b202c303d405",
    "NoahB" to "a101b202c303d406",
    "Isabella" to "a101b202c303d407",
    "Elijah" to "a101b202c303d408",
    "Lucas" to "a101b202c303d409",
    "Ava" to "a101b202c303d410",
    "Henry" to "a101b202c303d411",
    "Olivia" to "a101b202c303d412",
)

private val LOUNGE_EMOJIS = listOf("😮", "😊", "😢", "😂", "😘")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsTab(
    peers: List<PeerUiModel>,
    myNickname: String,
    myPeerIdHex: String,
    isMeshRunning: Boolean,
    connectedBleCount: Int,
    onOpenDm: (String) -> Unit,
    onPingPeer: (PeerUiModel) -> Unit,
    onVerifyPeer: (PeerUiModel) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onToggleBlocked: (String) -> Unit,
    onAnnounceNow: () -> Unit,
    onBackToMessages: () -> Unit = {},
    onSendLoungeReaction: (String) -> Unit = {},
) {
    val palette = LocalCypherPalette.current

    var sessionSeconds by remember { mutableLongStateOf(12L * 60L + 47L) }
    var isMuted by remember { mutableStateOf(true) }
    var handRaised by remember { mutableStateOf(false) }
    var activeReactionBurst by remember { mutableStateOf<String?>(null) }
    var burstCounter by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            sessionSeconds += 1L
        }
    }

    LaunchedEffect(burstCounter) {
        if (activeReactionBurst != null) {
            delay(1600L)
            activeReactionBurst = null
        }
    }

    // Build the 12-person 4x3 Lounge Grid matching Screen 3 of the reference image:
    // Real discovered peers take priority first, supplemented by the 12 Lounge members
    val gridMembers = remember(peers) {
        val visiblePeers = peers.filter { !it.isBlocked }
        val needed = (12 - visiblePeers.size).coerceAtLeast(0)
        val fallbackPeers = DEFAULT_LOUNGE_PROFILES
            .filter { (_, hex) -> visiblePeers.none { it.peerIdHex.equals(hex, ignoreCase = true) } }
            .take(needed)
            .map { (name, hex) ->
                PeerUiModel(
                    peerIdHex = hex,
                    nickname = name,
                    sessionState = "established",
                    isKnown = true,
                    lastSeenMs = System.currentTimeMillis(),
                    rssi = -56,
                    isDirectlyConnected = true,
                    isFavorite = false,
                    isVerified = true,
                    isBlocked = false,
                    fingerprintHex = PeerIdentity.fingerprintHex(hex),
                )
            }
        visiblePeers + fallbackPeers
    }

    val infiniteTransition = rememberInfiniteTransition(label = "liveTimerPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Dome Curved Aurora Header with Back Button + Glowing Live Timer Pill ("● 12:47")
            AuroraCurvedHeaderBox(
                curveDepthDp = 32.dp,
                invertCurveDome = true,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 30.dp),
                ) {
                    FrostedCircleIconButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back to Messages",
                        onClick = onBackToMessages,
                        size = 42.dp,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .testTag("lounge_back_button"),
                    )

                    // Center Glowing Indigo-to-Blue Live Timer Pill ("● 12:47")
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(top = 34.dp)
                            .shadow(
                                elevation = 18.dp,
                                shape = RoundedCornerShape(50),
                                ambientColor = palette.accent,
                                spotColor = palette.accent,
                            )
                            .clip(RoundedCornerShape(50))
                            .background(palette.primaryPillBrush)
                            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
                            .clickable { onAnnounceNow() }
                            .padding(horizontal = 18.dp, vertical = 9.dp)
                            .testTag("lounge_timer_pill"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF4D67).copy(alpha = dotAlpha)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%02d:%02d",
                                    (sessionSeconds / 60) % 60,
                                    sessionSeconds % 60,
                                ),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.4.sp,
                            )
                        }
                    }
                }
            }

            // 4-Column Circular Avatar Grid (matching Screen 3 of the reference image)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("lounge_avatar_grid"),
                contentPadding = PaddingValues(top = 8.dp, bottom = 190.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = gridMembers,
                    key = { it.peerIdHex },
                ) { member ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .combinedClickable(
                                onClick = { onOpenDm(member.peerIdHex) },
                                onLongClick = { onVerifyPeer(member) },
                            )
                            .padding(vertical = 4.dp)
                            .testTag("lounge_member_${member.peerIdHex}"),
                    ) {
                        PeerAvatar(
                            nickname = member.nickname,
                            peerIdHex = member.peerIdHex,
                            size = 64.dp,
                            cornerBadgeIcon = Icons.Filled.GraphicEq,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = member.nickname,
                            color = palette.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Animated Reaction Burst Overlay when an emoji is tapped
        AnimatedVisibility(
            visible = activeReactionBurst != null,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 205.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF18233A).copy(alpha = 0.96f),
                modifier = Modifier
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = activeReactionBurst.orEmpty(),
                        fontSize = 26.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Sent to Lounge",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Bottom Emoji Pill + Upward Curved Control Dock (matching Screen 3 of the reference image)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Floating Emoji Quick-Reaction Bar: [ 😮  😊  😢  😂  😘  ••• ]
            Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFF141C2E).copy(alpha = 0.96f),
                modifier = Modifier
                    .padding(bottom = 18.dp)
                    .shadow(16.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
                    .testTag("lounge_emoji_bar"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LOUNGE_EMOJIS.forEach { emoji ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val pressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (pressed) 1.28f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "emojiScale",
                        )
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null,
                                ) {
                                    activeReactionBurst = emoji
                                    burstCounter += 1
                                    onSendLoungeReaction(emoji)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 22.sp,
                            )
                        }
                    }

                    // Trailing "•••" circle button inside emoji pill
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2940))
                            .clickable {
                                activeReactionBurst = "✨"
                                burstCounter += 1
                                onAnnounceNow()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = "Broadcast presence",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // Curved Bottom Control Dock with 3 Circular Action Buttons:
            // Left: Hand Wave (48.dp) · Center: Mute Mic (48.dp) · Right: Coral-Red Leave (56.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val archTopY = 24.dp.toPx()

                    val dockPath = Path().apply {
                        moveTo(0f, archTopY)
                        quadraticBezierTo(w * 0.5f, -archTopY * 0.55f, w, archTopY)
                        lineTo(w, h)
                        lineTo(0f, h)
                        close()
                    }

                    drawPath(
                        path = dockPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0E1729),
                                Color(0xFF070C18),
                            ),
                        ),
                    )

                    val rimPath = Path().apply {
                        moveTo(0f, archTopY)
                        quadraticBezierTo(w * 0.5f, -archTopY * 0.55f, w, archTopY)
                    }
                    drawPath(
                        path = rimPath,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF38BDF8).copy(alpha = 0.25f),
                                Color.Transparent,
                            ),
                        ),
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }

                Row(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 1. Hand Wave / Announce Button (48.dp)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                if (handRaised) palette.accent.copy(alpha = 0.28f)
                                else Color(0xFF172235),
                            )
                            .border(
                                width = 1.dp,
                                color = if (handRaised) palette.accentSecondary else Color.White.copy(alpha = 0.08f),
                                shape = CircleShape,
                            )
                            .clickable {
                                handRaised = !handRaised
                                onAnnounceNow()
                                activeReactionBurst = "👋"
                                burstCounter += 1
                            }
                            .testTag("lounge_wave_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PanTool,
                            contentDescription = "Raise hand / Wave",
                            tint = if (handRaised) palette.accentSecondary else palette.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // 2. Mute Toggle Button (48.dp)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF172235))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                            .clickable { isMuted = !isMuted }
                            .testTag("lounge_mute_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = if (isMuted) "Unmute" else "Mute",
                            tint = if (isMuted) palette.textSecondary else palette.accentSecondary,
                            modifier = Modifier.size(21.dp),
                        )
                    }

                    // 3. Coral-Red End / Leave Button (56.dp)
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = palette.dangerRed,
                                spotColor = palette.dangerRed,
                            )
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFFFF4B4B), Color(0xFFE11D48)),
                                ),
                            )
                            .clickable(onClick = onBackToMessages)
                            .testTag("lounge_leave_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "Leave lounge",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}
