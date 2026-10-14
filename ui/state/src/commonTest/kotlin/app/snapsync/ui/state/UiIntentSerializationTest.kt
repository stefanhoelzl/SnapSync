package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every `UiIntent` round-trips through JSON, under its own name.
 *
 * The control channel's one person verb is a tap that carries the intent (`ScreenLevers.tap`), so an intent that failed
 * to round-trip would be a tap no test, journey or script could make. The encoder is compiler-generated; what this pins
 * is that every case stays serializable as the screen grows one, and that its serial name — what the `rig` CLI's
 * `--intent` names — is the case's own.
 */
class UiIntentSerializationTest {

    private val intents: List<UiIntent> = listOf(
        UiIntent.ConfirmCreate,
        UiIntent.CreateName("x"),
        UiIntent.KeepOpenFor(7),
        UiIntent.KeepOpenDismiss,
        UiIntent.RangeDay(LocalDate(2026, 6, 1)),
        UiIntent.RangeDragStart(LocalDate(2026, 6, 1)),
        UiIntent.RangeDragEnd(LocalDate(2026, 6, 1)),
        UiIntent.RangeSweep(LocalDate(2026, 6, 1), LocalDate(2026, 6, 1)),
        UiIntent.RangeFromHour(7),
        UiIntent.RangeFromMinute(7),
        UiIntent.RangeUntilHour(7),
        UiIntent.RangeUntilMinute(7),
        UiIntent.RangeUntilMinuteDrag,
        UiIntent.RangePickerOpen,
        UiIntent.RangePickerConfirm,
        UiIntent.RangePickerDismiss,
        UiIntent.RequestPermission,
        UiIntent.ChoosePhotos,
        UiIntent.OpenSettings,
        UiIntent.LeaveEvent,
        UiIntent.ShareInvite,
        UiIntent.OpenAppStore,
        UiIntent.ConfirmLeaveOpen,
        UiIntent.ConfirmLeaveDismiss,
        UiIntent.RenameOpen,
        UiIntent.RenameDismiss,
        UiIntent.QrOpen,
        UiIntent.QrDismiss,
        UiIntent.ReportBugOpen,
        UiIntent.ReportRefusal(ScreenMessage.entries.first()),
        UiIntent.ReportBugDismiss,
        UiIntent.MenuOpen,
        UiIntent.MenuDismiss,
        UiIntent.MenuReportBug,
        UiIntent.MobileData(true),
        UiIntent.OpenLink(AppLink.entries.first()),
        UiIntent.ReportNoticeDismiss,
        UiIntent.OpenReconfigure,
        UiIntent.CancelReconfigure,
        UiIntent.RenameEvent("x", "x"),
        UiIntent.RenameStatusConsumed,
        UiIntent.ConfirmStopSharing,
        UiIntent.KeepSharing,
        UiIntent.ShareOn(true),
        UiIntent.ReceiveOn(true),
        UiIntent.SaveToAlbum(true),
        UiIntent.RangePreset(RangeChoice.entries.first()),
        UiIntent.RetryLoad,
        UiIntent.ConfirmJoin,
        UiIntent.ConfirmSwitch,
        UiIntent.RetryJoin,
        UiIntent.CancelJoin,
        UiIntent.CancelSwitch,
        UiIntent.SendDiagnostics("x", "x"),
    )

    @Test
    fun every_intent_round_trips_under_its_own_name() {
        intents.forEach { intent ->
            val encoded = Json.encodeToString(UiIntent.serializer(), intent)
            assertEquals(intent, Json.decodeFromString(UiIntent.serializer(), encoded))
            assertEquals(
                intent::class.simpleName,
                Json.parseToJsonElement(encoded).jsonObject["type"]?.jsonPrimitive?.content,
            )
        }
    }

    /**
     * A tap missing a field the intent needs is refused, never made with a guess — every case that carries a value,
     * handed over with none (the deserializer's missing-field check, which the coverage gate counts).
     */
    @Test
    fun an_intent_missing_its_fields_is_refused() {
        intents.filter { Json.encodeToJsonElement(UiIntent.serializer(), it).jsonObject.size > 1 }.forEach { intent ->
            assertFailsWith<SerializationException>(intent::class.simpleName) {
                Json.decodeFromString(UiIntent.serializer(), """{"type":"${intent::class.simpleName}"}""")
            }
        }
    }

    /** The sealed serializer knows every case: a new one fails here until it joins [intents]. */
    @Test
    fun every_case_is_listed() {
        val cases = UiIntent.serializer().descriptor.getElementDescriptor(1)
        assertEquals(
            (0 until cases.elementsCount).map { cases.getElementName(it) }.toSet(),
            intents.map { it::class.simpleName }.toSet(),
            "a new intent joins the list",
        )
    }
}
