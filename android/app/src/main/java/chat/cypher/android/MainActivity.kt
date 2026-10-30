package chat.cypher.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import chat.cypher.android.ui.ChatScreen
import chat.cypher.android.ui.theme.CypherTheme

class MainActivity : ComponentActivity() {

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION, // BLE scan results on Android < 12
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

    /** All runtime permissions the mesh needs are granted. */
    private fun meshPermissionsGranted(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!meshPermissionsGranted()) {
            @Suppress("NAME_SHADOWING")
            val permissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    // Only start the mesh when the runtime grants are in —
                    // starting a connectedDevice foreground service without
                    // BLUETOOTH_CONNECT throws SecurityException on API 31+.
                    if (meshPermissionsGranted()) {
                        maybeStartMesh()
                    }
                }
            permissionLauncher.launch(requiredPermissions())
        } else {
            maybeStartMesh()
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter != null && !adapter.isEnabled && meshPermissionsGranted()) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1)
        }

        setContent {
            CypherTheme {
                ChatScreen()
            }
        }
    }

    private fun maybeStartMesh() {
        val hasBluetooth = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        if (!hasBluetooth) return
        MeshForegroundService.start(this, "cypher")
    }
}
