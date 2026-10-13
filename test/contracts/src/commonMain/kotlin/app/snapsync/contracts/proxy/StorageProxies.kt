@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts.proxy

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.contracts.CallLog
import app.snapsync.inbound.CompositionOnly
import app.snapsync.model.FileArea
import app.snapsync.model.SecureSlot
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.Databases
import app.snapsync.ports.Files
import app.snapsync.ports.PlatformDeviceId
import app.snapsync.ports.Preferences
import app.snapsync.ports.SecureStore

/** [Files] as its clause's [CallLog] sees it. */
fun Files.recorded(log: CallLog): Files = FilesProxy(log.wraps(this), log)

internal class FilesProxy(private val inner: Files, log: CallLog) : Files {
    private val r = log.recorder("Files")

    override fun read(area: FileArea, path: String) = r.answer("read", inner.read(area, path))
    override fun readTail(area: FileArea, path: String, maxBytes: Int) =
        r.answer("readTail", inner.readTail(area, path, maxBytes))
    override fun readRange(area: FileArea, path: String, offset: Long, maxBytes: Int) =
        r.answer("readRange", inner.readRange(area, path, offset, maxBytes))
    override fun write(
        area: FileArea,
        path: String,
        bytes: ByteArray,
    ) = r.answer("write", inner.write(area, path, bytes))
    override fun append(area: FileArea, path: String, bytes: ByteArray) =
        r.answer("append", inner.append(area, path, bytes))
    override fun delete(area: FileArea, path: String) = r.answer("delete", inner.delete(area, path))
    override fun exists(area: FileArea, path: String) = r.answer("exists", inner.exists(area, path))
    override fun locate(area: FileArea, path: String) = r.answer("locate", inner.locate(area, path))
    override fun move(area: FileArea, from: String, to: String) = r.answer("move", inner.move(area, from, to))
    override fun adopt(osPath: String, area: FileArea, to: String) = r.answer("adopt", inner.adopt(osPath, area, to))
    override fun list(area: FileArea, directory: String) = r.answer("list", inner.list(area, directory))
}

/** [Preferences] as its clause's [CallLog] sees it. */
fun Preferences.recorded(log: CallLog): Preferences = PreferencesProxy(log.wraps(this), log)

internal class PreferencesProxy(private val inner: Preferences, log: CallLog) : Preferences {
    private val r = log.recorder("Preferences")

    override fun get(key: String) = r.answer("get", inner.get(key))
    override fun set(key: String, value: String) = r.answer("set", inner.set(key, value))
    override fun remove(key: String) = r.answer("remove", inner.remove(key))
}

/** [SecureStore] as its clause's [CallLog] sees it. */
fun SecureStore.recorded(log: CallLog): SecureStore = SecureStoreProxy(log.wraps(this), log)

internal class SecureStoreProxy(private val inner: SecureStore, log: CallLog) : SecureStore {
    private val r = log.recorder("SecureStore")

    override fun read(slot: SecureSlot) = r.answer("read", inner.read(slot))
    override fun write(slot: SecureSlot, value: String) = r.answer("write", inner.write(slot, value))
    override fun delete(slot: SecureSlot) = r.answer("delete", inner.delete(slot))
}

/** [Databases] as its clause's [CallLog] sees it. */
fun Databases.recorded(log: CallLog): Databases = DatabasesProxy(log.wraps(this), log)

internal class DatabasesProxy(private val inner: Databases, log: CallLog) : Databases {
    private val r = log.recorder("Databases")

    override fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean) =
        r.answer("open", inner.open(name, schema, readOnly))

    @CompositionOnly
    override fun inbound(handlers: DatabaseHandlers) = r.returns(
        "inbound",
        inner.inbound(DatabaseHandlers(InboundProxy(handlers.onChangedElsewhere, r, "onChangedElsewhere", "String"))),
    )
}

/** [PlatformDeviceId] as its clause's [CallLog] sees it. */
fun PlatformDeviceId.recorded(log: CallLog): PlatformDeviceId = PlatformDeviceIdProxy(log.wraps(this), log)

internal class PlatformDeviceIdProxy(private val inner: PlatformDeviceId, log: CallLog) : PlatformDeviceId {
    private val r = log.recorder("PlatformDeviceId")

    override fun stableId() = r.returns("stableId", inner.stableId())
}
