package app.snapsync.model

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The matching contract a sealed vocabulary's consumers rely on: every case in [cases] is a value no other case
 * equals, so a `when` over the family can tell them apart.
 *
 * [kind] is the test's exhaustive `when` over the family, which the compiler holds to every case: a case added to
 * the family fails to compile there, and the table beside it is where its sample goes. A table that samples one
 * kind twice fails here, so the table cannot stand in for a missing case by repeating another.
 */
internal fun <T : Any> assertEveryCaseDistinct(cases: List<T>, kind: (T) -> String) {
    val kinds = cases.map(kind)
    assertEquals(kinds.distinct(), kinds, "the table samples a kind twice")
    cases.forEachIndexed { i, a ->
        cases.forEachIndexed { j, b -> if (i != j) assertNotEquals(a, b, "$a and $b must be distinct values") }
    }
}

/** A payload case compares by its payload: [make] twice is one value, and [other] (a different payload) is not. */
internal fun <T : Any> assertValueEquality(make: () -> T, other: T) {
    assertEquals(make(), make())
    assertEquals(make().hashCode(), make().hashCode())
    assertNotEquals(make(), other)
}
