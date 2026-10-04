package app.snapsync.mock

import platform.Foundation.NSRecursiveLock

internal actual fun mockLock(): MockLock = object : MockLock {
    private val lock = NSRecursiveLock()

    override fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
