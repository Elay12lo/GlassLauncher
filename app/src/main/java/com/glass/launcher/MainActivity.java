package com.glass.launcher;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    static class AppItem {
        String label;
        String pkg;
        Intent intent;
        Drawable icon;
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private PackageManager pm;
    private AppAdapter adapter;
    private EditText search;
    private GridView grid;

    private FrameLayout frame, panel;
    private LinearLayout home, panelBox, notifList;
    private TextView torchTile;
    private boolean panelOpen, torchOn;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable glass(int radiusDp, int fill) {
        int lo = (fill & 0x00FFFFFF) | (((fill >>> 24) * 6 / 10) << 24);
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{fill, lo});
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), 0x55FFFFFF);
        return d;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ---------- iPhone-style squircle icon ----------
    private Drawable iosIcon(Drawable src) {
        try {
            final int s = 160;
            Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            if (src instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) src;
                int o = s / 4;
                Drawable bg = ad.getBackground();
                Drawable fg = ad.getForeground();
                if (bg != null) { bg.setBounds(-o, -o, s + o, s + o); bg.draw(c); }
                if (fg != null) { fg.setBounds(-o, -o, s + o, s + o); fg.draw(c); }
            } else {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setShader(new LinearGradient(0, 0, 0, s, 0xFFFFFFFF, 0xFFD8DCE6, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, s, s, p);
                int pad = s / 8;
                src.setBounds(pad, pad, s - pad, s - pad);
                src.draw(c);
            }
            float r = s * 0.2237f;
            Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            c.drawRoundRect(new RectF(0, 0, s, s), r, r, mask);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2f);
            edge.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(1, 1, s - 1, s - 1), r, r, edge);
            return new BitmapDrawable(getResources(), bmp);
        } catch (Throwable t) {
            return src;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();

        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setBackgroundDrawable(new ColorDrawable(0x33000000));
        if (Build.VERSION.SDK_INT >= 31) {
            try { w.setBackgroundBlurRadius(30); } catch (Throwable ignored) { }
        }

        frame = new FrameLayout(this);
        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(16), dp(16), dp(16), dp(12));
        buildHome();
        buildPanel();
        frame.addView(home, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame.addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));
        panel.setVisibility(View.GONE);

        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            frame.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = ins.getSystemWindowInsetTop();
            int bottom = ins.getSystemWindowInsetBottom();
            home.setPadding(dp(16), top + dp(16), dp(16), bottom + dp(12));
            panelBox.setPadding(dp(16), top + dp(8), dp(16), bottom + dp(16));
            return ins;
        });
        setContentView(frame);
    }

    // ---------- Home screen ----------
    private void buildHome() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 64);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(8, 0, 2, 0x66000000);
        header.addView(clock, new LinearLayout.LayoutParams(WRAP, WRAP));

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0xDDFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        date.setShadowLayer(6, 0, 1, 0x66000000);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(WRAP, WRAP);
        dlp.bottomMargin = dp(16);
        header.addView(date, dlp);

        // Swipe down (or tap) on the clock opens the notification center
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { openPanel(); return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 != null && e2.getY() - e1.getY() > dp(50)) { openPanel(); return true; }
                return false;
            }
        });
        header.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        home.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        search = new EditText(this);
        search.setHint("Поиск приложений");
        search.setHintTextColor(0xAAFFFFFF);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(glass(24, 0x44FFFFFF));
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (!shown.isEmpty()) launch(shown.get(0));
            return true;
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, WRAP);
        slp.bottomMargin = dp(12);
        home.addView(search, slp);

        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(shown.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + shown.get(pos).pkg)));
            return true;
        });
        home.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(glass(30, 0x50FFFFFF));
        dock.setPadding(dp(8), dp(10), dp(8), dp(10));
        dock.addView(dockIcon("📞", 0xFF5EE07F, 0xFF30B94F, () -> go(new Intent(Intent.ACTION_DIAL))));
        dock.addView(dockIcon("💬", 0xFF5EE07F, 0xFF30B94F, () -> go(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))));
        dock.addView(dockIcon("🌐", 0xFF4DA3FF, 0xFF0A6FE0, () -> go(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))));
        dock.addView(dockIcon("📷", 0xFFB0B3BA, 0xFF6E7078, () -> go(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(MATCH, WRAP);
        klp.topMargin = dp(8);
        home.addView(dock, klp);
    }

    private View dockIcon(String emoji, int hi, int lo, Runnable action) {
        LinearLayout cell = new LinearLayout(this);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView t = new TextView(this);
        t.setText(emoji);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{hi, lo});
        bg.setCornerRadius(dp(14));
        t.setBackground(bg);
        cell.addView(t, new LinearLayout.LayoutParams(dp(58), dp(58)));
        cell.setOnClickListener(v -> action.run());
        return cell;
    }

    // ---------- Notification center ----------
    private void buildPanel() {
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0x99101828);
        panel.setClickable(true);

        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setPadding(dp(16), dp(8), dp(16), dp(16));
        panel.addView(panelBox, new FrameLayout.LayoutParams(MATCH, MATCH));

        // handle: tap or swipe up closes
        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        View pill = new View(this);
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0x99FFFFFF);
        pg.setCornerRadius(dp(3));
        pill.setBackground(pg);
        handle.addView(pill, new LinearLayout.LayoutParams(dp(44), dp(5)));
        final GestureDetector hd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { closePanel(); return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 != null && e1.getY() - e2.getY() > dp(30)) { closePanel(); return true; }
                return false;
            }
        });
        handle.setOnTouchListener((v, e) -> hd.onTouchEvent(e));
        panelBox.addView(handle, new LinearLayout.LayoutParams(MATCH, dp(40)));

        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(tile("📶", "Интернет", () -> go(Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                : new Intent(Settings.ACTION_WIRELESS_SETTINGS))));
        tiles.addView(tile("🔵", "Bluetooth", () -> go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
        tiles.addView(tile("🔊", "Звук", () -> go(Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_VOLUME)
                : new Intent(Settings.ACTION_SOUND_SETTINGS))));
        torchTile = tile("🔦", "Фонарик", this::toggleTorch);
        tiles.addView(torchTile);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tlp.bottomMargin = dp(16);
        panelBox.addView(tiles, tlp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tv("Уведомления", 22, Color.WHITE, true), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView clear = tv("Очистить", 14, Color.WHITE, false);
        clear.setBackground(glass(16, 0x44FFFFFF));
        clear.setPadding(dp(14), dp(6), dp(14), dp(6));
        clear.setOnClickListener(v -> {
            NotificationListener s = NotificationListener.instance;
            if (s != null) { try { s.cancelAllNotifications(); } catch (Exception ignored) { } }
            refreshNotifications();
        });
        row.addView(clear, new LinearLayout.LayoutParams(WRAP, WRAP));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        rlp.bottomMargin = dp(10);
        panelBox.addView(row, rlp);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        notifList = new LinearLayout(this);
        notifList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(notifList, new ViewGroup.LayoutParams(MATCH, WRAP));
        panelBox.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }

    private TextView tile(String emoji, String label, Runnable action) {
        TextView t = new TextView(this);
        SpannableString ss = new SpannableString(emoji + "\n" + label);
        ss.setSpan(new RelativeSizeSpan(2f), 0, emoji.length(), 0);
        t.setText(ss);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(20, 0x44FFFFFF));
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void toggleTorch() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) {
                    torchOn = !torchOn;
                    cm.setTorchMode(id, torchOn);
                    torchTile.setBackground(glass(20, torchOn ? 0xAAFFFFFF : 0x44FFFFFF));
                    torchTile.setTextColor(torchOn ? 0xFF1B1F3B : Color.WHITE);
                    return;
                }
            }
            Toast.makeText(this, "Вспышка не найдена", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            torchOn = false;
            Toast.makeText(this, "Не удалось включить фонарик", Toast.LENGTH_SHORT).show();
        }
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        hideKeyboard();
        refreshNotifications();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationY(-dp(60));
        panel.animate().alpha(1f).translationY(0f).setDuration(220);
        if (Build.VERSION.SDK_INT >= 31) {
            home.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        }
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        if (Build.VERSION.SDK_INT >= 31) home.setRenderEffect(null);
        panel.animate().alpha(0f).translationY(-dp(60)).setDuration(180)
                .withEndAction(() -> { if (!panelOpen) panel.setVisibility(View.GONE); });
    }

    private boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void refreshNotifications() {
        notifList.removeAllViews();
        if (!listenerEnabled()) {
            notifList.addView(card(null, null, "Разрешите доступ к уведомлениям",
                    "Нажмите, чтобы открыть настройки. Если пункт серый: Настройки → Приложения → "
                            + "Glass Launcher → ⋮ → «Разрешить ограниченные настройки».",
                    v -> go(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
            return;
        }
        StatusBarNotification[] arr = new StatusBarNotification[0];
        NotificationListener svc = NotificationListener.instance;
        if (svc != null) {
            try {
                StatusBarNotification[] a = svc.getActiveNotifications();
                if (a != null) arr = a;
            } catch (Exception ignored) { }
        }
        List<StatusBarNotification> items = new ArrayList<>();
        for (StatusBarNotification sbn : arr) {
            if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if (sbn.getPackageName().equals(getPackageName())) continue;
            items.add(sbn);
        }
        Collections.sort(items, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        if (items.isEmpty()) {
            notifList.addView(card(null, null, "Нет уведомлений", null, null));
            return;
        }
        for (StatusBarNotification sbn : items) {
            final StatusBarNotification f = sbn;
            Bundle ex = sbn.getNotification().extras;
            CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence x = ex.getCharSequence(Notification.EXTRA_TEXT);
            String app = sbn.getPackageName();
            Drawable ic = null;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(sbn.getPackageName(), 0);
                app = String.valueOf(pm.getApplicationLabel(ai));
                ic = iosIcon(pm.getApplicationIcon(ai));
            } catch (Exception ignored) { }
            notifList.addView(card(ic, app, t == null ? "" : t.toString(), x == null ? null : x.toString(), v -> {
                PendingIntent pi = f.getNotification().contentIntent;
                closePanel();
                if (pi != null) {
                    try { pi.send(); } catch (PendingIntent.CanceledException ignored) { }
                }
                NotificationListener s = NotificationListener.instance;
                if (f.isClearable() && s != null) {
                    try { s.cancelNotification(f.getKey()); } catch (Exception ignored) { }
                }
            }));
        }
    }

    private View card(Drawable icon, String app, String title, String text, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setBackground(glass(20, 0x44FFFFFF));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            c.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (app != null) {
            TextView a = tv(app, 11, 0xBBFFFFFF, false);
            a.setSingleLine(true);
            col.addView(a);
        }
        TextView ti = tv(title, 15, Color.WHITE, true);
        ti.setMaxLines(2);
        ti.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(ti);
        if (text != null) {
            TextView tx = tv(text, 14, 0xDDFFFFFF, false);
            tx.setMaxLines(3);
            tx.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(tx);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cl.leftMargin = icon != null ? dp(12) : 0;
        c.addView(col, cl);
        if (click != null) c.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------- Lifecycle ----------
    @Override
    protected void onResume() {
        super.onResume();
        NotificationListener.onChange = () -> runOnUiThread(() -> { if (panelOpen) refreshNotifications(); });
        loadApps();
        if (panelOpen) refreshNotifications();
    }

    @Override
    protected void onPause() {
        super.onPause();
        NotificationListener.onChange = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closePanel();
        search.setText("");
        search.clearFocus();
        hideKeyboard();
        grid.smoothScrollToPosition(0);
    }

    @Override
    public void onBackPressed() {
        if (panelOpen) closePanel();
        // otherwise a launcher never closes on Back
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void loadApps() {
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        all.clear();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            AppItem a = new AppItem();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.pkg = pkg;
            String key = pkg + "/" + ri.activityInfo.name;
            Drawable ic = iconCache.get(key);
            if (ic == null) {
                ic = iosIcon(ri.loadIcon(pm));
                iconCache.put(key, ic);
            }
            a.icon = ic;
            a.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            all.add(a);
        }
        Collections.sort(all, (x, y) ->
                x.label.toLowerCase(Locale.getDefault()).compareTo(y.label.toLowerCase(Locale.getDefault())));
        filter();
    }

    private void filter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (AppItem a : all) {
            if (q.isEmpty() || a.label.toLowerCase(Locale.getDefault()).contains(q)) shown.add(a);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void launch(AppItem a) {
        hideKeyboard();
        go(a.intent);
    }

    private void go(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show();
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convertView;
            if (cell == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(2), dp(4), dp(2), dp(4));

                ImageView iv = new ImageView(MainActivity.this);
                cell.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(58)));

                TextView tv = new TextView(MainActivity.this);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tv.setGravity(Gravity.CENTER);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setShadowLayer(4, 0, 1, 0x88000000);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, WRAP);
                tlp.topMargin = dp(4);
                cell.addView(tv, tlp);
            }
            AppItem a = shown.get(position);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) cell.getChildAt(1)).setText(a.label);
            return cell;
        }
    }
}
