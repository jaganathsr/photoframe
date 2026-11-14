package net.jaganath.framehome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.text.format.DateFormat;

import java.util.Calendar;

/** All sleep/wake schedule logic and settings. */
final class Schedule {
    static final String ACTION_SLEEP = "net.jaganath.framehome.SLEEP";
    static final String ACTION_WAKE = "net.jaganath.framehome.WAKE";

    private static final int REQ_SLEEP = 1;
    private static final int REQ_WAKE = 2;
    static final int REQ_TEST_WAKE = 3;
    static final int REQ_BOOT_SLEEP = 4;

    static final String[] FOTOO_PACKAGES = {"com.bo.fotoo.amazon", "com.bo.fotoo"};

    private Schedule() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("framehome", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", true); }
    static int sleepMinute(Context c) { return prefs(c).getInt("sleep", 23 * 60); }
    static int wakeMinute(Context c) { return prefs(c).getInt("wake", 7 * 60); }
    static boolean fotooOnWake(Context c) { return prefs(c).getBoolean("fotooOnWake", true); }
    static boolean fotooOnBoot(Context c) { return prefs(c).getBoolean("fotooOnBoot", true); }

    static ComponentName adminComponent(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static boolean hasAdmin(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(adminComponent(c));
    }

    /** Next time (millis) the given minute-of-day occurs, at least ~1 minute in the future. */
    static long nextOccurrence(int minuteOfDay) {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        while (cal.getTimeInMillis() <= now + 30_000) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return cal.getTimeInMillis();
    }

    static boolean inSleepWindow(Context c) {
        Calendar now = Calendar.getInstance();
        int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int s = sleepMinute(c), w = wakeMinute(c);
        if (s == w) return false;
        return s < w ? (m >= s && m < w) : (m >= s || m < w);
    }

    private static PendingIntent pending(Context c, String action, int req) {
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** (Re)arms the next sleep and next wake alarms independently. Safe to call any time. */
    static void reschedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent sleep = pending(c, ACTION_SLEEP, REQ_SLEEP);
        PendingIntent wake = pending(c, ACTION_WAKE, REQ_WAKE);
        am.cancel(sleep);
        am.cancel(wake);
        if (!enabled(c)) return;
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(sleepMinute(c)), sleep);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextOccurrence(wakeMinute(c)), wake);
    }

    static void scheduleOneOff(Context c, String action, long delayMs, int req) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs, pending(c, action, req));
    }

    /** Turns the screen off. Returns false if the screen-off permission is missing. */
    static boolean sleepNow(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        if (dpm == null || !dpm.isAdminActive(adminComponent(c))) return false;
        dpm.lockNow();
        return true;
    }

    /** Turns the screen on and brings Fotoo (or the home screen) to the front. */
    @SuppressWarnings("deprecation")
    static void wakeNow(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE, "framehome:wake");
            wl.acquire(10_000);
        }
        Intent i = new Intent(c, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            c.startActivity(i);
        } catch (RuntimeException ignored) {
            // Screen is on anyway thanks to the wake lock; whatever was showing returns.
        }
    }

    static Intent fotooIntent(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : FOTOO_PACKAGES) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) return i;
        }
        return null;
    }

    /** Starts Frame Home's own Immich slideshow. */
    static void startSlideshow(Context c) {
        c.startActivity(new Intent(c, SlideshowActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    static String formatMinute(Context c, int minuteOfDay) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        return DateFormat.getTimeFormat(c).format(cal.getTime());
    }

    static String formatWhen(Context c, long millis) {
        return DateFormat.format("EEE", millis) + " "
                + DateFormat.getTimeFormat(c).format(new java.util.Date(millis));
    }
}
