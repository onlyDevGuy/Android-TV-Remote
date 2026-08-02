package com.sizwe.tvremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sizwe.tvremote.core.RemoteKey

/**
 * The navigation cluster: four arrows around a centre OK.
 *
 * Laid out as a square so it stays under the thumb regardless of screen size, with the arrows on
 * the outer edges - the whole cluster is meant to be usable without looking at the phone.
 * Arrows repeat while held; OK does not.
 */
@Composable
fun DPad(
    onPress: (RemoteKey) -> Unit,
    onRelease: (RemoteKey) -> Unit,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
            )
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            DirectionButton(
                key = RemoteKey.DPAD_UP,
                label = "Up",
                icon = Icons.Filled.KeyboardArrowUp,
                onPress = onPress,
                onRelease = onRelease,
                hapticsEnabled = hapticsEnabled,
                enabled = enabled,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DirectionButton(
                    key = RemoteKey.DPAD_LEFT,
                    label = "Left",
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    onPress = onPress,
                    onRelease = onRelease,
                    hapticsEnabled = hapticsEnabled,
                    enabled = enabled,
                )

                RemoteButton(
                    label = "OK",
                    size = 84,
                    enabled = enabled,
                    hapticsEnabled = hapticsEnabled,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = { onPress(RemoteKey.DPAD_CENTER) },
                )

                DirectionButton(
                    key = RemoteKey.DPAD_RIGHT,
                    label = "Right",
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    onPress = onPress,
                    onRelease = onRelease,
                    hapticsEnabled = hapticsEnabled,
                    enabled = enabled,
                )
            }

            DirectionButton(
                key = RemoteKey.DPAD_DOWN,
                label = "Down",
                icon = Icons.Filled.KeyboardArrowDown,
                onPress = onPress,
                onRelease = onRelease,
                hapticsEnabled = hapticsEnabled,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun DirectionButton(
    key: RemoteKey,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPress: (RemoteKey) -> Unit,
    onRelease: (RemoteKey) -> Unit,
    hapticsEnabled: Boolean,
    enabled: Boolean,
) {
    RemoteButton(
        label = label,
        icon = icon,
        size = 72,
        enabled = enabled,
        hapticsEnabled = hapticsEnabled,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        onPress = { onPress(key) },
        onRelease = { onRelease(key) },
    )
}
