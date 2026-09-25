package app.snapsync.ports

/**
 * This install's stable device id (capability `photo-sharing`): the `/files/devices/<deviceId>/` byte
 * partition, the manifest key, and the identity every backend call is made under.
 *
 * A port and not a `() -> String`, because resolving it **leaves the process and can throw** (law "Ports are
 * the I/O boundary named for the need", `docs/architecture.md`): on device it is a Keychain read,
 * and in the app a possible adopt-or-mint write. It used to be a thunk pinned as "a value the composition
 * already holds", which was true only after the first successful read — every earlier call, and every call
 * after a failed one, was a platform read no caller could see from the type.
 *
 * An implementation caches its first **success** for the process lifetime and never caches a failure, so a
 * resolve attempted while the device is locked is retried on the next call rather than fixed for the process.
 */
fun interface DeviceIdentity {

    /**
     * The device id.
     *
     * @throws SecureStoreUnavailable while protected data is unavailable ("I could not look").
     * @throws DeviceIdentityAbsent in a process that may not mint one and found none.
     *
     * Never returns a placeholder. A caller that can defer treats both exceptions as "skip and retry".
     */
    fun deviceId(): String
}
