package app.snapsync.domain.upload

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.event.KeyPresence
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.sharing.ReadPlan

/** Whether this process may create upload jobs now — each uploader states its own. */
enum class UploadAdmission {
    ADMIT,
    WITHHELD,

    ;

    companion object {
        /**
         * The app's: once the grant's read plan can read — an unread partial-grant selection withholds, and it is the
         * same plan discovery reads under.
         */
        fun forApp(plan: ReadPlan<*>): UploadAdmission = when (plan) {
            ReadPlan.NotReadable -> WITHHELD
            ReadPlan.Walk, is ReadPlan.Snapshot -> ADMIT
        }

        /** The upload extension's: only under a full grant — it is registrable, and creates, under no other. */
        fun forExtension(access: PhotoAccess): UploadAdmission = when (access) {
            PhotoAccess.FULL -> ADMIT
            PhotoAccess.LIMITED, PhotoAccess.NONE -> WITHHELD
        }
    }
}

/** Why an admitted pass waits for another process: the app has not yet copied the records, or migrated its store. */
enum class UploadPause { LEGACY_RECORDS, OLD_SCHEMA }

/** Whether the build carries an upload host — one that does not cannot upload at all. */
enum class UploadHost {
    CARRIED,
    NONE,

    ;

    companion object {
        /** The build's [host] — a blank one is none. */
        fun of(host: String): UploadHost = when (host.isBlank()) {
            true -> NONE
            false -> CARRIED
        }
    }
}

/** What a pass's entry read: the membership (none when not joined) and the manifest version, or what it could not. */
sealed interface GateRead {
    /** Read first, before the membership: every change the pass's projection misses carries a higher [version]. */
    class Read(val membership: Membership?, val version: ManifestVersion, val device: DeviceId) : GateRead

    /** A required read failed — "I could not look", never "not joined". [detail] names which, for a device's log. */
    data class Unreadable(val detail: String) : GateRead
}

/**
 * **The entry of an upload pass** — before the walk, every hook and any write. Five outcomes, and the difference
 * between two of them is the difference between a settled join and a false leave: an [Unreadable] membership touches
 * nothing, where [NotJoined] is definite.
 */
sealed interface UploadGate {
    /** The pass stops at the gate: it reads, settles, walks, creates and publishes nothing — and answers [pass]. */
    sealed interface Stop : UploadGate {
        /** What the pass answers when it stops here. */
        val pass: UploadPass
    }

    /** A required input could not be read: nothing is touched, and the next pass retries. */
    data class Unreadable(val detail: String) : Stop {
        override val pass get() = UploadPass.CAUGHT_UP
    }

    /** Definitively not joined — or a build with no upload host: there is nothing to upload for. */
    data object NotJoined : Stop {
        override val pass get() = UploadPass.SKIPPED
    }

    /**
     * This process may not create now — no usable grant, a lost key, an event whose photos are deleted, or received
     * photos not yet recognised. Still owed: recording and acknowledging what the platform presents for [event].
     */
    class Withheld(val event: EventId) : Stop {
        override val pass get() = UploadPass.SKIPPED
    }

    /** Admitted, but another process's work comes first: nothing is touched, and the pass asks to be run again. */
    data class Paused(val reason: UploadPause) : Stop {
        override val pass get() = UploadPass.PAUSED
    }

    /** The pass may run for [membership], publishing as [device] with the [version] its entry read first. */
    class Run(val membership: Membership.Active, val version: ManifestVersion, val device: DeviceId) : UploadGate

    companion object {
        /** The entry, from what it [read], whether the build has a [host], the key's [presence] and the [admission]. */
        fun entry(read: GateRead, host: UploadHost, presence: KeyPresence, admission: UploadAdmission): GateStep =
            GateStep.of(
                when (read) {
                    is GateRead.Unreadable -> Unreadable(read.detail)
                    is GateRead.Read -> hosted(read, host, presence, admission)
                },
            )

        /**
         * A running pass against the echo-suppression store's [readiness]: uploading without it would send downloaded
         * photos back.
         */
        fun suppression(run: Run, readiness: EchoReadiness): GateStep = when (readiness) {
            EchoReadiness.Ready -> GateStep.Proceed(run)
            EchoReadiness.OldSchema -> GateStep.Stop(Paused(UploadPause.OLD_SCHEMA))
            is EchoReadiness.Unavailable ->
                GateStep.Stop(Unreadable("echo-suppression store unavailable (${readiness.detail})"))
        }

        /** A running pass once the membership's received photos are [recognised] — never before. */
        fun recognition(run: Run, recognised: Boolean): UploadGate = when (recognised) {
            true -> run
            false -> Withheld(run.membership.event.id)
        }

        private fun hosted(read: GateRead.Read, host: UploadHost, presence: KeyPresence, admission: UploadAdmission) =
            when (host) {
                UploadHost.CARRIED -> joined(read, presence, admission)
                UploadHost.NONE -> NotJoined
            }

        private fun joined(read: GateRead.Read, presence: KeyPresence, admission: UploadAdmission) =
            when (val membership = read.membership) {
                null -> NotJoined
                is Membership.Deleted -> Withheld(membership.event.id)
                is Membership.Active -> admitted(membership, read, presence, admission)
            }

        private fun admitted(
            active: Membership.Active,
            read: GateRead.Read,
            presence: KeyPresence,
            admission: UploadAdmission,
        ) =
            when (presence is KeyPresence.Lost || admission == UploadAdmission.WITHHELD) {
                true -> Withheld(active.event.id)
                false -> Run(active, read.version, read.device)
            }
    }
}

/** Whether the echo-suppression store can answer now. */
sealed interface EchoReadiness {
    data object Ready : EchoReadiness

    /** The app has not yet migrated it: the extension pauses rather than read a store it cannot. */
    data object OldSchema : EchoReadiness

    data class Unavailable(val detail: String) : EchoReadiness
}

/** One step of the gate: it stopped the pass at [Stop.gate], or the pass may go on to the next read. */
sealed interface GateStep {
    class Stop(val gate: UploadGate) : GateStep

    class Proceed(val run: UploadGate.Run) : GateStep

    companion object {
        /** [gate] as a step: a [UploadGate.Run] goes on, any other stops the pass there. */
        fun of(gate: UploadGate): GateStep = when (gate) {
            is UploadGate.Run -> Proceed(gate)
            else -> Stop(gate)
        }
    }
}
