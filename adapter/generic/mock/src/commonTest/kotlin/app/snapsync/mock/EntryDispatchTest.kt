package app.snapsync.mock

import app.snapsync.model.CycleResult
import app.snapsync.model.LinkDelivery
import app.snapsync.model.TransferNetwork
import app.snapsync.model.UiIntent
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadJobState
import app.snapsync.model.UploadSource
import app.snapsync.model.UploadTarget
import app.snapsync.ports.Completion
import app.snapsync.ports.DevHandlers
import app.snapsync.ports.ExtensionHandlers
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.PushHandlers
import app.snapsync.ports.UiHandlers
import app.snapsync.ports.UploadHandlers
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
        override fun onExpired(action: () -> Unit) = Unit
    }

    @Test
    fun `the app's foreground life reaches the foreground and background handlers`() {
        val lifecycle = LifecycleMock()
        lifecycle.port().listen(
            LifecycleHandlers(onForeground = { ran += "foreground" }, onBackground = { ran += "background" }),
        )

        lifecycle.operator.foreground()
        lifecycle.operator.background()

        assertEquals(listOf("foreground", "background"), ran)
    }

    @Test
    fun `an opened link reaches the link handler raw`() {
        val links = LinksMock()
        val delivered = mutableListOf<LinkDelivery>()
        links.port().listen(LinkHandlers(onLink = { delivered += it }))

        links.operator.open("https://snapsync.app/e/1#k", hook = "onOpenURL")

        assertEquals("https://snapsync.app/e/1#k", delivered.single().url)
        assertEquals("onOpenURL", delivered.single().hook)
    }

    @Test
    fun `the push service's token and failure and message each reach their own handler`() {
        val push = PushServiceMock()
        val messages = mutableListOf<Pair<Map<Any?, *>, Completion>>()
        push.port().listen(
            PushHandlers(
                onToken = { ran += "token ${it.value}" },
                onTokenFailure = { ran += "failure ${it?.description}" },
                onMessage = { message, done ->
                    ran += "message"
                    messages += message.payload to done
                },
            ),
        )

        push.operator.deliverToken("T1")
        push.operator.deliverTokenFailure("no network")
        push.operator.deliverTokenFailure(null)
        push.operator.deliverMessage(mapOf("eventId" to "E"), completion)

        assertEquals(listOf("token T1", "failure no network", "failure null", "message"), ran)
        assertEquals(mapOf<Any?, Any?>("eventId" to "E"), messages.single().first)
        assertSame(completion, messages.single().second, "the push's completion is handed over, not a stand-in")
    }

    @Test
    fun `a live screen and a tap reach the screen's two handlers`() {
        val screen = ScreenMock()
        screen.port().listen(UiHandlers(onIntent = { ran += "intent $it" }, onLive = { ran += "live" }))

        screen.operator.live()
        screen.operator.tap(UiIntent.LeaveEvent)

        assertEquals(listOf("live", "intent ${UiIntent.LeaveEvent}"), ran)
    }

    @Test
    fun `the channel's reset reaches the reset handler`() = runTest {
        val dev = DevControlsMock()
        dev.port().listen(DevHandlers(onReset = { ran += "reset" }))

        dev.operator.reset()

        assertEquals(listOf("reset"), ran)
    }

    @Test
    fun `the extension's invocation answers what the process handler returned and then its end`() = runTest {
        val host = ExtensionHostMock()
        host.port().listen(
            ExtensionHandlers(
                onProcess = {
                    ran += "process"
                    CycleResult.FAILED
                },
                onTerminate = { ran += "terminate" },
            ),
        )

        assertEquals(CycleResult.FAILED, host.operator.process())
        host.operator.terminate()

        assertEquals(listOf("process", "terminate"), ran)
    }

    @Test
    fun `a transfer's end and a hand-back reach the upload session's handlers in the platform's order`() = runTest {
        val session = UploadSessionMock(network = { _, _, _ -> 201 }, held = { false })
        val handed = mutableListOf<Completion>()
        val upload = session.port()
        upload.listen(
            UploadHandlers(
                onFinished = { ran += "finished ${it.tag} ${it.state}" },
                onBackgroundEvents = {
                    ran += "background events"
                    handed += it
                },
                onEventsDrained = { ran += "drained" },
            ),
        )
        val target = UploadTarget(
            "https://edge.example/api/v2/files/devices/D/A/primary",
            emptyMap(),
            TransferNetwork.ANY,
        )
        assertEquals(UploadCreateOutcome.CREATED, upload.create(UploadSource.File("staged/a"), target, "A-primary.jpg"))

        session.operator.complete("A-primary.jpg")
        session.operator.handBack(completion)

        assertEquals(
            listOf("finished A-primary.jpg ${UploadJobState.SUCCEEDED}", "background events", "drained"),
            ran,
        )
        assertSame(completion, handed.single(), "the relaunch's completion is handed over, not a stand-in")
    }
}
