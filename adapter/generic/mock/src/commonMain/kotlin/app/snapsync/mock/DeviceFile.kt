package app.snapsync.mock

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole

/** One resource a device has stored (`GET /files/devices/<id>`), in the terms the backend addresses it by. */
data class DeviceFile(val assetId: AssetId, val role: ResourceRole, val filename: String)
