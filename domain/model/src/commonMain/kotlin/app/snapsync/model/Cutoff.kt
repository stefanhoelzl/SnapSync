package app.snapsync.model

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.time.Timestamp

/** Capture-date helpers: the deletion witness. */

/**
 * Has this membership's own retention deadline passed? [deletesAt] is the server-derived instant persisted on the
 * membership (`EventConfig.deletesAt`); [now] is the current canonical instant.
 *
 * This is the **second witness** of the self-leave. A membership is torn down without user action only
 * when the backend reports the event definitively absent **and** this returns `true` — two independent
 * witnesses, one of which is **offline**. A backend that reports absence alone is disbelieved, because a
 * systemic fault (a deployed configuration once named a storage zone that does not exist) would otherwise
 * 404 every event and destroy every membership in the install base at once, unrecoverably: `EventConfig`
 * is the only record of the join and the invite QR is derived from it.
 *
 * The check is **exact, not heuristic**. Early reclamation deletes an event only once *every* enrolled
 * device has departed, and departing clears the local config before the backend is told — so a device
 * that still holds a membership can only ever be observing a **deadline** deletion, which is precisely
 * what this tests.
 */
fun confirmedGone(deletesAt: DeletesAt, now: Timestamp): Boolean = now > deletesAt.at
