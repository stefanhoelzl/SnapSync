package app.snapsync.model

/**
 * The **admitted set** of a membership, as a thing consumers *receive* rather than a policy they apply
 * (capability `photo-selection-policy`).
 *
 * ## Why an object and not a shared predicate
 *
 * A shared `admits()` that every consumer calls is already better than four hand-assembled rule lists —
 * but a consumer can still hold an asset and simply *not* call it, which is exactly the bug this change
 * exists to close (the device-manifest projection and the status total held the assets and applied only
 * the floor). Handing the consumer the set instead makes that unrepresentable: there is no disallowed
 * asset in reach to forget about.
 *
 * ## The cost ladder
 *
 * The three entry points are deliberately ordered by what they make you pay:
 *
 * ```
 *   count()      → facts only                     cheap
 *   assets()     → facts only, per asset          cheap
 *   resources()  → one platform read per ADMITTED asset   ~110 ms/asset on an SE2
 * ```
 *
 * Admission is decidable on [AssetFacts] alone, so the count and the listing never touch a resource. This
 * is also *faster* than what it replaces: the old shape read every candidate's resources and then dropped
 * most of them, where this filters first and fetches only for what survives. Whether that saving is real
 * depends on the backing — a facts-only walk (the app-side preview and total) collects it in full; a
 * platform that hands back resources with its discovery (the upload cycle's) pays nothing extra either
 * way. [Candidate.resources] is the seam that lets each backing decide.
 *
 * ## Admission happens at query, never at ingest
 *
 * The policy is applied *here*, when a consumer asks — never upstream, in whatever cache or accumulator
 * produced the candidates. That ordering is load-bearing: pre-filtering by a *subset* of the rules is
 * precisely how the ceiling went missing, because the device-global accumulator was fed the
 * origin-filtered set and left the capture bounds to a projection that applied only one of them.
 *
 * ## Why [candidates] is a lambda and not the port itself
 *
 * The production backing IS a port (`ports/CandidateSource`), but this type cannot name it: `model/` is the
 * innermost zone and references nothing project-internal outside itself (law `module-architecture`, "Zones
 * inside the core"), while a port lives in `ports/`. Features — which may hold ports — bind the two:
 * `EventPhotoSet(policy, source::candidates)`. That keeps the admission itself in `model/`, where it is
 * exercised in `commonTest` on JVM **and** the simulator, rather than in untested wiring.
 *
 * The lambda's hazard is real and was realised once: this seam previously existed with the same signature
 * and **all nine call sites ignored the parameter**, each fetching eagerly and handing over a finished
 * list, so the policy never reached the platform that could have narrowed on it. A type cannot catch that.
 * `EventPhotoSetSourceTest` (`:test:architecture`) does instead: outside the two backings that genuinely
 * already hold their resources, a construction whose lambda discards its parameter is a red build.
 */
class EventPhotoSet(
    private val policy: SelectionPolicy,
    private val candidates: suspend (SelectionPolicy) -> List<Candidate>,
) {

    /** How many of this device's assets the membership admits. Reads no resources. */
    suspend fun count(): Int = admitted().size

    /** The admitted assets, facts only. A disallowed asset is not in the result — there is none to skip. */
    suspend fun assets(): List<Candidate> = admitted()

    /**
     * Every resource of every admitted asset — the bytes to upload and the entries to list.
     *
     * Per **asset**, not per resource: an asset's resources stand or fall together, or a Live Photo's
     * paired video survives its excluded primary as an orphan whose bytes nothing uploads.
     */
    suspend fun resources(): List<Resource> = admitted().flatMap { it.resources() }

    private suspend fun admitted(): List<Candidate> =
        // No caller-side short-circuit for a non-contributing membership. The walk it used to avoid costs
        // one synchronous platform round-trip per asset, and that cost is now removed where it actually
        // arises: `DenyAll` is translated into a fetch predicate matching no asset (capability
        // `gallery-status`), so the expensive path — a cold-start whole-library enumeration — returns
        // nothing. The two predicate-less paths (the change-feed walk, the partial-grant observer) are
        // bounded to a delta or a hand-picked selection by construction.
        candidates(policy).filter { policy.admits(it.facts) }
}

/**
 * One asset a backing offers to [EventPhotoSet], before admission: its neutral [facts] and a **lazy** way
 * to reach its resources.
 *
 * The split is the cost ladder (see [EventPhotoSet]): facts are cheap in-memory properties on every
 * platform, resources are not.
 */
interface Candidate {
    val facts: AssetFacts

    /** This asset's resources, fetched on demand. Called only for an **admitted** asset. */
    suspend fun resources(): List<Resource>
}

/**
 * Candidates over resources the platform has **already** handed back — the upload cycle's backing, whose
 * discovery arrives resource-complete. [Candidate.resources] is then free; the ladder costs nothing here
 * and saves nothing, which is the honest shape rather than a pretend lazy read.
 */
fun candidatesFromResources(resources: List<Resource>): List<Candidate> {
    val byAsset = resources.groupBy { it.assetId }
    return factsFromResources(resources).map { facts ->
        HeldCandidate(facts, byAsset[facts.assetId].orEmpty())
    }
}

/**
 * Candidates over a **facts-only** walk — the app-side backing (the status total and the join preview),
 * which reads the cheap per-asset properties and skips the resource round-trip entirely.
 *
 * [resourcesFor] is how an admitted asset's resources are reached if a consumer needs them; the count
 * path never calls it. A backing with no way to fetch them supplies the default, which is honest for a
 * preview: it counts, it does not upload.
 */
fun candidatesFromFacts(
    facts: List<AssetFacts>,
    resourcesFor: suspend (String) -> List<Resource> = { emptyList() },
): List<Candidate> = facts.map { LazyCandidate(it, resourcesFor) }

private class HeldCandidate(
    override val facts: AssetFacts,
    private val held: List<Resource>,
) : Candidate {
    override suspend fun resources(): List<Resource> = held
}

private class LazyCandidate(
    override val facts: AssetFacts,
    private val resourcesFor: suspend (String) -> List<Resource>,
) : Candidate {
    override suspend fun resources(): List<Resource> = resourcesFor(facts.assetId)
}
