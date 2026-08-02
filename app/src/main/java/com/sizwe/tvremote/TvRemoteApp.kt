package com.sizwe.tvremote

import android.app.Application
import android.content.Context
import com.sizwe.tvremote.adb.AdbClient
import com.sizwe.tvremote.adb.AdbKeyStore
import com.sizwe.tvremote.adb.AdbTransport
import com.sizwe.tvremote.bluetooth.BluetoothHidTransport
import com.sizwe.tvremote.data.SettingsRepository
import com.sizwe.tvremote.diagnostics.DiagnosticsCollector
import com.sizwe.tvremote.diagnostics.LatencyProbe
import com.sizwe.tvremote.discovery.DeviceDiscovery
import com.sizwe.tvremote.shortcuts.ShortcutRepository
import com.sizwe.tvremote.transport.FakeTransport
import com.sizwe.tvremote.transport.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled dependency container. The graph is small and entirely singleton-scoped, so a DI
 * framework would be more ceremony than it is worth; everything below is created once and lives
 * for the process.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Application-lifetime scope for transports (reconnect loops, keep-alives, discovery). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)

    val adbKeyStore = AdbKeyStore(appContext)
    val adbClient = AdbClient(adbKeyStore, appScope)
    val adbTransport = AdbTransport(adbClient)

    val bluetoothTransport = BluetoothHidTransport(appContext)

    val fakeTransport = FakeTransport()

    val discovery = DeviceDiscovery(appContext)

    val shortcuts = ShortcutRepository(settings)

    val transports = TransportManager(
        fake = fakeTransport,
        adb = adbTransport,
        bluetooth = bluetoothTransport,
        settings = settings,
        scope = appScope,
    )

    val latencyProbe = LatencyProbe(adbClient)

    // Declared last: it reads from most of the graph above.
    val diagnostics = DiagnosticsCollector(
        context = appContext,
        keyStore = adbKeyStore,
        bluetooth = bluetoothTransport,
        discovery = discovery,
        transports = transports,
        settings = settings,
    )
}

class TvRemoteApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTerminate() {
        // Not called on real devices, but keeps instrumentation runs from leaking the HID proxy.
        container.transports.release()
        super.onTerminate()
    }
}
