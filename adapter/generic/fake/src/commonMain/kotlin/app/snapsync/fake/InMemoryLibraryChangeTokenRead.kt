package app.snapsync.fake

import app.snapsync.model.RawAsset
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import kotlinx.coroutines.flow.StateFlow

/**
 * The honest in-memory [LibraryChangeTokenRead] (capability `port-contracts`), held to `LibraryChangeTokenContract`
 * exactly as the PhotoKit read is.
 *
 * A token is the library **value** it was read at: every change to the caller's [library] cell replaces that value,
 * so a token read after it never compares equal to one read before, and two reads with nothing in between compare
 * equal although they are distinct token objects. Identity of the held value is the comparison on purpose — a
 * content comparison would call a remove-then-restore "unchanged", which the platform's token does not.
 */
internal class InMemoryLibraryChangeTokenRead(private val library: StateFlow<List<RawAsset>>) : LibraryChangeTokenRead {

    override suspend fun current(): LibraryChangeToken = Token(library.value)

    private class Token(private val readAt: List<RawAsset>) : LibraryChangeToken {
        override fun sameLibraryAs(other: LibraryChangeToken): Boolean = other is Token && other.readAt === readAt
    }
}
