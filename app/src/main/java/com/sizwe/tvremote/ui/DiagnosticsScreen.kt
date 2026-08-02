package com.sizwe.tvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sizwe.tvremote.diagnostics.DiagnosticsSnapshot
import com.sizwe.tvremote.diagnostics.LatencyProbe
import com.sizwe.tvremote.diagnostics.LogEntry
import com.sizwe.tvremote.diagnostics.LogLevel

/**
 * The troubleshooting surface, meant to be read on the phone while standing in front of the TV.
 *
 * Three things, in the order they get used when something is wrong: the environment snapshot
 * (which usually contains the answer outright — wrong subnet, denied permission, regenerated key),
 * a latency measurement, and the live event log.
 *
 * Styling here is intentionally utilitarian and outside the scope of the visual design work; this
 * is a developer tool that happens to live in the app.
 */
@Composable
fun DiagnosticsScreen(
    entries: List<LogEntry>,
    snapshot: DiagnosticsSnapshot?,
    latency: LatencyProbe.Result?,
    isMeasuringLatency: Boolean,
    canMeasureLatency: Boolean,
    onRunLatencyTest: () -> Unit,
    onClearLog: () -> Unit,
    onRefreshSnapshot: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var minimumLevel by remember { mutableStateOf(LogLevel.INFO) }

    // Newest first: when something just failed, the relevant line is the one at the top.
    val visible = remember(entries, minimumLevel) {
        entries.filter { it.level.ordinal >= minimumLevel.ordinal }.asReversed()
    }

    Column(modifier = modifier.fillMaxSize()) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Diagnostics", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Done") }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (snapshot != null) {
                MonospaceCard(text = snapshot.toReportText())
            }

            LatencyCard(
                latency = latency,
                isMeasuring = isMeasuringLatency,
                canMeasure = canMeasureLatency,
                onRun = onRunLatencyTest,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // Snapshot plus log in one blob: the two halves of a useful bug report,
                        // and neither is much good without the other.
                        val header = snapshot?.toReportText() ?: "--- TV Remote diagnostics ---"
                        clipboard.setText(
                            AnnotatedString(
                                com.sizwe.tvremote.diagnostics.DiagnosticsLog.export(header),
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Copy report")
                }
                OutlinedButton(onClick = onRefreshSnapshot, modifier = Modifier.weight(1f)) {
                    Text("Refresh")
                }
                OutlinedButton(onClick = onClearLog, modifier = Modifier.weight(1f)) {
                    Text("Clear")
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = minimumLevel == level,
                        onClick = { minimumLevel = level },
                        label = { Text(levelFilterLabel(level)) },
                    )
                }
            }

            Text(
                text = "${visible.size} of ${entries.size} events",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        if (visible.isEmpty()) {
            Text(
                text = "Nothing logged at this level yet. Try connecting, or lower the filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 20.dp,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = entry.time,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.level.label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = levelColour(entry.level),
            )
            Text(
                text = entry.tag,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.level == LogLevel.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        entry.detail?.let {
            Text(
                text = it,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LatencyCard(
    latency: LatencyProbe.Result?,
    isMeasuring: Boolean,
    canMeasure: Boolean,
    onRun: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Round-trip latency", style = MaterialTheme.typography.titleSmall)

        when {
            !canMeasure -> Text(
                "Only measurable over Wi-Fi, and only while connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            latency == null -> Text(
                "Not measured yet. This is an upper bound on button latency — a real key " +
                    "press does not wait for a reply, so it lands sooner than this.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = "median ${latency.median} ms   (min ${latency.min}, max ${latency.max})",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = latency.verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (latency.failures > 0) {
                    Text(
                        text = "${latency.failures} sample(s) failed outright.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Button(onClick = onRun, enabled = canMeasure && !isMeasuring) {
            Text(if (isMeasuring) "Measuring..." else "Run latency test")
        }
    }
}

@Composable
private fun MonospaceCard(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun levelColour(level: LogLevel): Color = when (level) {
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.WARN -> Color(0xFFFBBC04)
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
}

private fun levelFilterLabel(level: LogLevel): String = when (level) {
    LogLevel.DEBUG -> "All"
    LogLevel.INFO -> "Info+"
    LogLevel.WARN -> "Warnings+"
    LogLevel.ERROR -> "Errors"
}
