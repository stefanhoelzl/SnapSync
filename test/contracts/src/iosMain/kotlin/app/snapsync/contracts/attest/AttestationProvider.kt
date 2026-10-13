package app.snapsync.contracts.attest

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.cryptokit.CryptoKit

internal actual fun attestationProvider(): CryptographyProvider = CryptographyProvider.CryptoKit
