package app.snapsync.domain.delivery

import app.snapsync.domain.mobiledata.PhotoNetwork
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.report.BackgroundRefresh
import app.snapsync.domain.report.Charging
import app.snapsync.domain.report.DeviceConditionsReading
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.Platform
import app.snapsync.domain.report.StandbyBucket
import app.snapsync.domain.report.Thermal
import app.snapsync.domain.sharing.PhotoAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The words each delivery-context fact is written in (decision record `changes/delivery-record`, D8). */
class DeliveryFactsTest {
    private val ios = DeviceConditionsReading(
        powerSaving = Fact.Known(false),
        backgroundRefresh = Fact.Known(BackgroundRefresh.AVAILABLE),
        standbyBucket = Fact.Unsupported,
        batteryOptimizationExempt = Fact.Unsupported,
        batteryPercent = Fact.Known(80),
        charging = Fact.Known(Charging.UNPLUGGED),
        thermal = Fact.Known(Thermal.NOMINAL),
    )

    @Test
    fun the_header_joins_the_facts_in_order_and_strips_its_own_separators() {
        assertEquals(
            mapOf(DeliveryFacts.HEADER to "model=abc;os=ios"),
            DeliveryFacts.header(listOf("model" to "a;b=c", "os" to "ios")),
        )
        assertEquals(emptyMap(), DeliveryFacts.header(emptyList()))
    }

    @Test
    fun the_os_is_the_platform_and_the_version_number_it_states() {
        assertEquals(mapOf("os" to "ios", "os_version" to "26.6"), DeliveryFacts.os(Platform.IOS, "iOS 26.6"))
        assertEquals(mapOf("os" to "android", "os_version" to "15"), DeliveryFacts.os(Platform.ANDROID, "15"))
        assertEquals(mapOf("os" to "ios"), DeliveryFacts.os(Platform.IOS, "?"))
    }

    @Test
    fun a_production_build_is_a_release_any_other_a_dev_build() {
        assertEquals("release", DeliveryFacts.flavour("production"))
        assertEquals("dev", DeliveryFacts.flavour("development"))
        assertEquals("dev", DeliveryFacts.flavour("?"))
    }

    @Test
    fun the_build_number_is_kept_when_it_is_digits_and_left_out_otherwise() {
        assertEquals(mapOf("build" to "2140"), DeliveryFacts.build("2140"))
        assertEquals(mapOf("build" to "1"), DeliveryFacts.build("1"))
        assertEquals(emptyMap(), DeliveryFacts.build("?"))
        assertEquals(emptyMap(), DeliveryFacts.build(""))
        assertEquals(emptyMap(), DeliveryFacts.build("12a"))
    }

    @Test
    fun access_mobile_data_and_network_in_the_record_s_words() {
        assertEquals(listOf("full", "limited", "none"), PhotoAccess.entries.map(DeliveryFacts::access))
        assertEquals("1", DeliveryFacts.mobileData(PhotoNetwork.ANY))
        assertEquals("0", DeliveryFacts.mobileData(PhotoNetwork.UNRESTRICTED_ONLY))
        assertEquals(mapOf("network" to "restricted"), DeliveryFacts.network(NetworkAccess.Online(restricted = true)))
        assertEquals(mapOf("network" to "unrestricted"), DeliveryFacts.network(NetworkAccess.Online(false)))
        assertEquals(mapOf("network" to "offline"), DeliveryFacts.network(NetworkAccess.Offline))
        assertEquals(mapOf("network" to "blocked"), DeliveryFacts.network(NetworkAccess.Blocked))
        assertEquals(emptyMap(), DeliveryFacts.network(null))
    }

    @Test
    fun the_conditions_are_due_when_never_read_or_a_refresh_ago() {
        val at = Instant.parse("2026-11-10T12:00:00Z")
        assertTrue(ConditionFacts.due(null, at, 1.minutes))
        assertFalse(ConditionFacts.due(at, at + 30.seconds, 1.minutes))
        assertTrue(ConditionFacts.due(at, at + 1.minutes, 1.minutes))
    }

    @Test
    fun ios_background_is_background_app_refresh() {
        assertEquals(mapOf("low_power" to "0", "background" to "1"), ConditionFacts.of(ios, emptyMap()))
        val denied = ios.copy(powerSaving = Fact.Known(true), backgroundRefresh = Fact.Known(BackgroundRefresh.DENIED))
        assertEquals(mapOf("low_power" to "1", "background" to "0"), ConditionFacts.of(denied, emptyMap()))
    }

    @Test
    fun android_background_is_any_standby_bucket_but_the_restricted_one() {
        val android = ios.copy(backgroundRefresh = Fact.Unsupported, standbyBucket = Fact.Known(StandbyBucket.RARE))
        assertEquals("1", ConditionFacts.of(android, emptyMap())["background"])
        val restricted = android.copy(standbyBucket = Fact.Known(StandbyBucket.RESTRICTED))
        assertEquals("0", ConditionFacts.of(restricted, emptyMap())["background"])
    }

    @Test
    fun a_fact_the_platform_could_not_read_is_left_out_and_a_late_reading_keeps_the_last() {
        val unread = ios.copy(
            powerSaving = Fact.Failed("timeout"),
            backgroundRefresh = Fact.Failed("timeout"),
            standbyBucket = Fact.Unsupported,
        )
        assertEquals(emptyMap(), ConditionFacts.of(unread, emptyMap()))
        assertEquals(emptyMap(), ConditionFacts.of(unread.copy(powerSaving = Fact.Unsupported), emptyMap()))
        assertEquals(emptyMap(), ConditionFacts.of(unread.copy(standbyBucket = Fact.Failed("timeout")), emptyMap()))
        val previous = mapOf("low_power" to "1")
        assertEquals(previous, ConditionFacts.of(null, previous))
    }
}
