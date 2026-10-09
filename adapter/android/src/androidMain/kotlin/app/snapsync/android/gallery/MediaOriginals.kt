package app.snapsync.android.gallery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore

/**
 * The URI an item's **original** bytes are read through, since only the original is uploaded, complete.
 * Without it Android redacts a photo's location from what the app reads; with
 * `ACCESS_MEDIA_LOCATION` held, [MediaStore.setRequireOriginal] hands over the file as the camera wrote it. Without the
 * permission asking for the original throws, so the redacted bytes are read instead — the photo still travels.
 */
internal object MediaOriginals {
    fun of(context: Context, uri: Uri): Uri =
        if (context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            MediaStore.setRequireOriginal(uri)
        } else {
            uri
        }
}
