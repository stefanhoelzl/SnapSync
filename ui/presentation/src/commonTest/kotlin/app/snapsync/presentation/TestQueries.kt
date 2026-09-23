package app.snapsync.presentation

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.JoinLoad
import app.snapsync.model.UserQueries

/** A query bundle that answers nothing: every details load fails and no count is available. */
internal val noQueries: UserQueries = UserQueries(loadJoinDetails = { JoinLoad.Failed }, shareableCount = { _, _ -> null })

/** A query bundle whose join-details read is [load]; no count is available. */
internal fun joinDetails(load: suspend (String) -> JoinLoad): UserQueries =
    UserQueries(loadJoinDetails = load, shareableCount = { _, _ -> null })

/** A query bundle whose shareable count is [count]; every details load fails. */
internal fun counting(count: suspend (CaptureCutoff, CaptureCeiling?) -> Int?): UserQueries =
    UserQueries(loadJoinDetails = { JoinLoad.Failed }, shareableCount = count)
