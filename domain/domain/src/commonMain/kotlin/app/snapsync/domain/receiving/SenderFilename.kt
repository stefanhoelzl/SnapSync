package app.snapsync.domain.receiving

import kotlin.jvm.JvmInline

/**
 * The name a received photo's resource had on the sender's device — `IMG_4471.HEIC` — never empty. It is what
 * [ReceivedPhotoName.mark] keeps and marks, so a received photo always carries its sender's name.
 *
 * **Never empty, by construction.** Every upload names its file (the service refuses one that does not), and
 * nothing else writes a resource's name, so the event's union and the downloads planned from it always carry one
 * (change `a-received-photo-always-has-a-sender-filename`). An empty name is therefore a malformed input — a union
 * reply or a stored row this build cannot read — and is refused where it enters, as a value: [parse] is the one way
 * in, and it answers `null` for it.
 */
@JvmInline
value class SenderFilename private constructor(val value: String) {
    companion object {
        /** The sender filename [text] spells, or `null` when it is empty. */
        fun parse(text: String): SenderFilename? = text.takeIf { it.isNotEmpty() }?.let(::SenderFilename)
    }
}
