package app.snapsync.contracts

// Every Android process that runs contracts is on the emulator: a device-test APK, or the rig build of the app.
actual val currentHost: Host = Host.ANDROID_EMU
