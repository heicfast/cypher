package chat.cypher.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.onboarding.BatteryOptimizationHelper
import chat.cypher.android.onboarding.OnboardingPreferences
import chat.cypher.android.onboarding.OnboardingScreen
import chat.cypher.android.ui.CypherAppRoot
import chat.cypher.android.ui.theme.CypherTheme

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        startMeshService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        MeshStore.loadOrCreateSeed(applicationContext)

        setContent {
            val screenSecurity by MeshStore.screenSecurityEnabled.collectAsState()
            LaunchedEffect(screenSecurity) {
                if (screenSecurity) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            var onboardingDone by remember {
                mutableStateOf(OnboardingPreferences.isCompleted(applicationContext))
            }

            CypherTheme {
                if (!onboardingDone) {
                    OnboardingScreen(
                        onFinished = {
                            onboardingDone = true
                            startMeshService()
                        },
                    )
                } else {
                    LaunchedEffect(Unit) {
                        startMeshService()
                    }
                    CypherAppRoot(
                        onRequestPermissions = { requestMeshPermissions() },
                        onRequestBatteryOptimization = {
                            BatteryOptimizationHelper.launchExemptionRequest(this@MainActivity)
                        },
                        onEmergencyWipeCompleted = {
                            onboardingDone = false
                        },
                    )
                }
            }
        }
    }

    private fun requestMeshPermissions() {
        val perms = buildList {
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
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startMeshService() {
        MeshController.start(applicationContext)
        runCatching {
            val svcIntent = Intent(this, MeshForegroundService::class.java)
            ContextCompat.startForegroundService(this, svcIntent)
        }
    }
}
