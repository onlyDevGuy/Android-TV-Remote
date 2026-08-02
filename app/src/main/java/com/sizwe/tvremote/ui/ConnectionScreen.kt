package com.sizwe.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sizwe.tvremote.discovery.DiscoveredDevice
import com.sizwe.tvremote.discovery.DiscoverySource

/**
 * Device setup: find a TV, or fall back to typing its address.
 *
 * The screen doubles as the app's documentation. Network debugging being off is the single most
 * common reason nothing works, and the user has no way to guess that, so the instructions live
 * here rather than in a README nobody opens.
 */
@Composable
fun ConnectionScreen(
    state: RemoteViewModel.UiState,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDiscovered: (DiscoveredDevice) -> Unit,
    onConnectManual: (String) -> Unit,
    onDisconnect: () -> Unit,
    onForgetAuthorization: () -> Unit,
    onRegisterBluetooth: () -> Unit,
    onRefreshPaired: () -> Unit,
    onConnectBluetooth: (String, String) -> Unit,
    onSetAutoConnect: (Boolean) -> Unit,
    onSetAutoFallback: (Boolean) -> Unit,
    onSetHaptics: (Boolean) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualAddress by rememberSaveable { mutableStateOf(state.settings.lastHost.orEmpty()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Devices", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }

        Text(
            text = state.statusLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SetupInstructions()

        SectionCard(title = "Wi-Fi (ADB)") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = if (state.isScanning) onStopScan else onScan,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isScanning) "Stop scan" else "Scan network")
                }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                    Text("Disconnect")
                }
            }

            if (state.isScanning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text(
                        "Looking for TVs on this Wi-Fi network...",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            state.discovered.forEach { device ->
                DiscoveredRow(device = device, onClick = { onConnectDiscovered(device) })
            }

            // The "device not found" fallback: scanning finished and turned up nothing, so point
            // the user at manual entry instead of leaving an empty list.
            if (state.scanFinishedEmpty) {
                Text(
                    text = "No TV answered on port 5555. Check that the TV is awake, that it is on " +
                        "this same Wi-Fi network (not a guest network), and that network debugging " +
                        "is on - then enter the IP address by hand below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("TV address, e.g. 192.168.1.42 or 192.168.1.42:5555") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onConnectManual(manualAddress) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Connect")
            }

            TextButton(onClick = onForgetAuthorization) {
                Text("Forget authorisation (re-prompt on the TV)")
            }
        }

        SectionCard(title = "Bluetooth remote") {
            Text(
                text = "Bluetooth keeps working when the TV is off the network, and it can wake " +
                    "some TVs that Wi-Fi cannot. It cannot launch apps or type text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(onClick = onRegisterBluetooth, modifier = Modifier.fillMaxWidth()) {
                Text("Advertise this phone as a remote")
            }

            Text(
                text = "Then on the TV: Settings > Remotes & accessories > Add accessory, and pick " +
                    "this phone from the list.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = onRefreshPaired, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh paired devices")
            }

            state.pairedBluetoothDevices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnectBluetooth(device.address, device.name) }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(device.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        device.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }

            if (state.pairedBluetoothDevices.isEmpty()) {
                Text(
                    "No paired devices yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Behaviour") {
            ToggleRow(
                label = "Reconnect on launch",
                checked = state.settings.autoConnectOnLaunch,
                onChange = onSetAutoConnect,
            )
            ToggleRow(
                label = "Fall back to the other transport automatically",
                checked = state.settings.autoFallbackTransport,
                onChange = onSetAutoFallback,
            )
            ToggleRow(
                label = "Vibrate on press",
                checked = state.settings.hapticsEnabled,
                onChange = onSetHaptics,
            )
            Text(
                text = "Active transport: ${state.activeTransport.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Troubleshooting") {
            Text(
                text = "If the remote is not reaching the TV, the diagnostics screen shows what " +
                    "the app is actually doing — the connection handshake, Bluetooth pairing, and " +
                    "what the network scan found. It also copies a full report to the clipboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Open diagnostics")
            }
        }
    }
}

@Composable
private fun SetupInstructions() {
    var expanded by remember { mutableStateOf(false) }

    SectionCard(title = "Before the first connection") {
        Text(
            text = "On the TV, turn on network debugging - without it nothing on this screen can " +
                "reach the TV.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide steps" else "Show me how")
        }
        if (expanded) {
            Text(
                text = """
                    1. Settings > Device Preferences > About.
                    2. Tap "Build" seven times until it says you are a developer.
                    3. Back out to Settings > Device Preferences > Developer options.
                    4. Turn on "USB debugging" and "Network debugging" (some boxes call it
                       "Wireless debugging" or "ADB over network").
                    5. Note the TV's IP under Settings > Network & Internet.
                    6. First connect shows "Allow debugging?" on the TV - accept it and tick
                       "Always allow from this computer".

                    Some TV boxes turn network debugging back off after a reboot. If the remote
                    stops working after unplugging the TV, that is the first thing to check.
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DiscoveredRow(device: DiscoveredDevice, onClick: () -> Unit) {
    val sourceLabel = when (device.source) {
        DiscoverySource.MDNS -> "announced itself"
        DiscoverySource.PORT_SCAN -> "found by scan"
        DiscoverySource.MANUAL -> "entered by hand"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !device.requiresTls, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(device.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "${device.address} - $sourceLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (device.requiresTls) {
            Text(
                text = "This TV is advertising ADB over TLS (Android 11+ wireless debugging), " +
                    "which needs a pairing code. Use the legacy port 5555 toggle or Bluetooth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
