package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import kotlin.jvm.JvmInline

/**
 * The **manifest version** a device's publishes are ordered by: the upload ledger advances it with every change to
 * what the manifest declares, and an upload pass reads it first, so every change its projection misses carries a
 * higher one. The backend stores a publish only when it is not older than the one it holds, so two processes'
 * publishes crossing in the network never leave it a snapshot behind (decision record `changes/manifest-versions`).
 */
@JvmInline
value class ManifestVersion(val value: Long)

/**
 * **What this device declares to an event**: every photo it INTENDS to provide — each
 * row the membership's one policy admits, whatever its upload state, not only the ones whose bytes landed — with the
 * [version] it was projected under and what it says of the share ([declaration]).
 *
 * Intent, not completion: the backend serves a photo only once every role it declares has landed, which is how it
 * tells "this photo is coming" from "this photo does not exist" — listing only landed resources once offered a
 * half-uploaded Live Photo as a complete still. Deletion needs no second structure: a walk that no longer finds an
 * in-range photo deletes its rows, so it leaves the projection.
 *
 * Its assets are ordered by id and their resources by role and extension, so the same rows always make the same
 * manifest, and a record of it compares equal.
 */
class DeviceManifest private constructor(
    val device: DeviceId,
    val assets: List<ManifestAsset>,
    val version: ManifestVersion,
    val declaration: ShareDeclaration,
) {
    companion object {
        /**
         * [device]'s manifest of [rows] — the ones whose asset the policy [admitted] — under [version], declaring
         * [declaration]. Admission is the policy's (`SelectionPolicy.admitRecorded`), never judged here.
         */
        fun of(
            device: DeviceId,
            rows: List<UploadRow>,
            admitted: List<AdmittedRow>,
            version: ManifestVersion,
            declaration: ShareDeclaration,
        ): DeviceManifest {
            val shared = admitted.mapTo(HashSet()) { it.assetId }
            val assets = rows.filter { it.key.asset in shared }
                .groupBy { it.key.asset }
                .map { (asset, resources) -> ManifestAsset.of(asset, resources) }
                .sortedBy { it.asset.value }
            return DeviceManifest(device, assets, version, declaration)
        }
    }
}

/** One photo a manifest declares: when it was [captured], and every resource of it this device will provide. */
class ManifestAsset private constructor(
    val asset: AssetId,
    val captured: CaptureDate,
    val resources: List<ManifestResource>,
) {
    companion object {
        /** [asset]'s entry from its rows — one per resource, all written by the walk that recorded the photo. */
        fun of(asset: AssetId, rows: List<UploadRow>): ManifestAsset {
            val head = rows.firstOrNull() ?: throw InvalidProgramState("a photo's group holds at least one row")
            return ManifestAsset(
                asset,
                head.detail.captured,
                rows.sortedWith(compareBy({ it.key.role.ordinal }, { it.key.extension }))
                    .map { ManifestResource(it.key, it.detail.contentType) },
            )
        }
    }
}

/**
 * One resource a manifest declares: its [key] (which names its role) and its content type. Its name travels with its
 * bytes, never in the manifest (decision record `changes/seal-file-names`, D5).
 */
class ManifestResource(val key: UploadKey, val contentType: String)
