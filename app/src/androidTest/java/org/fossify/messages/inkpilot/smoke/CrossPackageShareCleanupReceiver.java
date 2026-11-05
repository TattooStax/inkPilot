package org.fossify.messages.inkpilot.smoke;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;

/** Ordered result proves source removal under the standalone sender APK's UID. */
public final class CrossPackageShareCleanupReceiver extends BroadcastReceiver {
    public static final String ACTION = "task11.cleanup-source";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) { setResultCode(0); return; }
        File source = new File(context.getCacheDir(), "task11-share.png");
        if (!source.exists()) { setResultCode(0); return; }
        setResultCode(source.delete() ? 1 : -2);
    }
}
