package app.snapsync.contracts

import app.snapsync.model.DateFormats
import app.snapsync.ports.DateFormatting
import kotlinx.datetime.LocalDateTime
import kotlin.test.assertEquals

/** The one state a platform's formatting is in: its own CLDR data, on the device's own locale. */
enum class DateFormattingState {
    /** The platform's formatting, as the app is handed it. */
    PLATFORM,
}

/**
 * What `DateFormatting` promises (`docs/architecture.md`, "Localization"): a skeleton names fields, never an order, a
 * month name or an hour cycle, and each locale writes them its own way — so the design system spells out none of them.
 * A tag with a region is that locale whatever the device's; a bare language speaks that language; no tag is the
 * device's own locale.
 *
 * A space inside an answer is compared as a plain space: CLDR puts a no-break or a narrow no-break space before `PM`
 * (and the platforms disagree on which, by version), which reads as a space. Every other character is compared as it is.
 */
object DateFormattingContract : Contract<DateFormattingState, DateFormatting>("DateFormatting") {

    /** Monday, 5 October 2026, 14:30 — a day, a month and an hour that read differently in every locale here. */
    private val AT = LocalDateTime(2026, 10, 5, 14, 30)

    private val covers = cells { on<DateFormatting>().answers(DateFormatting::formats).returns() }

    /** [formats]' reading of [AT] in [skeleton], its spaces plain. */
    private fun DateFormats.reads(skeleton: String): String =
        format(AT, skeleton).replace(' ', ' ').replace(' ', ' ')

    override val clauses = clauses {

        clause("A_SKELETON_FOLLOWS_THE_LOCALES_ORDER_AND_NAMES", DateFormattingState.PLATFORM, covers = covers) {
            assertEquals("5 Oct 2026", it.formats("en-GB").reads("yMMMd"), "British English puts the day first")
            assertEquals("Oct 5, 2026", it.formats("en-US").reads("yMMMd"), "American English puts the month first")
            assertEquals("Mo., 5. Okt.", it.formats("de-DE").reads("MMMEd"), "German names its own days and months")
        }

        clause("A_SKELETON_FOLLOWS_THE_LOCALES_HOUR_CYCLE", DateFormattingState.PLATFORM, covers = covers) {
            assertEquals("14:30", it.formats("en-GB").reads("jm"), "British English reads a 24-hour clock")
            assertEquals("2:30 PM", it.formats("en-US").reads("jm"), "American English reads a 12-hour clock")
        }

        clause("A_LONE_FIELD_READS_IN_FULL", DateFormattingState.PLATFORM, covers = covers) {
            assertEquals("Monday", it.formats("en-GB").reads("EEEE"), "a lone weekday is its full name")
            assertEquals("Oktober", it.formats("de-DE").reads("MMMM"), "a lone month is its full name")
        }

        clause("A_BARE_LANGUAGE_SPEAKS_IT", DateFormattingState.PLATFORM, covers = covers) {
            assertEquals("October", it.formats("en").reads("MMMM"), "a bare `en` reads English, whatever the device")
            assertEquals("Oktober", it.formats("de").reads("MMMM"), "a bare `de` reads German, whatever the device")
        }

        clause("NO_TAG_IS_THE_DEVICE_LOCALE", DateFormattingState.PLATFORM, covers = covers) {
            assertEquals(
                it.formats(null).reads("yMMMMEEEEdjm"),
                it.formats(" ").reads("yMMMMEEEEdjm"),
                "no tag and a blank one are both the device's own locale",
            )
            assertEquals("2026", it.formats(null).reads("y"), "the device's locale formats the value it was given")
        }
    }
}
