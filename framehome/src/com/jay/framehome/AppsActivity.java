package com.jay.framehome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** Grid of every launchable app, so nothing is out of reach with a custom home screen. */
public class AppsActivity extends Activity {

    private static final class App {
        final String label;
        final Drawable icon;
        final ComponentName component;
        App(String label, Drawable icon, ComponentName component) {
            this.label = label; this.icon = icon; this.component = component;
        }
    }

    private final List<App> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadApps();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 40);
        root.setPadding(pad, pad, pad, pad);
        root.addView(Ui.text(this, "Apps", 40, Ui.TEXT));

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 170));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setSelector(Ui.tileBackground(this));
        grid.setDrawSelectorOnTop(false);
        grid.setAdapter(new Adapter());
        grid.setOnItemClickListener((parent, view, pos, id) -> launch(apps.get(pos)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = Ui.dp(this, 24);
        root.addView(grid, glp);

        setContentView(root);
        grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.immersive(this);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            if (getPackageName().equals(r.activityInfo.packageName)) continue;
            apps.add(new App(r.loadLabel(pm).toString(), r.loadIcon(pm),
                    new ComponentName(r.activityInfo.packageName, r.activityInfo.name)));
        }
        Collator col = Collator.getInstance();
        apps.sort((a, b) -> col.compare(a.label, b.label));
    }

    private void launch(App a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell;
            ImageView icon;
            TextView label;
            if (convert == null) {
                cell = new LinearLayout(AppsActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                int p = Ui.dp(AppsActivity.this, 16);
                cell.setPadding(p, p, p, p);
                cell.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(AppsActivity.this, 170)));
                icon = new ImageView(AppsActivity.this);
                int s = Ui.dp(AppsActivity.this, 72);
                cell.addView(icon, new LinearLayout.LayoutParams(s, s));
                label = Ui.text(AppsActivity.this, "", 18, Ui.TEXT);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(AppsActivity.this, 10);
                cell.addView(label, lp);
            } else {
                cell = (LinearLayout) convert;
            }
            icon = (ImageView) cell.getChildAt(0);
            label = (TextView) cell.getChildAt(1);
            App a = apps.get(pos);
            icon.setImageDrawable(a.icon);
            label.setText(a.label);
            return cell;
        }
    }
}
