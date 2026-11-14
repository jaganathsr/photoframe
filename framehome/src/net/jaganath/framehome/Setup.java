package net.jaganath.framehome;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The permissions and settings Frame Home needs, how to check each one, and which Android screen
 * fixes it. Lets people set everything up on the frame itself, without ADB.
 */
final class Setup {
    enum Level { REQUIRED, RECOMMENDED, OPTIONAL }

    static final class Item {
        final String id, title, why;
        final Level level;
        final boolean done;
        final Intent fix; // null: handled specially (storage permission, home role)

        Item(String id, Level level, boolean done, String title, String why, Intent fix) {
            this.id = id;
            this.level = level;
            this.done = done;
            this.title = title;
            this.why = why;
            this.fix = fix;
        }
    }

    private Setup() {}

    static List<Item> items(Context c) {
        String pkg = c.getPackageName();
        Uri pkgUri = Uri.parse("package:" + pkg);
        List<Item> l = new ArrayList<>();

        l.add(new Item("admin", Level.REQUIRED, Schedule.hasAdmin(c),
                "Turn the screen off at night",
                "Lets the sleep schedule switch the screen off.", null));
        l.add(new Item("accessibility", Level.REQUIRED, accessibilityOn(c),
                "Keep Frame Home on screen",
                "Turn on \"Frame Home\" under Accessibility. It switches back from the vendor's launcher and catches the Home key.",
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        l.add(new Item("overlay", Level.REQUIRED, Settings.canDrawOverlays(c),
                "Allow \"Display over other apps\"",
                "Lets the morning wake-up open the slideshow by itself.",
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)));

        l.add(new Item("home", Level.RECOMMENDED, isHome(c),
                "Make Frame Home the home app",
                "So the Home key and power-on come here.", null));
        l.add(new Item("stayawake", Level.RECOMMENDED, !stayAwakeOn(c),
                "Turn off \"Stay awake\"",
                "In Developer options. If it's on, the screen can never go to sleep.",
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));

        l.add(new Item("brightness", Level.OPTIONAL, Settings.System.canWrite(c),
                "Brightness control",
                "Lets the phone remote change the screen brightness.",
                new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)));
        l.add(new Item("storage", Level.OPTIONAL,
                c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
                "Photos from a folder or USB stick",
                "Needed only for the \"Folder on the frame\" photo source.", null));
        l.add(new Item("updates", Level.OPTIONAL, c.getPackageManager().canRequestPackageInstalls(),
                "Updates from the phone remote",
                "Allow Frame Home to install apps, so you can send it updates from your phone.",
                new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkgUri)));
        l.add(new Item("screensaver", Level.OPTIONAL, screensaverIsUs(c),
                "Use the slideshow as screensaver",
                "Choose \"Frame Home slideshow\" so any idle screen goes back to photos.",
                new Intent(Settings.ACTION_DREAM_SETTINGS)));
        return l;
    }

    /** Number of required + recommended items still to do. */
    static int remaining(Context c) {
        int n = 0;
        for (Item i : items(c)) if (!i.done && i.level != Level.OPTIONAL) n++;
        return n;
    }

    static boolean requiredDone(Context c) {
        for (Item i : items(c)) if (!i.done && i.level == Level.REQUIRED) return false;
        return true;
    }

    static JSONArray toJson(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : items(c)) {
            a.put(new JSONObject().put("id", i.id).put("title", i.title)
                    .put("level", i.level.name().toLowerCase()).put("done", i.done));
        }
        return a;
    }

    static boolean accessibilityOn(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/") && s.contains("HomeGuardService");
    }

    static boolean isHome(Context c) {
        RoleManager rm = c.getSystemService(RoleManager.class);
        return rm != null && rm.isRoleHeld(RoleManager.ROLE_HOME);
    }

    private static boolean stayAwakeOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0;
    }

    private static boolean screensaverIsUs(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        return s != null && s.contains(c.getPackageName() + "/");
    }
}
