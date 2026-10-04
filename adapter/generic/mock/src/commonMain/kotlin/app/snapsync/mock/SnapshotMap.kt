package app.snapsync.mock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A map safe to read while another thread writes it, guarded as every mock's shared state is: one [MutableStateFlow]
 * over an immutable value, each write an atomic swap to a new one. A read is always of one whole map — [snapshot], or
 * any iteration, which walks the map current when it began — so it can neither die half-way nor see a key that is not
 * there, as a plain map copied while the app writes it does (a racing `keys.toList()` returns `null` slots).
 *
 * Each call is atomic on its own; a caller's sequence of calls is not. A change spanning several calls needs [edit]; a
 * copy needs [snapshot] — the [MutableMap] views answer each call over the map current then, and a copy is several
 * calls (`toMap()` and `toList()` read the size, then iterate) that may each see a different map.
 */
internal class SnapshotMap<K, V> : AbstractMutableMap<K, V>() {
    private val cell = MutableStateFlow<Map<K, V>>(emptyMap())

    /** The whole map, now: immutable, so it stays what it was while the writes go on. */
    fun snapshot(): Map<K, V> = cell.value

    /** One atomic change computed from the current map — re-run if another write landed first, so keep it pure. */
    fun edit(change: (Map<K, V>) -> Map<K, V>) = cell.update(change)

    override val size: Int get() = cell.value.size

    override fun get(key: K): V? = cell.value[key]

    override fun containsKey(key: K): Boolean = key in cell.value

    override fun put(key: K, value: V): V? {
        var previous: V? = null
        cell.update { previous = it[key]; it + (key to value) }
        return previous
    }

    override fun remove(key: K): V? {
        var previous: V? = null
        cell.update { previous = it[key]; it - key }
        return previous
    }

    override fun putAll(from: Map<out K, V>) = cell.update { it + from }

    override fun clear() {
        cell.value = emptyMap()
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = this@SnapshotMap.size

            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean = throw UnsupportedOperationException()

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val walked = cell.value.entries.iterator()
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    private var last: K? = null
                    override fun hasNext() = walked.hasNext()
                    override fun next(): MutableMap.MutableEntry<K, V> = walked.next().let { Entry(it.key, it.value) }.also { last = it.key }

                    @Suppress("UNCHECKED_CAST")
                    override fun remove() {
                        this@SnapshotMap.remove(last as K)
                    }
                }
            }
        }

    private inner class Entry(override val key: K, override var value: V) : MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V = value.also { put(key, newValue); value = newValue }
        override fun equals(other: Any?) = other is Map.Entry<*, *> && other.key == key && other.value == value
        override fun hashCode() = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)
    }
}
