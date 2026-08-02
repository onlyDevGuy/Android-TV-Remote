package com.sizwe.tvremote.shortcuts

/**
 * A launchable target on the TV.
 *
 * [packageName] is filled in only once we know which candidate is actually installed; until then
 * the shortcut is a catalogue entry with several possible package names.
 */
data class AppShortcut(
    val id: String,
    val label: String,
    val packageName: String,
    /** Optional explicit component, for apps whose launcher intent is not resolvable. */
    val activity: String? = null,
    val builtIn: Boolean = true,
)

/**
 * Catalogue of the apps people actually put on a TV remote, each with the package names it ships
 * under.
 *
 * The list per app is not redundancy for its own sake: the same service uses different packages on
 * different boxes. Netflix is `com.netflix.ninja` on Android TV but `com.netflix.mediaclient` on
 * tablet-flavoured TV boxes; YouTube ships as `com.google.android.youtube.tv` on Google TV and
 * `com.google.android.youtube.tvunplugged` for YouTube TV. Guessing wrong is the main source of
 * "the shortcut does nothing" bugs, so [ShortcutRepository] resolves candidates against
 * `pm list packages` on the actual device instead of hardcoding one.
 */
object KnownApps {

    data class Candidate(
        val id: String,
        val label: String,
        /** Ordered by likelihood; the first one installed on the TV wins. */
        val packages: List<String>,
    )

    val catalogue: List<Candidate> = listOf(
        Candidate(
            id = "netflix",
            label = "Netflix",
            packages = listOf("com.netflix.ninja", "com.netflix.mediaclient"),
        ),
        Candidate(
            id = "youtube",
            label = "YouTube",
            packages = listOf(
                "com.google.android.youtube.tv",
                "com.google.android.youtube.tvkids",
                "com.google.android.youtube",
            ),
        ),
        Candidate(
            id = "primevideo",
            label = "Prime Video",
            packages = listOf(
                "com.amazon.amazonvideo.livingroom",
                "com.amazon.avod.thirdpartyclient",
            ),
        ),
        Candidate(
            id = "disneyplus",
            label = "Disney+",
            packages = listOf("com.disney.disneyplus", "com.disney.disneyplus.tv"),
        ),
        Candidate(
            id = "showmax",
            label = "Showmax",
            packages = listOf("com.showmax.app", "com.showmax.androidtv"),
        ),
        Candidate(
            id = "dstv",
            label = "DStv",
            packages = listOf("com.dstvdm.dstvnow.androidtv", "com.multichoice.dstv"),
        ),
        Candidate(
            id = "spotify",
            label = "Spotify",
            packages = listOf("com.spotify.tv.android", "com.spotify.music"),
        ),
        Candidate(
            id = "plex",
            label = "Plex",
            packages = listOf("com.plexapp.android", "com.plexapp.mediaserver.smb"),
        ),
        Candidate(
            id = "kodi",
            label = "Kodi",
            packages = listOf("org.xbmc.kodi"),
        ),
        Candidate(
            id = "vlc",
            label = "VLC",
            packages = listOf("org.videolan.vlc"),
        ),
        Candidate(
            id = "settings",
            label = "TV Settings",
            packages = listOf("com.android.tv.settings", "com.android.settings"),
        ),
    )

    /** Picks the first candidate package that appears in [installedPackages]. */
    fun resolve(candidate: Candidate, installedPackages: Set<String>): AppShortcut? {
        val match = candidate.packages.firstOrNull { it in installedPackages } ?: return null
        return AppShortcut(id = candidate.id, label = candidate.label, packageName = match)
    }
}
