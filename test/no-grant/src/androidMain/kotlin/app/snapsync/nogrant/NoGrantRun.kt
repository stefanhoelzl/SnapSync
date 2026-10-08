package app.snapsync.nogrant

import android.app.Application
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.gallery.AndroidGallery
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
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

        override fun create(state: GalleryState, clauseId: String): Entered<GalleryChange> {
            if (state !in reaches) return Entered.Unreachable("this APK declares no photo permission: nothing is granted")
            val record = context.getSharedPreferences(RECORD, Context.MODE_PRIVATE).edit()
            if (state == GalleryState.REFUSED) record.putBoolean(ASKED, true).commit() else record.clear().commit()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val permission = AndroidPhotoPermission(context, foreground)
            return Entered.Ready(GalleryChange(AndroidGallery(context, permission, scope)) {})
        }
    }

    fun run() = verify(GalleryContract, gallery)

    private companion object {
        /** `AndroidPhotoPermission`'s record of having asked: the preferences file and the key its completion writes. */
        const val RECORD = "photo_permission"
        const val ASKED = "asked"
    }
}
