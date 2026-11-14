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
        Log.i("FrameHome", "update result " + status + ": "
                + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
