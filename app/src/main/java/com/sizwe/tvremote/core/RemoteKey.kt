package com.sizwe.tvremote.core

/**
 * Transport-independent vocabulary of remote buttons.
 *
 * Every transport maps these onto its own wire representation:
 *  - [com.sizwe.tvremote.adb.AdbKeyMap] -> Android `KEYCODE_*` integers for `input keyevent`
 *  - [com.sizwe.tvremote.bluetooth.HidKeyMap] -> HID usage IDs on the keyboard/consumer reports
 *
 * Adding a button means adding it here once, then filling in both maps. The maps are covered by
 * a unit test that fails if either one drops a key, so the two transports cannot drift apart.
 */
enum class RemoteKey {
    // D-pad
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    DPAD_CENTER,

    // Navigation
    BACK,
    HOME,
    MENU,
    NOTIFICATION,

    // Power
    POWER,
    SLEEP,
    WAKEUP,

    // Volume
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MUTE,

    // Media transport
    MEDIA_PLAY_PAUSE,
    MEDIA_PLAY,
    MEDIA_PAUSE,
    MEDIA_STOP,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_FAST_FORWARD,
    MEDIA_REWIND,
    MEDIA_RECORD,

    // Channels / TV
    CHANNEL_UP,
    CHANNEL_DOWN,
    TV_INPUT,
    GUIDE,
    INFO,
    CAPTIONS,

    // Text editing helpers (soft keyboard overlay)
    ENTER,
    DELETE,
    SEARCH,

    // Colour keys, present on most TV remotes
    PROG_RED,
    PROG_GREEN,
    PROG_YELLOW,
    PROG_BLUE,
    ;

    /** Keys that make sense to auto-repeat while the user holds the button down. */
    val isRepeatable: Boolean
        get() = this in REPEATABLE

    companion object {
        private val REPEATABLE = setOf(
            DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
            VOLUME_UP, VOLUME_DOWN,
            CHANNEL_UP, CHANNEL_DOWN,
            MEDIA_FAST_FORWARD, MEDIA_REWIND,
            DELETE,
        )
    }
}
