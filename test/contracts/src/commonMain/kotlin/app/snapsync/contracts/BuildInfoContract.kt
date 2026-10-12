package app.snapsync.contracts

import app.snapsync.model.Platform
import app.snapsync.ports.BuildInfo
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the running build is, as far as a clause can tell it apart. */
enum class BuildInfoState {
    /** A build that reports crashes and is listed in a store (a distributed build's facts handed in). */
    REPORTING_AND_LISTED,

    /** A process with a bundle of its own — every app and extension build, distributed or not. */
    BUNDLED,

    /** A build that reports nowhere and is listed nowhere — every dev, sideload and simulator build. */
    UNREPORTED_AND_UNLISTED,

    /** A process with no bundle of its own — the simulator's test executable. */
    UNBUNDLED,

    /** A build running on Android. */
    ON_ANDROID,

    /** A build running on iOS. */
    ON_IOS,
}

/**
 * What the running build says of itself (`docs/architecture.md`): its version and backend, where it reports and is
 * listed — or `null`, never an empty value, where it does neither — the platform, OS and device it runs on. Each
 * answer is a constant of the running build, read from what the build was given or from the platform as it states it.
 */
object BuildInfoContract : Contract<BuildInfoState, BuildInfo>("BuildInfo") {

    override val clauses = clauses {

        clause(
            "REPORTING_AND_LISTED_NAMES_ITS_CHANNEL_AND_STORE",
            BuildInfoState.REPORTING_AND_LISTED,
            covers = cells {
                on<BuildInfo> {
                    answers(BuildInfo::dsn).returns()
                    answers(BuildInfo::store).returns()
                }
            },
        ) { build ->
            assertTrue(assertNotNull(build.dsn, "a reporting build names its channel").isNotBlank())
            assertTrue(assertNotNull(build.store, "a listed build names its store page").url.isNotBlank())
        }

        clause(
            "BUNDLED_NAMES_ITS_PROCESS_BUILD_DEVICE_AND_BACKEND",
            BuildInfoState.BUNDLED,
            covers = cells {
                on<BuildInfo> {
                    answers(BuildInfo::processId).returns()
                    answers(BuildInfo::appVersion).returns()
                    answers(BuildInfo::uploadHost).returns()
                    answers(BuildInfo::buildNumber).returns()
                    answers(BuildInfo::pushEnvironment).returns()
                    answers(BuildInfo::osVersion).returns()
                    answers(BuildInfo::deviceModel).returns()
                    answers(BuildInfo::reporterEnvironment).returns()
                }
            },
        ) { build ->
            assertTrue(assertNotNull(build.processId, "a process with a bundle names it").isNotBlank())
            assertTrue(build.appVersion.isNotBlank(), "every request declares the build's version")
            assertTrue(build.uploadHost.isNotBlank(), "and the backend it talks to")
            assertTrue(build.pushEnvironment.isNotBlank(), "a push token belongs to an environment")
            for ((fact, value) in listOf(
                "build number" to build.buildNumber,
                "OS version" to build.osVersion,
                "device model" to build.deviceModel,
                "reporter environment" to build.reporterEnvironment,
            )) {
                assertTrue(value.isNotBlank(), "the $fact is stated, never blank")
                assertTrue("null" !in value, "an absent $fact reads as ?, never the string null: $value")
            }
        }

        clause(
            "UNREPORTED_AND_UNLISTED_NAMES_NEITHER",
            BuildInfoState.UNREPORTED_AND_UNLISTED,
            covers = cells {
                on<BuildInfo> {
                    answers(BuildInfo::dsn).with(null)
                    answers(BuildInfo::store).with(null)
                }
            },
        ) { build ->
            assertNull(build.dsn, "a build that reports nowhere names no channel: nothing starts")
            assertNull(build.store, "and no store page to send a refused build to")
        }

        clause(
            "UNBUNDLED_NAMES_NO_PROCESS",
            BuildInfoState.UNBUNDLED,
            covers = cells { on<BuildInfo>().answers(BuildInfo::processId).with(null) },
        ) { build ->
            assertNull(build.processId, "a process with no bundle has no id to tag a report with")
        }

        clause(
            "ON_ANDROID_IS_ANDROID",
            BuildInfoState.ON_ANDROID,
            covers = cells { on<BuildInfo>().answers(BuildInfo::platform).with(Platform.ANDROID) },
        ) { build ->
            assertEquals(Platform.ANDROID, build.platform)
        }

        clause(
            "ON_IOS_IS_IOS",
            BuildInfoState.ON_IOS,
            covers = cells { on<BuildInfo>().answers(BuildInfo::platform).with(Platform.IOS) },
        ) { build ->
            assertEquals(Platform.IOS, build.platform)
        }
    }
}
