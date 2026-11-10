package app.snapsync.data.backend

import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.mobiledata.MobileDataStore
import app.snapsync.data.network.NetworkReadings
import app.snapsync.domain.delivery.DeliveryFacts
import app.snapsync.domain.mobiledata.MobileDataChoice
import app.snapsync.domain.report.BackgroundRefresh
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.Platform
import app.snapsync.domain.report.StandbyBucket
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.BuildPlatform
import app.snapsync.mock.ClockMock
import app.snapsync.mock.DeviceConditionsMock
import app.snapsync.mock.inMemoryNetworkMonitor
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.NetworkAccess
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The delivery context every request carries (`DeliveryContext`; decision record `changes/delivery-record`, D8): one
 * header naming the device, its platform and its conditions in the words the service's request log reads, each part
 * silent when it cannot be read, and the device's conditions read at most once a minute.
 */
class DeliveryContextTest {
    private val device = testDeviceId("11111111-0000-4000-8000-000000000002")
    private val build = BuildInfoMock(
        osVersion = "iOS 18.6",
        deviceModel = "iPhone12,8",
        reporterEnvironment = "production",
        platform = BuildPlatform(Platform.IOS),
    ).port()
    private val clock = ClockMock(Instant.parse("2026-11-10T12:00:00Z"))
    private val conditions = DeviceConditionsMock()
    private val preferences = inMemoryPreferences()

    private fun context(network: NetworkAccess = NetworkAccess.Online(restricted = true)) = DeliveryContext(
        BuildContext(build),
        IdentityContext(FixedIdentity(DeviceIdRead.Ok(device, DeviceIdRead.Via.READ))),
        AccessContext(GrantRead(MutableStateFlow(LibraryGrant.LIMITED))),
        NetworkContext(NetworkReadings(inMemoryNetworkMonitor(MutableStateFlow(network)))),
        MobileDataContext(MobileDataStore(preferences)),
        ConditionsContext(conditions.port(), clock.port()),
    )

    @Test
    fun every_part_speaks_in_the_record_s_words() = runTest {
        MobileDataStore(preferences).write(MobileDataChoice.WIFI_ONLY)
        assertEquals(
            mapOf(
                DeliveryFacts.HEADER to "os=ios/18.6;model=iPhone12,8;flavour=release;device=$device;access=limited;" +
                    "network=restricted;mobile_data=0;low_power=0;background=1",
            ),
            context().headers(),
        )
    }

    @Test
    fun a_development_build_an_unrestricted_network_and_an_android_standby_bucket() = runTest {
        val android = BuildInfoMock(
            osVersion = "15",
            deviceModel = "samsung SM-A405FN",
            platform = BuildPlatform(Platform.ANDROID),
        )
        conditions.reading = conditions.reading.copy(
            backgroundRefresh = Fact.Unsupported,
            standbyBucket = Fact.Known(StandbyBucket.RESTRICTED),
            powerSaving = Fact.Known(true),
        )
        val header = DeliveryContext(
            BuildContext(android.port()),
            NetworkContext(NetworkReadings(inMemoryNetworkMonitor(MutableStateFlow(NetworkAccess.Online(false))))),
            ConditionsContext(conditions.port(), clock.port()),
        ).headers().getValue(DeliveryFacts.HEADER)
        assertEquals(
            "os=android/15;model=samsung SM-A405FN;flavour=dev;network=unrestricted;low_power=1;background=0",
            header,
        )
    }

    @Test
    fun a_part_that_cannot_be_read_says_nothing_and_nothing_at_all_sends_no_header() = runTest {
        val unread = DeliveryContext(
            IdentityContext(FixedIdentity(DeviceIdRead.Unavailable("locked"))),
            IdentityContext(FixedIdentity(DeviceIdRead.Absent)),
            ConditionsContext(conditions.port(), clock.port(), timeout = 1.seconds).also { conditions.held = true },
        )
        assertEquals(emptyMap(), unread.headers())
        assertEquals(emptyMap(), DeliveryContext().headers())
    }

    @Test
    fun the_conditions_are_read_at_most_once_a_minute() = runTest {
        val part = ConditionsContext(conditions.port(), clock.port())
        assertEquals("0", part.facts()["low_power"])
        conditions.reading = conditions.reading.copy(powerSaving = Fact.Known(true))
        clock.now = clock.now + 30.seconds
        assertEquals("0", part.facts()["low_power"], "within the minute, the last reading stands")
        clock.now = clock.now + 1.minutes
        assertEquals("1", part.facts()["low_power"])
    }

    @Test
    fun a_value_never_carries_the_header_s_separators() = runTest {
        val odd = BuildInfoMock(deviceModel = "a;b=c", reporterEnvironment = "development").port()
        assertEquals(
            "model=abc;flavour=dev",
            DeliveryContext(BuildContext(odd)).headers().getValue(DeliveryFacts.HEADER),
        )
    }

    @Test
    fun background_refresh_denied_on_ios_is_no_background() = runTest {
        conditions.reading = conditions.reading.copy(backgroundRefresh = Fact.Known(BackgroundRefresh.DENIED))
        assertEquals("0", ConditionsContext(conditions.port(), clock.port()).facts()["background"])
    }
}
