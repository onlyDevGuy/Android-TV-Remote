package com.sizwe.tvremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.sizwe.tvremote.ui.RemoteApp
import com.sizwe.tvremote.ui.RemoteViewModel
import com.sizwe.tvremote.ui.theme.TvRemoteTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as TvRemoteApp).container

    private val viewModel: RemoteViewModel by viewModels { RemoteViewModel.Factory(container) }

    /**
     * Bluetooth permissions are requested up front rather than at pairing time: on API 31+ the HID
     * profile proxy cannot even be obtained without BLUETOOTH_CONNECT, and a denial there looks
     * exactly like a failed registration, which is miserable to debug.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) viewModel.refreshPairedDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestBluetoothPermissions()

        setContent {
            TvRemoteTheme {
                RemoteApp(viewModel)
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            // Pre-12, BT discovery is gated behind location instead. FINE must be asked for
            // alongside COARSE; the user may grant only COARSE, which still covers our use.
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
        permissionLauncher.launch(permissions)
    }
}
