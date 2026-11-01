package chat.cypher.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pixel-exact Screen 9: Panic Mode Screen (`screen9_panic.png`).
 * Triggered by triple-tapping the Cipher logo on the home screen or from Settings.
 * Displays the red interlocking chain logo, concentric glowing crimson target rings
 * around the trash icon, "Panic Mode" title, description, "Clear Now" button, and "Cancel" button.
 */
@Composable
fun PanicModeScreen(
    onClearNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    BackHandler(onBack = onCancel)

    val infinite = rememberInfiniteTransition(label = "panic_pulse")
    val ringScale by infinite.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "panic_ring_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A0D14),
                        Color(0xFF280D16),
                        Color(0xFF170B12),
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 480.dp)
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Bar: < and Red Cipher Chain Logo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .testTag("panic_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }

                CipherChainLogo(
                    size = 44.dp,
                    isDanger = true,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Center Target Rings + Trash Icon + Title + Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.size(230.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val center = Offset(cx, cy)

                        // Soft crimson radial glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFEF4444).copy(alpha = 0.42f),
                                    Color(0xFF991B1B).copy(alpha = 0.18f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = size.minDimension * 0.50f * ringScale,
                            ),
                            radius = size.minDimension * 0.50f * ringScale,
                            center = center,
                        )

                        // Outer concentric rings
                        val radii = listOf(
                            size.minDimension * 0.47f,
                            size.minDimension * 0.36f,
                        )
                        radii.forEachIndexed { idx, r ->
                            drawCircle(
                                color = Color(0xFFEF4444).copy(alpha = 0.22f + idx * 0.14f),
                                radius = r,
                                center = center,
                                style = Stroke(width = 1.4.dp.toPx()),
                            )
                        }
                    }

                    // Inner Glowing Crimson Circle with White Trash Icon
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF9F1D2C),
                                        Color(0xFF5E1019),
                                    ),
                                ),
                            )
                            .border(2.dp, Color(0xFFF87171).copy(alpha = 0.85f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Clear All Data",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Panic Mode",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 31.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Triple-tap the Cipher logo to\ninstantly clear all local data,\nchats, and keys.",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 15.5.sp,
                        lineHeight = 23.sp,
                    ),
                    color = Color(0xFFE2D6DA),
                    textAlign = TextAlign.Center,
                )
            }

            // Bottom Action Buttons: Clear Now & Cancel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Primary Red Pill: Clear Now
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFEF4444),
                                    Color(0xFFDC2626),
                                ),
                            ),
                        )
                        .border(1.dp, Color(0xFFFCA5A5).copy(alpha = 0.45f), RoundedCornerShape(50))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClearNow()
                        }
                        .testTag("panic_clear_now_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Clear Now",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                        )
                    }
                }

                // Secondary Dark Pill: Cancel
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF221A2E))
                        .border(1.dp, Color(0xFF3E3252), RoundedCornerShape(50))
                        .clickable(onClick = onCancel)
                        .testTag("panic_cancel_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Cancel",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                }
            }
        }
    }
}
