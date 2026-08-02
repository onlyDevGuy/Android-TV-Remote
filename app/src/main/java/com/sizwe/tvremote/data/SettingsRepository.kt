package com.sizwe.tvremote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sizwe.tvremote.adb.AdbClient
import com.sizwe.tvremote.core.TransportType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tv_remote")

/**
 * Everything the app should remember between launches: which TV, over which transport, and the
 * package names we resolved for that TV's shortcuts.
 *
 * Small and flat on purpose - this is device-local UI state, not a data model. (No server-side
 * store is needed anywhere in this app; see the README note on MongoDB.)
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.dataStore

    data class Settings(
        val lastHost: String? = null,
        val lastPort: Int = AdbClient.DEFAULT_PORT,
        val lastDeviceLabel: String? = null,
        val preferredTransport: TransportType = TransportType.ADB,
        val bluetoothMac: String? = null,
        val autoConnectOnLaunch: Boolean = true,
        val autoFallbackTransport: Boolean = true,
        val hapticsEnabled: Boolean = true,
        /** Shortcut ids the user pinned to the remote, in display order. */
        val pinnedShortcutIds: List<String> = emptyList(),
        /** `id=packageName` pairs resolved against the connected TV. */
        val resolvedShortcutPackages: Map<String, String> = emptyMap(),
    )

    val settings: Flow<Settings> = store.data.map { prefs ->
        Settings(
            lastHost = prefs[KEY_HOST],
            lastPort = prefs[KEY_PORT] ?: AdbClient.DEFAULT_PORT,
            lastDeviceLabel = prefs[KEY_DEVICE_LABEL],
            preferredTransport = prefs[KEY_TRANSPORT]
                ?.let { runCatching { TransportType.valueOf(it) }.getOrNull() }
                ?: TransportType.ADB,
            bluetoothMac = prefs[KEY_BT_MAC],
            autoConnectOnLaunch = prefs[KEY_AUTO_CONNECT] ?: true,
            autoFallbackTransport = prefs[KEY_AUTO_FALLBACK] ?: true,
            hapticsEnabled = prefs[KEY_HAPTICS] ?: true,
            pinnedShortcutIds = prefs[KEY_PINNED]?.split(",")?.filter { it.isNotBlank() }.orEmpty(),
            resolvedShortcutPackages = prefs[KEY_RESOLVED_PACKAGES]
                .orEmpty()
                .mapNotNull { entry ->
                    val parts = entry.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap(),
        )
    }

    suspend fun setLastDevice(host: String, port: Int, label: String?) = store.edit { prefs ->
        prefs[KEY_HOST] = host
        prefs[KEY_PORT] = port
        if (label != null) prefs[KEY_DEVICE_LABEL] = label
    }

    suspend fun setPreferredTransport(type: TransportType) = store.edit { prefs ->
        prefs[KEY_TRANSPORT] = type.name
    }

    suspend fun setBluetoothMac(mac: String?) = store.edit { prefs ->
        if (mac == null) prefs.remove(KEY_BT_MAC) else prefs[KEY_BT_MAC] = mac
    }

    suspend fun setAutoConnect(enabled: Boolean) = store.edit { it[KEY_AUTO_CONNECT] = enabled }

    suspend fun setAutoFallback(enabled: Boolean) = store.edit { it[KEY_AUTO_FALLBACK] = enabled }

    suspend fun setHaptics(enabled: Boolean) = store.edit { it[KEY_HAPTICS] = enabled }

    // Joined rather than a Set: the pin order is what the user arranged, and a Set loses it.
    suspend fun setPinnedShortcuts(ids: List<String>) = store.edit { prefs ->
        prefs[KEY_PINNED] = ids.joinToString(",")
    }

    suspend fun setResolvedPackages(resolved: Map<String, String>) = store.edit { prefs ->
        prefs[KEY_RESOLVED_PACKAGES] = resolved.map { (id, pkg) -> "$id=$pkg" }.toSet()
    }

    suspend fun clearDevice() = store.edit { prefs ->
        prefs.remove(KEY_HOST)
        prefs.remove(KEY_PORT)
        prefs.remove(KEY_DEVICE_LABEL)
        prefs.remove(KEY_RESOLVED_PACKAGES)
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("last_host")
        val KEY_PORT = intPreferencesKey("last_port")
        val KEY_DEVICE_LABEL = stringPreferencesKey("last_device_label")
        val KEY_TRANSPORT = stringPreferencesKey("preferred_transport")
        val KEY_BT_MAC = stringPreferencesKey("bluetooth_mac")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEY_AUTO_FALLBACK = booleanPreferencesKey("auto_fallback")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
        val KEY_PINNED = stringPreferencesKey("pinned_shortcuts")
        val KEY_RESOLVED_PACKAGES = stringSetPreferencesKey("resolved_packages")
    }
}
