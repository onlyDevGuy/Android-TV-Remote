package com.sizwe.tvremote.bluetooth

import com.sizwe.tvremote.core.RemoteKey

/**
 * [RemoteKey] -> HID usage, the Bluetooth counterpart of
 * [com.sizwe.tvremote.adb.AdbKeyMap].
 *
 * Which page a button lands on is not arbitrary. Android's input stack translates HID usages into
 * the same `KEYCODE_*` values the ADB transport sends directly, so picking the usage that Android
 * already maps is what makes the two transports feel identical:
 *
 *  - Arrows on the keyboard page become `KEYCODE_DPAD_*`.
 *  - Escape becomes `KEYCODE_BACK` (this is why Back is a keyboard key and not consumer AC Back -
 *    the keyboard route is honoured by every Android build, the consumer one is not).
 *  - Consumer AC Home becomes `KEYCODE_HOME`; there is no keyboard equivalent.
 *  - Volume, transport and colour keys only exist on the consumer page.
 */
sealed interface HidUsage {

    /** A key on usage page 0x07, optionally with modifier bits set. */
    data class Keyboard(val usage: Byte, val modifiers: Byte = 0) : HidUsage

    /** A 16-bit usage on the consumer page (0x0C). */
    data class Consumer(val usage: Int) : HidUsage

    /** No sensible HID equivalent; the UI greys the button out on this transport. */
    data object Unsupported : HidUsage
}

object HidKeyMap {

    // Usage page 0x07 (keyboard)
    private const val KB_ENTER: Byte = 0x28
    private const val KB_ESCAPE: Byte = 0x29
    private const val KB_BACKSPACE: Byte = 0x2A
    private const val KB_RIGHT: Byte = 0x4F
    private const val KB_LEFT: Byte = 0x50
    private const val KB_DOWN: Byte = 0x51
    private const val KB_UP: Byte = 0x52
    private const val KB_APPLICATION: Byte = 0x65 // the "menu" key

    // Usage page 0x0C (consumer)
    private const val CC_POWER = 0x0030
    private const val CC_SLEEP = 0x0032
    private const val CC_MENU = 0x0040
    private const val CC_INFO = 0x0060
    private const val CC_CAPTIONS = 0x0061
    private const val CC_RED = 0x0069
    private const val CC_GREEN = 0x006A
    private const val CC_BLUE = 0x006B
    private const val CC_YELLOW = 0x006C
    private const val CC_MEDIA_SELECT_TV = 0x0089
    private const val CC_PROGRAM_GUIDE = 0x008D
    private const val CC_CHANNEL_UP = 0x009C
    private const val CC_CHANNEL_DOWN = 0x009D
    private const val CC_PLAY = 0x00B0
    private const val CC_PAUSE = 0x00B1
    private const val CC_RECORD = 0x00B2
    private const val CC_FAST_FORWARD = 0x00B3
    private const val CC_REWIND = 0x00B4
    private const val CC_NEXT = 0x00B5
    private const val CC_PREVIOUS = 0x00B6
    private const val CC_STOP = 0x00B7
    private const val CC_PLAY_PAUSE = 0x00CD
    private const val CC_MUTE = 0x00E2
    private const val CC_VOLUME_UP = 0x00E9
    private const val CC_VOLUME_DOWN = 0x00EA
    private const val CC_AC_SEARCH = 0x0221
    private const val CC_AC_HOME = 0x0223

