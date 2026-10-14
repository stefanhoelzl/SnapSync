package app.snapsync.rig

import app.snapsync.levers.UploadJobLevers

/**
 * The device target: **the OS holds the job queue**, so there is nothing to hand in, nothing to perform and no record to
 * plant — an operator would be racing the system for the same jobs, and the outcome would say nothing about what the OS
 * did. Every verb is refused; the string `processRawValue` entry drives the real queue, reporting only its result.
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
internal actual suspend fun beginStringCycle() = Unit

/**
 * What a device can report: the raw processing result and the fact that the queue was the OS's own. No created-job
 * list, and that absence is stated: the jobs live in the system's queue, where only the OS can enumerate them.
 */
internal actual suspend fun endStringCycle(raw: Int): String =
    """{"processRawValue":$raw,"result":"${processingResultName(raw)}","queue":"os",""" +
        """"created":null,"note":"the OS owns this queue; jobs it created are not enumerable from here"}""" + "\n"

private const val OS_OWNS_THE_QUEUE =
    "the operating system owns the upload-job queue on a device, so the channel cannot hand it job sets, perform a " +
        "job or record a registration for it; the simulator app, which has no such queue, plays it"
