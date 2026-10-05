package app.snapsync.ports

import app.snapsync.model.DeviceConditionsReading

/**
 * The device's power, battery, thermal and background-allowance state, as the operating system reports it to this app
 * — ONE external system, the platform's device-condition settings. Read only for a bug report (capability
 * `privacy-security`), and only by the app process: the upload extension sends none.
 *
 * iOS answers with `NSProcessInfo`, `UIDevice` and `UIApplication`; Android with `PowerManager`, `UsageStatsManager` and
 * the battery's sticky broadcast. A field the platform has no notion of answers `Fact.Unsupported`, and one whose read
 * fails answers `Fact.Failed` with why — [read] itself never throws for one field's sake.
 */
interface DeviceConditions : Port {
    /** One reading of every field. May hop to whatever thread the platform requires. */
    suspend fun read(): DeviceConditionsReading
}
