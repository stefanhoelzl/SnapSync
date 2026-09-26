package app.snapsync.fake

import app.snapsync.model.AssetRef

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
