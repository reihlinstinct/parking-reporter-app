package il.sidewalks.reporter

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Serves one generated JPEG as content:// so the share-entry instrumented
 * test exercises the real content:// path (file:// is refused by
 * EntryIntents). The provider runs in the test APK, a different package from
 * the app under test, so it is exported and serves the bytes itself: the
 * image is generated into this package's cacheDir on first use, never read
 * from the app's storage.
 */
class TestShareProvider : ContentProvider() {
    override fun onCreate() = true

    @Synchronized
    private fun jpeg(): File {
        val file = File(context!!.cacheDir, "testshare-synthetic.jpg")
        if (!file.isFile) {
            val tmp = File(context!!.cacheDir, "testshare-synthetic.jpg.tmp")
            val bmp = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
            bmp.setPixel(1, 1, Color.BLUE)
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            tmp.renameTo(file)
        }
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(jpeg(), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri) = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
