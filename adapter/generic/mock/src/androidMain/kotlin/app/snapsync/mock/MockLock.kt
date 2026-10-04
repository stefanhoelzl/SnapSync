package app.snapsync.mock

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual fun mockLock(): MockLock = object : MockLock {
    private val lock = ReentrantLock()

    override fun <T> locked(block: () -> T): T = lock.withLock(block)
}
