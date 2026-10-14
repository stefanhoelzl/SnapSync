package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test

/**
 * The **un-read status** pins, over the real composed stack, driven through the control protocol.
 *
 * These exist because the defect they cover was invisible to every other test in the repository. The
 * status projection reads three `StateFlow`s, and a `StateFlow` always has a value — so `combine`
 * emitted on its first dispatch, minting a snapshot from three placeholder zeros. `syncHealth` hides a
 * direction arrow when `synced >= total`, `0 >= 0` holds on BOTH arms, and the joined screen rendered a
 * check mark reading **"In sync"** on a device that had read nothing. Members reported it as the status
 * going backwards across launches, with no photos taken in between (`SNAPSYNC-14`, `SNAPSYNC-16`).
 *
 * No unit test could reach that state: the fakes seeded counts, so "never refreshed" was unreachable.
 * These drive the composed app, and assert the frame a member actually sees **before** any status read has
 * run — the cold-launch frame, reached by `/device/relaunch` while joined: the relaunched app performs no
 * status read until something triggers one. The foreground's refresh is that read.
 *
 * The load-bearing assertion in each is the NEGATIVE one: never `InSync` before a read. Each is followed by a
 * positive transition, which proves the projection was live during the wait — so the wait measured a real
 * absence of `InSync` rather than a dead screen.
 *
 * Not ported: `an_un_read_download_arm_alone_holds_the_screen_out_of_in_sync`. It needed a partial refresh —
 * the upload arm read, the download arm not — which no entry point performs, so the protocol cannot reach it
 * (design D8). That composed case is **uncovered**; its pieces stay in `StoreDownloadStatusSourceTest` and
 * `LedgerBackedSyncStatusSourceTest`.
 */
class UnreadStatusIntegrationTest {
    @Test
    @Verifies(spec = "sync-status", requirement = "\"Up to date\" is never claimed before the app has looked")
    fun a_joined_membership_with_photos_never_reads_in_sync_before_a_status_read() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            aPhotoTaken()
        }

        // The cold launch: a joined membership, photos in the library, and nothing read yet. The neutral first frame,
        // and it STAYS neutral: nothing has been counted, so there is nothing to be settled about. Before the fix the
        // projection minted a snapshot from three placeholder zeros and this read `Joined(InSync)` within a dispatch.
        whenever { device("relaunch") }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.Loading }
            neverInSync()
        }

        // Still neutral after work happens — because work is not a READ.
        whenever { cycle() }
        then { neverInSync() }

        // Once the counts are read the truth arrives — and it is "still working", not "settled".
        whenever { os("app", "onForeground") }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "\"Up to date\" is never claimed before the app has looked",
        scenario = "Nothing to share still settles",
    )
    fun a_counted_zero_still_settles_the_screen() = behaviour {
        // A download-only membership contributes nothing, so its upload total is a COUNTED zero — reached on the
        // selection policy's own no-upload branch without enumerating. The fix must not turn that legitimate
        // settled state into a permanent "Syncing…" (design D3).
        given {
            joinedEvent("direction" to "download")
            step("cold launched", act = { device("relaunch") }, until = { settled { true } })
            aPhotoTaken() // in the library, contributes nothing — must not hold the screen open
        }

        whenever { os("app", "onForeground") }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "\"Up to date\" is never claimed before the app has looked")
    fun `a failed enumeration leaves the total unknown and does not take its siblings down`() = behaviour {
        given {
            joinedEvent()
            aPhotoTaken()
            step("cold launched", act = { device("relaunch") }, until = { settled { true } })
        }

        // A platform walk can throw. The status refresh bounds it, because the refresh runs as one child of the
        // Foreground flow's scope — an escaping failure would cancel the download reconcile, the staged-byte
        // reclaim and the membership refresh, none of which have anything to do with enumerating a library
        // (design D5). The foreground must still be answered.
        //
        // The consequence, named rather than hidden: the total stays UNKNOWN, so the screen holds its neutral
        // line. It is deliberately not collapsed to `0`, which would read "In sync".
        whenever {
            library.failNextEnumeration()
            os("app", "onForeground")
        }
        then { neverInSync() }

        // And the next refresh recovers: the lever is one-shot, like a transient platform failure.
        whenever { os("app", "onForeground") }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } }
    }

    @Test
    @Verifies(spec = "sync-status", requirement = "\"Up to date\" is never claimed before the app has looked")
    @Verifies(spec = "photo-access", requirement = "A selection the app has not yet looked at withdraws nothing")
    fun `a limited grant whose selection has not arrived never reads in sync`() = behaviour {
        // A partial grant, a joined membership, photos in the library — and NO selection snapshot yet. On device
        // that window is the cold launch: the snapshot source's baseline read is a PhotoKit fetch plus an eager
        // per-asset resource read (~110 ms each), while the foreground status refresh is two SQLite reads and an
        // in-memory count. The refresh wins. Here the selection observer never emits, which holds that window
        // open.
        given { limitedMemberWithAPhoto() }

        // "We hold no selection" is not "the selection is empty". Collapsing them counts a zero, and a counted
        // zero SETTLES — on a member who has photos selected and simply has not been told which yet (the
        // `SNAPSYNC-14` / `SNAPSYNC-16` shape, one grant over from where it was fixed).
        whenever { os("app", "onForeground") }
        then { neverInSync() }
    }

    @Test
    fun `the arriving selection is what counts — and it counts a real total`() = behaviour {
        given {
            limitedMemberWithAPhoto()
            inFront()
        }

        // The sanctioned read lands. This is the ONLY thing that turns the un-answerable window into an answer —
        // and it proves the test above measured the window rather than a fixture that could never count at all.
        whenever { library.changeSelection(listOf("A")) }
        then { screen<Layer.Joined> { it.health is SyncHealth.Syncing } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "\"Up to date\" is never claimed before the app has looked",
        scenario = "Nothing to share still settles",
    )
    fun `an empty selection under a limited grant is still a counted zero`() = behaviour {
        given { limitedMemberWithAPhoto() } // the photo is in the library, but not selected — outside the scope

        // A snapshot DID arrive and it is empty. Receive-only under a partial grant is a valid resting state, so
        // this must still settle — the fix must not turn every limited
        // member's screen into a permanent "Syncing…".
        whenever {
            library.changeSelection(emptyList())
            os("app", "onForeground")
        }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The member takes a photo, which the app hears nothing of until it reads the library. */
    private suspend fun Given.aPhotoTaken() =
        step("a photo taken", act = { library.add("A") }, until = { settled { true } })

    /** A member who granted a partial selection and joined, with a photo in the library — and no selection read. */
    private suspend fun Given.limitedMemberWithAPhoto() {
        step("a partial grant", act = { library.permission(PhotoGrant.LIMITED) }, until = {})
        joinedEvent()
        aPhotoTaken()
    }

    /**
     * The screen does NOT reach the settled "In sync" frame — up to the settle, which is why each test follows it with
     * a positive transition proving the projection was alive.
     */
    private fun app.snapsync.control.behaviour.Expectations.neverInSync() =
        neverScreen<Layer.Joined> { it.health == SyncHealth.InSync }
}
