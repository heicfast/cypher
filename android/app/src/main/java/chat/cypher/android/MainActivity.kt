package chat.cypher.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import chat.cypher.android.data.MeshController
import chat.cypher.android.notify.Notifier
import chat.cypher.android.ui.CypherApp
import chat.cypher.android.ui.onboarding.BackgroundLocationScreen
import chat.cypher.android.ui.onboarding.BatteryOptimizationScreen
import chat.cypher.android.ui.onboarding.BluetoothCheckScreen
import chat.cypher.android.ui.onboarding.BluetoothCheckState
import chat.cypher.android.ui.onboarding.ErrorScreen
import chat.cypher.android.ui.onboarding.InitializingScreen
import chat.cypher.android.ui.onboarding.LocationCheckScreen
import chat.cypher.android.ui.onboarding.LocationCheckState
import chat.cypher.android.ui.onboarding.NicknameSetupScreen
import chat.cypher.android.ui.onboarding.OnboardingPermissions
import chat.cypher.android.ui.onboarding.PermissionExplanationScreen
import chat.cypher.android.ui.onboarding.WelcomeScreen
import chat.cypher.android.ui.onboarding.appSettingsIntent
import chat.cypher.android.ui.onboarding.batteryOptimizationIntent
import chat.cypher.android.ui.onboarding.locationSettingsIntent
import chat.cypher.android.ui.theme.CypherTheme
import chat.cypher.android.ui.theme.ThemePrefs

/**
 * Single-activity app hosting the bitchat-parity onboarding state machine and
 * the premium tab UI (v0.4 redesign).
 *
 * Flow: welcome → permission explanation → system grants → background
 * location → bluetooth/location/battery gates → nickname → initializing →
 * main app (mirrors bitchat's OnboardingCoordinator sequence with the setup
 * steps a messaging app is expected to show).
 */
class MainActivity : ComponentActivity() {

    /** Onboarding state machine (bitchat `OnboardingState` parity). */
    private enum class Onboarding {
        WELCOME,
        PERMISSION_EXPLANATION,
        PERMISSION_REQUESTING,
        BACKGROUND_LOCATION,
        BLUETOOTH_CHECK,
        LOCATION_CHECK,
        BATTERY_CHECK,
        NICKNAME_SETUP,
        INITIALIZING,
        COMPLETE,
        ERROR,
    }

    private var state by mutableStateOf(Onboarding.WELCOME)
    private var errorMessage by mutableStateOf("")
    private var btState by mutableStateOf(BluetoothCheckState.CHECKING)
    private var locState by mutableStateOf(LocationCheckState.CHECKING)
    private var batteryOptimized by mutableStateOf(false)
    private var nickname by mutableStateOf("")
    private var themeTick by mutableStateOf(0) // recomposition nudge on theme change
    private var openConversationId by mutableStateOf<String?>(null)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val missing = OnboardingPermissions.missingCritical(this)
            if (missing.isNotEmpty()) {
                errorMessage = "Cypher needs these permissions to work:\n\n" +
                    missing.joinToString("\n") { "• ${friendlyPermission(it)}" } +
                    "\n\nGrant them in Settings to continue."
                state = Onboarding.ERROR
            } else {
                proceedAfterPermissions()
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkBluetoothGate()
        }

