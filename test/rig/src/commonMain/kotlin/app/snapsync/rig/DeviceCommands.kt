package app.snapsync.rig

import app.snapsync.levers.LeverRefused

// The `/device` write commands BOTH hosts honour, with one request and response shape (capability
// `docs/testing.md`, "One control protocol, served by two hosts"). Each host supplies only the act; the
// parsing, the refusals and the rendering are here, so the two cannot drift into two dialects of one verb.

/**
 * A seed, or a moving photo, this host's library cannot hold: refused with [why] — never made as something else, since a
 * caller that asked for bytes on the wire and silently got flat fills would measure nothing.
 */
fun refused(why: String): Nothing = throw LeverRefused(why)
