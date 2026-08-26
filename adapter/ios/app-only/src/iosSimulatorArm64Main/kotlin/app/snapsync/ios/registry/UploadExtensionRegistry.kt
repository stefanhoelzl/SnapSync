package app.snapsync.ios.registry

import app.snapsync.model.RegistrationOutcome
import app.snapsync.model.registrationOutcome
import app.snapsync.ports.UploadExtensionRegistry
import co.touchlab.kermit.Logger

/**
 * The simulator target's binding: **the registration record, held here instead of by the OS.**
 *
 * Rationale and the measurement are on the `expect` declaration. In short: this host refuses the
 * registration outright, with a code (`PHPhotosErrorDomain:-1`) that is not one of the expected ones — and
 * because it can never succeed, leaving it to fail would put a permanent `Error` on every join here,
 * destroying the "zero Error/Assert lines" health assertion that is the cheapest check a scripted scenario
 * has. Holding the record instead makes the tier's own contract drivable rather than merely quiet.
 */
actual fun uploadExtensionRegistry(log: Logger): UploadExtensionRegistry = SimulatorExtensionRegistry(log)

/**
 * The record, and the levers that make its failure modes reachable.
 *
 * **This is state the OS owns in production**, so unlike the upload-job queue it cannot be per-invocation:
 * the app changes it mid-join with no caller in the loop, and a reader asks for it at arbitrary times. It
 * is two fields, not a queue, and it matches the real record's nature — durable and external, surviving app
 * delete/reinstall and reboot.
 *
 * It does **not** survive a process restart here, which the real record does. That divergence is stated
 * rather than fixed: persisting it would let a record planted by one scenario silently condition the next,
 * which is the failure mode a rig can least afford.
 */
object SimulatorExtensionRecord {

    /** Whether a configuration record currently exists. */
    var registered: Boolean = false
        private set

    /**
     * A `PHPhotosError` code the **next** change should fail with, or `null` to let it succeed.
     *
     * One-shot rather than sticky: the ritual this exists to exercise is a disable→enable pair, and the
     * interesting scenario is a *stale record* whose disable succeeds and whose enable would have failed
     * (`3202`) — which a sticky lever could not express, because it would fail both halves.
     */
    var failNextWith: Long? = null
        private set

    /** Plant a record, or clear one — what a prior or differently-signed build would have left behind. */
    fun setRegistered(value: Boolean) {
        registered = value
    }

    /** Arm the next change to fail with [code], or disarm with `null`. */
    fun failNextWith(code: Long?) {
        failNextWith = code
    }

    internal fun consumeFailure(): Long? = failNextWith.also { failNextWith = null }
}

/**
 * A registry over [SimulatorExtensionRecord].
 *
 * The outcomes are the **tested classifier's**, not this class's: it reports the same three raw facts the
 * PhotoKit adapter reports — did the write succeed, and if not, which domain and code — and renders what
 * [registrationOutcome] decides. So a scenario driven here and a device exercising the same code path
 * cannot disagree about what an outcome means, which is the one place a substitute could quietly lie.
 */
private class SimulatorExtensionRegistry(private val log: Logger) : UploadExtensionRegistry {

    override suspend fun setEnabled(enabled: Boolean): RegistrationOutcome {
        val forced = SimulatorExtensionRecord.consumeFailure()
        // A disable against no record is a genuine failure on a real device (`3201`), not a courtesy
        // success — and the ritual's leading disable relies on exactly that outcome being classified as
        // expected. Reproducing it is what makes a clean-device join look here as it does there.
        val naturalFailure = if (!enabled && !SimulatorExtensionRecord.registered) PHOTOS_IDENTIFIER_NOT_FOUND else null
        val code = forced ?: naturalFailure
        if (code == null) SimulatorExtensionRecord.setRegistered(enabled)
        val outcome = registrationOutcome(
            enabling = enabled,
            ok = code == null,
            errorDomain = code?.let { PHOTOS_ERROR_DOMAIN },
            errorCode = code,
        )
        log.log(outcome.severity, log.tag, null, outcome.message)
        return outcome
    }

    override fun isEnabled(): Boolean? = SimulatorExtensionRecord.registered
}

/** Apple's own domain string, so a rendered outcome reads identically to a device's. */
private const val PHOTOS_ERROR_DOMAIN: String = "PHPhotosErrorDomain"

/** `PHPhotosErrorIdentifierNotFound` — "Unable to find the configuration", the clean-device disable. */
private const val PHOTOS_IDENTIFIER_NOT_FOUND: Long = 3201L