    private val locationSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkLocationGate()
        }

    private val batteryLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Treated as success either way (bitchat parity: non-blocking).
            state = Onboarding.NICKNAME_SETUP
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Optional; continue regardless of the outcome.
            state = Onboarding.NICKNAME_SETUP
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.init(this)
        handleNotificationIntent(intent)

        // Returning users with everything set skip straight to the app.
        if (isReturningUser()) {
            nickname = Prefs.nickname(this)
            if (OnboardingPermissions.granted(this)) {
                state = Onboarding.COMPLETE
                startMesh()
            } else {
                state = Onboarding.PERMISSION_EXPLANATION
            }
        }

        setContent {
            CypherTheme {
                when (state) {
                    Onboarding.WELCOME -> WelcomeScreen(
                        onGetStarted = {
                            state =
                                if (OnboardingPermissions.granted(this)) Onboarding.BLUETOOTH_CHECK
                                else Onboarding.PERMISSION_EXPLANATION
                        },
                    )
                    Onboarding.PERMISSION_EXPLANATION -> PermissionExplanationScreen(
                        onGrant = {
                            state = Onboarding.PERMISSION_REQUESTING
                            permissionLauncher.launch(OnboardingPermissions.required(this))
                        },
                    )
                    Onboarding.PERMISSION_REQUESTING -> InitializingScreen()
                    Onboarding.BACKGROUND_LOCATION -> BackgroundLocationScreen(
                        onAllow = {
                            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        },
                        onSkip = { state = Onboarding.NICKNAME_SETUP },
                    )
                    Onboarding.BLUETOOTH_CHECK -> BluetoothCheckScreen(
                        state = btState,
                        onEnable = {
                            try {
                                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                            } catch (_: SecurityException) {
                                btState = BluetoothCheckState.DISABLED
                            }
                        },
                        onSkip = { checkLocationGate() },
                        onContinue = { checkLocationGate() },
                    )
                    Onboarding.LOCATION_CHECK -> LocationCheckScreen(
                        state = locState,
                        onOpenSettings = { locationSettingsLauncher.launch(locationSettingsIntent()) },
                        onCheckAgain = { checkLocationGate() },
                    )
                    Onboarding.BATTERY_CHECK -> BatteryOptimizationScreen(
                        isOptimized = batteryOptimized,
                        onDisable = {
                            runCatching { batteryLauncher.launch(batteryOptimizationIntent(this)) }
                            state = Onboarding.NICKNAME_SETUP
                        },
                        onCheckAgain = { state = Onboarding.NICKNAME_SETUP },
                        onSkip = { state = Onboarding.NICKNAME_SETUP },
                    )
                    Onboarding.NICKNAME_SETUP -> NicknameSetupScreen(
                        initial = nickname.ifEmpty { defaultNickname() },
                        onJoin = { chosen ->
                            nickname = chosen
                            Prefs.setNickname(this, chosen)
                            state = Onboarding.INITIALIZING
                            startMesh(chosen)
                        },
                    )
                    Onboarding.INITIALIZING -> InitializingScreen()
                    Onboarding.ERROR -> ErrorScreen(
                        message = errorMessage,
                        onRetry = { state = Onboarding.PERMISSION_EXPLANATION },
                        onOpenSettings = { startActivity(appSettingsIntent(this)) },
                    )
                    Onboarding.COMPLETE -> androidx.compose.runtime.key(themeTick) {
                        CypherApp(
                            controller = MeshController.get(this),
                            openConversationId = openConversationId,
                            onConversationOpened = { openConversationId = null },
                            onChangeNickname = { newNickname ->
                                MeshController.get(this).setNickname(newNickname)
                            },
                            onThemeChange = { themeTick++ },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MeshController.get(this).foregrounded = true
        // bitchat parity: re-check gates if radios were disabled in background.
        if (state == Onboarding.COMPLETE && !OnboardingPermissions.bluetoothEnabled()) {
            btState = BluetoothCheckState.DISABLED
            state = Onboarding.BLUETOOTH_CHECK
        }
    }

    override fun onPause() {
        super.onPause()
        val controller = MeshController.get(this)
        controller.foregrounded = false
        controller.persistNow()
    }

    // ---- flow helpers ---------------------------------------------------------

    private fun proceedAfterPermissions() {
        if (OnboardingPermissions.needsBackgroundLocation() &&
            !OnboardingPermissions.backgroundLocationGranted(this) &&
            !Prefs.backgroundLocationSkipped(this)
        ) {
            state = Onboarding.BACKGROUND_LOCATION
        } else {
            checkBluetoothGate()
        }
    }

    private fun checkBluetoothGate() {
        if (!OnboardingPermissions.bluetoothSupported(this)) {
            btState = BluetoothCheckState.NOT_SUPPORTED
        } else if (!OnboardingPermissions.bluetoothEnabled()) {
            btState = BluetoothCheckState.DISABLED
        } else {
            checkLocationGate()
            return
        }
        state = Onboarding.BLUETOOTH_CHECK
    }

    private fun checkLocationGate() {
        if (!OnboardingPermissions.locationEnabled(this)) {
            locState = LocationCheckState.DISABLED
            state = Onboarding.LOCATION_CHECK
        } else {
            batteryOptimized = OnboardingPermissions.batteryOptimized(this)
            state = Onboarding.BATTERY_CHECK
        }
    }

    private fun startMesh(nicknameOverride: String? = null) {
        val nick = nicknameOverride ?: Prefs.nickname(this).ifEmpty { defaultNickname() }
        Prefs.setOnboarded(this)
        MeshController.get(this).apply {
            start()
            setNickname(nick) { }
        }
        MeshForegroundService.start(this, nick)
        // The engine + UI warm up; flip to COMPLETE on the next frame.
        if (state != Onboarding.COMPLETE) {
            state = Onboarding.COMPLETE
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val conversation = intent?.getStringExtra(Notifier.EXTRA_OPEN_CONVERSATION) ?: return
        openConversationId = conversation
        // Deep links only land after onboarding; if the app is still in
        // onboarding, remember it — CypherApp consumes it post-COMPLETE.
        if (state == Onboarding.COMPLETE) {
            MeshController.get(this).openConversation(conversation)
        }
    }

    private fun isReturningUser(): Boolean = Prefs.onboarded(this)

    private fun defaultNickname(): String = "anon${(1000..9999).random()}"

    private fun friendlyPermission(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_SCAN -> "Nearby devices (Bluetooth scanning)"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth connections"
        Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth advertising"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Location (required by Android for BLE scanning)"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate location"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> permission.substringAfterLast('.')
    }
}

/** Lightweight onboarding/preferences storage. */
object Prefs {
    private const val FILE = "cypher_prefs"
    private const val KEY_ONBOARDED = "onboarded_complete"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_BG_LOCATION_SKIPPED = "background_location_skipped"

    fun onboarded(context: android.content.Context): Boolean =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: android.content.Context) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    fun nickname(context: android.content.Context): String =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .getString(KEY_NICKNAME, "") ?: ""

    fun setNickname(context: android.content.Context, nickname: String) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_NICKNAME, nickname).apply()
    }

    fun backgroundLocationSkipped(context: android.content.Context): Boolean =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_BG_LOCATION_SKIPPED, false)
}
