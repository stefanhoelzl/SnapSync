package app.snapsync.contracts

/**
 * Where a binding runs, as far as it changes which states an implementation can reach
 * (capability `port-contracts`, "Hosts are a closed set of what changes reachable states").
 *
 * Identity is platform x process kind x entitlements — the attributes known statically, so the
 * contract-coverage gate can read them from source. Operating-system version, device model, toolchain
 * and date are PROVENANCE, carried on a recording's header: an iOS update that changes an answer is a
 * change on the same host, with history, never a new host without any.
 *
 * Only hosts some binding names are listed; the gate fails an unused value. The measured matrix,
 * including hosts nothing binds yet (the simulator's Swift `.xctest`, the simulator app, the device
 * extension), is kept in the spec so the next binding starts from it.
 */
enum class Host {
    /** A JVM test task. No Keychain at all. */
    JVM,

    /**
     * The Kotlin/Native `test.kexe` spawned by `simctl`, unentitled. Every `SecItem*` call answers
     * `-25291` (`errSecNotAvailable`). NOT the same host as a Swift `.xctest` on the same simulator,
     * which answers `-34018` to the same calls — the distinction this name exists to keep.
     */
    IOS_SIM_KEXE,

    /** The entitled app on a real device, reached through the rig. Recorded there, replayed in CI. */
    IOS_DEVICE_APP,
}

/** Whether a binding is the honest fake, a real implementation run live, or a recording replayed. */
enum class BindingKind { Fake, Live, Replay }
