package app.snapsync.compose

import app.snapsync.application.mobiledata.SetMobileData
import app.snapsync.query.mobiledata.MobileDataLookup
import app.snapsync.query.mobiledata.MobileDataQuery
import app.snapsync.query.mobiledata.MobileDataStatuses

/**
 * The mobile-data choice's layers over [core] (`docs/patterns.md`): the change the menu's switch runs, the status it is
 * answered on, and the menu's read — each built once, on first touch.
 */
internal class MobileDataLayers(private val core: AppCore) {
    val statuses: MobileDataStatuses = MobileDataStatuses()

    val set: SetMobileData by lazy { SetMobileData(core.services.mobileData) }

    val query: MobileDataQuery by lazy { MobileDataLookup(core.services.mobileData, statuses) }
}
