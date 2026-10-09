package app.snapsync.ui.state

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlin.test.Test

/**
 * [UiIntent] is the one value a platform's screen hands the core (`docs/architecture.md`, "Events arrive through
 * `listen`"), and the container decides what each one means by matching on it. So every case must be a value no
 * other case equals, and a payload case must compare by its payload — a recorded tap is asserted by equality.
 */
class UiIntentVocabularyTest {

    private val at = LocalDateTime(2026, 7, 6, 14, 0)
    private val day = at.date

    @Suppress("CyclomaticComplexMethod") // one arm per case of a closed set: a table, not a decision tree
    private fun kind(intent: UiIntent): String = when (intent) {
        UiIntent.ConfirmCreate -> "ConfirmCreate"
        is UiIntent.CreateName -> "CreateName"
        is UiIntent.RangeDay -> "RangeDay"
        is UiIntent.RangeDragStart -> "RangeDragStart"
        is UiIntent.RangeDragEnd -> "RangeDragEnd"
        is UiIntent.RangeSweep -> "RangeSweep"
        is UiIntent.RangeFromHour -> "RangeFromHour"
        is UiIntent.RangeFromMinute -> "RangeFromMinute"
        is UiIntent.RangeUntilHour -> "RangeUntilHour"
        is UiIntent.RangeUntilMinute -> "RangeUntilMinute"
        UiIntent.RangeUntilMinuteDrag -> "RangeUntilMinuteDrag"
        UiIntent.RangePickerOpen -> "RangePickerOpen"
        UiIntent.RangePickerConfirm -> "RangePickerConfirm"
        UiIntent.RangePickerDismiss -> "RangePickerDismiss"
        UiIntent.RequestPermission -> "RequestPermission"
        UiIntent.ChoosePhotos -> "ChoosePhotos"
        UiIntent.OpenSettings -> "OpenSettings"
        UiIntent.LeaveEvent -> "LeaveEvent"
        UiIntent.ShareInvite -> "ShareInvite"
        UiIntent.OpenAppStore -> "OpenAppStore"
        UiIntent.ConfirmLeaveOpen -> "ConfirmLeaveOpen"
        UiIntent.ConfirmLeaveDismiss -> "ConfirmLeaveDismiss"
        UiIntent.RenameOpen -> "RenameOpen"
        UiIntent.RenameDismiss -> "RenameDismiss"
        UiIntent.QrOpen -> "QrOpen"
        UiIntent.QrDismiss -> "QrDismiss"
        UiIntent.ReportBugOpen -> "ReportBugOpen"
        is UiIntent.ReportRefusal -> "ReportRefusal"
        UiIntent.ReportBugDismiss -> "ReportBugDismiss"
        UiIntent.MenuOpen -> "MenuOpen"
        UiIntent.MenuDismiss -> "MenuDismiss"
        UiIntent.MenuReportBug -> "MenuReportBug"
        is UiIntent.MobileData -> "MobileData"
        is UiIntent.OpenLink -> "OpenLink"
        UiIntent.ReportNoticeDismiss -> "ReportNoticeDismiss"
        UiIntent.OpenReconfigure -> "OpenReconfigure"
        UiIntent.CancelReconfigure -> "CancelReconfigure"
        is UiIntent.RenameEvent -> "RenameEvent"
        UiIntent.RenameStatusConsumed -> "RenameStatusConsumed"
        UiIntent.ConfirmStopSharing -> "ConfirmStopSharing"
        UiIntent.KeepSharing -> "KeepSharing"
        is UiIntent.ShareOn -> "ShareOn"
        is UiIntent.ReceiveOn -> "ReceiveOn"
        is UiIntent.SaveToAlbum -> "SaveToAlbum"
        is UiIntent.RangePreset -> "RangePreset"
        UiIntent.RetryLoad -> "RetryLoad"
        UiIntent.ConfirmJoin -> "ConfirmJoin"
        UiIntent.ConfirmSwitch -> "ConfirmSwitch"
        UiIntent.RetryJoin -> "RetryJoin"
        UiIntent.CancelJoin -> "CancelJoin"
        UiIntent.CancelSwitch -> "CancelSwitch"
        is UiIntent.SendDiagnostics -> "SendDiagnostics"
    }

