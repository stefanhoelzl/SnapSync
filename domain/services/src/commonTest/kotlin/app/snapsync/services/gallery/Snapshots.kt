package app.snapsync.services.gallery

import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.testids.testAssetId

/** A selection snapshot of one primary photo per id, each taken on the same in-range day. */
internal fun snapshotOf(vararg ids: String): List<Resource> = ids.map {
    Resource(
        "$it-primary.jpg",
        testAssetId(it),
        "image/jpeg",
        mapOf(RESOURCE_META_CREATION_DATE to "2026-06-01T00:00:00Z"),
        null,
    )
}
