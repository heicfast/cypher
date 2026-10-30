package chat.cypher.android.ui.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import chat.cypher.android.ui.theme.LocalCypherPalette

/**
 * Onboarding suite — the bitchat setup flow (permission explanation → system
 * grants → background-location → bluetooth/location/battery checks →
 * initializing), skinned with the Cypher premium design, plus a welcome step
 * and nickname setup (the "full setup" expected of a messaging app).
 */

// ---- shared building blocks ---------------------------------------------------

@Composable
private fun OnboardingScaffold(content: @Composable () -> Unit) {
    val palette = LocalCypherPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .systemBarsPadding()
    ) { content() }
}

@Composable
private fun CypherWordMark(modifier: Modifier = Modifier, tagline: String? = null) {
    val palette = LocalCypherPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Cypher",
            color = palette.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = (-0.8).sp,
        )
        if (tagline != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                tagline,
                color = palette.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    bullets: List<String>,
    tint: Color? = null,
) {
    val palette = LocalCypherPalette.current
    val color = tint ?: palette.primary
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            bullets.forEach { line ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("•  ", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(line, color = palette.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCypherPalette.current
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(27.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.primary,
            contentColor = palette.onPrimary,
        ),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryRow(primaryText: String, primaryAction: () -> Unit, secondaryText: String, secondaryAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = primaryAction,
            modifier = Modifier.weight(1f).height(50.dp),
            shape = RoundedCornerShape(25.dp),
        ) { Text(primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
        TextButton(
            onClick = secondaryAction,
            modifier = Modifier.weight(1f).height(50.dp),
        ) { Text(secondaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun BigStatusIcon(icon: ImageVector, tint: Color) {
    val palette = LocalCypherPalette.current
    Box(
        Modifier
            .size(84.dp)
            .background(palette.primarySoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun ScreenTitle(text: String) {
    val palette = LocalCypherPalette.current
    Text(
        text,
        color = palette.textPrimary,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- 1. welcome ----------------------------------------------------------------

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    val palette = LocalCypherPalette.current
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // logo mark
            Box(
                Modifier
                    .size(96.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(palette.primary, palette.primaryDark)
                        ),
                        RoundedCornerShape(28.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("C", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(28.dp))
            CypherWordMark(tagline = "Encrypted mesh messaging")
            Spacer(Modifier.height(14.dp))
            Text(
                "Chat over Bluetooth with no servers,\nno phone numbers, and no internet.",
                color = palette.textSecondary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(44.dp))
            InfoCard(
                icon = Icons.Outlined.Bluetooth,
                title = "Offline by design",
                bullets = listOf(
                    "Messages hop peer-to-peer over Bluetooth",
                    "Works anywhere — planes, protests, tunnels",
                    "No account, no tracking, no ads",
                ),
            )
            Spacer(Modifier.height(16.dp))
            InfoCard(
                icon = Icons.Filled.Security,
                title = "End-to-end encrypted",
                bullets = listOf(
                    "Noise XX mutual authentication per chat",
                    "Identity keys generated on this device",
                    "Even relays can't read your messages",
                ),
            )
            Spacer(Modifier.height(40.dp))
            PrimaryButton("Get Started", onGetStarted)
            Spacer(Modifier.height(16.dp))
            Text(
                "Free and open source",
                color = palette.textTertiary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- 2. permission explanation ----------------------------------------------------

private data class PermissionRow(val icon: ImageVector, val name: String, val description: String)

@Composable
fun PermissionExplanationScreen(onGrant: () -> Unit) {
    val palette = LocalCypherPalette.current
    val rows = listOf(
        PermissionRow(
            Icons.Outlined.Bluetooth, "Nearby Devices",
            "Required to discover Cypher peers over Bluetooth"
        ),
        PermissionRow(
            Icons.Outlined.LocationOn, "Location",
            "Required by Android to discover nearby Bluetooth devices"
        ),
        PermissionRow(
            Icons.Filled.Notifications, "Notifications",
            "Get notified when private messages arrive"
        ),
        PermissionRow(
            Icons.Filled.Power, "Battery",
            "Disabling optimization keeps the mesh connected"
        ),
    )
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(48.dp))
            CypherWordMark(tagline = "A few permissions to set up")
            Spacer(Modifier.height(28.dp))
            InfoCard(
                icon = Icons.Filled.Security,
                title = "Your privacy is protected",
                bullets = listOf(
                    "No tracking or data collection",
                    "Chats travel peer-to-peer over Bluetooth",
                    "Cypher never sees your location",
                ),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "PERMISSIONS",
                color = palette.textTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surface,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .background(palette.primarySoft, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(row.icon, null, tint = palette.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                row.name,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                            )
                            Text(
                                row.description,
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Grant Permissions", onGrant)
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- 3. bluetooth check -------------------------------------------------------------

enum class BluetoothCheckState { DISABLED, NOT_SUPPORTED, CHECKING }

@Composable
fun BluetoothCheckScreen(
    state: BluetoothCheckState,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            when (state) {
                BluetoothCheckState.DISABLED -> {
                    BigStatusIcon(Icons.Outlined.Bluetooth, palette.primary)
                    Spacer(Modifier.height(24.dp))
                    ScreenTitle("Bluetooth Required")
                    Spacer(Modifier.height(20.dp))
                    InfoCard(
                        Icons.Outlined.Bluetooth,
                        "Cypher needs Bluetooth to",
                        listOf(
                            "Discover nearby peers",
                            "Create mesh network connections",
                            "Send and receive messages",
                            "Work without internet or servers",
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Enable Bluetooth", onEnable)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Skip for now", color = palette.textSecondary)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                BluetoothCheckState.NOT_SUPPORTED -> {
                    BigStatusIcon(Icons.Filled.Warning, palette.error)
                    Spacer(Modifier.height(24.dp))
                    ScreenTitle("Bluetooth Not Supported")
                    Spacer(Modifier.height(20.dp))
                    InfoCard(
                        Icons.Filled.Warning,
                        "This device can't run Cypher",
                        listOf(
                            "Bluetooth Low Energy is required",
                            "Cypher creates mesh networks over BLE",
                            "No internet fallback exists by design",
                        ),
                        tint = palette.error,
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Continue Anyway", onContinue)
                    Spacer(Modifier.height(24.dp))
                }
                BluetoothCheckState.CHECKING -> {
                    Spacer(Modifier.height(120.dp))
                    CircularProgressIndicator(
                        color = palette.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    ScreenTitle("Checking Bluetooth…")
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ---- 4. location check ---------------------------------------------------------------

enum class LocationCheckState { DISABLED, UNAVAILABLE, CHECKING }

@Composable
fun LocationCheckScreen(
    state: LocationCheckState,
    onOpenSettings: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            when (state) {
                LocationCheckState.DISABLED -> {
                    BigStatusIcon(Icons.Outlined.LocationOn, palette.primary)
                    Spacer(Modifier.height(24.dp))
                    ScreenTitle("Location Services Required")
                    Spacer(Modifier.height(20.dp))
                    InfoCard(
                        Icons.Filled.Security,
                        "Privacy first",
                        listOf(
                            "Cypher does NOT track your location",
                            "Android requires location for BLE scanning",
                            "Nothing leaves this device",
                        ),
                    )
                    Spacer(Modifier.height(16.dp))
                    InfoCard(
                        Icons.Outlined.LocationOn,
                        "Location is used for",
                        listOf(
                            "Bluetooth device scanning",
                            "Discovering nearby Cypher peers",
                            "No tracking or collection, ever",
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Open Location Settings", onOpenSettings)
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onCheckAgain, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Check Again", color = palette.primary)
                    }
                    Spacer(Modifier.height(24.dp))
                }
                LocationCheckState.UNAVAILABLE -> {
                    BigStatusIcon(Icons.Filled.Warning, palette.error)
                    Spacer(Modifier.height(24.dp))
                    ScreenTitle("Location Services Unavailable")
                    Spacer(Modifier.height(20.dp))
                    InfoCard(
                        Icons.Filled.Warning,
                        "Can't scan for peers",
                        listOf("This device reports no location services"),
                        tint = palette.error,
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Continue Anyway", onCheckAgain)
                    Spacer(Modifier.height(24.dp))
                }
                LocationCheckState.CHECKING -> {
                    Spacer(Modifier.height(120.dp))
                    CircularProgressIndicator(
                        color = palette.primary,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                    ScreenTitle("Checking location…")
                }
            }
        }
    }
}

// ---- 5. battery optimization ----------------------------------------------------------

@Composable
fun BatteryOptimizationScreen(
    isOptimized: Boolean,
    onDisable: () -> Unit,
    onCheckAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            BigStatusIcon(
                if (isOptimized) Icons.Filled.BatteryStd else Icons.Filled.CheckCircle,
                if (isOptimized) palette.warning else palette.online,
            )
            Spacer(Modifier.height(24.dp))
            ScreenTitle(
                if (isOptimized) "Battery Optimization Detected"
                else "Battery Optimization Off"
            )
            Spacer(Modifier.height(20.dp))
            InfoCard(
                Icons.Filled.BatteryStd,
                "Why this matters",
                listOf(
                    "Cypher runs a foreground service to keep the mesh alive",
                    "Optimization can interrupt connections",
                    "Messages may be missed while backgrounded",
                ),
            )
            Spacer(Modifier.height(16.dp))
            InfoCard(
                Icons.Filled.CheckCircle,
                "Benefits of disabling",
                listOf(
                    "Reliable message delivery",
                    "Maintains mesh connectivity",
                    "Reconnects after reboot",
                ),
            )
            Spacer(Modifier.weight(1f))
            if (isOptimized) {
                PrimaryButton("Disable Battery Optimization", onDisable)
                Spacer(Modifier.height(10.dp))
                SecondaryRow("Check Again", onCheckAgain, "Skip for Now", onSkip)
                Spacer(Modifier.height(24.dp))
            } else {
                PrimaryButton("Continue", onCheckAgain)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---- 6. background location -----------------------------------------------------------

@Composable
fun BackgroundLocationScreen(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    val palette = LocalCypherPalette.current
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(72.dp))
            BigStatusIcon(Icons.Outlined.LocationOn, palette.primary)
            Spacer(Modifier.height(24.dp))
            ScreenTitle("Background Location")
            Spacer(Modifier.height(8.dp))
            Text(
                "Optional — improves mesh reliability",
                color = palette.textSecondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
            InfoCard(
                Icons.Outlined.LocationOn,
                "Recommended for reliability",
                listOf(
                    "Scans for nearby devices while the app is closed",
                    "Reconnects after reboot",
                    "Keeps the mesh running in the background",
                ),
            )
            Spacer(Modifier.height(16.dp))
            InfoCard(
                Icons.Filled.Security,
                "Your privacy is safe",
                listOf(
                    "We never collect or store your location",
                    "When settings opens, choose \"Allow all the time\"",
                ),
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton("Allow Background Location", onAllow)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Skip for Now", color = palette.textSecondary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- 7. nickname setup -------------------------------------------------------------------

@Composable
fun NicknameSetupScreen(
    initial: String,
    onJoin: (String) -> Unit,
) {
    val palette = LocalCypherPalette.current
    var nickname by remember { mutableStateOf(initial) }
    val valid = nickname.trim().length in 1..24
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(84.dp))
            CypherWordMark()
            Spacer(Modifier.height(10.dp))
            Text(
                "Pick a nickname",
                color = palette.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(36.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "NICKNAME",
                        color = palette.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = nickname,
                        onValueChange = { if (it.length <= 24) nickname = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            color = palette.textPrimary
                        ),
                        cursorBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(palette.primary, palette.primary)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            Box(Modifier.padding(vertical = 8.dp)) {
                                if (nickname.isEmpty()) {
                                    Text(
                                        "nickname",
                                        color = palette.textTertiary,
                                        style = MaterialTheme.typography.headlineMedium,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Visible to nearby peers · ${24 - nickname.length} characters left",
                        color = palette.textTertiary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Your nickname travels over the mesh with a signed identity announcement. No registration — change it anytime in Profile.",
                color = palette.textTertiary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            PrimaryButton(
                text = if (valid) "Join the mesh" else "Enter a nickname",
                onClick = { if (valid) onJoin(nickname.trim()) },
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ---- 8. initializing + error -----------------------------------------------------------------

@Composable
fun InitializingScreen() {
    val palette = LocalCypherPalette.current
    val alpha by animateFloatAsState(1f, tween(600))
    OnboardingScaffold {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CypherWordMark()
            Spacer(Modifier.height(36.dp))
            CircularProgressIndicator(
                color = palette.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Setting up the mesh network",
                color = palette.textSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This should only take a few seconds",
                color = palette.textTertiary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val palette = LocalCypherPalette.current
    OnboardingScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))
            BigStatusIcon(Icons.Filled.Warning, palette.error)
            Spacer(Modifier.height(24.dp))
            ScreenTitle("Setup Not Complete")
            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    message,
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Try Again", onRetry)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Open Settings", color = palette.primary)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- permission utilities (bitchat OnboardingCoordinator parity) -------------------------------

object OnboardingPermissions {
    fun required(context: Context): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    fun granted(context: Context): Boolean =
        required(context).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun missingCritical(context: Context): List<String> =
        required(context).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun needsBackgroundLocation(): Boolean = Build.VERSION.SDK_INT >= 29

    fun backgroundLocationGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    fun bluetoothSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun bluetoothEnabled(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return runCatching { adapter.isEnabled }.getOrDefault(false)
    }

    fun locationEnabled(context: Context): Boolean {
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    fun batteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

/** Intent that opens the app's system settings page. */
fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(android.net.Uri.fromParts("package", context.packageName, null))

fun locationSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

fun batteryOptimizationIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(android.net.Uri.fromParts("package", context.packageName, null))
