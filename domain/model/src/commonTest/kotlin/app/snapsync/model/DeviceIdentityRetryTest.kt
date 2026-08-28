package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **`by lazy` does not memoize a thrown initializer** — pinned here rather than inherited.
 *
 * The iOS composition root holds its device identity as `private val deviceId: String by lazy { … }`, and a
 * resolve that threw must be retried on the next access. On a **locked** device the secure store raises
 * `SecureStoreUnavailable` rather than serving an id — deliberately, since minting there would hand the
 * device a second identity — so a failed touch is an ordinary, recoverable state that the next unlocked
 * access resolves. If `lazy` cached the failure instead, one early touch would poison the value for the
 * life of the process, and the device would run identity-less until relaunch — silently, since nothing
 * would re-attempt.
 *
 * That behaviour is a property of `SynchronizedLazyImpl` (`_value` is assigned only on the success path),
 * which is a **standard-library implementation detail this code now depends on**. Depending on one without
 * pinning it is how a language or stdlib upgrade removes a guarantee nobody wrote down — so this test
 * exists to fail loudly if that ever changes, rather than letting the identity path fail quietly.
 *
 * Deliberately written against `lazy` itself rather than against `SnapSyncRoot`: the shell is untested by
 * project rule, and the property under test belongs to the delegate, not to the class using it.
 */
class DeviceIdentityRetryTest {

    @Test
    fun `a lazy whose initializer throws is retried on the next access`() {
        var attempts = 0
        val value: String by lazy {
            attempts++
            if (attempts < 3) error("the secure store cannot serve an identity yet") else "device-abc"
        }

        assertFailsWith<IllegalStateException> { value }
        assertFailsWith<IllegalStateException> { value }

        assertEquals("device-abc", value, "the third access must reach the initializer, not a cached failure")
        assertEquals(3, attempts, "each failed access re-ran the initializer")
    }

    @Test
    fun `a lazy memoizes the first success and stops re-running`() {
        var attempts = 0
        val value: String by lazy { attempts++; "device-abc" }

        repeat(5) { assertEquals("device-abc", value) }

        assertEquals(1, attempts, "a resolved identity is read once per process, not per use")
    }
}
