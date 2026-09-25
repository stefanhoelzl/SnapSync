package app.snapsync.keychain

import app.snapsync.ports.SecureStore

/** The device: the Keychain, for every slot. See the expect declaration. */
actual fun platformSecureStore(): SecureStore = IosSecureStore()
