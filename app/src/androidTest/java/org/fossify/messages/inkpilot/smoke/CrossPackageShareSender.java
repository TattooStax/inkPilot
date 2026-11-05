package org.fossify.messages.inkpilot.smoke;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Standalone test APK process uses only Android and Java classes. */
public final class CrossPackageShareSender extends Activity {
    public static final String CLEANUP = "task11.cleanup";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File source = new File(getCacheDir(), "task11-share.png");
        File stage = new File(getCacheDir(), "task11-share-stage");
        if (CLEANUP.equals(getIntent().getAction())) {
            if (source.exists() && !source.delete()) throw new IllegalStateException("Test source cleanup failed");
            writeStage(stage, "cleaned");
        } else {
            stage.delete();
            writeStage(stage, "entered");
            Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            try (FileOutputStream output = new FileOutputStream(source)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            } catch (IOException error) {
                throw new IllegalStateException("Test image creation failed", error);
            } finally {
                bitmap.recycle();
            }
            String shared = getIntent().getStringExtra("shareUri");
            String target = getIntent().getStringExtra("targetPackage");
            if (shared == null || target == null) throw new IllegalArgumentException("Share fixture missing destination");
            Uri uri = Uri.parse(shared);
            writeStage(stage, "prepared");
            Intent share = new Intent(Intent.ACTION_SEND)
                .setClassName(target, "org.fossify.messages.activities.NewConversationActivity")
                .setType("image/png")
                .putExtra(Intent.EXTRA_TEXT, "Private client outline")
                .putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newRawUri("Reference", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(share);
            writeStage(stage, "forwarded");
        }
        finish();
    }

    private static void writeStage(File file, String value) {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException error) {
            throw new IllegalStateException("Test stage marker failed", error);
        }
    }
}
