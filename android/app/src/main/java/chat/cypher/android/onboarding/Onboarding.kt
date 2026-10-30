package chat.cypher.android.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat.cypher.android.ui.theme.CypherDisplayFontFamily
import chat.cypher.android.ui.theme.CypherMonoFontFamily
import chat.cypher.android.ui.theme.LocalCypherPalette
import kotlinx.coroutines.delay

enum class OnboardingStep {
    CHECKING,
    PERMISSION_EXPLANATION,
    BLUETOOTH_CHECK,
    LOCATION_CHECK,
    BATTERY_OPTIMIZATION,
    INITIALIZING,
    COMPLETE,
}

/**
 * Shared helper for inspecting and requesting Android battery optimization exemptions,
 * modeled after bitchat-android's BatteryOptimizationManager with 3-tier intent fallback.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptHelper"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query isIgnoringBatteryOptimizations", e)
            false
        }
    }

    /**
     * Launches the system exemption dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
     * and falls back to the system Battery Optimization list (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`)
     * or App Details Settings (`ACTION_APPLICATION_DETAILS_SETTINGS`) if the direct intent fails.
     */
    fun launchExemptionRequest(
        context: Context,
        launcher: ActivityResultLauncher<Intent>? = null,
        forceListSettings: Boolean = false,
        onError: (String) -> Unit = {},
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val candidateIntents = buildList {
            if (!forceListSettings) {
                add(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    },
                )
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
            add(Intent(Settings.ACTION_SETTINGS))
        }

        for (intent in candidateIntents) {
            val launched = runCatching {
                if (launcher != null) {
                    launcher.launch(intent)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                true
            }.getOrElse { err ->
                Log.w(TAG, "Intent ${intent.action} failed: ${err.message}")
                false
            }
            if (launched) return
        }

        onError("Unable to open Android battery settings on this device.")
    }
}

fun requiredMeshPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

fun hasAllMeshPermissions(context: Context): Boolean =
    requiredMeshPermissions().all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

private fun isBluetoothEnabled(context: Context): Boolean {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return bm?.adapter?.isEnabled == true
}

private fun isLocationEnabledIfRequired(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return runCatching {
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)
}

