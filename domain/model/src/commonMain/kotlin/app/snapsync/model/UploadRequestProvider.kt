package app.snapsync.model

/**
 * The engine's request-minting seam (spec: background-upload): mints the executable request for a
 * resource. Implementations: S3 presigner, dumb-HTTP test provider.
 *
 * Contract:
 * - `resource.filename → destination` is **deterministic and injective** — this is where
 *   upload idempotency lives. Encoding and placement (a `photos/` path prefix, or carrying
 *   identity as a header on transports that do) are the provider's alone.
 * - The returned request carries the **same [Resource] instance** it was given.
 *   [Resource.data] is never read.
 * - Failures are thrown, never masked — the engine doesn't catch (the event counts as
 *   unprocessed, the ledger is left untouched, and re-handling is safe).
 * - The provider is invoked only for [SyncDecision.Work] answers — never when the engine
 *   skips ([SyncDecision.AlreadyUploaded]).
 */
interface UploadRequestProvider {
    suspend fun provide(resource: Resource): UploadRequest

    /**
     * [provide] for a **retry**: the same destination, with the credential read from its store of record rather
     * than from any in-process copy (capability `background-upload`, "A retry picks up a refreshed token").
     *
     * A retry is exactly when a copy is most likely stale — the failure may have been the `401` of a token another
     * process has since renewed — so it pays the one uncached read that [provide] saves on every first request.
     */
    suspend fun provideForRetry(resource: Resource): UploadRequest
}
