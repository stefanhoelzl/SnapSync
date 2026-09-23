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
 * including hosts nothing binds yet (the simulator's Swift `.xctest`), is kept in the spec so the next
 * binding starts from it.
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

    /**
     * The rig build of the app bundle on a simulator, ad-hoc signed with the App Group only. Its bundle
     * identifier is what lets `applesimutils` grant it photo access, which no test executable can hold, so it
     * is where PhotoKit runs under a real full grant. It is run live on every push by the `ios-contracts`
     * job (capability `port-contracts`, "In-app hosts CI can reach are run live over the rig"). Its Keychain
     * answers `-34018` to an explicit-group query, so it reaches none of `IOS_DEVICE_APP`'s Keychain states.
     */
    IOS_SIM_APP,

    /** The entitled app on a real device, reached through the rig. Recorded there, replayed in CI. */
    IOS_DEVICE_APP,

    /**
     * The upload extension process on a real device — its own process kind: the OS launches it, bounds each
     * `process()` call to about 60 s and kills it without notice past that, and only it receives `process()`
     * (measured, SE2, iOS 26.6). Production calls the upload-job API only from here, which is why the job
     * clauses record here although the app process can call the same API. The rig cannot reach it: a run is
     * requested and answered through the App Group, and the OS invokes the extension to perform it. Recorded
     * there, replayed in CI. Named for the extension TYPE, so another extension kind is another host.
     */
    IOS_DEVICE_PHOTOKIT_EXT,
}

/** Whether a binding is the honest fake, a real implementation run live, or a recording replayed. */
enum class BindingKind { Fake, Live, Replay }

/**
 * The host this process is, for bindings compiled into a source set shared by several targets — the
 * fakes' bindings in `commonTest`, which run on the JVM and the simulator alike. A binding for a single
 * target names its host literally instead, which is what the contract-coverage gate reads.
 */
expect val currentHost: Host