@Composable
fun OnboardingFlow(
    batteryOptimizationSkipped: Boolean,
    onSetBatteryOptimizationSkipped: (Boolean) -> Unit,
    onReady: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = LocalCypherPalette.current

    var refreshTrigger by remember { mutableIntStateOf(0) }
    var batteryError by remember { mutableStateOf<String?>(null) }

    fun computeStep(skippedBattery: Boolean): OnboardingStep {
        return when {
            !hasAllMeshPermissions(context) -> OnboardingStep.PERMISSION_EXPLANATION
            !isBluetoothEnabled(context) -> OnboardingStep.BLUETOOTH_CHECK
            !isLocationEnabledIfRequired(context) -> OnboardingStep.LOCATION_CHECK
            !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) && !skippedBattery ->
                OnboardingStep.BATTERY_OPTIMIZATION
            else -> OnboardingStep.COMPLETE
        }
    }

    var step by remember { mutableStateOf(computeStep(batteryOptimizationSkipped)) }

    // Re-evaluate onboarding step whenever the user returns from any system Settings screen or dialog
    DisposableEffect(lifecycleOwner, batteryOptimizationSkipped) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(refreshTrigger, batteryOptimizationSkipped) {
        if (step != OnboardingStep.COMPLETE && step != OnboardingStep.INITIALIZING) {
            val next = computeStep(batteryOptimizationSkipped)
            if (next == OnboardingStep.COMPLETE) {
                step = OnboardingStep.INITIALIZING
            } else {
                step = next
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshTrigger++
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshTrigger++
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryError = null
        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
            step = OnboardingStep.INITIALIZING
        } else {
            refreshTrigger++
        }
    }

    LaunchedEffect(step) {
        if (step == OnboardingStep.INITIALIZING) {
            onReady()
            delay(450)
            step = OnboardingStep.COMPLETE
        } else if (step == OnboardingStep.COMPLETE) {
            onReady()
        }
    }

    if (step == OnboardingStep.COMPLETE) {
        content()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette.canvas,
                        palette.surface0,
                        palette.canvas,
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                fadeIn(tween(220)) togetherWith fadeOut(tween(160))
            },
            label = "onboarding_step",
        ) { targetStep ->
            when (targetStep) {
                OnboardingStep.CHECKING,
                OnboardingStep.PERMISSION_EXPLANATION,
                -> PermissionExplanationScreen(
                    onContinue = {
                        permissionLauncher.launch(requiredMeshPermissions().toTypedArray())
                    },
                    onSkipForEmulator = {
                        step = OnboardingStep.INITIALIZING
                    },
                )

                OnboardingStep.BLUETOOTH_CHECK -> RadioRequirementScreen(
                    icon = Icons.Filled.Bluetooth,
                    badge = "RADIO LINK REQUIRED",
                    title = "Enable Bluetooth LE",
                    subtitle = "Cypher communicates directly peer-to-peer over Bluetooth Low Energy without servers, cell towers, or Wi-Fi.",
                    primaryLabel = "Turn On Bluetooth",
                    onPrimary = {
                        runCatching {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }.onFailure {
                            runCatching {
                                enableBtLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        }
                    },
                    onSecondary = {
                        step = OnboardingStep.INITIALIZING
                    },
                    secondaryLabel = "Continue in Emulator / Offline Mode",
                )

                OnboardingStep.LOCATION_CHECK -> RadioRequirementScreen(
                    icon = Icons.Filled.LocationOn,
                    badge = "ANDROID BLE SCANNING",
                    title = "Enable Location Services",
                    subtitle = "Android 11 and earlier require Location Services to discover nearby Bluetooth LE mesh packets. Cypher never tracks or transmits your GPS coordinates.",
                    primaryLabel = "Open Location Settings",
                    onPrimary = {
                        runCatching {
                            enableBtLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        }
                    },
                    onSecondary = {
                        step = OnboardingStep.INITIALIZING
                    },
                    secondaryLabel = "Skip for Now",
                )

                OnboardingStep.BATTERY_OPTIMIZATION -> BatteryOptimizationOnboardingScreen(
                    errorMessage = batteryError,
                    onRequestExemption = {
                        batteryError = null
                        BatteryOptimizationHelper.launchExemptionRequest(
                            context = context,
                            launcher = batteryLauncher,
                            forceListSettings = false,
                            onError = { batteryError = it },
                        )
                    },
                    onOpenBatterySettingsList = {
                        batteryError = null
                        BatteryOptimizationHelper.launchExemptionRequest(
                            context = context,
                            launcher = batteryLauncher,
                            forceListSettings = true,
                            onError = { batteryError = it },
                        )
                    },
                    onCheckAgain = {
                        batteryError = null
                        if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) {
                            step = OnboardingStep.INITIALIZING
                        } else {
                            refreshTrigger++
                        }
                    },
                    onSkip = {
                        onSetBatteryOptimizationSkipped(true)
                        step = OnboardingStep.INITIALIZING
                    },
                )

                OnboardingStep.INITIALIZING -> InitializingMeshScreen()

                OnboardingStep.COMPLETE -> Unit
            }
        }
    }
}

@Composable
private fun PermissionExplanationScreen(
    onContinue: () -> Unit,
    onSkipForEmulator: () -> Unit,
) {
    val palette = LocalCypherPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 520.dp)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Brand badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.accentSoft)
                        .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Radar,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column {
                    Text(
                        text = "CYPHER MESH",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                    )
                    Text(
                        text = "Off-Grid Encrypted Messaging",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textPrimary,
                    )
                }
            }

            Text(
                text = "Zero servers. Zero phone numbers. Messages hop directly between nearby devices over Bluetooth Low Energy using Noise XX end-to-end encryption.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )

            // Privacy Guarantee Card
            Surface(
                color = palette.encryptedGreenSoft,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = palette.encryptedGreen.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(16.dp),
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = palette.encryptedGreen,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Cryptographic Privacy Architecture",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary,
                        )
                        Text(
                            text = "Your X25519 + Ed25519 keys are generated on-device and sealed in the Android Keystore. Cypher has no accounts, no telemetry, and no internet permission.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }
            }

            Text(
                text = "REQUIRED SYSTEM PERMISSIONS",
                style = MaterialTheme.typography.labelMedium,
                color = palette.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )

            PermissionFeatureCard(
                icon = Icons.Filled.Bluetooth,
                title = "Nearby Bluetooth Devices",
                description = "Advertise your ephemeral node presence and relay encrypted multi-hop frames with nearby peers.",
            )

            PermissionFeatureCard(
                icon = Icons.Filled.Notifications,
                title = "Direct Message & Mesh Alerts",
                description = "Receive instant alerts for Noise XX private messages and keep the foreground mesh relay active.",
            )

            PermissionFeatureCard(
                icon = Icons.Filled.Lock,
                title = "End-to-End Verification",
                description = "Compare SHA-256 safety numbers out-of-band to verify peer identities against active MITM attacks.",
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_grant_permissions_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.accentOnPrimary,
                ),
            ) {
                Text(
                    text = "Grant Mesh Permissions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }

            TextButton(
                onClick = onSkipForEmulator,
                modifier = Modifier.testTag("onboarding_skip_permissions_button"),
            ) {
                Text(
                    text = "Preview UI without BLE hardware",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textMuted,
                )
            }
        }
    }
}

