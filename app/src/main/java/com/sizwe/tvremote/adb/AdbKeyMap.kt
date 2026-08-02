package com.sizwe.tvremote.adb

import android.view.KeyEvent
import com.sizwe.tvremote.core.RemoteKey

/**
 * [RemoteKey] -> Android keycode, as consumed by `input keyevent <code>` on the TV.
 *
 * Numeric codes are stable public API, so sending them by number works even on OEM skins that
 * relabel the buttons. Anything missing here is a compile-time hole caught by `AdbKeyMapTest`.
 */
object AdbKeyMap {

    private val codes: Map<RemoteKey, Int> = mapOf(
        RemoteKey.DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
        RemoteKey.DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        RemoteKey.DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        RemoteKey.DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        RemoteKey.DPAD_CENTER to KeyEvent.KEYCODE_DPAD_CENTER,

        RemoteKey.BACK to KeyEvent.KEYCODE_BACK,
        RemoteKey.HOME to KeyEvent.KEYCODE_HOME,
        RemoteKey.MENU to KeyEvent.KEYCODE_MENU,
        RemoteKey.NOTIFICATION to KeyEvent.KEYCODE_NOTIFICATION,

        RemoteKey.POWER to KeyEvent.KEYCODE_POWER,
        RemoteKey.SLEEP to KeyEvent.KEYCODE_SLEEP,
        RemoteKey.WAKEUP to KeyEvent.KEYCODE_WAKEUP,

        RemoteKey.VOLUME_UP to KeyEvent.KEYCODE_VOLUME_UP,
        RemoteKey.VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_DOWN,
        RemoteKey.VOLUME_MUTE to KeyEvent.KEYCODE_VOLUME_MUTE,

        RemoteKey.MEDIA_PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        RemoteKey.MEDIA_PLAY to KeyEvent.KEYCODE_MEDIA_PLAY,
        RemoteKey.MEDIA_PAUSE to KeyEvent.KEYCODE_MEDIA_PAUSE,
        RemoteKey.MEDIA_STOP to KeyEvent.KEYCODE_MEDIA_STOP,
        RemoteKey.MEDIA_NEXT to KeyEvent.KEYCODE_MEDIA_NEXT,
        RemoteKey.MEDIA_PREVIOUS to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        RemoteKey.MEDIA_FAST_FORWARD to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        RemoteKey.MEDIA_REWIND to KeyEvent.KEYCODE_MEDIA_REWIND,
        RemoteKey.MEDIA_RECORD to KeyEvent.KEYCODE_MEDIA_RECORD,

        RemoteKey.CHANNEL_UP to KeyEvent.KEYCODE_CHANNEL_UP,
        RemoteKey.CHANNEL_DOWN to KeyEvent.KEYCODE_CHANNEL_DOWN,
        RemoteKey.TV_INPUT to KeyEvent.KEYCODE_TV_INPUT,
        RemoteKey.GUIDE to KeyEvent.KEYCODE_GUIDE,
        RemoteKey.INFO to KeyEvent.KEYCODE_INFO,
        RemoteKey.CAPTIONS to KeyEvent.KEYCODE_CAPTIONS,

        RemoteKey.ENTER to KeyEvent.KEYCODE_ENTER,
        RemoteKey.DELETE to KeyEvent.KEYCODE_DEL,
        RemoteKey.SEARCH to KeyEvent.KEYCODE_SEARCH,

        RemoteKey.PROG_RED to KeyEvent.KEYCODE_PROG_RED,
        RemoteKey.PROG_GREEN to KeyEvent.KEYCODE_PROG_GREEN,
        RemoteKey.PROG_YELLOW to KeyEvent.KEYCODE_PROG_YELLOW,
        RemoteKey.PROG_BLUE to KeyEvent.KEYCODE_PROG_BLUE,
    )

    fun keyCode(key: RemoteKey): Int =
        codes[key] ?: error("No ADB keycode mapped for $key")

    fun contains(key: RemoteKey): Boolean = key in codes

    /** The shell command for a single press. */
    fun keyEventCommand(key: RemoteKey): String = "input keyevent ${keyCode(key)}"

    /** Long-press variant; some TV launchers use it for the assistant or the app switcher. */
    fun longPressCommand(key: RemoteKey): String = "input keyevent --longpress ${keyCode(key)}"

    /**
     * Text entry. `input text` treats a space as an argument separator and chokes on shell
     * metacharacters, so escape both.
     */
    fun textCommand(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace(" ", "%s")
        return "input text '$escaped'"
    }
}
