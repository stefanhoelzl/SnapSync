package app.snapsync.android.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.model.EntryScope
import app.snapsync.model.GalleryAccess
import app.snapsync.model.invocation
import app.snapsync.ports.PhotoAccessStatusSource
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * The Android photo-permission adapter (capability `photo-access`): the status source, and — for the gallery — the
 * permission dialog and the selection sheet.
 *
 * | Android grants | reads as |
 * |---|---|
 * | `READ_MEDIA_IMAGES` **and** `READ_MEDIA_VIDEO` (API 33+) · `READ_EXTERNAL_STORAGE` (API 30–32) | [GalleryAccess.GRANTED] |
 * | only `READ_MEDIA_VISUAL_USER_SELECTED` (API 34+: "Select photos and videos") | [GalleryAccess.LIMITED] |
 * | nothing, after the app asked | [GalleryAccess.DENIED] |
 * | nothing, never asked | [GalleryAccess.NOT_DETERMINED] |
 *
 * Android does not report "never asked", so the adapter records in its own preferences that it has asked. Once the
 * member has answered, the dialog is not raised again — a refused member is taken to Settings instead, as on iPhone.
 *
 * `ACCESS_MEDIA_LOCATION` is requested alongside, so the originals keep their location (`MediaOriginals`). The status is
 * re-read whenever an activity is resumed: an access change made in Settings is picked up on return, and revoking one
 * there stops the process anyway.
 */
class AndroidPhotoPermission(
    context: Context,
    private val foreground: ForegroundActivity,
    private val log: Logger = Logger.withTag("photoPermission"),
) : PhotoAccessStatusSource {

    private val appContext = context.applicationContext
    private val record = appContext.getSharedPreferences(RECORD, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    private val requests = AtomicInteger()

    override val permission: StateFlow<GalleryAccess> = state

    init {
        foreground.onResumed { refresh() }
    }

    /** The grant as it stands now. Cheap, synchronous, never raises anything. */
    fun current(): GalleryAccess = read().also { state.value = it }

    /**
     * Ask for access while it was never decided, and answer the grant that results. With no activity in front, or once
     * the member has answered, it asks nothing and answers the grant as it stands (`Gallery.requestAccess`).
     */
    suspend fun requestAccess(): GalleryAccess =
        if (current() != GalleryAccess.NOT_DETERMINED) state.value else ask("requestAccess")

    /**
     * Android's selection sheet, for a partial grant (API 34+): asking again is how Android lets a member revise the
     * selection, or allow every photo. The selection arrives through the gallery's observer; this answers the grant.
     */
    suspend fun widenSelection(): GalleryAccess =
        if (current() != GalleryAccess.LIMITED) state.value else ask("widenSelection")

    private fun refresh() {
        log.invocation(
            EntryScope.None,
            "photoPermission.onResumed",
            result = { access: GalleryAccess -> "$access" },
        ) { current() }
    }

    private suspend fun ask(name: String): GalleryAccess = withContext(Dispatchers.Main) {
        val activity = foreground.current ?: return@withContext current().also {
            log.i { "$name: no activity in front — asked nothing" }
        }
        suspendCancellableCoroutine { cont ->
            val key = "snapsync.photoPermission.${requests.incrementAndGet()}"
            var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
            launcher = activity.activityResultRegistry.register(key, ActivityResultContracts.RequestMultiplePermissions()) { answers ->
                val granted = answers.filterValues { it }.keys.map { it.substringAfterLast('.') }
                val now = log.invocation(
                    EntryScope.None,
                    "photoPermission.$name.completion",
                    params = "granted=$granted",
                    result = { access: GalleryAccess -> "$access" },
                ) {
                    launcher?.unregister()
                    record.edit().putBoolean(ASKED, true).apply()
                    current()
                }
                if (cont.isActive) cont.resume(now)
            }
            launcher.launch(requested())
        }
    }

    private fun read(): GalleryAccess = when {
        full() -> GalleryAccess.GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && holds(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
            GalleryAccess.LIMITED
        record.getBoolean(ASKED, false) -> GalleryAccess.DENIED
        else -> GalleryAccess.NOT_DETERMINED
    }

    private fun full(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        holds(Manifest.permission.READ_MEDIA_IMAGES) && holds(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        holds(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun holds(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val RECORD = "photo_permission"
        const val ASKED = "asked"

        fun requested(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
    }
}
