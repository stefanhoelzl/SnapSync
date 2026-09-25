package app.snapsync.fake

import app.snapsync.model.CandidateRead
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RawAsset
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.resourcesFrom
import app.snapsync.ports.CandidateSource
import app.snapsync.ports.Discovery
import app.snapsync.ports.UploadDiscovery
import kotlinx.coroutines.flow.StateFlow

/**
 * The honest in-memory [UploadDiscovery] (`docs/architecture.md`): the upload cycle's two library reads
 * over an in-memory library, held to `UploadDiscoveryContract` exactly as `IosDiscovery` is.
 *
 * Every walk ([discover]) is a **full enumeration** through [source], as on a device. There is no change
 * feed and no token: an added asset appears in the next walk, and a removed one is simply missing from it,
 * which is the evidence the cycle's presence diff consumes.
 *
 * [library] is the unscoped contents, standing in for "fetch these assets by identifier". It is a cell
 * rather than the [source] beside it because that seam takes a policy and a fetch by identifier has none.
 * The admission over ledger rows belongs to the CYCLE, which applies it before it asks (capability
 * `photo-sharing`). A fake that admitted here as well would hide whether the cycle ever did.
 *
 * State arrives by constructor, per the fake-honesty rule. Levers and inspection belong in `:test:world`
 * wrappers.
 */
internal class InMemoryUploadDiscovery(
    private val source: CandidateSource,
    private val library: StateFlow<List<RawAsset>>,
    /**
     * The process's photo grant. As on a device, a walk is authoritative for deletion only under a full grant:
     * without one there is no library to have read, and under a partial one only a selection.
     */
    private val grant: () -> PermissionStatus,
) : UploadDiscovery {

    /**
     * Scoped by the policy exactly as far as a platform predicate scopes a device's fetch, and no further.
     * The honest source narrows by the capture floor only and leaves every other rule to the cycle's
     * admission. That matters because what comes back is also the walk's PRESENCE set: a fake that applied
     * the whole admission would make an asset the admission excludes (a denylisted album) look departed,
     * and the cycle would delete rows a device keeps.
     */
    override suspend fun discover(policy: SelectionPolicy): Discovery =
        when (val read = source.candidates(policy)) {
            is CandidateRead.Readable ->
                Discovery(candidates = read.candidates, fullEnumeration = grant() == PermissionStatus.GRANTED)
            CandidateRead.NotReadable -> Discovery(candidates = emptyList(), fullEnumeration = false)
        }

    /**
     * Resolves ledger keys by identifier. It is deliberately unscoped by the policy, as the real discovery
     * is: it resolves the keys it is handed. An asset no longer in the library resolves to nothing, which is
     * the port's partial contract.
     */
    override suspend fun resourcesFor(keys: Set<String>): List<Resource> =
        resourcesFrom(library.value).filter { it.filename in keys }
}
