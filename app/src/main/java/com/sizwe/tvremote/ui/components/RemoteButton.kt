package com.sizwe.tvremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The one button primitive the whole remote is built from.
 *
 * Sized for one-handed use: the minimum touch target is 64dp, comfortably above the 48dp
 * accessibility floor, because this app is used without looking at the phone.
 *
 * [onPress]/[onRelease] exist so held buttons (volume, D-pad) can repeat while down instead of
 * firing once per tap.
 */
@Composable
fun RemoteButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    size: Int = 64,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = size.dp, minHeight = size.dp)
            .size(size.dp)
            .background(
                color = if (pressed) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    containerColor.copy(alpha = if (enabled) 1f else 0.35f)
                },
                shape = shape,
            )
            .pointerInput(enabled, onClick, onPress, onRelease) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        pressed = true
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                        // Wait for the finger to lift so held buttons can repeat.
                        tryAwaitRelease()
                        pressed = false
                        onRelease()
                    },
                    onTap = { onClick?.invoke() },
                )
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor.copy(alpha = if (enabled) 1f else 0.4f),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = if (enabled) 1f else 0.4f),
            )
        }
    }
}

/** Wider variant for labelled actions (shortcuts, transport switch). */
@Composable
fun RemotePillButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .background(
                color = container.copy(alpha = if (enabled) 1f else 0.35f),
                shape = RoundedCornerShape(24.dp),
            )
            .pointerInput(enabled, onClick) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
