package app.snapsync.format

import app.snapsync.domain.event.EventKeyId

/** An event key's id as the event row and the backend hold it: the format's id bytes in lowercase hex. */
object EventKeyIdText {
    fun of(bytes: ByteArray): EventKeyId =
        EventKeyId(bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') })
}
