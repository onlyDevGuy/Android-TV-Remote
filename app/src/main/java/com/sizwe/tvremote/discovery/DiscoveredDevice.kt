package com.sizwe.tvremote.discovery

/** Where a candidate came from, so the UI can explain why it is on the list. */
enum class DiscoverySource {
    /** Advertised over mDNS by the TV itself. Highest confidence. */
    MDNS,

    /** Found by knocking on port 5555 across the local subnet. */
    PORT_SCAN,

    /** Typed in by the user, or remembered from a previous session. */
    MANUAL,
}

data class DiscoveredDevice(
    val host: String,
    val port: Int = 5555,
    val name: String? = null,
    val source: DiscoverySource,
    /** True when the service advertised is `_adb-tls-connect._tcp` (Android 11+ wireless debugging). */
    val requiresTls: Boolean = false,
) {
    val address: String get() = "$host:$port"
    val displayName: String get() = name ?: host
}
