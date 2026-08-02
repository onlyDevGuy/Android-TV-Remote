package com.sizwe.tvremote.bluetooth

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings

/**
 * The HID report descriptor that makes this phone look like a TV remote to the TV's Bluetooth
 * stack.
 *
 * It declares two independent top-level collections, distinguished on the wire by report ID:
 *
 *  - **Report 1 - boot-style keyboard.** Carries the D-pad (arrow keys), OK (Enter), Back (Escape)
 *    and Menu. Android's built-in `Generic.kl` already maps these usages to `KEYCODE_DPAD_*`,
 *    `KEYCODE_BACK` and friends, so no TV-side configuration is needed.
 *  - **Report 2 - consumer control.** Carries volume, transport controls, power, Home and the
 *    colour keys. These have no keyboard equivalent, and the consumer page is what every real TV
 *    remote uses for them.
 *
 * Declaring a plain keyboard rather than something exotic is deliberate: OEM Bluetooth stacks
 * (Sony, TCL, Xiaomi all skin theirs differently) reliably accept a keyboard, whereas custom
 * report layouts are where pairing tends to fail silently.
 */
object HidDescriptors {

    const val REPORT_ID_KEYBOARD: Byte = 1
    const val REPORT_ID_CONSUMER: Byte = 2

    /** Keyboard reports are 8 bytes: modifier, reserved, then up to 6 concurrent key usages. */
    const val KEYBOARD_REPORT_SIZE = 8

    /** Consumer reports are a single 16-bit usage, little-endian. */
    const val CONSUMER_REPORT_SIZE = 2

    val REPORT_DESCRIPTOR: ByteArray = byteArrayOf(
        // ---- Keyboard (report ID 1) ----
        0x05.toByte(), 0x01.toByte(), //  Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(), //  Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(), //  Collection (Application)
        0x85.toByte(), 0x01.toByte(), //    Report ID (1)
        0x05.toByte(), 0x07.toByte(), //    Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0xE0.toByte(), //    Usage Minimum (Left Control)
        0x29.toByte(), 0xE7.toByte(), //    Usage Maximum (Right GUI)
        0x15.toByte(), 0x00.toByte(), //    Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), //    Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), //    Report Size (1)
        0x95.toByte(), 0x08.toByte(), //    Report Count (8)
        0x81.toByte(), 0x02.toByte(), //    Input (Data, Variable, Absolute) - modifier byte
        0x95.toByte(), 0x01.toByte(), //    Report Count (1)
        0x75.toByte(), 0x08.toByte(), //    Report Size (8)
        0x81.toByte(), 0x01.toByte(), //    Input (Constant) - reserved byte
        0x95.toByte(), 0x06.toByte(), //    Report Count (6)
        0x75.toByte(), 0x08.toByte(), //    Report Size (8)
        0x15.toByte(), 0x00.toByte(), //    Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(), //    Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(), //    Usage Page (Keyboard/Keypad)
        0x19.toByte(), 0x00.toByte(), //    Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(), //    Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(), //    Input (Data, Array) - the six key slots
        0xC0.toByte(), //  End Collection

        // ---- Consumer control (report ID 2) ----
        0x05.toByte(), 0x0C.toByte(), //  Usage Page (Consumer)
        0x09.toByte(), 0x01.toByte(), //  Usage (Consumer Control)
        0xA1.toByte(), 0x01.toByte(), //  Collection (Application)
        0x85.toByte(), 0x02.toByte(), //    Report ID (2)
        0x15.toByte(), 0x00.toByte(), //    Logical Minimum (0)
        0x26.toByte(), 0x9C.toByte(), 0x02.toByte(), // Logical Maximum (0x029C)
        0x19.toByte(), 0x00.toByte(), //    Usage Minimum (0)
        0x2A.toByte(), 0x9C.toByte(), 0x02.toByte(), // Usage Maximum (0x029C)
        0x75.toByte(), 0x10.toByte(), //    Report Size (16)
        0x95.toByte(), 0x01.toByte(), //    Report Count (1)
        0x81.toByte(), 0x00.toByte(), //    Input (Data, Array)
        0xC0.toByte(), //  End Collection
    )

    /**
     * What the TV shows in its Bluetooth device list. [BluetoothHidDevice.SUBCLASS1_KEYBOARD] is
     * what makes the TV treat us as a remote-capable input device rather than a generic peripheral.
     */
    val SDP_SETTINGS = BluetoothHidDeviceAppSdpSettings(
        /* name = */ "TV Remote",
        /* description = */ "Phone remote control",
        /* provider = */ "Sizwe",
        /* subclass = */ BluetoothHidDevice.SUBCLASS1_KEYBOARD,
        /* descriptors = */ REPORT_DESCRIPTOR,
    )

    /**
     * QoS for the interrupt channel. Button presses are tiny and bursty, so the token bucket is
     * sized for a handful of reports per second with a generous latency budget - tightening it
     * causes some stacks to reject the registration outright.
     */
    val QOS_SETTINGS = BluetoothHidDeviceAppQosSettings(
        /* serviceType = */ BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        /* tokenRate = */ 800,
        /* tokenBucketSize = */ 9,
        /* peakBandwidth = */ 0,
        /* latency = */ 11250,
        /* delayVariation = */ BluetoothHidDeviceAppQosSettings.MAX,
    )
}
