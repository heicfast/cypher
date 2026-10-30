package chat.cypher.android.ui.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneMissed
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.cypher.android.ui.theme.LocalCypherPalette

/**
 * Calls tab — present in the premium tab bar for design fidelity. Cypher is
 * an offline mesh messenger: there is no call signaling by architecture, so
 * this screen carries a tasteful empty state.
 */
@Composable
fun CallsScreen() {
    val palette = LocalCypherPalette.current
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
    ) {
        Text(
            "Calls",
            color = palette.textPrimary,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        )
        Surface(
            color = palette.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().padding(top = 140.dp, start = 40.dp, end = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(84.dp)
                        .background(palette.surfaceSearch, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Call, null,
                        tint = palette.textTertiary,
                        modifier = Modifier.size(34.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "No calls yet",
                    color = palette.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cypher is offline-first — messages travel over the Bluetooth mesh with no servers involved. Voice notes are on the roadmap.",
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
