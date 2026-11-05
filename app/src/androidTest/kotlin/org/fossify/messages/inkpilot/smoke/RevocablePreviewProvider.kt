package org.fossify.messages.inkpilot.smoke

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/** Real Binder read denial while MIME queries still work; installed only with the test APK. */
class RevocablePreviewProvider : ContentProvider() {
    private val file get() = File(requireNotNull(context).filesDir, "task5-preview.png")
    private val prefs get() = requireNotNull(context).getSharedPreferences("task5-grant", 0)
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method == "grant") {
            file.outputStream().use { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(prefs.edit().putBoolean("readable", true).commit())
        } else if (method == "corrupt") {
            file.writeText("not a decodable image")
            check(prefs.edit().putBoolean("readable", true).commit())
        } else if (method == "revoke") {
            check(prefs.edit().putBoolean("readable", false).commit())
        } else if (method == "cleanup") {
            file.delete()
            prefs.edit().clear().commit()
        } else error("Unknown test operation")
        return Bundle()
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!prefs.getBoolean("readable", false)) throw SecurityException("Synthetic read grant revoked")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
