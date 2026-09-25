package app.snapsync.compose

import app.snapsync.feature.membership.toJoinLoad
import app.snapsync.model.UserQueries

/**
 * The user-query bundle (`docs/architecture.md`, "Queries cross a lane-gated door"): the reads the status
 * container invokes, each awaited on the composition lane — so a store, photo-library or network read never runs
 * on the thread that asked, which for the shareable count used to be a composable effect on the main thread.
 *
 * Built beside [AppCore.userCommands], through the same decorator, and gated by the same lane test. A top-level
 * builder rather than an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]).
 */
internal fun AppCore.userQueriesFor(): UserQueries = UserQueries(
    loadJoinDetails = { id ->
        awaitingOnCoreLane("query.loadJoinDetails", "eventId=$id") { joinEvent.loadDetails(id).toJoinLoad() }
    },
    shareableCount = { cutoff, until ->
        awaitingOnCoreLane("query.shareableCount") { loadShareableCount(cutoff, until) }
    },
)
