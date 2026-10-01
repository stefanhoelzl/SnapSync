package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which build number a platform's crash events are stamped with (see [crashDist]). */
class CrashDistTest {

    @Test
    fun ios_leaves_the_build_number_to_the_crash_report() {
        assertNull(crashDist(Platform.IOS, "2512"))
    }

    @Test
    fun android_stamps_the_build_it_runs() {
        assertEquals("2512", crashDist(Platform.ANDROID, "2512"))
    }

    @Test
    fun a_blank_build_number_stamps_nothing() {
        assertNull(crashDist(Platform.ANDROID, " "))
    }
}
