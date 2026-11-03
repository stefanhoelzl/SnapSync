package app.snapsync.data.receiving

import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.receiving.StagingFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A transfer's tag — the one field the platform keeps across a relaunch (capability `receiving-photos`): it names the
 * photo, its resource and the event whose folder its bytes stage under, and a tag a ≤0.4 build started has three
 * fields and names no event (`PENDING_CLEANUPS.md`, "3-field download task tags").
 */
class TransferTagTest {
    private val a = ref("A")

    @Test
    fun `a tag names the photo, its resource and its event, and reads back as written`() {
        val tag = TransferTag(a, "a-live.mov", StagingFolder.of(EVENT.value))

        val read = TransferTag.parse(tag.text)!!
        assertEquals(a, read.ref)
        assertEquals("a-live.mov", read.resourceKey)
        assertEquals(EVENT.value, assertIs<StagingFolder.Event>(read.folder).name)
        assertSame(ResourceRole.LIVE, read.role)
        assertEquals("download-staging/${EVENT.value}/${ANNA.value}/a-live.mov", read.stagedAt.value)
    }

    @Test
    fun `a tag a 0_4 build started names no event and stages in the root`() {
        val legacy = TransferTag.parse("${ANNA.value}\nA\na-primary.heic")!!

        assertSame(StagingFolder.Legacy, legacy.folder)
        assertSame(ResourceRole.PRIMARY, legacy.role)
        assertEquals("download-staging/${ANNA.value}/a-primary.heic", legacy.stagedAt.value)
        assertEquals("${ANNA.value}\nA\na-primary.heic\n", legacy.text, "written back with an empty event")
        assertIs<StagingFolder.Legacy>(TransferTag.parse(legacy.text)!!.folder)
    }

    @Test
    fun `a text no build wrote is no tag`() {
        assertNull(TransferTag.parse("just one field"))
        assertNull(TransferTag.parse("a\nb\nc\nd\ne"))
        assertNull(TransferTag.parse("not-a-device\nA\nk\n${EVENT.value}"))
        assertNull(TransferTag.parse("${ANNA.value}\nno..id\nk\n${EVENT.value}"))
    }
}
