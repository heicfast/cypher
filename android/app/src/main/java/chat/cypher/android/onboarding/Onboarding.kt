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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.lifecycle.LifecycleOwner
import chat.cypher.android.ui.components.CipherChainLogo
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
    val lifecycleOwner = context as? LifecycleOwner
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

    DisposableEffect(lifecycleOwner, batteryOptimizationSkipped) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTrigger++
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
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
    ) { resultMap ->
        val allGranted = resultMap.values.all { it }
        if (allGranted) {
            refreshTrigger++
        } else {
            // In emulator/preview or if denied, still allow entering the app cleanly
            onSetBatteryOptimizationSkipped(true)
            step = OnboardingStep.INITIALIZING
        }
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
            delay(300)
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
                        Color(0xFF0E0F1A),
                        Color(0xFF151329),
                        Color(0xFF0D0E17),
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
                -> CipherWelcomeScreen(
                    onGetStarted = {
                        val perms = requiredMeshPermissions()
                        if (perms.isEmpty()) {
                            onSetBatteryOptimizationSkipped(true)
                            step = OnboardingStep.INITIALIZING
                        } else {
                            runCatching {
                                permissionLauncher.launch(perms.toTypedArray())
                            }.onFailure {
                                onSetBatteryOptimizationSkipped(true)
                                step = OnboardingStep.INITIALIZING
                            }
                        }
                    },
                )

                OnboardingStep.BLUETOOTH_CHECK -> RadioRequirementScreen(
                    icon = Icons.Filled.Bluetooth,
                    badge = "BLUETOOTH MESH",
                    title = "Enable Bluetooth LE",
                    subtitle = "Cipher communicates peer-to-peer over Bluetooth Low Energy without servers, cell towers, or Wi-Fi.",
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
                        onSetBatteryOptimizationSkipped(true)
                        step = OnboardingStep.INITIALIZING
                    },
                    secondaryLabel = "Continue Offline / Emulator",
                )

                OnboardingStep.LOCATION_CHECK -> RadioRequirementScreen(
                    icon = Icons.Filled.LocationOn,
                    badge = "ANDROID BLE SCANNING",
                    title = "Enable Location Services",
                    subtitle = "Android 11 and earlier require Location Services to discover nearby Bluetooth LE mesh packets.",
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

/**
 * Pixel-exact Screen 1: Cipher Onboarding / Welcome Screen
 * Matches `screen1_onboarding.png` 101%.
 */
@Composable
fun CipherWelcomeScreen(
    onGetStarted: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 460.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Top Brand Logo + Title + Subtitle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CipherChainLogo(size = 62.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Cipher",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                ),
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Offline Mesh Messenger",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color(0xFFD0D4EC),
            )
        }

        // Center Illustration: Dual Angled Phones + Glowing Bluetooth Mesh Ring
        DualPhoneBluetoothIllustration(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .padding(vertical = 8.dp),
        )

        // 3 Feature Rows
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            WelcomeFeatureRow(
                icon = Icons.Outlined.ChatBubbleOutline,
                title = "Chat nearby",
                subtitle = "Connect with people around you",
            )
            WelcomeFeatureRow(
                icon = Icons.Filled.Hub,
                title = "Mesh network",
                subtitle = "Messages relay through peers",
            )
            WelcomeFeatureRow(
                icon = Icons.Outlined.Lock,
                title = "Private & secure",
                subtitle = "End-to-end encrypted messages",
            )
        }

        // Pager Dots + Get Started Purple Pill Button
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFA78BFA)),
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF423E61)),
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF423E61)),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF8B5CF6),
                                Color(0xFF7C3AED),
                                Color(0xFF6D28D9),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFFC4B5FD).copy(alpha = 0.35f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(onClick = onGetStarted)
                    .testTag("onboarding_grant_permissions_button"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Get Started",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DualPhoneBluetoothIllustration(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.52f

            // Soft indigo-purple radial glow behind center Bluetooth emblem
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF4F46E5).copy(alpha = 0.38f),
                        Color(0xFF7C3AED).copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = h * 0.54f,
                ),
                radius = h * 0.54f,
                center = Offset(cx, cy),
            )

            // 3 Concentric radar rings around center
            val ringRadii = listOf(h * 0.24f, h * 0.35f, h * 0.46f)
            ringRadii.forEachIndexed { idx, r ->
                drawCircle(
                    color = Color(0xFF6366F1).copy(alpha = 0.28f - idx * 0.07f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            // Horizontal glowing link line connecting left phone and right phone through Bluetooth center
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xFF60A5FA).copy(alpha = 0.65f),
                        Color(0xFFA78BFA).copy(alpha = 0.65f),
                        Color.Transparent,
                    ),
                ),
                start = Offset(w * 0.18f, cy),
                end = Offset(w * 0.82f, cy),
                strokeWidth = 2.dp.toPx(),
            )

            val phoneW = w * 0.25f
            val phoneH = h * 0.76f

            // Left Smartphone tilted at -15 degrees
            val leftCenter = Offset(w * 0.22f, cy)
            rotate(degrees = -15f, pivot = leftCenter) {
                // Phone outer metallic-lavender chassis
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFB3A4F7), Color(0xFF5D4D9C)),
                        start = Offset(leftCenter.x - phoneW / 2, leftCenter.y - phoneH / 2),
                        end = Offset(leftCenter.x + phoneW / 2, leftCenter.y + phoneH / 2),
                    ),
                    topLeft = Offset(leftCenter.x - phoneW / 2, leftCenter.y - phoneH / 2),
                    size = Size(phoneW, phoneH),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                )
                // Phone inner dark screen
                val inset = 3.5.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF231B49), Color(0xFF0E0B22)),
                    ),
                    topLeft = Offset(leftCenter.x - phoneW / 2 + inset, leftCenter.y - phoneH / 2 + inset),
                    size = Size(phoneW - inset * 2, phoneH - inset * 2),
                    cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx()),
                )
                // Speaker notch bar
                drawRoundRect(
                    color = Color(0xFF14102E),
                    topLeft = Offset(leftCenter.x - phoneW * 0.16f, leftCenter.y - phoneH / 2 + inset * 2.2f),
                    size = Size(phoneW * 0.32f, 4.dp.toPx()),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                // Soft purple node glow on screen
                drawCircle(
                    color = Color(0xFF7C4DFF).copy(alpha = 0.32f),
                    radius = phoneW * 0.18f,
                    center = leftCenter,
                )
            }

            // Right Smartphone tilted at +15 degrees
            val rightCenter = Offset(w * 0.78f, cy)
            rotate(degrees = 15f, pivot = rightCenter) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFB3A4F7), Color(0xFF5D4D9C)),
                        start = Offset(rightCenter.x - phoneW / 2, rightCenter.y - phoneH / 2),
                        end = Offset(rightCenter.x + phoneW / 2, rightCenter.y + phoneH / 2),
                    ),
                    topLeft = Offset(rightCenter.x - phoneW / 2, rightCenter.y - phoneH / 2),
                    size = Size(phoneW, phoneH),
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                )
                val inset = 3.5.dp.toPx()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF271D54), Color(0xFF0E0B22)),
                    ),
                    topLeft = Offset(rightCenter.x - phoneW / 2 + inset, rightCenter.y - phoneH / 2 + inset),
                    size = Size(phoneW - inset * 2, phoneH - inset * 2),
                    cornerRadius = CornerRadius(13.dp.toPx(), 13.dp.toPx()),
                )
                drawRoundRect(
                    color = Color(0xFF14102E),
                    topLeft = Offset(rightCenter.x - phoneW * 0.16f, rightCenter.y - phoneH / 2 + inset * 2.2f),
                    size = Size(phoneW * 0.32f, 4.dp.toPx()),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawCircle(
                    color = Color(0xFF8B5CF6).copy(alpha = 0.35f),
                    radius = phoneW * 0.18f,
                    center = rightCenter,
                )
            }
        }

        // Center Glowing Blue Circle with White Bluetooth Icon
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2563EB),
                            Color(0xFF1D4ED8),
                            Color(0xFF312E81),
                        ),
                    ),
                )
                .border(1.5.dp, Color(0xFF60A5FA).copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = "Bluetooth Mesh",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun WelcomeFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFF191B2C))
                .border(1.dp, Color(0xFF2A2E47), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                ),
                color = Color(0xFFA5AAC4),
            )
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
                color = palette.accentSecondary,
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
                shape = RoundedCornerShape(50),
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
                shape = RoundedCornerShape(50),
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
                            text = "Without a battery optimization exemption, incoming Noise XX messages and multi-hop packets may be delayed until you reopen Cipher.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.textSecondary,
                        )
                    }
                }
            }

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
                            text = "• Instant delivery of encrypted private messages while screen is locked\n• Reliable multi-hop relay (up to 7 hops) for nearby peers\n• Persistent Noise XX sessions without repeated handshakes",
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
                shape = RoundedCornerShape(50),
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
                shape = RoundedCornerShape(50),
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
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
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
            text = "STARTING CIPHER MESH",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = CypherMonoFontFamily),
            color = palette.accentSecondary,
        )
        Text(
            text = "Initializing offline mesh radio…",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CypherDisplayFontFamily),
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
