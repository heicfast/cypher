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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.ui.theme.CypherTheme

@Composable
fun PanicModeScreen(
    onClearNow: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)

    val palette = CypherTheme.colors
    val transition = rememberInfiniteTransition(label = "panic_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Scaffold(
        containerColor = Color(0xFF140C14),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E0E18),
                            Color(0xFF140B13),
                            Color(0xFF0E0910),
                        ),
                    ),
                )
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .testTag("panic_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = palette.textPrimary,
                    )
                }

                CipherChainLogoIcon(
                    size = 34.dp,
                    tintOverride = Color(0xFFF43F5E),
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Spacer(modifier = Modifier.weight(0.6f))

            Box(
                modifier = Modifier.size(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val maxR = size.minDimension / 2f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFEF4444).copy(alpha = 0.32f * pulse),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = maxR * 1.15f,
                        ),
                        radius = maxR * 1.15f,
                        center = center,
                    )
                    listOf(0.66f, 0.84f, 1.0f).forEach { factor ->
                        drawCircle(
                            color = Color(0xFFF43F5E).copy(alpha = 0.22f),
                            radius = maxR * factor,
                            center = center,
                            style = Stroke(width = 1.3.dp.toPx()),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFFEF4444),
                            spotColor = Color(0xFFEF4444),
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF881D2F),
                                    Color(0xFF4C111E),
                                ),
                            ),
                        )
                        .border(
                            width = 2.dp,
                            color = Color(0xFFF87171).copy(alpha = 0.80f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Trash",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Panic Mode",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
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
                color = Color(0xFFD1C4CE),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(50),
                        ambientColor = Color(0xFFEF4444),
                        spotColor = Color(0xFFEF4444),
                    )
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFFEF4444),
                                Color(0xFFDC2626),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onClearNow() }
                    .testTag("panic_clear_now_button"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = "Clear Now",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        ),
                        color = Color.White,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF241A29))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF3E2D47),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onCancel() }
                    .testTag("panic_cancel_button"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    ),
                    color = Color.White,
                )
            }
        }
    }
}
