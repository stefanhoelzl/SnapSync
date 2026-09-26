package app.snapsync.services.databases

import app.cash.sqldelight.ColumnAdapter
import app.snapsync.model.AssetId

/**
 * Stores an [AssetId] as its canonical string — the form every row written before the type existed already
 * holds, so wiring this adapter changes no byte on disk and needs no migration. Shared by both databases,
 * the one place that knows the encoding.
 */
internal object AssetIdColumnAdapter : ColumnAdapter<AssetId, String> {
    override fun decode(databaseValue: String): AssetId = AssetId(databaseValue)

    override fun encode(value: AssetId): String = value.value
}
