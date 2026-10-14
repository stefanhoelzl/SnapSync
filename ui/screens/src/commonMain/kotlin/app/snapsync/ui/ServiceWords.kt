package app.snapsync.ui

import androidx.compose.runtime.Composable
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.load_failed_body
import app.snapsync.ui.resources.load_failed_body_updating
import app.snapsync.ui.resources.message_rename_trouble
import app.snapsync.ui.resources.message_rename_unreachable
import app.snapsync.ui.resources.message_rename_updating
import app.snapsync.ui.resources.message_service_trouble
import app.snapsync.ui.resources.message_service_unreachable
import app.snapsync.ui.resources.message_service_updating
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ServiceNotice
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// A failing service in words (decision record `changes/tell-service-faults`): each choice a plain mapping — checked
// where it is chosen — and the composable that says it, once.

/** A rename's failure as the rename dialog says it. */
@Composable
internal fun ScreenMessage.renameText(): String = stringResource(renameResource())

/**
 * The sentence a rename's failure is: a failing service is told together with what failed — the rename — every other
 * message as anywhere else.
 */
internal fun ScreenMessage.renameResource(): StringResource = when (this) {
    ScreenMessage.SERVICE_UNREACHABLE -> Res.string.message_rename_unreachable
    ScreenMessage.SERVICE_TROUBLE -> Res.string.message_rename_trouble
    ScreenMessage.SERVICE_UPDATING -> Res.string.message_rename_updating
    else -> resource()
}

/** How a failing service is told on the join screen, in the words its messages use everywhere. */
@Composable
internal fun ServiceNotice.text(): String = stringResource(resource())

/** The sentence a [ServiceNotice] is on the join screen. */
internal fun ServiceNotice.resource(): StringResource = when (this) {
    ServiceNotice.UNREACHABLE -> Res.string.message_service_unreachable
    ServiceNotice.TROUBLE -> Res.string.message_service_trouble
    ServiceNotice.UPDATING -> Res.string.message_service_updating
}

/** A failed load's body: a failing service says which way, and an update also that the event loads by itself. */
internal fun loadFailedBody(service: ServiceNotice?): StringResource = when (service) {
    null -> Res.string.load_failed_body
    ServiceNotice.UPDATING -> Res.string.load_failed_body_updating
    else -> service.resource()
}
