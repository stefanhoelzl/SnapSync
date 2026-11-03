package app.snapsync.data.upload

import app.snapsync.domain.upload.UploadKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * **What the latest walk of this process read**, handed once to the top-up that follows it: a row the walk just
 * recorded is created from the resource in hand rather than looked up a second time — one platform read of the same
 * asset, moments earlier, bought nothing but its cost (decision record `changes/selection-is-the-walk`, D5). Taken
 * once: a later top-up looks its rows up again.
 */
class RecentWalk {
    private val read = MutableStateFlow<Map<UploadKey, SendFrom>>(emptyMap())

    /** The resources a walk that recorded rows read. */
    fun put(read: Map<UploadKey, SendFrom>) {
        this.read.value = read
    }

    /** What the latest walk read, once — none after it was taken. */
    fun take(): Map<UploadKey, SendFrom> = read.getAndUpdate { emptyMap() }
}
