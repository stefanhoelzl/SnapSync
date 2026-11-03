package app.snapsync.data.upload

import app.snapsync.domain.membership.EndsUploads
import app.snapsync.domain.upload.FullGrant
import app.snapsync.domain.upload.RegistrationReply
import app.snapsync.domain.upload.RegistrationVerdict
import app.snapsync.domain.upload.RegistrationWrite
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.ExtensionRegistry
import app.snapsync.ports.RegistrationAnswer

/**
 * **The upload extension's registration with the OS** (iOS ≥ 26.1) — the one thing the app does for the tier whose
 * uploads the system performs. Through one port, [ExtensionRegistry], constructed on every platform: where the OS
 * carries no such mechanism it answers `Unsupported` and asks nothing. Each write is reported at what it did
 * ([RegistrationVerdict]): an `Error` here is what crash reporting carries onward as field telemetry.
 *
 * Registering requires a full grant, read at the moment of the write ([FullGrant]): iOS refuses either write under a
 * partial one. Deregistering requires evidence the membership's uploads ended ([EndsUploads]): the disable wipes every
 * in-flight OS job.
 */
class ExtensionRegistrar(private val registry: ExtensionRegistry) {
    private val log = Log("ExtensionRegistrar")

    /**
     * Register the extension — a **disable → enable toggle**, never a bare enable. The OS's configuration record is
     * keyed by bundle id and survives a delete, reinstall and reboot, so a stale one (a prior or differently signed
     * build) makes a bare enable fail with 3202, after which the OS never launches the extension; the leading disable
     * deletes it, and the re-register is what reliably prompts the OS to schedule `process()`. The disable wipes the
     * record's in-flight jobs: run only where none can be live — a join, or where the OS reads no record at all.
     */
    suspend fun register(@Suppress("UNUSED_PARAMETER") grant: FullGrant) {
        write(RegistrationWrite.DISABLE)
        write(RegistrationWrite.ENABLE)
    }

    /** Deregister the extension — the disable alone — once [ending] says this membership's uploads end. */
    suspend fun deregister(@Suppress("UNUSED_PARAMETER") ending: EndsUploads) = write(RegistrationWrite.DISABLE)

    private suspend fun write(write: RegistrationWrite) {
        val answer = registry.setEnabled(enabled(write))
        reported(RegistrationVerdict.of(write, replyOf(answer)), answer)
    }

    private fun enabled(write: RegistrationWrite): Boolean = when (write) {
        RegistrationWrite.ENABLE -> true
        RegistrationWrite.DISABLE -> false
    }

    private fun replyOf(answer: RegistrationAnswer): RegistrationReply = when (answer) {
        is RegistrationAnswer.Answered -> answered(answer)
        RegistrationAnswer.Unsupported -> RegistrationReply.NotCarried
    }

    private fun answered(answer: RegistrationAnswer.Answered): RegistrationReply = when (answer.ok) {
        true -> RegistrationReply.Took
        false -> RegistrationReply.Refused(answer.code)
    }

    private suspend fun reported(verdict: RegistrationVerdict, answer: RegistrationAnswer) = when (verdict) {
        is RegistrationVerdict.Applied -> log.i("extension registration written", "write" of verdict.write)
        RegistrationVerdict.NothingToDisable ->
            log.i("extension disable found no configuration record (3201) — expected on a clean device")
        RegistrationVerdict.DisableRefusedByGrant -> log.w(
            "extension disable refused under a partial photo grant (3311) — the record survives; the OS may still " +
                "invoke the extension, whose entry gate withholds without a full grant",
        )
        RegistrationVerdict.EnableRefusedByGrant -> log.e(
            "extension enable REFUSED under a partial photo grant (3311) — the OS will never launch the extension",
        )
        is RegistrationVerdict.Unsupported ->
            log.i("extension registration not applicable — this platform has no upload extension")
        is RegistrationVerdict.Failed -> log.e(
            "extension registration FAILED — the extension is not in the state the app believes, and nothing else " +
                "will report it",
            "write" of verdict.write,
            "answer" of answer,
        )
    }
}
