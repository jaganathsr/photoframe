package net.jaganath.framehome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Handles the sleep/wake alarms and re-arms them after boot, time changes and app updates. */
public class ScheduleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case Schedule.ACTION_SLEEP:
                Schedule.goodnight(c);
                break;
            case Schedule.ACTION_WAKE:
                Schedule.wakeNow(c);
                break;
            case Intent.ACTION_BOOT_COMPLETED:
                // Power came back during the night (e.g. outage): go back to sleep in 5 minutes.
                if (Schedule.enabled(c) && Schedule.inSleepWindow(c)) {
                    Schedule.scheduleOneOff(c, Schedule.ACTION_SLEEP, 5 * 60_000L,
                            Schedule.REQ_BOOT_SLEEP);
                }
                break;
            case Intent.ACTION_MY_PACKAGE_REPLACED: {
                // After a (remote) update the app restarts: go back to showing photos.
                android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
                if (pm != null && pm.isInteractive()) Schedule.startSlideshow(c);
                break;
            }
            default:
                break;
        }
        Schedule.reschedule(c);
        WebService.start(c);
    }
}
