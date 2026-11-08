package app.snapsync.ports

import kotlin.time.Instant

/**
 * What a gallery read asks the platform for: the assets captured in `[createdFrom, createdUntil]`, either bound open
 * when `null`. A **hint**, never a decision: the platform narrows its native query by it as far as it can express it,
 * MAY return assets outside the range (iOS widens each bound by a day), and MUST NOT leave out one inside it — what
 * comes back is also a walk's presence set. Which assets a member shares is decided over the answer, by the caller.
 *
 * Only the date range: on iOS the lower bound is the narrowing a walk cannot do without (an unbounded walk is killed
 * by the watchdog before anything decides over it), and nothing else the platforms could narrow by is worth a second
 * statement of the sharing rules here.
 */
class AssetFilter(val createdFrom: Instant?, val createdUntil: Instant?)
