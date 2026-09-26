package app.snapsync.rig

import app.snapsync.ports.Completion

/**
 * The completion handler the rig hands an event port when it plays the operating system (`/os`): the verb's own
 * `done`, called once when the app releases it. It carries no expiry of its own — the rig has no operating-system
 * signal to forward — so the app's release after its work is the only one it can receive.
 */
fun rigCompletion(done: () -> Unit): Completion = object : Completion {
    private var released = false

    override fun complete() {
        if (released) return
        released = true
        done()
    }

    override fun onExpired(action: () -> Unit) = Unit
}
