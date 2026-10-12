package app.snapsync.model

/**
 * Outcome of a create attempt. [CREATED] → the platform job exists (record `UploadStarted`); [LIMIT_EXCEEDED] → the
 * platform's in-flight limit (defer, request re-invocation); [FAILED] → the job could not be created (a malformed
 * destination, an unusable source) and was NOT created, so the caller must NOT record `REQUESTED` for a job that does
 * not exist.
 */
enum class UploadCreateOutcome { CREATED, LIMIT_EXCEEDED, FAILED }

/**
 * A resource as the photo library itself holds it — opaque to the core, which only carries it from a read to the
 * upload; the gallery services make one and read one.
 */
interface LibraryResource
