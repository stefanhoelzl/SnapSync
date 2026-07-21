package app.snapsync.fake

import app.snapsync.ports.CrashReporting

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An honest in-memory [CrashReporting]: flips a constructor-injected cell on [start]. Whoever owns
 * the cell observes that the composition started reporting; the fake itself exposes only the port
 * (the honesty gate). Repeated starts are the contract's no-op — the cell just stays `true`.
 */
class InMemoryCrashReporting(private val started: MutableStateFlow<Boolean>) : CrashReporting {

    constructor() : this(MutableStateFlow(false))

    override fun start() {
        started.value = true
    }
}
