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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import chat.cypher.android.ui.components.CipherChainLogoIcon
import chat.cypher.android.ui.theme.CypherTheme

object OnboardingPreferences {
    private const val PREF_FILE = "cypher_onboarding_prefs"
    private const val KEY_COMPLETED = "onboarding_completed"
    private const val KEY_BATTERY_SKIPPED = "battery_opt_skipped"

    fun isCompleted(context: Context): Boolean {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)
    }

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    fun isBatterySkipped(context: Context): Boolean {
        return context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_BATTERY_SKIPPED, false)
    }

    fun markBatterySkipped(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BATTERY_SKIPPED, true)
            .apply()
    }

    fun resetOnboarding(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}

object BatteryOptimizationHelper {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun buildRequestIgnoreBatteryOptimizationsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun buildBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    fun buildAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    fun launchExemptionRequest(
        context: Context,
        launcher: ActivityResultLauncher<Intent>? = null,
    ): Boolean {
        val candidates = listOf(
            buildRequestIgnoreBatteryOptimizationsIntent(context),
            buildBatteryOptimizationSettingsIntent(),
            buildAppDetailsSettingsIntent(context),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            val resolved = intent.resolveActivity(context.packageManager) != null
            if (resolved) {
                val ok = runCatching {
                    if (launcher != null) {
                        launcher.launch(intent)
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }.isSuccess
                if (ok) return true
            }
        }
        return runCatching {
            val fallback = buildAppDetailsSettingsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }.isSuccess
    }
}

enum class OnboardingStep {
    WELCOME,
    RADIO_SERVICES,
    BATTERY_EXEMPTION,
}

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = CypherTheme.colors

    var refreshTick by remember { mutableIntStateOf(0) }

    val requiredPermissions = remember {
        buildList {
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
    }

    fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothReady(): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bm?.adapter
        if (adapter == null) return true
        return adapter.isEnabled
    }

    fun isLocationReady(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun isBatteryReady(): Boolean {
        return BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) ||
            OnboardingPreferences.isBatterySkipped(context)
    }

    var manualContinueFromWelcome by remember { mutableStateOf(false) }

