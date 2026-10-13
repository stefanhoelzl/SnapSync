package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.CryptoContract
import app.snapsync.contracts.CryptoState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.Crypto
import kotlin.test.Test

/**
 * The stand-in [Crypto] the common tests run over, held to what every implementation keeps (`docs/testing.md`, "Mocks"):
 * its bytes are its own, so it is bound to the contract's `OWN_BYTES` state and never to the published vectors.
 */
class CryptoMockContractBindingTest {

    private val binding = object : Binding<CryptoState, Crypto> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = FakeCrypto::class
        override val reaches = setOf(CryptoState.OWN_BYTES)
        override fun create(state: CryptoState, clauseId: String, log: CallLog): Entered<Crypto> =
            if (state in reaches) {
                Entered.Ready(FakeCrypto().recorded(log))
            } else {
                Entered.Unreachable("its bytes are its own, not the standard algorithms'")
            }
    }

    @Test
    fun `the stand-in crypto keeps what every implementation keeps`() = verify(CryptoContract, binding)
}
