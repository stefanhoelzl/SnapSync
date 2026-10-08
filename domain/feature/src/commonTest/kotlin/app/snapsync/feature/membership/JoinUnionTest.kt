package app.snapsync.feature.membership

import app.snapsync.model.UnionPage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The union a provision already read, handed from the adoption to the download reconcile (capability
 * `receiving-photos`): held only during a provision and only for the event it provisions, and answered once — so a
 * join reads the union once and no later trigger plans from a union it did not read.
 */
class JoinUnionTest {

    private val union = JoinUnion()
    private val page = UnionPage(emptyList(), cursor = 7)

    @Test
    fun `an offer made during the provision is taken once`() = runTest {
        union.during("E") {
            union.offer("E", page)
            assertSame(page, union.take("E"))
            assertNull(union.take("E"), "a take answers the union once")
        }
    }

    @Test
    fun `an offer outside a provision is dropped`() = runTest {
        union.offer("E", page)
        union.during("E") { assertNull(union.take("E")) }
        assertNull(union.take("E"), "and nothing is held after it")
    }

    @Test
    fun `an offer for another event is dropped`() = runTest {
        union.during("E") {
            union.offer("OTHER", page)
            assertNull(union.take("E"))
        }
    }

    @Test
    fun `a take for another event answers nothing and leaves the held union`() = runTest {
        union.during("E") {
            union.offer("E", page)
            assertNull(union.take("OTHER"))
            assertSame(page, union.take("E"))
        }
    }

    @Test
    fun `a provision that throws still lets go of the union`() = runTest {
        assertFailsWith<IllegalStateException> {
            union.during("E") {
                union.offer("E", page)
                error("the provision failed")
            }
        }
        assertNull(union.take("E"))
        union.offer("E", page)
        assertNull(union.take("E"), "an offer after it is dropped too")
    }

    @Test
    fun `the provision's answer is during's`() = runTest {
        assertSame(page, union.during("E") { page })
    }
}
