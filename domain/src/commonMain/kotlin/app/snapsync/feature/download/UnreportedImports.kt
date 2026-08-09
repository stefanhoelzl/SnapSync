package app.snapsync.feature.download

import app.snapsync.ports.AssetRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The refs whose import outcome the photo library has **not reported** (capability `photo-download`).
 *
 * A ref enters when an import's wait is abandoned on its deadline, and leaves when the library finally
 * reports that import's outcome. While it is held, the library's answer that the created asset is
 * *absent* means nothing: `PHPhotoLibrary` answers about **committed** state, so it answers honestly that
 * an asset does not exist while the transaction creating it is still open. Acting on that clears the
 * marker of an asset that does exist, which drops it from the suppression set — and the device uploads a
 * photo it downloaded back into someone else's event (Bugsink `SNAPSYNC-9`: 19 such clears, each 9–44 ms
 * after that same asset was created).
 *
 * **ONE reader, and that is a decision, not an accident.** This is consulted by adjudication and by
 * nothing else:
 *
 *  - **not** by import selection — a row carrying a `createdLocalId` is already excluded from importable
 *    work by the store, which is a durable fact that survives the process; adding this as a second
 *    gate would duplicate it in memory, where it is weaker.
 *  - **not** by wake quiescence — "may this ref's outcome still arrive" and "is this wake's work
 *    finished" are different questions, and this set answers only the first. A superseded design
 *    (`parked/settle-imports-by-transaction`) used one set for all three; membership there began at the
 *    import's *claim*, so work that had been launched but not yet claimed was invisible to it, and a
 *    wake could report itself finished with imports pending. The name of this type is chosen for its one
 *    reader for exactly that reason: the previous name described a fact general enough to invite the
 *    second reader.
 *
 * **No clock, anywhere.** A ref is released because the library reported, never because time passed. The
 * process is suspended for arbitrary spans between a change block and its completion (measured 116 s and
 * 254 s), so any elapsed-time bound would expire against transactions that are alive — which is the
 * mistake the import deadline itself made, and reproducing it here would defeat the guard.
 *
 * **In memory, and its erasure is load-bearing.** A durable record would outlive the process that owned
 * the transaction, and a ref recorded by a process that no longer exists would be distrusted forever, so
 * its photo would never arrive. A transaction cannot outlive its process, so after a relaunch every
 * *absent* answer is trustworthy again — which is precisely when the guard should do its original job.
 * Instance state, legitimate under `module-architecture`'s state law as a coordination primitive: nothing
 * here is a fact about the world that must survive a relaunch.
 *
 * Mutated from two lanes — the composition lane records when a wait is abandoned, and the platform's
 * completion callback forgets from whatever queue the OS runs it on — so the state is a
 * `MutableStateFlow` updated by atomic CAS rather than a plain set.
 */
class UnreportedImports {

    private val unreported = MutableStateFlow<Set<AssetRef>>(emptySet())

    /**
     * We stopped waiting for [ref]'s import and never learned its outcome, so nothing may conclude that
     * its asset is absent until the library says so.
     */
    fun record(ref: AssetRef) = unreported.update { it + ref }

    /**
     * The library reported this import's outcome — success, or an observed failure. Called from the
     * completion callback, which runs whether or not anything is still awaiting it.
     *
     * Forgetting a ref that was never recorded is a no-op: the ordinary import path reports normally and
     * never records one, so the common case is a call that finds nothing.
     */
    fun forget(ref: AssetRef) = unreported.update { it - ref }

    /** True while [ref]'s outcome is unknown to us, so an *absent* answer about it means "cannot tell". */
    fun holds(ref: AssetRef): Boolean = ref in unreported.value
}
