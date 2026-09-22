@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.keychain.contract

import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.maskKeys
import app.snapsync.contracts.parseAttributes
import app.snapsync.contracts.renderAttributes
import app.snapsync.keychain.KeychainApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFTypeRefVar
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain

/*
 * The Keychain's operating-system boundary as TEXT (capability `port-contracts`, "Hosts CI cannot reach are
 * recorded at the operating-system boundary and replayed on every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and
 * into `iosTest` otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a
 * call differently.
 */

/**
 * Answer attributes that change on every run without the operating system's substantive answer changing:
 * creation and modification dates and the item's content hash. Masked so a re-recording diffs only where
 * iOS moved. Extend when a second recording shows noise.
 */
internal val VOLATILE_KEYS = setOf("cdat", "mdat", "sha1")

private fun attributesOf(dictionary: CFDictionaryRef?): Map<*, *> =
    dictionary?.let { CFBridgingRelease(CFRetain(it)) as? Map<*, *> } ?: emptyMap<Any, Any>()

private fun call(symbol: String, vararg dictionaries: CFDictionaryRef?): String =
    "$symbol(" + dictionaries.joinToString(" | ") { renderAttributes(attributesOf(it)) } + ")"

/** Passes every call to [real] and records it, with the answer, in the clause block [recorder] has open. */
internal class RecordingKeychainApi(private val real: KeychainApi, private val recorder: Recorder) : KeychainApi {

    override fun add(attributes: CFDictionaryRef?): Int =
        real.add(attributes).also { recorder.record(call("SecItemAdd", attributes), "$it") }

    override fun copyMatching(query: CFDictionaryRef?, result: CPointer<CFTypeRefVar>?): Int {
        val status = real.copyMatching(query, result)
        // A copy of the answer for the tape; the +1 original stays with the caller, which releases it.
        val found = result?.pointed?.value?.let { CFBridgingRelease(CFRetain(it)) as? Map<*, *> }
        val answer = listOfNotNull("$status", found?.let(::renderAttributes)).joinToString(" ")
        recorder.record(call("SecItemCopyMatching", query), maskKeys(answer, VOLATILE_KEYS))
        return status
    }

    override fun update(query: CFDictionaryRef?, attributes: CFDictionaryRef?): Int =
        real.update(query, attributes).also { recorder.record(call("SecItemUpdate", query, attributes), "$it") }

    override fun delete(query: CFDictionaryRef?): Int =
        real.delete(query).also { recorder.record(call("SecItemDelete", query), "$it") }
}

/** Answers every call from one clause's recorded block, exactly and in order. */
internal class ReplayingKeychainApi(private val replayer: Replayer) : KeychainApi {

    override fun add(attributes: CFDictionaryRef?): Int = replayer.answer(call("SecItemAdd", attributes)).toInt()

    override fun copyMatching(query: CFDictionaryRef?, result: CPointer<CFTypeRefVar>?): Int {
        val answer = replayer.answer(call("SecItemCopyMatching", query))
        val space = answer.indexOf(' ')
        if (space < 0) return answer.toInt()
        // A +1 dictionary, as SecItemCopyMatching hands one out; the adapter releases it.
        result?.pointed?.value = CFBridgingRetain(parseAttributes(answer.substring(space + 1)))
        return answer.substring(0, space).toInt()
    }

    override fun update(query: CFDictionaryRef?, attributes: CFDictionaryRef?): Int =
        replayer.answer(call("SecItemUpdate", query, attributes)).toInt()

    override fun delete(query: CFDictionaryRef?): Int = replayer.answer(call("SecItemDelete", query)).toInt()
}
