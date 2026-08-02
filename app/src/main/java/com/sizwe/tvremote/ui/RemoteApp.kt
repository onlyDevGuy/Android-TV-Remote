package com.sizwe.tvremote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Screen { REMOTE, CONNECTION }

/**
 * Root composable. Two screens and a text-entry dialog is the whole app, so navigation is a single
 * piece of state rather than a nav graph.
 */
@Composable
fun RemoteApp(viewModel: RemoteViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.REMOTE) }
    var keyboardOpen by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice) {
        val notice = state.notice
        if (notice != null) {
            snackbarHostState.showSnackbar(notice)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (screen) {
            Screen.REMOTE -> RemoteControlScreen(
                state = state,
                onPress = viewModel::startRepeat,
                onRelease = viewModel::stopRepeat,
                onLaunchShortcut = viewModel::launchShortcut,
                onSelectTransport = viewModel::selectTransport,
                onOpenConnection = {
                    viewModel.refreshPairedDevices()
                    screen = Screen.CONNECTION
                },
                onOpenKeyboard = { keyboardOpen = true },
                onReconnect = viewModel::reconnect,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )

            Screen.CONNECTION -> ConnectionScreen(
                state = state,
                onScan = viewModel::startScan,
                onStopScan = viewModel::stopScan,
                onConnectDiscovered = viewModel::connectTo,
                onConnectManual = viewModel::connectManual,
                onDisconnect = viewModel::disconnect,
                onForgetAuthorization = viewModel::forgetAuthorization,
                onRegisterBluetooth = viewModel::registerBluetooth,
                onRefreshPaired = viewModel::refreshPairedDevices,
                onConnectBluetooth = { address, label ->
                    viewModel.connectBluetooth(address, label)
                },
                onSetAutoConnect = viewModel::setAutoConnect,
                onSetAutoFallback = viewModel::setAutoFallback,
                onSetHaptics = viewModel::setHaptics,
                onBack = { screen = Screen.REMOTE },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        }
    }

    if (keyboardOpen) {
        TextEntryDialog(
            onDismiss = { keyboardOpen = false },
            onSend = { text ->
                viewModel.sendText(text)
                keyboardOpen = false
            },
        )
    }
}

/**
 * Typing on a TV with a D-pad is miserable; this pushes a whole string at once over ADB's
 * `input text`. Bluetooth cannot do this, so the button that opens it is disabled on that
 * transport rather than failing after the user has typed a search query.
 */
@Composable
private fun TextEntryDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send text to the TV") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Text") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSend(text) }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
