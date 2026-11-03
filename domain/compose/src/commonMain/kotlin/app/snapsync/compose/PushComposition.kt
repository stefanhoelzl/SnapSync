package app.snapsync.compose

import app.snapsync.application.push.RegisterPush
import app.snapsync.query.push.PushTailLookup

/**
 * The push's layers over [core] (`docs/patterns.md`): the registration a delivered token, a new credential and a join
 * run, and the lookup that tells whether a silent push's wake joins the tail — each built once, on first touch.
 */
internal class PushLayers(private val core: AppCore) {
    val register: RegisterPush by lazy {
        RegisterPush(core.services.pushTokens, core.services.pushRecord, core.backend.push)
    }

    val tail: PushTailLookup by lazy { PushTailLookup(core.services.membership) }
}
