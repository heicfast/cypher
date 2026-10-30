package chat.cypher.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import chat.cypher.android.onboarding.OnboardingFlow
import chat.cypher.android.onboarding.hasAllMeshPermissions
import chat.cypher.android.ui.AppScaffold
import chat.cypher.android.ui.theme.CypherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val controller = (application as CypherApplication).meshController
        handleConversationIntent(intent)

        setContent {
            val themePreference by controller.themePreference.collectAsState()
            val batterySkipped by controller.batteryOptimizationSkipped.collectAsState()

            CypherTheme(preference = themePreference) {
                OnboardingFlow(
                    batteryOptimizationSkipped = batterySkipped,
                    onSetBatteryOptimizationSkipped = { skipped ->
                        controller.setBatteryOptimizationSkipped(skipped)
                    },
                    onReady = {
                        if (hasAllMeshPermissions(this@MainActivity)) {
                            if (controller.backgroundServiceEnabled.value) {
                                MeshForegroundService.start(this@MainActivity)
                            }
                            controller.startMesh()
                        }
                    },
                ) {
                    LaunchedEffect(Unit) {
                        if (hasAllMeshPermissions(this@MainActivity)) {
                            controller.startMesh()
                        }
                    }
                    AppScaffold(controller = controller)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleConversationIntent(intent)
    }

    private fun handleConversationIntent(intent: Intent?) {
        val convKey = intent?.getStringExtra("conversation_key") ?: return
        val controller = (application as? CypherApplication)?.meshController ?: return
        controller.openConversation(convKey)
    }
}
