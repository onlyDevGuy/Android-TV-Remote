package com.sizwe.tvremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.TransportType
import com.sizwe.tvremote.shortcuts.AppShortcut
import com.sizwe.tvremote.ui.components.DPad
import com.sizwe.tvremote.ui.components.RemoteButton
import com.sizwe.tvremote.ui.components.RemotePillButton
import com.sizwe.tvremote.ui.components.StatusHeader

/**
 * The remote itself.
 *
 * Layout follows how the phone is actually held: status at the top where it is glanceable, the
 * D-pad in the vertical middle where the thumb rests, and everything else fanned out around it.
 * The screen is transport-agnostic - it calls [onPress]/[onRelease] with a [RemoteKey] and never
 * learns whether that went out over Wi-Fi or Bluetooth.
 */
@Composable
fun RemoteControlScreen(
    state: RemoteViewModel.UiState,
    onPress: (RemoteKey) -> Unit,
    onRelease: (RemoteKey) -> Unit,
    onLaunchShortcut: (AppShortcut) -> Unit,
    onSelectTransport: (TransportType) -> Unit,
    onOpenConnection: () -> Unit,
    onOpenKeyboard: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = state.settings.hapticsEnabled
    val enabled = state.connection.isUsable

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        StatusHeader(
            state = state.connection,
            activeTransport = state.activeTransport,
            onReconnect = onReconnect,
            onOpenConnection = onOpenConnection,
        )

        TransportSwitch(
            active = state.activeTransport,
            onSelect = onSelectTransport,
        )

        // Power and inputs sit above the D-pad, out of accidental-thumb range.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteButton(
                label = "Power",
                icon = Icons.Filled.PowerSettingsNew,
                enabled = enabled,
                hapticsEnabled = haptics,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = { onPress(RemoteKey.POWER) },
            )
            RemoteButton(
                label = "Input",
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.TV_INPUT) },
            )
            RemoteButton(
                label = "Guide",
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.GUIDE) },
            )
            RemoteButton(
                label = "Keyboard",
                icon = Icons.Filled.Keyboard,
                enabled = enabled && state.activeTransport != TransportType.BLUETOOTH_HID,
                hapticsEnabled = haptics,
                onClick = onOpenKeyboard,
            )
        }

        DPad(
            onPress = onPress,
            onRelease = onRelease,
            hapticsEnabled = haptics,
            enabled = enabled,
        )

        // Back / Home / Menu, the three most-used buttons after the D-pad.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RemoteButton(
                label = "Back",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.BACK) },
            )
            RemoteButton(
                label = "Home",
                icon = Icons.Filled.Home,
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.HOME) },
            )
            RemoteButton(
                label = "Menu",
                icon = Icons.Filled.Menu,
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.MENU) },
            )
        }

        // Volume repeats while held, which is why it uses onPress/onRelease rather than onClick.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteButton(
                label = "Volume down",
                icon = Icons.AutoMirrored.Filled.VolumeDown,
                enabled = enabled,
                hapticsEnabled = haptics,
                onPress = { onPress(RemoteKey.VOLUME_DOWN) },
                onRelease = { onRelease(RemoteKey.VOLUME_DOWN) },
            )
            RemoteButton(
                label = "Mute",
                icon = Icons.AutoMirrored.Filled.VolumeOff,
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.VOLUME_MUTE) },
            )
            RemoteButton(
                label = "Volume up",
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                enabled = enabled,
                hapticsEnabled = haptics,
                onPress = { onPress(RemoteKey.VOLUME_UP) },
                onRelease = { onRelease(RemoteKey.VOLUME_UP) },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RemoteButton(
                label = "Rewind",
                icon = Icons.Filled.FastRewind,
                size = 56,
                enabled = enabled,
                hapticsEnabled = haptics,
                onPress = { onPress(RemoteKey.MEDIA_REWIND) },
                onRelease = { onRelease(RemoteKey.MEDIA_REWIND) },
            )
            RemoteButton(
                label = "Play or pause",
                icon = Icons.Filled.PlayArrow,
                size = 56,
                enabled = enabled,
                hapticsEnabled = haptics,
                onClick = { onPress(RemoteKey.MEDIA_PLAY_PAUSE) },
            )
            RemoteButton(
                label = "Fast forward",
                icon = Icons.Filled.FastForward,
                size = 56,
                enabled = enabled,
                hapticsEnabled = haptics,
                onPress = { onPress(RemoteKey.MEDIA_FAST_FORWARD) },
                onRelease = { onRelease(RemoteKey.MEDIA_FAST_FORWARD) },
            )
        }

        ShortcutRow(
            shortcuts = state.shortcuts,
            enabled = enabled && state.canLaunchApps,
            reason = if (!state.canLaunchApps) {
                "Shortcuts need the Wi-Fi transport - Bluetooth can only send buttons."
            } else {
                null
            },
            onLaunch = onLaunchShortcut,
        )
    }
}

@Composable
private fun TransportSwitch(
    active: TransportType,
    onSelect: (TransportType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TransportType.entries.forEach { type ->
            RemotePillButton(
                label = type.label,
                selected = type == active,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(type) },
            )
        }
    }
}

@Composable
private fun ShortcutRow(
    shortcuts: List<AppShortcut>,
    enabled: Boolean,
    reason: String?,
    onLaunch: (AppShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Apps",
            style = MaterialTheme.typography.titleSmall,
        )

        when {
            reason != null -> Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            shortcuts.isEmpty() -> Text(
                text = "Connect over Wi-Fi and the app list is read straight off the TV, " +
                    "so the package names always match this box.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shortcuts, key = { it.id }) { shortcut ->
                RemotePillButton(
                    label = shortcut.label,
                    enabled = enabled,
                    modifier = Modifier.widthIn(min = 96.dp),
                    onClick = { onLaunch(shortcut) },
                )
            }
        }
    }
}
