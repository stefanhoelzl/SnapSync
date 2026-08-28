package app.snapsync.fake

import app.snapsync.model.Candidate
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.resourcesFrom
import app.snapsync.model.toFacts
import app.snapsync.ports.CandidateSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [CandidateSource]: reads a constructor-injected state cell, so whoever owns the cell
 * (a test, the world's gallery wrapper) adds and removes assets — the fake itself exposes only the port
 * (the honesty gate).
 *
 * **It translates only the two REQUIRED narrowings** — the capture floor and the deny-everything rule —
 * and nothing else, which is deliberate rather than lazy. The real
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
internal class InMemoryCandidateSource(private val state: MutableStateFlow<List<RawAsset>>) : CandidateSource {

    constructor(initial: List<RawAsset> = emptyList()) : this(MutableStateFlow(initial))

    override suspend fun candidates(policy: SelectionPolicy): List<Candidate> {
        // Stands in for a platform's NARROWED fetch, so it translates the policy's rules the way a real
        // translator does (capability `photo-selection-policy`) rather than reading a bound off the policy
        // — which is no longer a thing a policy offers. Only the two narrowings that are REQUIRED are
        // modelled; every other rule is left to the caller's authoritative admission, exactly as an
        // untranslatable rule is on device.
        if (policy.rules.any { it.deniesEverything }) return emptyList()
        val floor = policy.rules.filterIsInstance<SelectionRule.CaptureAfter>()
            .maxOfOrNull { it.cutoff.at.iso }
        return state.value
            .filter { floor == null || it.creationDate >= floor }
            .map(::InMemoryCandidate)
    }
}

private class InMemoryCandidate(private val raw: RawAsset) : Candidate {
    override val facts = raw.toFacts()
    override suspend fun resources(): List<Resource> = resourcesFrom(listOf(raw))
}
