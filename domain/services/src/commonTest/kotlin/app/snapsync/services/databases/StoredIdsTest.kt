package app.snapsync.services.databases

import app.snapsync.data.store.Stored
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The old zones read an id column as they always did: the id, or a throw when the column spells none. */
class StoredIdsTest {

    @Test
    fun `a column that spells an id is that id`() = assertEquals("A", held(Stored.Valid("A")))

    @Test
    fun `a column that spells none throws, as its adapter once did`() {
        assertFailsWith<IllegalArgumentException> { held(Stored.Invalid("a/b")) }
    }
}