    val currentStep = remember(refreshTick, manualContinueFromWelcome) {
        when {
            !manualContinueFromWelcome && !hasAllPermissions() -> OnboardingStep.WELCOME
            !isBluetoothReady() || !isLocationReady() -> OnboardingStep.RADIO_SERVICES
            !isBatteryReady() -> OnboardingStep.BATTERY_EXEMPTION
            else -> null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (currentStep == null) {
        OnboardingPreferences.markCompleted(context)
        onFinished()
        return
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        manualContinueFromWelcome = true
        refreshTick += 1
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        refreshTick += 1
    }

    Scaffold(
        containerColor = palette.bgPrimary,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF15162B),
                            Color(0xFF101222),
                            palette.bgPrimary,
                        ),
                    ),
                )
                .padding(innerPadding)
                .statusBarsPadding(),
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                },
                label = "onboarding_step",
            ) { step ->
                when (step) {
                    OnboardingStep.WELCOME -> {
                        WelcomeStepContent(
                            onGetStarted = {
                                if (hasAllPermissions()) {
                                    manualContinueFromWelcome = true
                                    refreshTick += 1
                                } else {
                                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                                }
                            },
                        )
                    }

                    OnboardingStep.RADIO_SERVICES -> {
                        RadioServicesStepContent(
                            bluetoothReady = isBluetoothReady(),
                            locationReady = isLocationReady(),
                            onEnableBluetooth = {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                                runCatching { settingsLauncher.launch(intent) }.onFailure {
                                    runCatching {
                                        settingsLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                    }
                                }
                            },
                            onEnableLocation = {
                                runCatching {
                                    settingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                }
                            },
                            onSkipInEmulator = {
                                OnboardingPreferences.markCompleted(context)
                                onFinished()
                            },
                        )
                    }

                    OnboardingStep.BATTERY_EXEMPTION -> {
                        BatteryExemptionStepContent(
                            isExempt = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context),
                            onRequestDirectDialog = {
                                BatteryOptimizationHelper.launchExemptionRequest(context, settingsLauncher)
                            },
                            onOpenBatterySettingsList = {
                                val listIntent = BatteryOptimizationHelper.buildBatteryOptimizationSettingsIntent()
                                val launched = runCatching { settingsLauncher.launch(listIntent) }.isSuccess
                                if (!launched) {
                                    runCatching {
                                        settingsLauncher.launch(
                                            BatteryOptimizationHelper.buildAppDetailsSettingsIntent(context),
                                        )
                                    }
                                }
                            },
                            onCheckAgain = {
                                refreshTick += 1
                            },
                            onSkip = {
                                OnboardingPreferences.markBatterySkipped(context)
                                OnboardingPreferences.markCompleted(context)
                                onFinished()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStepContent(
    onGetStarted: () -> Unit,
) {
    val palette = CypherTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(10.dp))
            CipherChainLogoIcon(size = 54.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Cipher",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    letterSpacing = (-0.8).sp,
                ),
                color = palette.textPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Offline Mesh Messenger",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.5.sp,
                ),
                color = palette.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        DualPhoneBluetoothGraphic(
            modifier = Modifier
                .fillMaxWidth()
                .height(185.dp),
        )

        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OnboardingFeatureRow(
                icon = Icons.Rounded.ChatBubbleOutline,
                title = "Chat nearby",
                subtitle = "Connect with people around you",
            )
            OnboardingFeatureRow(
                icon = Icons.Rounded.Hub,
                title = "Mesh network",
                subtitle = "Messages relay through peers",
            )
            OnboardingFeatureRow(
                icon = Icons.Rounded.Lock,
                title = "Private & secure",
                subtitle = "End-to-end encrypted messages",
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(palette.accentLight),
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.borderSubtle),
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.borderSubtle),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(
                        elevation = 18.dp,
                        shape = RoundedCornerShape(50),
                        ambientColor = palette.accent,
                        spotColor = palette.accent,
                    )
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF9061FF),
                                palette.accent,
                                Color(0xFF6938EF),
                            ),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.28f),
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(onClick = onGetStarted)
                    .testTag("onboarding_get_started_button"),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Get Started",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.5.sp,
                        ),
                        color = Color.White,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun DualPhoneBluetoothGraphic(
    modifier: Modifier = Modifier,
) {
    val palette = CypherTheme.colors
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.52f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF3B82F6).copy(alpha = 0.25f),
                        palette.accent.copy(alpha = 0.14f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = h * 0.52f,
                ),
                radius = h * 0.52f,
                center = Offset(cx, cy),
            )

            listOf(0.24f, 0.36f, 0.48f).forEach { factor ->
                drawCircle(
                    color = Color(0xFF60A5FA).copy(alpha = 0.20f),
                    radius = h * factor,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        palette.accentLight.copy(alpha = 0.15f),
                        Color(0xFF60A5FA).copy(alpha = 0.70f),
                        palette.accentLight.copy(alpha = 0.15f),
                    ),
                ),
                start = Offset(w * 0.20f, cy),
                end = Offset(w * 0.80f, cy),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )

            val phoneW = h * 0.46f
            val phoneH = h * 0.80f
            val phoneCorner = CornerRadius(14.dp.toPx(), 14.dp.toPx())

            val leftPivot = Offset(w * 0.23f, cy)
            rotate(degrees = -16f, pivot = leftPivot) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF312864), Color(0xFF1A163B)),
                    ),
                    topLeft = Offset(leftPivot.x - phoneW / 2f, leftPivot.y - phoneH / 2f),
                    size = Size(phoneW, phoneH),
                    cornerRadius = phoneCorner,
                )
                drawRoundRect(
                    color = Color(0xFFA78BFA).copy(alpha = 0.65f),
                    topLeft = Offset(leftPivot.x - phoneW / 2f, leftPivot.y - phoneH / 2f),
                    size = Size(phoneW, phoneH),
                    cornerRadius = phoneCorner,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            val rightPivot = Offset(w * 0.77f, cy)
            rotate(degrees = 16f, pivot = rightPivot) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF312864), Color(0xFF1A163B)),
                    ),
                    topLeft = Offset(rightPivot.x - phoneW / 2f, rightPivot.y - phoneH / 2f),
                    size = Size(phoneW, phoneH),
                    cornerRadius = phoneCorner,
                )
                drawRoundRect(
                    color = Color(0xFFA78BFA).copy(alpha = 0.65f),
                    topLeft = Offset(rightPivot.x - phoneW / 2f, rightPivot.y - phoneH / 2f),
                    size = Size(phoneW, phoneH),
                    cornerRadius = phoneCorner,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        Box(
            modifier = Modifier
                .size(54.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFF3B82F6),
                    spotColor = Color(0xFF3B82F6),
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF3B82F6),
                            Color(0xFF2563EB),
                            Color(0xFF1D4ED8),
                        ),
                    ),
                )
                .border(1.5.dp, Color(0xFF93C5FD).copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bluetooth,
                contentDescription = "Bluetooth Mesh",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun OnboardingFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
) {
    val palette = CypherTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1F33))
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = palette.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp,
                ),
                color = palette.textPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp,
                ),
                color = palette.textSecondary,
            )
        }
    }
}

