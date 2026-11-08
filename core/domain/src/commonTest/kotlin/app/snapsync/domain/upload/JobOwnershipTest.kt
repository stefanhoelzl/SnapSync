package app.snapsync.domain.upload

import kotlin.test.Test
import kotlin.test.assertEquals

class JobOwnershipTest {

    @Test
    fun `an ownerless job on the byte route is a withdrawn row's, on any other an unmappable outcome`() {
        assertEquals(Ownerless.WITHDRAWN, JobRoute.UPLOAD.ownerless())
        assertEquals(Ownerless.UNMAPPABLE, JobRoute.OTHER.ownerless())
    }
}
