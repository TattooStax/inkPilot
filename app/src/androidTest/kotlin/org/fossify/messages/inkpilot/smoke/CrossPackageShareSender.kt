package org.fossify.messages.inkpilot.smoke

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

internal const val INGRESS_AUTHORITY = "org.fossify.messages.task11.cross-package"

/** Test APK owns this private provider; only a URI grant can let the target APK read it. */
class CrossPackageShareProvider : ContentProvider() {
    private val source get() = File(requireNotNull(context).cacheDir, "task11-share.png")
    override fun onCreate() = true
    override fun getType(uri: Uri) = "image/png"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Runs in the test APK UID to grant its private URI through the old exported share alias. */
class CrossPackageShareSender : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = File(cacheDir, "task11-share.png")
        if (intent.action == CLEANUP) {
            source.delete()
        } else {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val uri = Uri.parse(requireNotNull(intent.getStringExtra("shareUri")))
            startActivity(Intent().setClassName(requireNotNull(intent.getStringExtra("targetPackage")),
                "org.fossify.messages.activities.NewConversationActivity").apply {
                action = Intent.ACTION_SEND
                type = "image/png"
                putExtra(Intent.EXTRA_TEXT, "Private client outline")
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Reference", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }
        finish()
    }

    companion object { const val CLEANUP = "task11.cleanup" }
}
