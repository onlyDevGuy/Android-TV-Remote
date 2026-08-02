package com.sizwe.tvremote.adb

import com.sizwe.tvremote.core.ConnectionState
import com.sizwe.tvremote.core.RemoteKey
import com.sizwe.tvremote.core.RemoteTarget
import com.sizwe.tvremote.core.RemoteTransport
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.core.TransportError
import com.sizwe.tvremote.core.TransportException
import com.sizwe.tvremote.core.TransportType
import kotlinx.coroutines.flow.StateFlow

/**
 * [RemoteTransport] over ADB. Thin by design: [AdbClient] handles the session, this class only
 * turns remote buttons and shortcuts into shell commands.
 */
class AdbTransport(
    private val client: AdbClient,
) : RemoteTransport {

    override val type = TransportType.ADB

    override val capabilities = setOf(
        TransportCapability.KEYS,
        TransportCapability.TEXT_INPUT,
        TransportCapability.APP_LAUNCH,
        TransportCapability.PACKAGE_QUERY,
    )

    override val state: StateFlow<ConnectionState> = client.state

    override var target: RemoteTarget? = null
        private set

    override suspend fun connect(target: RemoteTarget): Result<Unit> {
        val network = target as? RemoteTarget.Network
            ?: return Result.failure(
                TransportException(TransportError.Protocol("ADB needs a network target, got $target")),
            )
        this.target = network
        return client.connect(network.host, network.port)
    }

    override suspend fun disconnect() = client.disconnect()

    override suspend fun sendKey(key: RemoteKey): Result<Unit> =
        // Fire-and-forget: `input keyevent` prints nothing, and waiting for the stream to close
        // would double the perceived latency of every press.
        client.shell(AdbKeyMap.keyEventCommand(key), waitForOutput = false).map { }

    suspend fun sendLongPress(key: RemoteKey): Result<Unit> =
        client.shell(AdbKeyMap.longPressCommand(key), waitForOutput = false).map { }

    override suspend fun sendText(text: String): Result<Unit> {
        if (text.isEmpty()) return Result.success(Unit)
        return client.shell(AdbKeyMap.textCommand(text), waitForOutput = false).map { }
    }

    override suspend fun launchApp(packageName: String, activity: String?): Result<Unit> {
        val command = if (activity != null) {
            "am start -n $packageName/$activity"
        } else {
            // Resolves through the launcher intent, which is what actually works across the OEM
            // skins; `monkey` is the fallback for packages with no LEANBACK_LAUNCHER category.
            "monkey -p $packageName -c android.intent.category.LEANBACK_LAUNCHER 1 " +
                "|| monkey -p $packageName -c android.intent.category.LAUNCHER 1"
        }
        return client.shell(command, waitForOutput = true).mapCatching { output ->
            if (output.contains("Error:") || output.contains("No activities found")) {
                throw TransportException(
                    TransportError.Protocol("The TV could not start $packageName"),
                )
            }
        }
    }

    override suspend fun listPackages(): Result<List<String>> =
        client.shell("pm list packages -3", waitForOutput = true).map { output ->
            output.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:") }
                .filter { it.isNotBlank() }
                .sorted()
                .toList()
        }

    /** Used by the shortcut editor to show a human-readable app name next to a package. */
    suspend fun resolveLabel(packageName: String): Result<String?> =
        client.shell("dumpsys package $packageName | grep -m1 versionName", waitForOutput = true)
            .map { it.trim().takeIf(String::isNotBlank) }

    suspend fun resetAuthorization() = client.resetAuthorization()

    override fun release() = client.release()
}
