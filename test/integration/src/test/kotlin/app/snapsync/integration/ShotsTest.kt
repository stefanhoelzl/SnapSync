package app.snapsync.integration

import kotlin.test.Test

/**
 * Every marketing screenshot's state is reached on the JVM host, on every build — so the scenarios `screenshots.yml`
 * captures from on a simulator cannot rot unnoticed between its dispatches.
 */
class ShotsTest {

    @Test
    fun the_create_shot_is_reached() = rigTest { reach(Shot.CREATE) { device("relaunch") } }

    @Test
    fun the_joining_shot_is_reached() = rigTest { reach(Shot.JOINING) { device("relaunch") } }

    @Test
    fun the_in_sync_shot_is_reached() = rigTest { reach(Shot.IN_SYNC) { device("relaunch") } }
}
