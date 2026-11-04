package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.ResourceRoleText
import app.snapsync.format.UploadKeyText

// A platform resource-type value is read by the platform, in `:adapter:ios:ext-safe`'s
// `PhotoKitResourceKind.kt`, and what each kind is to the upload is the gallery services'. It used to sit here as a `when` over the raw
// `PHAssetResourceType` integers — an ABI table written in bare literals, which no import- or
// token-based gate can see and which a second platform's integers would silently collide with. The
// adapter now reports the role itself, which is the platform-independent fact (spec
// `docs/architecture.md`, "Ports are the I/O boundary named for the need").

/**
 * An asset resource's key / object name as the old zones spell it — the format's one layout (`UploadKeyText`, over the
 * domain's [UploadKey]), so the key the downloads and the mocks name is byte-identical to the one an upload lands
 * under. MIGRATING: its callers (the downloads' jobs, the mocks) move onto [UploadKey] with their concepts (phase 4.5).
 */
fun uploadKey(assetId: AssetId, role: ResourceRole, originalFilename: String): String =
    UploadKeyText.of(UploadKey.of(assetId, role, originalFilename))

/**
 * Recover the [ResourceRole] from an upload-key [filename] (`"<assetId>-<role>.<ext>"`): the role token
 * is the segment after the **last** `-` and before the `.` (`primary`/`live`; the role token carries
 * no `-`, though an `assetId` may). Defaults to [ResourceRole.PRIMARY] for an unrecognised token (never
 * produced by [uploadKey]).
 */
fun roleFromUploadKey(filename: String): ResourceRole = ResourceRoleText.ofKey(filename)

/**
 * Recover the `assetId` from an upload-key [filename] (`"<assetId>-<role>.<ext>"`): drop the extension,
 * then take everything before the **final** `-` (the role token `primary`/`live` carries no `-`, though
 * an `assetId` may). The exact inverse of [uploadKey] — `assetIdFromUploadKey(uploadKey(id, role, name))
 * == id` — and the single shared implementation of that parse, so every site holding only a key (the
 * upload-job reconstruction, the discovery's key-to-asset lookup) recovers the same identity (the parse
 * is load-bearing at the record path). The join-time load is not one of them: it seeds the `assetId`
 * the backend's listing reports. Kept here next to [uploadKey], never duplicated per-consumer.
 */
fun assetIdFromUploadKey(filename: String): AssetId =
    requireNotNull(AssetId.parse(filename.substringBeforeLast('.').substringBeforeLast('-'))) {
        "not a canonical asset id"
    }