    private val usages: Map<RemoteKey, HidUsage> = mapOf(
        RemoteKey.DPAD_UP to HidUsage.Keyboard(KB_UP),
        RemoteKey.DPAD_DOWN to HidUsage.Keyboard(KB_DOWN),
        RemoteKey.DPAD_LEFT to HidUsage.Keyboard(KB_LEFT),
        RemoteKey.DPAD_RIGHT to HidUsage.Keyboard(KB_RIGHT),
        RemoteKey.DPAD_CENTER to HidUsage.Keyboard(KB_ENTER),

        RemoteKey.BACK to HidUsage.Keyboard(KB_ESCAPE),
        RemoteKey.HOME to HidUsage.Consumer(CC_AC_HOME),
        RemoteKey.MENU to HidUsage.Keyboard(KB_APPLICATION),
        // No HID usage reaches Android's notification shade; ADB-only.
        RemoteKey.NOTIFICATION to HidUsage.Unsupported,

        RemoteKey.POWER to HidUsage.Consumer(CC_POWER),
        RemoteKey.SLEEP to HidUsage.Consumer(CC_SLEEP),
        // Waking a TV over HID is done by any keypress, not a dedicated usage.
        RemoteKey.WAKEUP to HidUsage.Consumer(CC_POWER),

        RemoteKey.VOLUME_UP to HidUsage.Consumer(CC_VOLUME_UP),
        RemoteKey.VOLUME_DOWN to HidUsage.Consumer(CC_VOLUME_DOWN),
        RemoteKey.VOLUME_MUTE to HidUsage.Consumer(CC_MUTE),

        RemoteKey.MEDIA_PLAY_PAUSE to HidUsage.Consumer(CC_PLAY_PAUSE),
        RemoteKey.MEDIA_PLAY to HidUsage.Consumer(CC_PLAY),
        RemoteKey.MEDIA_PAUSE to HidUsage.Consumer(CC_PAUSE),
        RemoteKey.MEDIA_STOP to HidUsage.Consumer(CC_STOP),
        RemoteKey.MEDIA_NEXT to HidUsage.Consumer(CC_NEXT),
        RemoteKey.MEDIA_PREVIOUS to HidUsage.Consumer(CC_PREVIOUS),
        RemoteKey.MEDIA_FAST_FORWARD to HidUsage.Consumer(CC_FAST_FORWARD),
        RemoteKey.MEDIA_REWIND to HidUsage.Consumer(CC_REWIND),
        RemoteKey.MEDIA_RECORD to HidUsage.Consumer(CC_RECORD),

        RemoteKey.CHANNEL_UP to HidUsage.Consumer(CC_CHANNEL_UP),
        RemoteKey.CHANNEL_DOWN to HidUsage.Consumer(CC_CHANNEL_DOWN),
        RemoteKey.TV_INPUT to HidUsage.Consumer(CC_MEDIA_SELECT_TV),
        RemoteKey.GUIDE to HidUsage.Consumer(CC_PROGRAM_GUIDE),
        RemoteKey.INFO to HidUsage.Consumer(CC_INFO),
        RemoteKey.CAPTIONS to HidUsage.Consumer(CC_CAPTIONS),

        RemoteKey.ENTER to HidUsage.Keyboard(KB_ENTER),
        RemoteKey.DELETE to HidUsage.Keyboard(KB_BACKSPACE),
        RemoteKey.SEARCH to HidUsage.Consumer(CC_AC_SEARCH),

        RemoteKey.PROG_RED to HidUsage.Consumer(CC_RED),
        RemoteKey.PROG_GREEN to HidUsage.Consumer(CC_GREEN),
        RemoteKey.PROG_YELLOW to HidUsage.Consumer(CC_YELLOW),
        RemoteKey.PROG_BLUE to HidUsage.Consumer(CC_BLUE),
    )

    /** Consumer "Menu", kept for TVs whose launcher ignores the keyboard Application key. */
    val alternateMenuUsage = HidUsage.Consumer(CC_MENU)

    fun usage(key: RemoteKey): HidUsage = usages[key] ?: HidUsage.Unsupported

    fun isSupported(key: RemoteKey): Boolean = usage(key) !is HidUsage.Unsupported

    val unsupportedKeys: Set<RemoteKey>
        get() = RemoteKey.entries.filterTo(mutableSetOf()) { !isSupported(it) }
}
