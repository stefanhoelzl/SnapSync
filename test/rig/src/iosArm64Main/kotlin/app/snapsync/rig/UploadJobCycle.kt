package app.snapsync.rig

/**
 * The device target: **the OS holds the job queue**, so there is nothing to hand in and nothing to hand
 * back beyond the cycle's own result.
 *
 * A body is REFUSED rather than ignored. A caller who sends job sets here has mistaken this host for the
 * simulator, and silently discarding them would produce a cycle that looks like it honoured a scripted
 * queue while actually driving the real one — the shape most likely to be read as evidence for something
 * it did not test.
 */
internal actual suspend fun beginUploadJobCycle(body: String?): String? =
    if (body == null) {
        null
    } else {
        """{"refused":"this target drives the REAL OS upload-job queue, so job sets cannot be handed in",""" +
            """"queue":"os","hint":"invoke with no body; the OS holds the queue and this cycle reads it"}""" +
            "\n"
    }

/**
 * What a device can report: the raw processing result and the fact that the queue was the OS's own.
 *
 * No created-job list, and that absence is stated rather than left as an empty array: the jobs this cycle
 * created live in the system's queue, where only the OS can enumerate them, and an empty `created` would
 * read as "it created nothing".
 */
internal actual suspend fun endUploadJobCycle(raw: Int): String =
    """{"processRawValue":$raw,"result":"${processingResultName(raw)}","queue":"os",""" +
        """"created":null,"note":"the OS owns this queue; jobs it created are not enumerable from here"}""" +
        "\n"

/**
 * None. The OS moves the bytes on this target, so `perform` would be an operator racing the system for the
 * same job — and its outcome would say nothing about what the OS did.
 */
internal actual fun uploadJobDeviceCommands(): Map<String, RigCommand> = emptyMap()
