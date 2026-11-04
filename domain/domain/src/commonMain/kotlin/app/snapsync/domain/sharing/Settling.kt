package app.snapsync.domain.sharing

import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline

// Settling a share (change `settling-needs-a-real-look`): a member's device says its share
// is settled only with evidence — a look at what the member shares that succeeded and began after their own range
// ended, or a policy that shares nothing and so has nothing to look at. The evidence has one mint, its constructor's
// opt-in pinned to that one site (`OptInPinsTest`); never stored, never serialized, never a data class
// (`docs/patterns.md`, "Evidence types").

/** Only [ShareDeclaration.of] mints a [SettledShare]. */
@RequiresOptIn("Only ShareDeclaration.of settles a share.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
annotation class MintsSettledShare

/** The member's range ended [ended], and a look since then — or none needed — saw everything they share. */
@JvmInline
value class SettledShare @MintsSettledShare constructor(val ended: Timestamp)

/** What a publish follows: the look at what the member shares, or why there is none. */
sealed interface SettlingLook {
    /** A read that saw the whole of what the member shares from — the library walked, or the selection read. */
    class Looked(val discovery: Discovery) : SettlingLook

    /** The read could not be made: no look, and evidence of nothing. */
    data object CouldNotLook : SettlingLook

    /** The member shares nothing: there is nothing to look at. */
    data object NothingToLook : SettlingLook
}

/** What a device manifest declares of the member's share: still [Sharing], or [Settled] on its evidence. */
sealed interface ShareDeclaration {
    data object Sharing : ShareDeclaration

    class Settled(val evidence: SettledShare) : ShareDeclaration

    companion object {
        /** The declaration a publish following [look] makes, the member's range standing at [phase]. */
        fun of(phase: SharePhase, look: SettlingLook): ShareDeclaration = when (phase) {
            SharePhase.Sharing -> Sharing
            is SharePhase.Ended -> ended(phase.at, Proof.of(phase.at, look))
        }

        @OptIn(MintsSettledShare::class)
        private fun ended(at: Timestamp, proof: Proof): ShareDeclaration = when (proof) {
            Proof.PROVEN -> Settled(SettledShare(at))
            Proof.UNPROVEN -> Sharing
        }
    }

    /** Whether a look proves the share settled for a range that ended at a given moment. */
    private enum class Proof {
        PROVEN,
        UNPROVEN,
        ;

        companion object {
            fun of(at: Timestamp, look: SettlingLook): Proof = when (look) {
                is SettlingLook.Looked -> after(at, look.discovery.takenAt)
                SettlingLook.NothingToLook -> PROVEN
                SettlingLook.CouldNotLook -> UNPROVEN
            }

            // A look begun at or before the end may have missed a photo taken in its last moments.
            private fun after(at: Timestamp, takenAt: Timestamp): Proof = when (takenAt > at) {
                true -> PROVEN
                false -> UNPROVEN
            }
        }
    }
}

/** What this device last declared to an event, as its record holds it — a fact about what was SENT, not evidence. */
enum class PublishedDeclaration { NONE, SHARING, SETTLED }

/**
 * Whether a publish carrying a declaration goes out ([SEND]), or is skipped to keep a settlement an earlier look made
 * ([KEEP_SETTLED]): once the range has ended and a settled share was sent, a publish that cannot prove it again — its
 * look failed — would only take it back, and a later find only adds photos, which the backend counts by itself.
 */
enum class PublishVerdict {
    SEND,
    KEEP_SETTLED,
    ;

    companion object {
        /** Whether [declaration], the range standing at [phase], goes out after [last] was sent. */
        fun of(phase: SharePhase, declaration: ShareDeclaration, last: PublishedDeclaration): PublishVerdict =
            when (declaration) {
                is ShareDeclaration.Settled -> SEND
                ShareDeclaration.Sharing -> unproven(phase, last)
            }

        // A range moved later in the settings stands at Sharing: its Sharing goes out, and takes the settlement back.
        private fun unproven(phase: SharePhase, last: PublishedDeclaration): PublishVerdict = when (phase) {
            SharePhase.Sharing -> SEND
            is SharePhase.Ended -> afterEnd(last)
        }

        private fun afterEnd(last: PublishedDeclaration): PublishVerdict = when (last) {
            PublishedDeclaration.SETTLED -> KEEP_SETTLED
            PublishedDeclaration.NONE, PublishedDeclaration.SHARING -> SEND
        }
    }
}

/** Whether the end of a wake owes a settling look: [DUE] once the range has ended and no settled share was sent. */
enum class SettleNeed {
    DUE,
    NOT_DUE,
    ;

    companion object {
        /** The need, the member's range standing at [phase] and [last] sent. */
        fun of(phase: SharePhase, last: PublishedDeclaration): SettleNeed = when (phase) {
            SharePhase.Sharing -> NOT_DUE
            is SharePhase.Ended -> ended(last)
        }

        private fun ended(last: PublishedDeclaration): SettleNeed = when (last) {
            PublishedDeclaration.SETTLED -> NOT_DUE
            PublishedDeclaration.NONE, PublishedDeclaration.SHARING -> DUE
        }
    }
}
