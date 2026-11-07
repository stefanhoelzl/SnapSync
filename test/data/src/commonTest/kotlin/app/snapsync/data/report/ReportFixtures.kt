package app.snapsync.data.report

import app.snapsync.domain.crash.ReportedIds
import app.snapsync.domain.report.Fact

/** The ids a report carries where a test is about something else: in no event, no device id read, the app's process. */
internal val NO_IDS: ReportedIds = ReportedIds(event = null, device = Fact.Unsupported, process = "app.snapsync")
