package app.snapsync.objc

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSError
import platform.Foundation.NSFileNoSuchFileError

/**
 * The two helpers every Kotlin/Objective-C crossing goes through (law "ObjC boundaries contain every throw",
 * capability `module-architecture`; decision record `harden-seam-bug-classes`, D9). The ObjC-boundary gate in
 * `:test:architecture` holds the adapters to them.
 *
 * **Inbound — [objcBoundary].** A Kotlin exception that unwinds into Objective-C frames does not become an
 * `NSException` a caller could catch: Kotlin/Native terminates the process. So every Kotlin block handed to an
 * ObjC API (a PhotoKit change block, a completion handler, a `dispatch_async` body, a notification block) and every
 * delegate method Kotlin overrides runs its body inside this: a throw is logged at `Error` — a real fault, so it
 * reaches crash reporting — and the ObjC side gets [fallback] and a normal return. This is the one place in
 * production source that catches `Throwable` *including* cancellation, deliberately: nothing may cross, and a
 * cancellation surfacing here has no coroutine above it left to cancel.
 *
 * **Outbound — [checkedObjC].** An ObjC API that reports failure through a `Boolean` result and an `NSError**`
 * out-parameter was being called with `error = null` and its result dropped, so a refused background-task submit
 * or a failed PhotoKit change left no trace anywhere. This allocates the out-parameter, reads the result, and hands
 * the caller a [Result] whose failure carries the platform's domain, code and description ([ObjCFailure]). It does
 * not log: whether a failure is a fault, a warning or expected (removing a file that is already gone) is the call
 * site's to say — and a log writer rolling its own file cannot log at all.
 */
inline fun <R> objcBoundary(log: Logger, name: String, fallback: R, block: () -> R): R =
    try {
        block()
    } catch (t: Throwable) {
        log.e(t) { "$name: a throw reached the Objective-C boundary — contained, returning the fallback" }
        fallback
    }

/** [objcBoundary] for a block or delegate method that returns nothing. */
inline fun objcBoundary(log: Logger, name: String, block: () -> Unit) = objcBoundary(log, name, Unit, block)

/** A failure an Objective-C API reported through its `Boolean`/`NSError**` result. */
class ObjCFailure(val call: String, val domain: String?, val code: Long?, val description: String?) :
    Exception("$call failed: domain=$domain code=$code $description") {
    companion object {
        fun of(call: String, error: NSError?) =
            ObjCFailure(call, error?.domain, error?.code, error?.localizedDescription)
    }
}

/**
 * Run an ObjC [call] that reports success as a `Boolean` and failure through the `NSError**` it is handed, and
 * return its outcome as a [Result] instead of discarding it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
inline fun checkedObjC(name: String, call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean): Result<Unit> = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (call(error.ptr)) Result.success(Unit) else Result.failure(ObjCFailure.of(name, error.value))
}

/**
 * [checkedObjC] for an ObjC [call] that reports success as a non-nil value (`attributesOfItemAtPath`,
 * `URLForDirectory`, …) and failure as `nil` plus the `NSError**`.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
inline fun <T : Any> checkedObjCValue(name: String, call: (CPointer<ObjCObjectVar<NSError?>>) -> T?): Result<T> =
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        call(error.ptr)?.let { Result.success(it) } ?: Result.failure(ObjCFailure.of(name, error.value))
    }

/**
 * Whether this is Foundation's "no such file" — the expected answer when removing a file that is already gone
 * (a staged resource PhotoKit moved in, a roll target on the first roll), so a caller can pass it over and still
 * report every other failure.
 */
val Throwable.isNoSuchFile: Boolean
    get() = this is ObjCFailure && domain == NSCocoaErrorDomain && code == NSFileNoSuchFileError
