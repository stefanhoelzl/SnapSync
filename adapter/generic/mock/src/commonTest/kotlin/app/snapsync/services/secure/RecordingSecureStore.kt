package app.snapsync.services.secure

import app.snapsync.model.SecureSlot
import app.snapsync.model.SecureStoreRead
import app.snapsync.model.StoredProtection
import app.snapsync.model.WriteOutcome
import app.snapsync.ports.SecureStore

/**
 * A [SecureStore] that **records what was asked of it**, per slot — the services' test double.
 *
 * The recording is the point, not the storage. Everything the secure-item services can get catastrophically
 * wrong is a question of *which slot was consulted and whether anything was written* — the 2026-07-20 split
 * identity was two successful reads of two different items, and the build-297 crash was a write that should
 * never have been attempted. Neither is visible in a return value, so the counters are what the assertions are
 * about.
 *
 * A slot's answer is [answers]`[slot]` (absent when unset). An accepted write updates it, so a
 * resolve-then-read sequence behaves like the real thing; [refuseWrites] answers every write with
 * [writeRefusal] instead and changes nothing.
 */
internal class RecordingSecureStore(
    vararg initial: Pair<SecureSlot, SecureStoreRead>,
) : SecureStore {

    val answers: MutableMap<SecureSlot, SecureStoreRead> = mutableMapOf(*initial)

    var refuseWrites: Boolean = false
    var writeRefusal: WriteOutcome = WriteOutcome.Failed("OSStatus -25308")

    val reads: MutableList<SecureSlot> = mutableListOf()
    val writes: MutableList<Pair<SecureSlot, String>> = mutableListOf()
    val migrations: MutableList<SecureSlot> = mutableListOf()
    val deletes: MutableList<SecureSlot> = mutableListOf()

    fun readsOf(slot: SecureSlot): Int = reads.count { it == slot }

    fun writesTo(slot: SecureSlot): List<String> = writes.filter { it.first == slot }.map { it.second }

    override fun read(slot: SecureSlot): SecureStoreRead {
        reads += slot
        return answers[slot] ?: SecureStoreRead.Absent
    }

    override fun write(slot: SecureSlot, value: String): WriteOutcome {
        writes += slot to value
        if (refuseWrites) return writeRefusal
        answers[slot] = SecureStoreRead.Found(value, StoredProtection.BACKGROUND_READABLE)
        return WriteOutcome.Ok
    }

    override fun migrateProtection(slot: SecureSlot): WriteOutcome {
        migrations += slot
        // In place, value preserved — the Keychain's SecItemUpdate supplies no value either.
        (answers[slot] as? SecureStoreRead.Found)?.let {
            answers[slot] = it.copy(protection = StoredProtection.BACKGROUND_READABLE)
        }
        return WriteOutcome.Ok
    }

    override fun delete(slot: SecureSlot): WriteOutcome {
        deletes += slot
        answers.remove(slot)
        return WriteOutcome.Ok
    }

    /** True when nothing was ever persisted, upgraded or deleted — the never-mint invariant's oracle. */
    fun untouched(): Boolean = writes.isEmpty() && deletes.isEmpty() && migrations.isEmpty()
}
