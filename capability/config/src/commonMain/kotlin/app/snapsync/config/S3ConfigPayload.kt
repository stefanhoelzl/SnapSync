package app.snapsync.config

import kotlinx.serialization.Serializable

/**
 * The runtime-provisioned subset of an S3 destination carried by the `snapsync://` deeplink and
 * persisted in the Keychain: bucket, region, and credentials. The upload **host** is deliberately
 * NOT here — it is fixed at compile time by the extension's `BackgroundUploadURLBase` (a
 * user-configurable host is impossible with PhotoKit's background-upload API), so carrying it at
 * runtime would be redundant and could drift from the baked value. The consuming iOS composition
 * root combines this payload with the baked host into the provider's `S3Config`.
 *
 * This class is also the wire DTO: its property names are the exact JSON keys of the deeplink
 * payload. Not a `data class` — it holds a secret, so it must not leak via a synthesized
 * `toString`; use [sameAs] for field-wise equality.
 */
@Serializable
class S3ConfigPayload(
    val bucket: String,
    val region: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

/** Field-wise equality for the secret-bearing payload (not a data class). */
internal fun S3ConfigPayload.sameAs(other: S3ConfigPayload): Boolean =
    bucket == other.bucket &&
        region == other.region &&
        accessKeyId == other.accessKeyId &&
        secretAccessKey == other.secretAccessKey
