package com.sizwe.tvremote.shortcuts

import android.util.Log
import com.sizwe.tvremote.core.RemoteTransport
import com.sizwe.tvremote.core.TransportCapability
import com.sizwe.tvremote.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Turns the [KnownApps] catalogue into shortcuts that work on *this* TV.
 *
 * The resolution step exists because package names vary by vendor and OEM skin. Rather than
 * shipping a guess, [refresh] asks the TV what it actually has installed (`pm list packages -3`)
 * and pins the first candidate that matches. The result is cached in [SettingsRepository] so the
 * shortcuts still render before the transport is up on the next launch.
 *
 * Falls back gracefully: with no ADB connection (e.g. running over Bluetooth, which cannot launch
 * apps at all) the cached resolution is used, and if there is none the shortcut row stays empty
 * rather than showing buttons that silently do nothing.
 */
class ShortcutRepository(
    private val settings: SettingsRepository,
) {

    /** Last known set of packages installed on the TV; empty until a refresh succeeds. */
    @Volatile
    var installedPackages: Set<String> = emptySet()
        private set

    /**
     * Re-queries the TV and re-resolves every catalogue entry.
     *
     * @return the shortcuts that are actually launchable, or an empty list if the transport cannot
     *   enumerate packages (Bluetooth) or the query failed.
     */
    suspend fun refresh(transport: RemoteTransport): List<AppShortcut> {
        if (!transport.supports(TransportCapability.PACKAGE_QUERY)) {
            Log.d(TAG, "${transport.type} cannot list packages; using cached resolution")
            return cached()
        }

        val packages = transport.listPackages().getOrElse {
            Log.w(TAG, "Package query failed; using cached resolution", it)
            return cached()
        }.toSet()

        if (packages.isEmpty()) return cached()
        installedPackages = packages

        val resolved = KnownApps.catalogue.mapNotNull { KnownApps.resolve(it, packages) }
        settings.setResolvedPackages(resolved.associate { it.id to it.packageName })
        Log.i(TAG, "Resolved ${resolved.size} of ${KnownApps.catalogue.size} shortcuts on this TV")
        return resolved
    }

    /** Shortcuts from the last successful resolution. */
    suspend fun cached(): List<AppShortcut> {
        val stored = settings.settings.first().resolvedShortcutPackages
        return KnownApps.catalogue.mapNotNull { candidate ->
            val packageName = stored[candidate.id] ?: return@mapNotNull null
            AppShortcut(id = candidate.id, label = candidate.label, packageName = packageName)
        }
    }

    /**
     * A shortcut for a package the catalogue does not know about, so the user can pin anything
     * they find on the TV. [label] defaults to the last path segment of the package name, which is
     * a decent guess ("com.plexapp.android" -> "android" is not, hence the caller usually supplies
     * one from the picker).
     */
    fun custom(packageName: String, label: String? = null, activity: String? = null): AppShortcut =
        AppShortcut(
            id = "custom:$packageName",
            label = label ?: packageName.substringAfterLast('.'),
            packageName = packageName,
            activity = activity,
            builtIn = false,
        )

    suspend fun pinnedOrDefault(all: List<AppShortcut>): List<AppShortcut> {
        val pinned = settings.settings.first().pinnedShortcutIds
        if (pinned.isEmpty()) return all.take(DEFAULT_PIN_COUNT)
        val byId = all.associateBy { it.id }
        return pinned.mapNotNull { byId[it] }
    }

    private companion object {
        const val TAG = "ShortcutRepository"
        const val DEFAULT_PIN_COUNT = 6
    }
}
