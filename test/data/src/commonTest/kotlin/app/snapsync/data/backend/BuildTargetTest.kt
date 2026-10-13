package app.snapsync.data.backend

import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DeclaredVersion
import kotlin.test.Test
import kotlin.test.assertEquals

/** The running build's own backend and version, read per request — so a version the build declares anew is sent. */
class BuildTargetTest {
    @Test
    fun the_target_is_the_builds_backend_and_the_version_it_declares_now() {
        val declared = DeclaredVersion("0.4")
        val target =
            BuildTarget(BuildInfoMock(uploadHost = "https://edge.test/api/v2", declaredVersion = declared).port())
        assertEquals("https://edge.test/api/v2", target.base)
        assertEquals("0.4", target.declaredVersion)
        declared.value = "0.5"
        assertEquals("0.5", target.declaredVersion)
    }
}
