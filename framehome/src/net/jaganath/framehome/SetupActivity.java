package net.jaganath.framehome;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * On-frame setup checklist: every permission Frame Home needs, with a button that opens the right
 * Android screen. Works with a keyboard (arrow keys + Enter); come back with Back.
 */
public class SetupActivity extends Activity {
    private static final int REQ_HOME = 1, REQ_STORAGE = 2;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(outer);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 36);
        list.setPadding(pad, pad, pad, pad);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, Ui.dp(this, 820));
        outer.addView(list, new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(this);
        Schedule.prefs(this).edit().putBoolean("setupShown", true).apply();
        build();
    }

    private void build() {
        list.removeAllViews();
        list.addView(Ui.text(this, "Set up Frame Home", 38, Ui.TEXT));
        TextView intro = Ui.text(this, "Use the arrow keys and Enter. Each button opens the right Android "
                + "screen; press Back to return here. Items turn green when done.", 18, Ui.MUTED);
        list.addView(intro, margins(6, 18));

        Button first = null;
        Setup.Level section = null;
        for (Setup.Item item : Setup.items(this)) {
            if (item.level != section) {
                section = item.level;
                String name = section == Setup.Level.REQUIRED ? "Required"
                        : section == Setup.Level.RECOMMENDED ? "Recommended" : "Optional";
                list.addView(Ui.text(this, name, 22, Ui.TEXT), margins(22, 6));
            }
            String title = (item.done ? "✓  " : "") + item.title;
            String sub = item.done ? "Done" : item.why;
            Button b = Ui.tile(this, title, sub, v -> fix(item));
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setTextSize(20);
            if (item.done) b.setTextColor(0xFF6EE7A0);
            list.addView(b, margins(4, 4));
            if (first == null && !item.done) first = b;
        }

        String remote = WebService.address();
        list.addView(Ui.text(this, "Phone remote", 22, Ui.TEXT), margins(22, 6));
        list.addView(Ui.text(this, remote == null ? "Connect the frame to Wi-Fi to use the phone remote."
                : "Open " + remote + " on your phone and enter PIN " + Prefs.pin(this)
                + ". Choose your photos under \"Photo source\".", 18, Ui.MUTED), margins(0, 8));

        Button done = Ui.tile(this, "Done", Setup.requiredDone(this) ? null : "Some required items are still open",
                v -> finish());
        list.addView(done, margins(18, 6));
        (first != null ? first : done).requestFocus();
    }

    private void fix(Setup.Item item) {
        try {
            switch (item.id) {
                case "admin":
                    startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Schedule.adminComponent(this))
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Frame Home uses this only to turn the screen off at your sleep time."));
                    return;
                case "home": {
                    RoleManager rm = getSystemService(RoleManager.class);
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME);
                    return;
                }
                case "storage":
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
                    return;
                default:
                    if (item.fix != null) startActivity(item.fix);
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "This frame doesn't have that settings screen. See the setup guide.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        build();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        build();
    }

    private LinearLayout.LayoutParams margins(int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topDp);
        lp.bottomMargin = Ui.dp(this, bottomDp);
        return lp;
    }
}
