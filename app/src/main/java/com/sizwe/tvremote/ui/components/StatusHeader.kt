package com.sizwe.tvremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.TransportType

/**
 * Always-visible connection status. Colour-coded so the state is readable at a glance, with the
 * action that fixes the current problem sitting right next to it - a dead connection should never
 * leave the user hunting through settings.
 */
@Composable
fun StatusHeader(
    state: ConnectionState,
    activeTransport: TransportType,
    modifier: Modifier = Modifier,
    onReconnect: () -> Unit,
    onOpenConnection: () -> Unit,
) {
    val indicator = when (state) {
        is ConnectionState.Connected -> Color(0xFF34A853)
        is ConnectionState.Connecting,
        is ConnectionState.Reconnecting,
        is ConnectionState.AwaitingAuthorization,
        -> Color(0xFFFBBC04)

        is ConnectionState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    val detail = when (state) {
        is ConnectionState.Connected -> "${state.deviceLabel} (${state.address})"
        is ConnectionState.Connecting -> state.detail
        is ConnectionState.AwaitingAuthorization -> state.detail
        is ConnectionState.Reconnecting -> "Reconnecting, attempt ${state.attempt} - ${state.cause}"
        is ConnectionState.Disconnected -> state.reason ?: "Disconnected"
        is ConnectionState.Failed -> state.error.message
        ConnectionState.Idle -> "No TV selected"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(indicator, CircleShape),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = activeTransport.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when (state) {
            is ConnectionState.Failed,
            is ConnectionState.Disconnected,
            -> TextButton(onClick = onReconnect) { Text("Retry") }

            ConnectionState.Idle -> TextButton(onClick = onOpenConnection) { Text("Set up") }

            else -> TextButton(onClick = onOpenConnection) { Text("Devices") }
        }
    }
}
