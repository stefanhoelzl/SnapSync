@file:UseSerializers(
    AssetIdAsString::class,
    DeviceIdAsString::class,
    ResourceRoleAsString::class,
)

package app.snapsync.mock

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.device.DeviceId
import app.snapsync.format.ResourceRoleText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * A device's manifest as the backend mock holds it — the mock's own record of what a member declared to an event, and
 * of what an operator seeds for another member. The app never sees this type: it publishes the wire's `Manifest`, which
 * the mock's router reads into one. The field names are the wire's, so a mock state saved by an earlier build still
 * reads.
 */
@Serializable
class MockManifest(
    val deviceId: DeviceId,
    val assets: List<MockManifestAsset>,
    /** The manifest version the publish carried; `null` is the wire's "no version". */
    val version: Long? = null,
    /** Whether the member declared the share settled. */
    val final: Boolean = false,
)

/** One photo a [MockManifest] declares: its id, its capture text and its resources. */
@Serializable
class MockManifestAsset(
    val assetId: AssetId,
    val creationDate: String,
    val resources: List<MockManifestResource>,
)

/** One resource of a [MockManifestAsset]: its role, content type, storage key and file name. */
@Serializable
class MockManifestResource(
    val role: ResourceRole,
    val contentType: String,
    val key: String,
    val filename: String,
)

/** This manifest, declaring whether the member's share is settled. */
fun MockManifest.withFinal(final: Boolean): MockManifest = MockManifest(deviceId, assets, version, final)

private val mockManifestJson = Json { encodeDefaults = true }

/** This manifest as the mock's saved state spells it. */
fun MockManifest.encodeToJson(): String = mockManifestJson.encodeToString(MockManifest.serializer(), this)

/** The manifest [text] spells, as the mock's saved state holds it; throws on text that is none. */
fun mockManifestFromJson(text: String): MockManifest = mockManifestJson.decodeFromString(
    MockManifest.serializer(),
    text,
)

// The mock's saved state spells each id and role as the bare string the wire carries.
private abstract class AsString<T : Any>(
    name: String,
    private val wrap: (String) -> T?,
    private val unwrap: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(unwrap(value))
    override fun deserialize(decoder: Decoder): T =
        decoder.decodeString().let { wrap(it) ?: throw SerializationException("not a ${descriptor.serialName}: $it") }
}

private object DeviceIdAsString : AsString<DeviceId>("mock.DeviceId", DeviceId::parse, DeviceId::value)

private object AssetIdAsString : AsString<AssetId>("mock.AssetId", AssetId::parse, AssetId::value)

private object ResourceRoleAsString : AsString<ResourceRole>(
    "mock.ResourceRole",
    ResourceRoleText::parse,
    ResourceRoleText::of,
)
