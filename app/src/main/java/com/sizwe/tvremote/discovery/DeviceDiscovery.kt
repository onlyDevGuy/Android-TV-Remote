package com.sizwe.tvremote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.sizwe.tvremote.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Finds Android TV devices that will accept an ADB connection, so the user never has to go hunting
 * for an IP address in the TV's settings.
 *
 * Two strategies, run together because neither is reliable on its own:
 *
 *  - **mDNS.** `adbd` advertises `_adb._tcp` (legacy) and `_adb-tls-connect._tcp` (Android 11+)
 *    once debugging is on. Instant and gives a real device name — but plenty of TV boxes never
 *    advertise, and some routers block multicast between clients.
 *  - **Port scan.** Knock on 5555 across the local /24. Works when mDNS does not; costs a few
 *    seconds and finds nothing if the phone is on a different subnet or on guest Wi-Fi.
 *
 * Both are bounded by [Config.overallTimeoutMs]. When they come up empty the caller falls back to
 * manual IP entry, which is why [scan] always completes rather than hanging on an empty network.
 */
class DeviceDiscovery(
    context: Context,
    private val config: Config = Config(),
) {

    data class Config(
        val overallTimeoutMs: Long = 8_000,
        val mdnsTimeoutMs: Long = 5_000,
        val perHostTimeoutMs: Int = 300,
        val scanConcurrency: Int = 48,
        val adbPort: Int = 5555,
    )

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Emits candidates as they turn up, then completes. Duplicates across the two strategies are
     * filtered here so the UI can just append.
     */
    fun scan(): Flow<DiscoveredDevice> = flow {
        val seen = mutableSetOf<String>()

        DiagnosticsLog.i(
            TAG,
            "Scan started",
            "phone=${localIpv4Address() ?: "no IPv4"} " +
                "subnet=${localSubnetPrefix()?.let { "$it.0/24" } ?: "unavailable"}",
        )

        withTimeoutOrNull(config.overallTimeoutMs) {
            coroutineScope {
                val results = kotlinx.coroutines.channels.Channel<DiscoveredDevice>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED,
                )

                val jobs = listOf(
                    launch {
                        runCatching {
                            withTimeoutOrNull(config.mdnsTimeoutMs) {
                                mdnsFlow(SERVICE_TYPE_ADB).collect { results.send(it) }
                            }
                        }
                    },
                    launch {
                        runCatching {
                            withTimeoutOrNull(config.mdnsTimeoutMs) {
                                mdnsFlow(SERVICE_TYPE_ADB_TLS).collect { results.send(it) }
                            }
                        }
                    },
                    launch {
                        runCatching { scanSubnet { results.send(it) } }
                    },
                )

                launch {
                    jobs.forEach { it.join() }
                    results.close()
                }

                for (device in results) {
                    if (seen.add(device.address)) {
                        DiagnosticsLog.i(
                            TAG,
                            "Found ${device.address} via ${device.source}",
                            device.name?.let { "name=$it tls=${device.requiresTls}" },
                        )
                        emit(device)
                    }
                }
            }
        }

        if (seen.isEmpty()) {
            DiagnosticsLog.w(
                TAG,
                "Scan finished with no devices found",
                "Check the TV is awake, on this same network (not guest Wi-Fi), and that " +
                    "network debugging is on. Then enter the IP by hand.",
            )
        } else {
            DiagnosticsLog.i(TAG, "Scan finished, ${seen.size} device(s) found")
        }
    }.flowOn(Dispatchers.IO)

    /** Cheap reachability probe, used by "reconnect" and before trusting a remembered address. */
    suspend fun isReachable(host: String, port: Int = config.adbPort): Boolean =
        probe(host, port)

    // --- mDNS ---

    private fun mdnsFlow(serviceType: String): Flow<DiscoveredDevice> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            close()
            return@callbackFlow
        }

        // NsdManager resolves one service at a time; concurrent resolves fail with
        // FAILURE_ALREADY_ACTIVE, so requests are serialised through this queue.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = pending.removeFirstOrNull() ?: return
            resolving = true
            manager.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        resolving = false
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host?.hostAddress
                        if (address != null) {
                            trySend(
                                DiscoveredDevice(
                                    host = address,
                                    // The TLS service advertises its own (random) port; the legacy
                                    // one is what we can actually speak, so pin 5555 there.
                                    port = if (serviceType == SERVICE_TYPE_ADB_TLS) {
                                        serviceInfo.port
                                    } else {
                                        config.adbPort
                                    },
                                    name = serviceInfo.serviceName,
                                    source = DiscoverySource.MDNS,
                                    requiresTls = serviceType == SERVICE_TYPE_ADB_TLS,
                                ),
                            )
                        }
                        resolving = false
                        resolveNext()
                    }
                },
            )
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.d(TAG, "Discovery start failed for $serviceType: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                close()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                pending.addLast(serviceInfo)
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }

        try {
            manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.d(TAG, "discoverServices($serviceType) threw", e)
            close()
        }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }

    // --- port scan ---

    /**
     * Knocks on [Config.adbPort] for every host in the phone's /24. Anything wider is not worth
     * it: a /16 sweep is 65k sockets and users are effectively never on one.
     */
    private suspend fun scanSubnet(onFound: suspend (DiscoveredDevice) -> Unit) = coroutineScope {
        val prefix = localSubnetPrefix() ?: run {
            Log.d(TAG, "No IPv4 /24 to scan; skipping port scan")
            return@coroutineScope
        }

        val gate = Semaphore(config.scanConcurrency)
        (1..254).map { suffix ->
            launch {
                gate.withPermit {
                    val host = "$prefix.$suffix"
                    if (probe(host, config.adbPort)) {
                        onFound(
                            DiscoveredDevice(
                                host = host,
                                port = config.adbPort,
                                source = DiscoverySource.PORT_SCAN,
                            ),
                        )
                    }
                }
            }
        }.forEach { it.join() }
    }

    private suspend fun probe(host: String, port: Int): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), config.perHostTimeoutMs)
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }

    /** e.g. "192.168.1" for a phone at 192.168.1.34/24. Null when not on a suitable IPv4 network. */
    fun localSubnetPrefix(): String? =
        localIpv4Address()?.substringBeforeLast('.')

    /**
     * The phone's own IPv4 address on the active network. Public because the diagnostics report
     * needs it: "phone is on 192.168.8.x, TV is on 192.168.1.x" explains an entire failed evening.
     */
    fun localIpv4Address(): String? {
        val network = connectivityManager.activeNetwork ?: return null
        val properties = connectivityManager.getLinkProperties(network) ?: return null
        val candidate: LinkAddress = properties.linkAddresses.firstOrNull {
            it.address is Inet4Address && it.prefixLength >= 24 && !it.address.isLoopbackAddress
        } ?: return null
        return candidate.address.hostAddress
    }

    private companion object {
        const val TAG = "DeviceDiscovery"
        const val SERVICE_TYPE_ADB = "_adb._tcp."
        const val SERVICE_TYPE_ADB_TLS = "_adb-tls-connect._tcp."
    }
}
