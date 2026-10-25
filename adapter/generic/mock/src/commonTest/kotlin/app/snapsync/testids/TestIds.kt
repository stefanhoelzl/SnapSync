package app.snapsync.testids

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId

// Fixture ids: a test names an event or a device for what it is ("E1", "other device"), and the domain accepts only a
// canonical UUID. A name that is already one is kept as it is; any other is spelled as a fixed UUID derived from it —
// its first twelve bytes in hex, then a hash of the whole — so the same name is the same id everywhere in the run, two
// names are two ids, and ids sort as their names do.

/** The event id fixture [name] stands for. */
internal fun testEventId(name: String): EventId = EventId.parse(name) ?: EventId.parse(uuidOf(name))!!

/** The device id fixture [name] stands for. */
internal fun testDeviceId(name: String): DeviceId = DeviceId.parse(name) ?: DeviceId.parse(uuidOf(name))!!

/** The asset id [text] spells — a fixture's own, so a non-canonical one fails the test that wrote it. */
internal fun testAssetId(
    text: String,
): AssetId = requireNotNull(AssetId.parse(text)) { "not a canonical asset id: '$text'" }

private const val SHAPE = "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
private const val HEAD_BYTES = 12
private const val HEX = 16
private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val BYTE = 0xff

private fun uuidOf(name: String): String {
    val bytes = name.encodeToByteArray()
    var hash = FNV_OFFSET
    for (byte in bytes) hash = (hash xor (byte.toLong() and BYTE.toLong())) * FNV_PRIME
    val head = bytes.take(HEAD_BYTES).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }
    val digits = (head.padEnd(HEAD_BYTES * 2, '0') + hash.toULong().toString(HEX).padStart(HEX, '0')).iterator()
    return SHAPE.map { if (it == 'x') digits.next() else it }.joinToString("")
}
