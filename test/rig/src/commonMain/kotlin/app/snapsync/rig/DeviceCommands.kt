package app.snapsync.rig

import app.snapsync.rig.gallery.SeedKind
import app.snapsync.rig.gallery.SeedOutcome

// The `/device` write commands BOTH hosts honour, with one request and response shape (capability
// `docs/testing.md`, "One control protocol, served by two hosts"). Each host supplies only the act; the
// parsing, the refusals and the rendering are here, so the two cannot drift into two dialects of one verb.

/**
 * `POST /device/reset` — void durable sync state through the build's development controls' [reset].
 *
 * It answers that the reset ran, and nothing about the ledger: the ledger is the app's own bookkeeping, which the
 * channel no longer reads (`docs/testing.md`, "The control channel"). What a reset changed is observed where a person
 * would see it — the screen, and the uploads that follow.
 */
fun resetCommand(reset: suspend () -> Unit): RigCommand = RigCommand { _, _ ->
    reset()
    CommandResult.ok("""{"reset":true}""")
}

/**
 * `POST /device/gallery/seed?n=&kind=` — seed [SeedKind]-shaped assets through the host's [seed].
 *
 * A kind the host cannot honour answers `409` with the reason the host gives ([SeedRefused]), never a seed of
 * some other kind: a caller that asked for bytes on the wire and silently got flat fills would measure nothing.
 */
fun seedCommand(seed: suspend (n: Int, kind: SeedKind) -> SeedOutcome): RigCommand = RigCommand { params, _ ->
    val n = params["n"]?.toIntOrNull()
    val kind = SeedKind.entries.firstOrNull { it.name.equals(params["kind"], ignoreCase = true) }
    when {
        n == null || n <= 0 -> CommandResult.badRequest("n must be a positive integer, was '${params["n"]}'")
        kind == null -> CommandResult.badRequest(
            "kind must be one of ${SeedKind.entries.joinToString("|") { it.name.lowercase() }}, " +
                "was '${params["kind"]}'",
        )
        else -> try {
            val outcome = seed(n, kind)
            CommandResult.ok(
                """{"requested":${outcome.requested},"created":${outcome.created},""" +
                    """"kind":"${outcome.kind.name.lowercase()}","failedAtChunk":${outcome.failedAtChunk}}""",
            )
        } catch (refused: SeedRefused) {
            CommandResult.refused(refused.message.orEmpty())
        }
    }
}

/** A seed kind this host cannot produce, with the reason — answered `409` by [seedCommand]. */
class SeedRefused(reason: String) : Exception(reason)
