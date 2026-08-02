package com.sizwe.tvremote

import com.sizwe.tvremote.bluetooth.HidKeyMap
import com.sizwe.tvremote.bluetooth.HidUsage
import com.sizwe.tvremote.core.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5's acceptance criterion: the two transports must feel identical. That only holds if both
 * keymaps cover the same vocabulary, so this test fails the build when a [RemoteKey] is added
 * without wiring up both sides.
 *
 * [HidKeyMap] is allowed a documented exception list - a handful of Android concepts genuinely
 * have no HID usage - but every entry has to be deliberate rather than forgotten.
 */
class KeyMapParityTest {

    /** Keys with no Bluetooth equivalent. Adding to this list should be a considered decision. */
    private val expectedHidGaps = setOf(RemoteKey.NOTIFICATION)

    @Test
    fun `every remote key has a bluetooth usage or a documented gap`() {
        val gaps = RemoteKey.entries.filter { HidKeyMap.usage(it) is HidUsage.Unsupported }.toSet()
        assertEquals(
            "Bluetooth keymap gaps changed. Add the usage, or add the key to expectedHidGaps.",
            expectedHidGaps,
            gaps,
        )
    }

    @Test
    fun `dpad and volume map to distinct usages`() {
        val navigation = listOf(
            RemoteKey.DPAD_UP,
            RemoteKey.DPAD_DOWN,
            RemoteKey.DPAD_LEFT,
            RemoteKey.DPAD_RIGHT,
            RemoteKey.DPAD_CENTER,
            RemoteKey.VOLUME_UP,
            RemoteKey.VOLUME_DOWN,
        )
        val usages = navigation.map { HidKeyMap.usage(it) }
        assertEquals(
            "Two navigation keys collided on the same HID usage",
            navigation.size,
            usages.toSet().size,
        )
    }

    @Test
    fun `repeatable keys are the ones a user holds`() {
        assertTrue(RemoteKey.DPAD_DOWN.isRepeatable)
        assertTrue(RemoteKey.VOLUME_UP.isRepeatable)
        assertTrue("OK should fire once per tap", !RemoteKey.DPAD_CENTER.isRepeatable)
        assertTrue("Power must never auto-repeat", !RemoteKey.POWER.isRepeatable)
    }
}
