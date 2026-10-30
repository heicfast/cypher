package chat.cypher.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.PeerIdentity
import chat.cypher.android.ui.theme.CypherThemeDefaults
import chat.cypher.android.ui.theme.peerAvatarColor
import chat.cypher.android.ui.theme.peerTextColor

/** Deterministic color for a peer's avatar from their mesh identity. */
@Composable
fun peerColorFor(peerId: String): Color =
    peerAvatarColor(PeerIdentity.mesh(peerId))

/** Display-name text color for a peer. */
@Composable
fun peerNameColor(peerId: String): Color {
    val colors = CypherThemeDefaults.colors
    return if (peerId == MeshController.myPeerId) {
        if (colors.isDark) Color(0xFFFF9F0A) else Color(0xFFE8850B)
    } else {
        peerTextColor(PeerIdentity.mesh(peerId), colors.isDark)
    }
}

/**
 * The signature avatar: colored circle with the peer's initials — the
 * mesh-native equivalent of a contact photo.
 */
@Composable
fun PeerAvatar(
    name: String,
    peerId: String,
    size: Dp,
    modifier: Modifier = Modifier,
    online: Boolean = false,
    showOnlineDot: Boolean = false,
    group: Boolean = false,
) {
    val colors = CypherThemeDefaults.colors
    Box(modifier = modifier.size(size)) {
        val shape = if (group) RoundedCornerShape(size * 0.34f) else CircleShape
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(
                    if (peerId.isEmpty()) {
                        Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF2563EB)))
                    } else {
                        Brush.linearGradient(
                            listOf(peerColorFor(peerId), peerColorFor(peerId).darken(0.12f)),
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
                maxLines = 1,
            )
        }
        if (showOnlineDot && online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.26f)
                    .clip(CircleShape)
                    .background(colors.online)
                    .border(2.5.dp, colors.canvas, CircleShape),
            )
        }
    }
}

/** Stories-row avatar: accent ring + presence dot. */
@Composable
fun StoryAvatar(
    name: String,
    peerId: String,
    online: Boolean,
    onClick: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        PeerAvatar(
            name = name,
            peerId = peerId,
            size = 56.dp,
            modifier = Modifier
                .border(
                    width = if (online) 2.5.dp else 2.dp,
                    color = if (online) colors.accent else colors.badgeMuted,
                    shape = CircleShape,
                )
                .padding(2.5.dp),
            online = online,
            showOnlineDot = true,
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 6.dp)
                .size(width = 62.dp, height = 16.dp),
            textAlign = TextAlign.Center,
        )
    }
}

fun initialsOf(name: String): String {
    val clean = name.trim()
    if (clean.isEmpty()) return "?"
    val parts = clean.split(Regex("\\s+"))
    return when {
        parts.size >= 2 -> parts[0].first().toString() + parts[1].first().toString()
        else -> clean.first().toString()
    }.uppercase()
}

/** Solid blue unread-count pill. */
@Composable
fun UnreadBadge(count: Int, muted: Boolean = false, modifier: Modifier = Modifier) {
    val colors = CypherThemeDefaults.colors
    Box(
        modifier = modifier
            .size(width = if (count > 9) 24.dp else 20.dp, height = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (muted) colors.badgeMuted else colors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Rounded search field in the canvas tone (tap-to-edit variant). */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = CypherThemeDefaults.colors
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.searchField),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 11.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(17.dp),
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                            )
                        }
                    } else {
                        inner()
                    }
                },
            )
        }
    }
}

/** Big centered empty state with an icon and a hint. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.searchField)
                .padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.textTertiary)
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun Color.darken(factor: Float): Color = Color(
    red = red * (1 - factor),
    green = green * (1 - factor),
    blue = blue * (1 - factor),
    alpha = alpha,
)
