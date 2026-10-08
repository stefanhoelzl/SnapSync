package app.snapsync.crypto

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

/**
 * The iOS primitives — CryptoKit's AES-GCM through its Swift bridge, CommonCrypto's HMAC, the Security framework's
 * generator — answer the published vectors every platform's crypto is held to, in the simulator's test executable.
 */
class IosCryptoContractTest {

    private val binding = object : Binding<CryptoState, Crypto> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(CryptoState.READY)
        override fun create(state: CryptoState, clauseId: String, log: CallLog): Entered<Crypto> = Entered.Ready(IosCrypto().recorded(log))
    }

    @Test
    fun `it satisfies the Crypto contract`() = verify(CryptoContract, binding)
}
