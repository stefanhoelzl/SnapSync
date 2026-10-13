package app.snapsync.contracts.attest

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.providers.jdk.JDK

internal actual fun attestationProvider(): CryptographyProvider = CryptographyProvider.JDK
