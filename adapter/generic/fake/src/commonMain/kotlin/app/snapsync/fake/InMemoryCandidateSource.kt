package app.snapsync.fake

import app.snapsync.model.Candidate
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.resourcesFrom
import app.snapsync.model.toFacts
import app.snapsync.ports.CandidateSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [CandidateSource]: reads a constructor-injected state cell, so whoever owns the cell
 * (a test, the world's gallery wrapper) adds and removes assets — the fake itself exposes only the port
 * (the honesty gate).
 *
 * **It narrows by the capture floor and nothing else**, and that is deliberate rather than lazy. The real
 * adapter's `PHFetchOptions` predicate is an *optimization* the authoritative in-memory admission must be
 * proven to work without (capability `photo-selection-policy`: a platform fetch can neither widen nor
 * narrow the admitted set). A fake that mirrored the predicate would hide exactly the bug that matters —
 * an admission relying on the fetch to have already excluded something. So a screenshot crosses this seam
 * and is dropped downstream, which is what a real, deliberately-widened fetch produces.
 *
 * The floor *is* mirrored because it is the one narrowing that is required rather than advisory: without
 * it the real walk is unbounded and the process is watchdog-killed, so a fake returning the whole library
 * would misrepresent the seam's contract rather than merely its performance.
 *
 * [Candidate.resources] maps through the shared [resourcesFrom], so the fan-out (role filter, upload-key
 * derivation, id normalization) is driven on the JVM and the simulator exactly as it is on device.
 */
class InMemoryCandidateSource(private val state: MutableStateFlow<List<RawAsset>>) : CandidateSource {

    constructor(initial: List<RawAsset> = emptyList()) : this(MutableStateFlow(initial))

    override suspend fun candidates(policy: SelectionPolicy): List<Candidate> {
        // Exhausting the sealed policy rather than testing an absent floor: the non-contributing case is
        // its own branch, and a contributing one always carries a bound.
        val floor = when (policy) {
            SelectionPolicy.None -> return emptyList()
            is SelectionPolicy.Admitting -> policy.cutoff.at.iso
        }
        return state.value.filter { it.creationDate >= floor }.map(::InMemoryCandidate)
    }
}

private class InMemoryCandidate(private val raw: RawAsset) : Candidate {
    override val facts = raw.toFacts()
    override suspend fun resources(): List<Resource> = resourcesFrom(listOf(raw))
}
