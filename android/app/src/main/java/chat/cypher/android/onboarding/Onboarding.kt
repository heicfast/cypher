package chat.cypher.android.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import chat.cypher.android.ui.theme.CypherThemeDefaults

/**
 * The first-launch flow — a faithful port of bitchat-android's
 * `OnboardingState` machine (CHECKING → PERMISSION_EXPLANATION →
 * PERMISSION_REQUESTING → BLUETOOTH_CHECK → LOCATION_CHECK →
 * BATTERY_OPTIMIZATION_CHECK → INITIALIZING → COMPLETE / ERROR) with its
 * verbatim copy, restyled for the Cypher design system.
 */
enum class OnboardingState {
    CHECKING,
    PERMISSION_EXPLANATION,
    PERMISSION_REQUESTING,
    INITIALIZING,
    BLUETOOTH_CHECK,
    LOCATION_CHECK,
    BATTERY_CHECK,
    COMPLETE,
    ERROR,
}

object Onboarding {
    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    fun permissionsGranted(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun bluetoothEnabled(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false

    fun locationEnabled(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE) !=
                Settings.Secure.LOCATION_MODE_OFF
        } catch (_: Exception) {
            true
        }
    }

    fun batteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}

@Composable
fun OnboardingFlow(
    firstLaunch: Boolean,
    onComplete: () -> Unit,
) {
    var state by remember { mutableStateOf(OnboardingState.CHECKING) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Proceed through the radio checks regardless of the exact grant
        // set; the app degrades gracefully (bitchat parity).
        state = OnboardingState.BLUETOOTH_CHECK
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { state = OnboardingState.LOCATION_CHECK }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        when {
            state == OnboardingState.CHECKING -> {
                state = when {
                    !Onboarding.permissionsGranted(context) && firstLaunch ->
                        OnboardingState.PERMISSION_EXPLANATION
                    !Onboarding.permissionsGranted(context) ->
                        OnboardingState.PERMISSION_EXPLANATION
                    else -> OnboardingState.BLUETOOTH_CHECK
                }
            }
            else -> {}
        }
    }

    when (state) {
        OnboardingState.CHECKING -> CheckingScreen()

        OnboardingState.PERMISSION_EXPLANATION -> PermissionExplanationScreen(
            onGrant = {
                state = OnboardingState.PERMISSION_REQUESTING
                permissionLauncher.launch(Onboarding.requiredPermissions())
            },
        )

        OnboardingState.PERMISSION_REQUESTING -> InitializingScreen(
            subtitle = "Waiting for permissions…",
            onDone = { state = OnboardingState.BLUETOOTH_CHECK },
        )

        OnboardingState.BLUETOOTH_CHECK -> {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (Onboarding.bluetoothEnabled()) {
                    state = OnboardingState.LOCATION_CHECK
                }
            }
            BluetoothCheckScreen(
                onEnable = {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                },
                onSkip = { state = OnboardingState.LOCATION_CHECK },
            )
        }

        OnboardingState.LOCATION_CHECK -> {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (Onboarding.locationEnabled(context)) {
                    state = OnboardingState.BATTERY_CHECK
                }
            }
            LocationCheckScreen(
                onOpenSettings = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                },
                onCheckAgain = {
                    if (Onboarding.locationEnabled(context)) {
                        state = OnboardingState.BATTERY_CHECK
                    }
                },
                onSkip = { state = OnboardingState.BATTERY_CHECK },
            )
        }

        OnboardingState.BATTERY_CHECK -> {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (!Onboarding.batteryOptimized(context)) {
                    state = OnboardingState.INITIALIZING
                }
            }
            BatteryOptimizationScreen(
                onDisable = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                },
                onCheckAgain = {
                    if (!Onboarding.batteryOptimized(context)) {
                        state = OnboardingState.INITIALIZING
                    }
                },
                onSkip = { state = OnboardingState.INITIALIZING },
            )
        }

        OnboardingState.INITIALIZING -> {
            InitializingScreen(onDone = {
                state = OnboardingState.COMPLETE
                onComplete()
            })
        }

        OnboardingState.ERROR -> ErrorScreen(
            message = errorMessage ?: "Unknown setup failure",
            onTryAgain = { state = OnboardingState.CHECKING },
        )

        OnboardingState.COMPLETE -> Box(Modifier.fillMaxSize())
    }
}

// ---- screens -----------------------------------------------------------------

@Composable
private fun CheckingScreen() {
    CenteredCard { LoadingRow("Checking…") }
}

