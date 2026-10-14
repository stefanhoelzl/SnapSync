package app.snapsync.levers

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** [GalleryLevers.addMoving]'s photo: the capture [date] (ISO-8601 instant) and the camera's [files]. */
@Serializable
data class MovingPhoto(val date: String, val files: List<MovingFile>)

/** One file a camera wrote: its [name] as the camera named it, its bytes as base64 [data]. */
@Serializable
data class MovingFile(val name: String, val data: String) {
    @OptIn(ExperimentalEncodingApi::class)
    fun bytes(): ByteArray = Base64.decode(data)

    /** A movie by its extension — the paired video of a Live Photo. */
    val isMovie: Boolean get() = name.substringAfterLast('.').lowercase() in setOf("mov", "mp4")
}
