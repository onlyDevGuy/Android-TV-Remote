package com.sizwe.tvremote.bluetooth

/**
 * Builds the report payloads described by [HidDescriptors.REPORT_DESCRIPTOR].
 *
 * A press is always two reports: the usage, then an all-zero report to release it. Without the
 * release the TV sees the key as held down and starts auto-repeating - the classic "one tap
 * scrolled the whole menu" bug.
 */
object HidReport {

    /** `[modifiers, reserved, key1..key6]`. Only one key at a time is ever needed here. */
    fun keyboardPress(usage: Byte, modifiers: Byte = 0): ByteArray {
        val report = ByteArray(HidDescriptors.KEYBOARD_REPORT_SIZE)
        report[0] = modifiers
        report[2] = usage
        return report
    }

    fun keyboardRelease(): ByteArray = ByteArray(HidDescriptors.KEYBOARD_REPORT_SIZE)

    /** One 16-bit consumer usage, little-endian. */
    fun consumerPress(usage: Int): ByteArray = byteArrayOf(
        (usage and 0xFF).toByte(),
        ((usage shr 8) and 0xFF).toByte(),
    )

    fun consumerRelease(): ByteArray = ByteArray(HidDescriptors.CONSUMER_REPORT_SIZE)

    fun press(usage: HidUsage): Pair<Byte, ByteArray>? = when (usage) {
        is HidUsage.Keyboard ->
            HidDescriptors.REPORT_ID_KEYBOARD to keyboardPress(usage.usage, usage.modifiers)

        is HidUsage.Consumer ->
            HidDescriptors.REPORT_ID_CONSUMER to consumerPress(usage.usage)

        HidUsage.Unsupported -> null
    }

    fun release(usage: HidUsage): Pair<Byte, ByteArray>? = when (usage) {
        is HidUsage.Keyboard -> HidDescriptors.REPORT_ID_KEYBOARD to keyboardRelease()
        is HidUsage.Consumer -> HidDescriptors.REPORT_ID_CONSUMER to consumerRelease()
        HidUsage.Unsupported -> null
    }
}
