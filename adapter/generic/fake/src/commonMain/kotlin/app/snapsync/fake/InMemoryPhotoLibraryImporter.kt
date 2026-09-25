package app.snapsync.fake

import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.importFilename
import app.snapsync.model.normalizeAssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportResult
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.model.StagedResource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How the in-memory photo library answers one import's change: the stand-in for `performChanges`, whose
 * change block, commit and completion are three separate platform events (capability `receiving-photos`).
 *
 * It is the library's behaviour, so it is a constructor collaborator of the honest importer rather than a
 * lever on it. The default is the ordinary answer: the change runs, it lands, and the completion reports
 * success. `:test:world` scripts the other answers (a refusal, a hold, a failure reported after the commit)
 * by supplying its own. Each member answers `null` to proceed or a message to fail with.
 */
interface LibraryChangeAnswers {
    /** Before the change block runs. A message refuses the change: no marker, no asset. */
    suspend fun beforeChange(ref: AssetRef): String? = null

    /** After the block wrote the marker, before the commit lands. A message fails the change: no asset. */
    suspend fun beforeCommit(ref: AssetRef): String? = null

    /** After the commit landed (the asset exists). A message is a completion reporting failure anyway. */
    suspend fun afterCommit(ref: AssetRef): String? = null

    companion object {
        /** The ordinary library: every change runs, lands and reports success. */
        val Ordinary: LibraryChangeAnswers = object : LibraryChangeAnswers {}
    }
}

/**
 * The honest in-memory [PhotoLibraryImporter], held to `PhotoLibraryImporterContract` exactly as
 * `IosPhotoLibraryImporter` is.
 *
 * **Two-phase, exactly like the real adapter.** It records the created-asset marker through
 * [recordCreatedLocalId] inside the "change block", before the asset is observable, and settles it on the
 * completion: [confirmCreatedLocalId] on success, [clearCreatedLocalId] on a reported failure. The three
 * lambdas are the real adapter's own constructor collaborators.
 *
 * **Every import mints a fresh identifier**, as `PHAssetCreationRequest` does, counted **before** anything can
 * fail. So a repeat import never lands on an earlier import's handle, and a test cannot observe a duplicate
 * through an identifier that repeats. The first import keeps the bare form `imported-<device>-<asset>`.
 *
 * [library] is the caller's own cell: an import that lands adds its asset there, which is how the rest of an
 * in-memory world sees it. [answers] is how the library answers each change (see [LibraryChangeAnswers]).
 */
internal class InMemoryPhotoLibraryImporter(
    private val library: MutableStateFlow<List<RawAsset>>,
    private val recordCreatedLocalId: (AssetRef, String) -> Boolean,
    private val clearCreatedLocalId: (AssetRef, String) -> Unit,
    private val confirmCreatedLocalId: (AssetRef, String) -> Unit,
    private val answers: LibraryChangeAnswers = LibraryChangeAnswers.Ordinary,
) : PhotoLibraryImporter {

    private val attempts = mutableMapOf<AssetRef, Int>()

    override suspend fun import(
        ref: AssetRef,
        resources: List<StagedResource>,
        creationDate: String,
    ): ImportResult {
        val attempt = attempts.getOrElse(ref) { 0 } + 1
        attempts[ref] = attempt
        answers.beforeChange(ref)?.let { return ImportResult.Failed(it) }
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = normalizeAssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}$suffix")
        // `false` means the row was pruned out from under this import, so the asset about to be created would
        // have no suppression handle at all (capability `receiving-photos`). The real adapter logs an error; an
        // in-memory library raises, because a test that reaches this has hit the defect the prune's
        // `protecting` set exists to prevent.
        check(recordCreatedLocalId(ref, createdLocalId)) {
            "marker $createdLocalId for ${ref.sourceAssetId} landed on NO ROW — its row was pruned mid-import"
        }
        answers.beforeCommit(ref)?.let {
            clearCreatedLocalId(ref, createdLocalId)
            return ImportResult.Failed(it)
        }
        library.value = library.value + createdAsset(createdLocalId, resources, creationDate)
        answers.afterCommit(ref)?.let {
            clearCreatedLocalId(ref, createdLocalId)
            return ImportResult.Failed(it)
        }
        confirmCreatedLocalId(ref, createdLocalId)
        return ImportResult.Imported(createdLocalId)
    }

    private fun createdAsset(id: String, resources: List<StagedResource>, creationDate: String) = RawAsset(
        assetId = id,
        creationDate = creationDate,
        rawResources = resources.map { staged ->
            RawResource(
                role = if (staged.role == ResourceRole.LIVE.wire) ResourceRole.LIVE else ResourceRole.PRIMARY,
                mimeContentType = staged.contentType,
                // The SAME naming rule the iOS importer applies (`importFilename`), so an in-memory library
                // cannot show a human name where a device would show a storage key.
                originalFilename = importFilename(staged.originalFilename, staged.resourceKey),
                handle = Unit,
            )
        },
    )
}
