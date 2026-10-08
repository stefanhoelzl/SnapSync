package app.snapsync.android.crypto

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.CryptoContract
import app.snapsync.contracts.CryptoState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.Crypto
import kotlin.test.Test

/** The platform's JCA provider (Conscrypt) answers the published vectors every platform's crypto is held to, on ART. */
class AndroidCryptoContractTest {

    private val binding = object : Binding<CryptoState, Crypto> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(CryptoState.READY)
        override fun create(state: CryptoState, clauseId: String, log: CallLog): Entered<Crypto> =
            Entered.Ready(AndroidCrypto().recorded(log))
    }

    @Test
    fun `it satisfies the Crypto contract`() = verify(CryptoContract, binding)
}
