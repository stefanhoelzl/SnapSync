package app.snapsync.model

/**
 * A platform-neutral view of a returned system upload job — now only ever a **retry-spent failure the
 * cycle must re-create**, since terminal facts are recorded by the platform adapter and never handed up
 * (see [BackgroundTransfer.drainTerminals]).
 *
 * [key] is recovered from the job's own destination URL (the only field reliably present across the whole
 * lifecycle — `resource` is nil for succeeded jobs). [contentType] is the type the request was created
 * with; reporting a placeholder here is not inert, because the cycle rebuilds a retried job's `Resource`
 * from the key alone and the object would be mistyped for the rest of its life. [data] is the opaque
 * `PHAssetResource`, present because a job only appears here when it can still be re-created. [error] is
 * what the platform said went wrong — carried for the engine's failure line, which is the only record of
 * why a key is being retried.
 *
 * `state` and `handle` are gone with the terminal facts: one kind of job comes back now, and the adapter
 * settles with the platform in place rather than handing a system handle up to be acknowledged later.
 */
class PlatformUploadJob(
    val key: String,
    val contentType: String,
    val error: UploadError?,
    val data: Any?,
)
