package app.snapsync.rig

import app.snapsync.levers.UploadJobLevers

/**
 * The device target: **the OS holds the job queue**, so there is nothing to hand in, nothing to perform and no record to
 * plant — an operator would be racing the system for the same jobs, and the outcome would say nothing about what the OS
 * did. Every verb is refused; `extension-host.process()` drives the real queue, answering only its result.
 */
internal actual fun uploadJobService(
    world: MockWorld,
    process: () -> Int,
): HostService<UploadJobLevers> = uploadJobService(
    process = refusing(OS_OWNS_THE_QUEUE),
    perform = refusing(OS_OWNS_THE_QUEUE),
    extensionRecord = refusing(OS_OWNS_THE_QUEUE),
)

/** Nothing to begin: the OS hands its own sets to the real `process()`. */
internal actual suspend fun beginUnscriptedCycle() = Unit

private const val OS_OWNS_THE_QUEUE =
    "the operating system owns the upload-job queue on a device, so the channel cannot hand it job sets, perform a " +
        "job or record a registration for it; the simulator app, which has no such queue, plays it"
