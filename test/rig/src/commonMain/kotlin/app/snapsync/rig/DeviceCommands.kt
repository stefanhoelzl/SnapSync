package app.snapsync.rig

import app.snapsync.levers.LeverRefused

// What the gallery service's writes refuse on every host: one refusal, so the hosts cannot drift into two dialects of
// one verb.

/**
 * A seed, or a moving photo, this host's library cannot hold: refused with [why] — never made as something else, since a
 * caller that asked for bytes on the wire and silently got flat fills would measure nothing.
 */
fun refused(why: String): Nothing = throw LeverRefused(why)
