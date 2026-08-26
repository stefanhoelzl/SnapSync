package app.snapsync.ios.urlsession

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The executable half of the transport-binding pin (capability `architecture-guards`, "The simulator
 * transport binding is asserted where it can be executed").
 *
 * `:test:architecture`'s `TransferSessionBindingTest` reads the two actuals as **source text**, because the
 * `iosArm64` one is compiled into every shipped binary and executed by nothing in this repo. This test
 * covers what text cannot: that the actual the build actually *selected* for this target really produces a
 * non-background configuration. Neither half subsumes the other — a file can name the right factory and
 * still not be the one linked.
 *
 * Deliberately in `iosSimulatorArm64Test` rather than `iosTest`: `iosTest` compiles for both targets, and
 * these assertions are true of exactly one of them.
 *
 * The identifier is the discriminator because it is the platform's own: a background configuration carries
 * the identifier it was created with, and a default configuration has none. That is a stronger check than
 * reading [transferSessionBinding], which is a string this repo writes — so both are asserted, and the
 * first is what would catch them disagreeing.
 */
class TransferSessionsTest {

    @Test
    fun `the simulator target yields a default configuration`() {
        val config = transferSessionConfiguration("app.snapsync.probe.binding")
        assertNull(
            config.identifier,
            "expected a default configuration on iosSimulatorArm64, but this one carries a session " +
                "identifier — which only a background configuration does. A background session transfers " +
                "nothing here: nsurlsessiond rejects every client with no bundle identifier, so every " +
                "transfer would end NSURLErrorDomain/-1 and the simulator host could not move a byte.",
        )
    }

    @Test
    fun `the simulator target reports the default binding`() {
        assertEquals(
            "default",
            transferSessionBinding,
            "the reported binding must match the configuration this target actually builds — the control " +
                "channel serves this value on /device/state, and a scenario decides what it may assert " +
                "from it.",
        )
    }

    /**
     * `allowsCellularAccess` is honoured on both configurations, so the transports' intent survives the
     * target split. `discretionary` and `sessionSendsLaunchEvents` are background-only and are deliberately
     * absent from this actual; asserting their values here would pin platform defaults this code does not
     * set, which is not this test's business.
     */
    @Test
    fun `the simulator configuration still allows cellular`() {
        assertEquals(true, transferSessionConfiguration("app.snapsync.probe.binding").allowsCellularAccess)
    }
}
