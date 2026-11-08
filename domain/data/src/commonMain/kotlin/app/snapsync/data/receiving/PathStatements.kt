package app.snapsync.data.receiving

import app.snapsync.data.crypto.SealedLanding
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.held
import app.snapsync.data.receiving.RowStatements.refsOf
import app.snapsync.data.receiving.db.DownloadStoreQueries
import app.snapsync.data.receiving.db.SelectResourcePaths
import app.snapsync.domain.receiving.AssetRef
import app.snapsync.domain.receiving.StagedPath
import app.snapsync.domain.receiving.StagingFolder

// The download store's statements over the staged paths the rows name.

object PathStatements {
    /**
     * Every staged path a resource row may still use: its recorded staged path, and — for a row whose download has not
     * landed — the path it will land at, under its event's folder and the event-less one a transfer started before
     * transfers named their event still lands at; and an encrypted download's sealed files beside each.
     */
    internal fun pathsInUse(q: DownloadStoreQueries): Set<StagedPath> =
        q.selectResourcePaths().executeAsList().flatMapTo(HashSet(), ::pathsOf)

    private fun pathsOf(row: SelectResourcePaths): List<StagedPath> =
        refsOf(row.sourceDeviceId.held() to row.sourceAssetId.held()).flatMap { ref -> landings(row, ref) }
            .flatMap { listOf(it) + SealedLanding.filesOf(it.value).map(::StagedPath) }

    private fun landings(row: SelectResourcePaths, ref: AssetRef): List<StagedPath> = listOfNotNull(
        row.stagedPath?.let(::StagedPath),
        StagedPath.of(StagingFolder.of(row.eventId), ref, row.resourceKey),
        StagedPath.of(StagingFolder.Legacy, ref, row.resourceKey),
    )

    /**
     * The staged paths of resource rows no photo row owns any more, swept with those rows — read AFTER the deletes that
     * orphaned them, so the paths are what was really stranded: freeing the bytes of a row a delete spared would leave
     * a
     * resource recorded as staged that is never downloaded again.
     */
    internal fun stranded(q: DownloadStoreQueries): List<StagedPath> =
        q.selectStagedPathsOfOrphanedResources().executeAsList().filterNotNull().map(::StagedPath).also {
            q.deleteNonTerminalResources()
        }
}
