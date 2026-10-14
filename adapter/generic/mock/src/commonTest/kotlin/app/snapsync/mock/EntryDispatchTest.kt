@file:OptIn(CompositionOnly::class)

package app.snapsync.mock

import app.snapsync.contracts.answeredOf
import app.snapsync.contracts.backgroundOf
import app.snapsync.contracts.inboundOf
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.ProcessingAnswer
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkDelivery
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.SessionRelaunch
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.TransferNetwork
import app.snapsync.ports.TransferSource
import app.snapsync.ports.UiHandlers
import app.snapsync.ports.UploadTarget
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The event ports' dispatch (`docs/testing.md`, "Mocks"): what the platform reports reaches the ONE handler of the
 * bundle a composition registered that names it, carrying what the platform handed over — and no other handler.
 * Each test registers a bundle through the mock's port face, pulls the operator's lever, and reads what ran.
 */
class EntryDispatchTest {

    /** What ran, in order, each entry naming the handler and what it was handed. */
    private val ran = mutableListOf<String>()

    private val completion = object : Completion {
        override fun complete() = Unit
    }

    @Test
    fun `the app's foreground life reaches the foreground and background handlers`() {
        val lifecycle = LifecycleMock()
        lifecycle.port().inbound(
            LifecycleHandlers(
                onForeground = backgroundOf { _, done ->
                    ran += "foreground"
                    done.complete()
                },
                onBackground = inboundOf { ran += "background" },
            ),
        )

        lifecycle.foreground()
        lifecycle.background()

        assertEquals(listOf("foreground", "background"), ran)
    }

    @Test
    fun `an opened link reaches the link handler raw`() {
        val links = LinksMock()
        val delivered = mutableListOf<LinkDelivery>()
        links.port().inbound(LinkHandlers(onLink = inboundOf { delivered += it }))

        links.open("https://snapsync.app/e/1#k", hook = "onOpenURL")

        assertEquals(LinkDelivery.Link("onOpenURL", "https://snapsync.app/e/1#k"), delivered.single())
    }

    @Test
    fun `the push service's token and failure and message each reach their own handler`() {
        val push = PushServiceMock()
        val messages = mutableListOf<Pair<Map<Any?, *>, Completion>>()
        push.port().inbound(
            PushHandlers(
                onToken = inboundOf { ran += "token ${it.value}" },
                onTokenFailure = inboundOf { ran += "failure ${it.description}" },
                onMessage = backgroundOf { message, done ->
                    ran += "message"
                    messages += message.payload to done
                },
            ),
        )

        push.deliverToken("T1")
        push.deliverTokenFailure("no network")
        push.deliverMessage(mapOf("eventId" to "E"), completion)

        assertEquals(listOf("token T1", "failure no network", "message"), ran)
        assertEquals(mapOf<Any?, Any?>("eventId" to "E"), messages.single().first)
        assertSame(completion, messages.single().second, "the push's completion is handed over, not a stand-in")
    }

    @Test
    fun `a live screen and a tap reach the screen's two handlers`() {
        val screen = ScreenMock()
        screen.port().inbound(
            UiHandlers<UiState, UiIntent>(
                onIntent = inboundOf { ran += "intent $it" },
                onLive = inboundOf {
                    ran += "live"
                    UiState(Layer.CreatingEvent)
                },
            ),
        )

        screen.live()
        screen.tap(UiIntent.LeaveEvent)

        assertEquals(listOf("live", "intent ${UiIntent.LeaveEvent}"), ran)
        assertEquals(
            UiState(Layer.CreatingEvent),
            screen.shown.value,
            "the live screen renders what the app answered",
        )
    }

    @Test
    fun `the channel's reset reaches the reset handler`() = runTest {
        val dev = DevControlsMock()
        dev.port().inbound(
            DevHandlers(
                onReset = backgroundOf { _, done ->
                    ran += "reset"
                    done.complete()
                },
            ),
        )

        dev.operator.reset()

        assertEquals(listOf("reset"), ran)
    }

    @Test
    fun `the extension's invocation answers what the process handler returned and then its end`() = runTest {
        val host = ExtensionHostMock()
        host.port().inbound(
            ExtensionHandlers(
                onProcess = answeredOf {
                    ran += "process"
                    ProcessingAnswer.FAILED
                },
                onTerminate = inboundOf { ran += "terminate" },
            ),
        )

        assertEquals(ProcessingAnswer.FAILED, host.process(Dispatchers.Default))
        host.terminate()

        assertEquals(listOf("process", "terminate"), ran)
    }

    @Test
    fun `a transfer's end and a hand-back reach the upload session's handlers in the platform's order`() = runTest {
        val session = UploadSessionMock(network = { _, _, _ -> 201 }, held = { false })
        val handed = mutableListOf<Completion>()
        val upload = session.port()
        upload.inbound(
            TransferHandlers(
                onFinished = inboundOf { ran += "finished ${it.tag} ${it.failure ?: "ok"}" },
                onBackgroundEvents = backgroundOf { relaunch: SessionRelaunch, done ->
                    ran += "background events after ${relaunch.drainsBefore}"
                    handed += done
                },
                onEventsDrained = inboundOf { ran += "drained ${it.drains}" },
            ),
        )
        val target = UploadTarget(
            "https://edge.example/api/v2/files/devices/D/A/primary",
            emptyMap(),
            TransferNetwork.ANY,
        )
        assertEquals(CreateOutcome.CREATED, upload.create(TransferSource.File("staged/a"), target, "A-primary.jpg"))

        session.operator.complete("A-primary.jpg")
        session.handBack(completion)

        assertEquals(
            listOf("finished A-primary.jpg ok", "background events after 0", "drained 1"),
            ran,
        )
        assertSame(completion, handed.single(), "the relaunch's completion is handed over, not a stand-in")
    }
}
