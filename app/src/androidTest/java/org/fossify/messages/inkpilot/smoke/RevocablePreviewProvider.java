package org.fossify.messages.inkpilot.smoke;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test APK provider stays independent of Kotlin runtime in its separate process. */
public final class RevocablePreviewProvider extends ContentProvider {
    private File image() { return new File(getContext().getFilesDir(), "task5-preview.png"); }
    private SharedPreferences state() { return getContext().getSharedPreferences("task5-grant", 0); }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "grant":
                Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
                try (FileOutputStream output = new FileOutputStream(image())) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        throw new IOException("Synthetic image compression failed");
                } catch (IOException error) {
                    throw new IllegalStateException("Synthetic image unavailable", error);
                } finally {
                    bitmap.recycle();
                }
                if (!state().edit().putBoolean("readable", true).commit())
                    throw new IllegalStateException("Synthetic grant unavailable");
                break;
            case "corrupt":
                try (FileOutputStream output = new FileOutputStream(image())) {
                    output.write("not a decodable image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (IOException error) {
                    throw new IllegalStateException("Synthetic image unavailable", error);
                }
                if (!state().edit().putBoolean("readable", true).commit())
                    throw new IllegalStateException("Synthetic grant unavailable");
                break;
            case "revoke":
                if (!state().edit().putBoolean("readable", false).commit())
                    throw new IllegalStateException("Synthetic revoke unavailable");
                break;
            case "cleanup":
                image().delete();
                state().edit().clear().commit();
                break;
            default:
                throw new IllegalArgumentException("Unknown synthetic operation");
        }
        return new Bundle();
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!state().getBoolean("readable", false)) throw new SecurityException("Synthetic read grant revoked");
        return ParcelFileDescriptor.open(image(), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                                  String sortOrder) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
