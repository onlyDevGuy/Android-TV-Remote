package com.sizwe.tvremote.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.sizwe.tvremote.adb.AdbKeyStore
import com.sizwe.tvremote.bluetooth.BluetoothHidTransport
import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.data.SettingsRepository
import com.sizwe.tvremote.discovery.DeviceDiscovery
import com.sizwe.tvremote.transport.TransportManager
import kotlinx.coroutines.flow.first

/**
 * Snapshot of everything that explains a failure but is invisible from the remote screen.
 *
 * Most "it doesn't work" reports come down to one of these lines: the phone is on a different
 * subnet from the TV, Bluetooth permission was denied, or the ADB key changed (so the TV's
 * "always allow" no longer applies). Each is cheap to read here and near-impossible to guess.
 */
data class DiagnosticsSnapshot(
    val appVersion: String,
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
    val activeTransport: String,
    val connectionSummary: String,
    val localIpAddress: String?,
    val subnetPrefix: String?,
    val lastKnownTv: String?,
    val adbKeyPresent: Boolean,
    val adbKeyFingerprint: String?,
    val bluetoothAvailable: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothRegistered: Boolean,
    val grantedPermissions: List<String>,
    val missingPermissions: List<String>,
    val autoConnect: Boolean,
    val autoFallback: Boolean,
) {
    /** The header pasted above the event log when the user copies a report. */
    fun toReportText(): String = buildString {
        appendLine("--- TV Remote diagnostics ---")
        appendLine("App              : $appVersion")
        appendLine("Phone            : $deviceModel (Android $androidRelease, API $sdkInt)")
        appendLine("Active transport : $activeTransport")
        appendLine("Connection       : $connectionSummary")
        appendLine("Phone IP         : ${localIpAddress ?: "unknown"}")
        appendLine("Scanned subnet   : ${subnetPrefix?.let { "$it.0/24" } ?: "unavailable"}")
        appendLine("Last known TV    : ${lastKnownTv ?: "none saved"}")
        appendLine("ADB key          : ${if (adbKeyPresent) "present (${adbKeyFingerprint ?: "?"})" else "not generated yet"}")
        appendLine("Bluetooth        : available=$bluetoothAvailable enabled=$bluetoothEnabled registered=$bluetoothRegistered")
        appendLine("Permissions      : granted=${grantedPermissions.joinToString().ifEmpty { "none" }}")
        appendLine("                   missing=${missingPermissions.joinToString().ifEmpty { "none" }}")
        appendLine("Auto-connect     : $autoConnect     Auto-fallback: $autoFallback")
    }
}

class DiagnosticsCollector(
    context: Context,
    private val keyStore: AdbKeyStore,
    private val bluetooth: BluetoothHidTransport,
    private val discovery: DeviceDiscovery,
    private val transports: TransportManager,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext

    suspend fun snapshot(): DiagnosticsSnapshot {
        val saved = settings.settings.first()
        val permissions = permissionStatus()

        return DiagnosticsSnapshot(
            appVersion = appVersion(),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            activeTransport = transports.activeType.value.label,
            connectionSummary = summarise(transports.state.value),
            localIpAddress = discovery.localIpv4Address(),
            subnetPrefix = discovery.localSubnetPrefix(),
            lastKnownTv = saved.lastHost?.let { "$it:${saved.lastPort}" },
            adbKeyPresent = keyStore.exists,
            adbKeyFingerprint = keyStore.fingerprint(),
            bluetoothAvailable = bluetooth.bluetoothAvailable(),
            bluetoothEnabled = bluetooth.bluetoothEnabled(),
            bluetoothRegistered = bluetooth.isRegistered,
            grantedPermissions = permissions.first,
            missingPermissions = permissions.second,
            autoConnect = saved.autoConnectOnLaunch,
            autoFallback = saved.autoFallbackTransport,
        )
    }

    private fun summarise(state: ConnectionState): String = when (state) {
        is ConnectionState.Connected -> "connected to ${state.deviceLabel} (${state.address})"
        is ConnectionState.Connecting -> "connecting - ${state.detail}"
        is ConnectionState.AwaitingAuthorization -> "waiting for the TV to authorise"
        is ConnectionState.Reconnecting -> "reconnecting, attempt ${state.attempt} (${state.cause})"
        is ConnectionState.Disconnected -> "disconnected - ${state.reason ?: "no reason given"}"
        is ConnectionState.Failed -> "failed - ${state.error::class.java.simpleName}: ${state.error.message}"
        ConnectionState.Idle -> "idle, no TV selected"
    }

    /** Returns granted-to-missing, using the short permission names to keep the report readable. */
    private fun permissionStatus(): Pair<List<String>, List<String>> {
        val relevant = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        val granted = mutableListOf<String>()
        val missing = mutableListOf<String>()
        relevant.forEach { permission ->
            val short = permission.substringAfterLast('.')
            if (ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                granted += short
            } else {
                missing += short
            }
        }
        return granted to missing
    }

    private fun appVersion(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        "${info.versionName} (${info.longVersionCodeCompat()})"
    }.getOrDefault("unknown")

    @Suppress("DEPRECATION") // the deprecated field is only read below API 28
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