@Composable
private fun RadioServicesStepContent(
    bluetoothReady: Boolean,
    locationReady: Boolean,
    onEnableBluetooth: () -> Unit,
    onEnableLocation: () -> Unit,
    onSkipInEmulator: () -> Unit,
) {
    val palette = CypherTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Enable Mesh Radios",
                style = MaterialTheme.typography.displayMedium,
                color = palette.textPrimary,
            )
            Text(
                text = "Cipher uses Bluetooth Low Energy to discover peers and relay encrypted packets without internet.",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp)),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            tint = if (bluetoothReady) palette.success else palette.accentLight,
                        )
                        Column {
                            Text("Bluetooth Adapter", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                            Text(
                                if (bluetoothReady) "Active & ready" else "Required for BLE mesh",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.textSecondary,
                            )
                        }
                    }
                    if (!bluetoothReady) {
                        Button(
                            onClick = onEnableBluetooth,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                        ) {
                            Text("Enable", color = Color.White)
                        }
                    } else {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = palette.success)
                    }
                }
            }

            if (!locationReady) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = palette.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, palette.borderSubtle, RoundedCornerShape(18.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = palette.warning)
                            Column {
                                Text("Location Services", style = MaterialTheme.typography.titleSmall, color = palette.textPrimary)
                                Text("Required by Android for BLE scanning", style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
                            }
                        }
                        Button(
                            onClick = onEnableLocation,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                        ) {
                            Text("Enable", color = Color.White)
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onSkipInEmulator,
            shape = RoundedCornerShape(50),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("Continue to Cipher", color = palette.textPrimary)
        }
    }
}

@Composable
private fun BatteryExemptionStepContent(
    isExempt: Boolean,
    onRequestDirectDialog: () -> Unit,
    onOpenBatterySettingsList: () -> Unit,
    onCheckAgain: () -> Unit,
    onSkip: () -> Unit,
) {
    val palette = CypherTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.accentSubtle)
                    .border(1.dp, palette.accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.BatterySaver,
                    contentDescription = null,
                    tint = palette.accentLight,
                    modifier = Modifier.size(30.dp),
                )
            }

            Text(
                text = "Background Mesh Reliability",
                style = MaterialTheme.typography.displayMedium,
                color = palette.textPrimary,
            )
            Text(
                text = "Android's battery optimizer can pause Bluetooth LE scanning when your screen is off. Allow unrestricted background battery usage so Cipher can relay messages reliably.",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.textSecondary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRequestDirectDialog,
                colors = ButtonDefaults.buttonColors(containerColor = palette.accent),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("battery_disable_optimization_button"),
            ) {
                Text("Disable Battery Optimization", color = Color.White, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onOpenBatterySettingsList,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("battery_open_settings_button"),
            ) {
                Text("Open System Battery Settings", color = palette.textPrimary)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onCheckAgain,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                ) {
                    Text("Check Again", color = palette.textSecondary)
                }
                OutlinedButton(
                    onClick = onSkip,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("battery_skip_button"),
                ) {
                    Text("Skip for Now", color = palette.textSecondary)
                }
            }
        }
    }
}
