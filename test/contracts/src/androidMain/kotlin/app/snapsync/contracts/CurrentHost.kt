package app.snapsync.contracts

// No binding runs on Android yet: the host an emulator binding names arrives with the first one (the Android storage
// adapters'), since the coverage gate fails a host value nothing binds. A shared binding asking before then is a
// wiring fault, stated rather than answered with a host that is not this one.
actual val currentHost: Host
    get() = error("no port-contract binding runs on Android yet, so there is no host for one to name")
