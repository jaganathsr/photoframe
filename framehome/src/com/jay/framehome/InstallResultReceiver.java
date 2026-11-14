package com.jay.framehome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Logs the result of a remote self-update (on success this process is replaced anyway). */
public class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Not device owner: show Android's "Install?" prompt on the frame.
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(confirm);
            }
            return;
        }
        Log.i("FrameHome", "update result " + status + ": "
                + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