@Composable
private fun PermissionExplanationScreen(onGrant: () -> Unit) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(72.dp))
        Text("Cypher", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary)
        Text(
            "Bluetooth mesh, end to end",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(28.dp))
        InfoCard(
            icon = Icons.Filled.Security,
            title = "Your Privacy is Protected",
            body = "• No tracking or data collection\n• Chats stay between nearby devices\n• Messages are end-to-end encrypted",
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Permissions",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary,
        )
        Spacer(Modifier.height(12.dp))
        PermissionRow(Icons.Filled.Bluetooth, "Nearby Devices", "Required to discover Cypher users via Bluetooth")
        PermissionRow(Icons.Filled.LocationOn, "Precise Location", "Required by Android to discover nearby devices via Bluetooth")
        PermissionRow(Icons.Filled.Notifications, "Notifications", "Receive notifications when you receive private messages")
        PermissionRow(Icons.Filled.Power, "Battery Optimization", "Disable to keep mesh connections reliable in the background")
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Grant Permissions", Modifier.fillMaxWidth(), onClick = onGrant)
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun BluetoothCheckScreen(onEnable: () -> Unit, onSkip: () -> Unit) {
    val colors = CypherThemeDefaults.colors
    RadioCheckScreen(
        icon = Icons.Filled.Bluetooth,
        title = "Bluetooth Recommended",
        cards = listOf(
            InfoCardData(
                Icons.Filled.Bluetooth,
                "Cypher needs Bluetooth to:",
                "• Discover nearby users\n• Create mesh network connections\n• Send and receive messages\n• Work without internet or servers",
            ),
        ),
        primary = "Enable Bluetooth" to onEnable,
        secondary = "Skip" to onSkip,
    )
}

@Composable
private fun LocationCheckScreen(
    onOpenSettings: () -> Unit,
    onCheckAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    RadioCheckScreen(
        icon = Icons.Filled.LocationOn,
        title = "Location Services Required",
        cards = listOf(
            InfoCardData(
                Icons.Filled.Security,
                "Privacy First",
                "Cypher does NOT track your location.\n\nLocation services are required for Bluetooth scanning on Android.",
            ),
        ),
        primary = "Open Location Settings" to onOpenSettings,
        secondary = "Check Again" to onCheckAgain,
        tertiary = "Skip for Now" to onSkip,
    )
}

@Composable
private fun BatteryOptimizationScreen(
    onDisable: () -> Unit,
    onCheckAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    RadioCheckScreen(
        icon = Icons.Filled.Power,
        title = "Battery optimization detected",
        cards = listOf(
            InfoCardData(
                Icons.Filled.CheckCircle,
                "Benefits of Disabling",
                "• Reliable message delivery\n• Maintains mesh connectivity\n• Prevents connection drops",
            ),
        ),
        primary = "Disable Battery Optimization" to onDisable,
        secondary = "Check Again" to onCheckAgain,
        tertiary = "Skip for Now" to onSkip,
    )
}

@Composable
private fun InitializingScreen(
    subtitle: String = "Setting up Bluetooth mesh networking...\nThis should only take a few seconds",
    onDone: () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        onDone()
    }
    CenteredCard {
        LoadingRow("Initializing mesh network…")
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CypherThemeDefaults.colors.textSecondary,
            modifier = Modifier
                .padding(top = 14.dp)
                .align(Alignment.CenterHorizontally),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorScreen(message: String, onTryAgain: () -> Unit) {
    CenteredCard {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = CypherThemeDefaults.colors.danger)
        Text(
            "Setup Not Complete",
            style = MaterialTheme.typography.titleLarge,
            color = CypherThemeDefaults.colors.textPrimary,
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, color = CypherThemeDefaults.colors.textSecondary)
        PrimaryButton("Try Again", Modifier.padding(top = 20.dp), onClick = onTryAgain)
    }
}

// ---- shared building blocks -----------------------------------------------------

private data class InfoCardData(val icon: ImageVector, val title: String, val body: String)

@Composable
private fun RadioCheckScreen(
    icon: ImageVector,
    title: String,
    cards: List<InfoCardData>,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    tertiary: Pair<String, () -> Unit>? = null,
) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(84.dp))
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(colors.searchField)
                .padding(22.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent)
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
        Spacer(Modifier.height(24.dp))
        for (card in cards) {
            InfoCard(card.icon, card.title, card.body)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(primary.first, Modifier.fillMaxWidth(), onClick = primary.second)
        TextButton(onClick = secondary.second) {
            Text(secondary.first, color = colors.textSecondary, fontWeight = FontWeight.SemiBold)
        }
        if (tertiary != null) {
            TextButton(onClick = tertiary.second) {
                Text(tertiary.first, color = colors.textTertiary)
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, name: String, description: String) {
    val colors = CypherThemeDefaults.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(colors.searchField)
                .padding(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = colors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
internal fun InfoCard(icon: ImageVector, title: String, body: String) {
    val colors = CypherThemeDefaults.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(22.dp),
            color = CypherThemeDefaults.colors.accent,
        )
        Text(label, style = MaterialTheme.typography.titleSmall, color = CypherThemeDefaults.colors.textPrimary)
    }
}

@Composable
private fun CenteredCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = CypherThemeDefaults.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
internal fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = CypherThemeDefaults.colors
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}
