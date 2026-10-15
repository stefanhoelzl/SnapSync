package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.TimestampText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The domain's values as the **bare strings** every JSON format carries — the persisted `EventConfig`, the device
 * manifest, the backend's replies. Written out rather than relying on the plugin's value-class inlining, because those
 * formats depend on staying bare strings: a silent switch to an object wrapper would strand every joined device's
 * membership. The domain holds no format (`docs/patterns.md`, "The domain"), so a format opts in per file with
 * `@file:UseSerializers(…)`, and a type it carries without a serializer is a compile error.
 */
internal abstract class AsString<T>(
    name: String,
    private val wrap: (String) -> T,
    private val unwrap: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(unwrap(value))
    override fun deserialize(decoder: Decoder): T = wrap(decoder.decodeString())
}

internal object EventIdAsString : AsString<EventId>(
    "app.snapsync.domain.event.EventId",
    { EventId.parse(it) ?: throw SerializationException("not an event id") },
    { it.value },
)

internal object DeviceIdAsString : AsString<DeviceId>(
    "app.snapsync.domain.device.DeviceId",
    { DeviceId.parse(it) ?: throw SerializationException("not a device id") },
    { it.value },
)

internal object AssetIdAsString : AsString<AssetId>("app.snapsync.domain.asset.AssetId", ::AssetId, { it.value })

internal object CaptureCutoffAsString : AsString<CaptureCutoff>(
    "app.snapsync.domain.sharing.CaptureCutoff",
    { CaptureCutoff(TimestampText.parse(it)) },
    { TimestampText.format(it.at) },
)

internal object CaptureCeilingAsString : AsString<CaptureCeiling>(
    "app.snapsync.domain.sharing.CaptureCeiling",
    { CaptureCeiling(TimestampText.parse(it)) },
    { TimestampText.format(it.at) },
)

internal object EventStartAsString : AsString<EventStart>(
    "app.snapsync.domain.event.EventStart",
    { EventStart(TimestampText.parse(it)) },
    { TimestampText.format(it.at) },
)

internal object EventEndAsString : AsString<EventEnd>(
    "app.snapsync.domain.event.EventEnd",
    { EventEnd(TimestampText.parse(it)) },
    { TimestampText.format(it.at) },
)

internal object OpenUntilAsString : AsString<OpenUntil>(
    "app.snapsync.domain.event.OpenUntil",
    { OpenUntil(TimestampText.parse(it)) },
    { TimestampText.format(it.at) },
)

internal object DeletesAtAsString : AsString<DeletesAt>(
    "app.snapsync.domain.event.DeletesAt",
    { DeletesAt(TimestampText.parse(it)) },
    { TimestampText.format(it.at) },
)
