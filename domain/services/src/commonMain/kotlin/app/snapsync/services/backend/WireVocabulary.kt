package app.snapsync.services.backend

import app.snapsync.model.DeviceManifest
import app.snapsync.model.ResourceRole
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.ManifestAsset
import app.snapsync.wire.models.ManifestResource
import app.snapsync.wire.models.Role

/** A resource's role, as the description names it (`Role`). */
internal fun ResourceRole.onWire(): Role = when (this) {
    ResourceRole.PRIMARY -> Role.PRIMARY
    ResourceRole.LIVE -> Role.LIVE
}

/** A described role, as the app names it. */
internal fun Role.inApp(): ResourceRole = when (this) {
    Role.PRIMARY -> ResourceRole.PRIMARY
    Role.LIVE -> ResourceRole.LIVE
}

/**
 * This snapshot as the backend takes it (`Manifest`), or `null` when it carries no version: every snapshot a
 * publish sends is stamped by its producer, and the backend refuses one without.
 */
internal fun DeviceManifest.onWire(): Manifest? = version?.let { stamped ->
    Manifest(
        deviceId = deviceId,
        assets = assets.map { asset ->
            ManifestAsset(
                assetId = asset.assetId.value,
                creationDate = asset.creationDate,
                resources = asset.resources.map { it.onWire() },
            )
        },
        version = stamped,
        `final` = final,
    )
}

private fun app.snapsync.model.ManifestResource.onWire(): ManifestResource =
    ManifestResource(role.onWire(), contentType, key, filename)
