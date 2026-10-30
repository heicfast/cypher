package chat.cypher.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import chat.cypher.android.core.MeshController
import chat.cypher.android.core.MeshStore
import chat.cypher.android.onboarding.OnboardingFlow
import chat.cypher.android.ui.AppScaffold
import chat.cypher.android.ui.theme.CypherTheme

/**
 * Single-activity shell: runs the bitchat-parity onboarding state machine
 * on first launch, then hosts the main app scaffold.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            var onboarded by rememberSaveable { mutableStateOf(MeshStore.onboardingComplete) }
            CypherTheme {
                if (!onboarded) {
                    OnboardingFlow(firstLaunch = true) {
                        MeshStore.onboardingComplete = true
                        onboarded = true
                        maybeStartMesh()
                    }
                } else {
                    maybeStartMesh()
                    AppScaffold()
                }
            }
        }

        handleOpenIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MeshController.appInForeground = true
    }

    override fun onPause() {
        super.onPause()
        MeshController.appInForeground = false
    }

    /** Deep link from a DM notification: open that conversation. */
    private fun handleOpenIntent(intent: Intent?) {
        val peer = intent?.getStringExtra("open_peer") ?: return
        MeshController.openConversation = peer
    }

    private fun maybeStartMesh() {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)) {
            return
        }
        MeshForegroundService.start(this)
    }
}
