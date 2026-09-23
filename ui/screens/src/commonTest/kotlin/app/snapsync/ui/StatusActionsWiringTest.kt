package app.snapsync.ui

import app.snapsync.feature.status.SyncStatusSource
import app.snapsync.model.JoinCommit
import app.snapsync.model.JoinLoad
import app.snapsync.model.PermissionStatus
import app.snapsync.model.ReconfigureOutcome
import app.snapsync.model.SyncStatus
import app.snapsync.model.UserCommands
import app.snapsync.model.UserQueries
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The ONE wiring table, `statusActions(host)`, reaches the container (capability `sync-status-screen`).
 *
 * It exists because the table used to be copied by hand into three hosts over bundles whose every action
 * defaulted to `{}`, and one copy dropped "Choose more photos" — a tappable button that did nothing, which no
 * test could see because each host's table was untested shell code. There is one table now, and this drives
 * it: the platform taps must arrive at the command bundle, through the real container.
 */
class StatusActionsWiringTest {

    private object Loading : SyncStatusSource {
        override val status = MutableStateFlow<SyncStatus>(SyncStatus.Loading)
    }

    private fun recording(fired: MutableStateFlow<Set<String>>): UserCommands {
        fun hit(name: String) = fired.update { it + name }
        return UserCommands(
            leave = { hit("leave") },
            create = { _, _, _ -> hit("create") },
            commitJoin = { _, _, _, _, _, _, _, _, _ -> hit("commitJoin"); JoinCommit.Failed },
            share = { hit("share") },
            requestAccess = { hit("requestAccess") },
            openSettings = { hit("openSettings") },
            openLink = { hit("openLink") },
            choosePhotos = { hit("choosePhotos") },
            reconfigure = { _, _, _, _, _ -> hit("reconfigure"); ReconfigureOutcome.Saved },
            rename = { _, _ -> hit("rename") },
            resetRename = { hit("resetRename") },
            sendDiagnostics = { _, _ -> hit("sendDiagnostics") },
        )
    }

    @Test
    fun `the one wiring table routes every platform tap to its command`() = runTest {
        withContext(Dispatchers.Default) {
            val fired = MutableStateFlow<Set<String>>(emptySet())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val host = StatusContainerHost(
                    StatusSources(Loading, MutableStateFlow(PermissionStatus.LIMITED), MutableStateFlow(null)),
                    scope = scope,
                    cutoffFormatter = CutoffFormatter(now = { Instant.parse("2026-07-06T12:00:00Z") }, zone = TimeZone.UTC),
                    commands = recording(fired),
                    queries = UserQueries(loadJoinDetails = { JoinLoad.Failed }, shareableCount = { _, _ -> null }),
                    diagnostics = StatusDiagnostics(log = {}, onIntentError = {}),
                )
                val actions = statusActions(host)

                actions.access.onRequestPermission()
                actions.access.onOpenSettings()
                actions.access.onChoosePhotos()
                actions.onCreateEvent("Party", LocalDateTime(2026, 7, 6, 12, 0), LocalDateTime(2026, 7, 8, 12, 0))
                actions.onSendDiagnostics?.invoke("note", "screen")

                val expected = setOf("requestAccess", "openSettings", "choosePhotos", "create", "sendDiagnostics")
                withTimeout(5.seconds) { fired.first { it.containsAll(expected) } }

                // The rest of the table has no command to observe on the create layer, but every entry must
                // still be a live route into this container rather than a no-op: invoking each must not throw.
                with(actions) {
                    join.onConfirmJoin(); join.onRetryJoin(); join.onAcknowledgeAccess(); join.onCancelJoin()
                    join.onRetryLoad()
                    joined.onLeaveEvent(); joined.onShareInvite(); joined.onReconfigure()
                    joined.onRenameEvent("E", "Name"); joined.onRenameStatusConsumed()
                    switch.onConfirmSwitch(); switch.onCancelSwitch()
                    surfaces.onConfirmLeaveOpen(); surfaces.onConfirmLeaveDismiss(); surfaces.onRenameOpen()
                    surfaces.onRenameDismiss(); surfaces.onOpenReconfigure(); surfaces.onCancelReconfigure()
                    surfaces.onReportBugOpen(); surfaces.onReportBugDismiss()
                    onOpenLink("https://apps.apple.com/app/id0")
                    participation.onShareOn(true); participation.onReceiveOn(true); participation.onSaveToAlbum(true)
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