    @Test
    fun `every intent is a value no other intent equals`() {
        assertEveryCaseDistinct(
            listOf(
                UiIntent.ConfirmCreate,
                UiIntent.CreateName("Picnic"),
                UiIntent.RangeDay(day),
                UiIntent.RangeDragStart(day),
                UiIntent.RangeDragEnd(day),
                UiIntent.RangeSweep(day, day),
                UiIntent.RangeFromHour(1),
                UiIntent.RangeFromMinute(1),
                UiIntent.RangeUntilHour(1),
                UiIntent.RangeUntilMinute(1),
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
                UiIntent.ReportRefusal(ScreenMessage.DEVICE_UNVERIFIABLE),
                UiIntent.ReportBugDismiss,
                UiIntent.MenuOpen,
                UiIntent.MenuDismiss,
                UiIntent.MenuReportBug,
                UiIntent.MobileData(on = true),
                UiIntent.OpenLink(AppLink.WEBSITE),
                UiIntent.ReportNoticeDismiss,
                UiIntent.OpenReconfigure,
                UiIntent.CancelReconfigure,
                UiIntent.RenameEvent("event", "Picnic"),
                UiIntent.RenameStatusConsumed,
                UiIntent.ConfirmStopSharing,
                UiIntent.KeepSharing,
                UiIntent.ShareOn(on = true),
                UiIntent.ReceiveOn(on = true),
                UiIntent.SaveToAlbum(on = true),
                UiIntent.RangePreset(RangeChoice.WHOLE_EVENT),
                UiIntent.RetryLoad,
                UiIntent.ConfirmJoin,
                UiIntent.ConfirmSwitch,
                UiIntent.RetryJoin,
                UiIntent.CancelJoin,
                UiIntent.CancelSwitch,
                UiIntent.SendDiagnostics("note", "joined"),
            ),
            ::kind,
        )
    }

    @Test
    fun `a payload intent compares by its payload`() {
        assertValueEquality(
            { UiIntent.ReportRefusal(ScreenMessage.DEVICE_UNVERIFIABLE) },
            UiIntent.ReportRefusal(ScreenMessage.DEVICE_MODIFIED),
        )
        assertValueEquality({ UiIntent.MobileData(on = true) }, UiIntent.MobileData(on = false))
        assertValueEquality({ UiIntent.OpenLink(AppLink.WEBSITE) }, UiIntent.OpenLink(AppLink.PRIVACY_POLICY))
        assertValueEquality({ UiIntent.RenameEvent("event", "Picnic") }, UiIntent.RenameEvent("event", "Party"))
        assertValueEquality({ UiIntent.ShareOn(on = true) }, UiIntent.ShareOn(on = false))
        assertValueEquality({ UiIntent.ReceiveOn(on = true) }, UiIntent.ReceiveOn(on = false))
        assertValueEquality({ UiIntent.SaveToAlbum(on = true) }, UiIntent.SaveToAlbum(on = false))
        assertValueEquality(
            { UiIntent.RangePreset(RangeChoice.WHOLE_EVENT) },
            UiIntent.RangePreset(RangeChoice.FROM_NOW),
        )
        assertValueEquality({ UiIntent.CreateName("Picnic") }, UiIntent.CreateName("Party"))
        assertValueEquality({ UiIntent.RangeDay(day) }, UiIntent.RangeDay(day.plus(1, DateTimeUnit.DAY)))
        assertValueEquality({ UiIntent.RangeDragStart(day) }, UiIntent.RangeDragStart(day.plus(1, DateTimeUnit.DAY)))
        assertValueEquality({ UiIntent.RangeDragEnd(day) }, UiIntent.RangeDragEnd(day.plus(1, DateTimeUnit.DAY)))
        assertValueEquality({ UiIntent.RangeSweep(day, day) }, UiIntent.RangeSweep(day, day.plus(1, DateTimeUnit.DAY)))
        assertValueEquality({ UiIntent.RangeFromHour(1) }, UiIntent.RangeFromHour(2))
        assertValueEquality({ UiIntent.RangeFromMinute(1) }, UiIntent.RangeFromMinute(2))
        assertValueEquality({ UiIntent.RangeUntilHour(1) }, UiIntent.RangeUntilHour(2))
        assertValueEquality({ UiIntent.RangeUntilMinute(1) }, UiIntent.RangeUntilMinute(2))
        assertValueEquality({ UiIntent.SendDiagnostics("note", "joined") }, UiIntent.SendDiagnostics("note", "create"))
    }
}
