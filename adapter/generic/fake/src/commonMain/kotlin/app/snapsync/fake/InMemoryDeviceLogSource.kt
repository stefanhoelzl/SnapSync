package app.snapsync.fake

import app.snapsync.ports.DeviceLogSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [DeviceLogSource]: hands back the tail of constructor-supplied log text, with
 * the port's own bound and line-alignment applied — so a test that over-asks gets exactly what a
 * device would give it, and a budget bug fails here rather than only on a phone.
 *
 * A process whose text is absent reads as an unreadable log (`null`), which is the honest model of a
 * device where the extension has never run.
 */
class InMemoryDeviceLogSource(
    private val logs: MutableStateFlow<Map<DeviceLogSource.Process, String>>,
) : DeviceLogSource {

    constructor(logs: Map<DeviceLogSource.Process, String> = emptyMap()) : this(MutableStateFlow(logs))

    override suspend fun tail(process: DeviceLogSource.Process, maxBytes: Int): String? {
        val text = logs.value[process] ?: return null
        if (maxBytes <= 0) return null
        val bytes = text.encodeToByteArray()
        if (bytes.size <= maxBytes) return text
        val tail = bytes.decodeToString(bytes.size - maxBytes, bytes.size, throwOnInvalidSequence = false)
        val firstBreak = tail.indexOf('\n')
        return if (firstBreak < 0) tail else tail.substring(firstBreak + 1)
    }
}