@Composable
private fun PermissionFeatureCard(
    icon: ImageVector,
    title: String,
    description: String,
) {
    val palette = LocalCypherPalette.current
    Surface(
        color = palette.surface1,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.textPrimary,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun RadioRequirementScreen(
    icon: ImageVector,
    badge: String,
    title: String,
    subtitle: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
) {
    val palette = LocalCypherPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 520.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(palette.accentSoft)
                    .border(1.5.dp, palette.accent.copy(alpha = 0.45f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(40.dp),
                )
            }

            Text(
                text = badge,
                style = MaterialTheme.typography.labelMedium,
                color = palette.accent,
            )

            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("onboarding_radio_primary_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.accentOnPrimary,
                ),
            ) {
                Text(
                    text = primaryLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = onSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("onboarding_radio_secondary_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = secondaryLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun BatteryOptimizationOnboardingScreen(
    errorMessage: String?,
    onRequestExemption: () -> Unit,
    onOpenBatterySettingsList: () -> Unit,
    onCheckAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    val palette = LocalCypherPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 520.dp)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.warningAmberSoft)
                        .border(1.dp, palette.warningAmber.copy(alpha = 0.45f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        tint = palette.warningAmber,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column {
                    Text(
                        text = "BACKGROUND RELIABILITY",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.warningAmber,
                    )
                    Text(
                        text = "Unrestricted Mesh Relay",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.textPrimary,
                    )
                }
            }

            Text(
                text = "Android's Doze mode suspends background Bluetooth LE scanning and disconnects active mesh links when your screen turns off.",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )

            // Problem Explanation Card
            Surface(
                color = palette.surface1,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Power,
                        contentDescription = null,
                        tint = palette.warningAmber,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(22.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Battery Optimization Detected",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary,
                        )
                        Text(
                            text = "Without a battery optimization exemption, incoming Noise XX messages and multi-hop packets may be delayed until you manually reopen Cypher.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }
            }

            // Benefits Card
            Surface(
                color = palette.encryptedGreenSoft,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.encryptedGreen.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = palette.encryptedGreen,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(22.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Benefits of Unrestricted Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.textPrimary,
                        )
                        Text(
                            text = "• Instant delivery of encrypted private messages while screen is locked\n• Reliable store-and-forward courier relay for nearby peers\n• Persistent Noise XX sessions without repeated handshakes",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    color = palette.dangerRedSoft,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.dangerRed,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onRequestExemption,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("disable_battery_optimization_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.accentOnPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Disable Battery Optimization",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                onClick = onOpenBatterySettingsList,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_battery_settings_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = palette.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open System Battery Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textPrimary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = palette.textMuted,
                    modifier = Modifier.size(15.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onCheckAgain,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("battery_check_again_button"),
                ) {
                    Text(
                        text = "Check Status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                }

                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("battery_skip_button"),
                ) {
                    Text(
                        text = "Skip for Now",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun InitializingMeshScreen() {
    val palette = LocalCypherPalette.current
    val pulseTransition = rememberInfiniteTransition(label = "mesh_init_pulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .scale(pulseScale)
                .clip(CircleShape)
                .background(palette.accentSoft)
                .border(1.5.dp, palette.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = palette.accent,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(44.dp),
            )
        }
        Text(
            text = "INITIALIZING CYPHER NODE",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = CypherMonoFontFamily),
            color = palette.accent,
        )
        Text(
            text = "Unsealing X25519 identity & starting BLE mesh…",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CypherDisplayFontFamily),
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
