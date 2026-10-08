package app.snapsync.nogrant

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.gallery.AndroidGallery
import app.snapsync.android.gallery.AndroidGalleryReader
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.GalleryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's gallery with no photo grant, on the emulator: an APK that declares no photo permission, so nothing is ever
 * granted. [NoGrantContractTest] runs it. Never asked is the adapter with no record of having asked; refused is its own
 * record that it asked — what a member's "Don't allow" leaves, written as its completion writes it. In this module's
 * main code because only the main compilation sees `:adapter:android`; the module is test-only.
 */
internal class NoGrantRun {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val foreground by lazy { ForegroundActivity(context.applicationContext as Application) }

    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(GalleryState.NEVER_ASKED, GalleryState.REFUSED)

        override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
            if (state !in reaches) return Entered.Unreachable("this APK declares no photo permission: nothing is granted")
            record(asked = state == GalleryState.REFUSED)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val permission = AndroidPhotoPermission(context, foreground)
            return Entered.Ready(GalleryChange(AndroidGallery(context, permission, scope).recorded(log)) {})
        }
    }

    /** The library reader over the same grant read the composition hands it: the permission adapter's. */
    private val galleryReader = object : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(GalleryReaderState.NEVER_ASKED, GalleryReaderState.REFUSED)

        override fun create(
            state: GalleryReaderState,
            clauseId: String,
            log: CallLog,
        ): Entered<SeededLibrary<GalleryReader>> {
            if (state !in reaches) return Entered.Unreachable("this APK declares no photo permission: nothing is granted")
            record(asked = state == GalleryReaderState.REFUSED)
            val permission = AndroidPhotoPermission(context, foreground)
            return Entered.Ready(SeededLibrary(AndroidGalleryReader(context, permission::current).recorded(log)))
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(PhotoAccessState.NEVER_ASKED, PhotoAccessState.REFUSED)

        override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> {
            if (state !in reaches) return Entered.Unreachable("this APK declares no photo permission: nothing is granted")
            record(asked = state == PhotoAccessState.REFUSED)
            return Entered.Ready(PhotoAccess(AndroidPhotoPermission(context, foreground).recorded(log)))
        }
    }

    /** Writes the permission adapter's record of having asked, as its completion writes it — or clears it. */
    private fun record(asked: Boolean) {
        val record = context.getSharedPreferences(RECORD, Context.MODE_PRIVATE).edit()
        if (asked) record.putBoolean(ASKED, true).commit() else record.clear().commit()
    }

    fun run() {
        verify(GalleryContract, gallery)
        verify(GalleryReaderContract, galleryReader)
        verify(PhotoAccessContract, photoAccess)
    }

    private companion object {
        /** `AndroidPhotoPermission`'s record of having asked: the preferences file and the key its completion writes. */
        const val RECORD = "photo_permission"
        const val ASKED = "asked"
    }
}
