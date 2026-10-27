package com.glass.launcher;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
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
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends Activity {

    static class AppItem {
        String label;
        String pkg;
        Intent intent;
        Drawable icon;
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] HEARTS = {"❤️", "💖", "💕", "💗", "💘"};

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private final Random rnd = new Random();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PackageManager pm;
    private AudioManager am;

    // layout
    private FrameLayout frame, panel, controls, hearts;
    private LinearLayout home, panelBox, notifList, pages, dots;
    private HorizontalScrollView pager;
    private EditText search;
    private TextClock clockView;
    private String lastQuery = "";
    private int pageW, pageH, curPage;
    private boolean panelOpen, torchOn, writeAsked;

    // media
    private MediaSessionManager msm;
    private MediaController mc;
    private boolean mediaListening;
    private long mediaDur;
    private LinearLayout mediaCard;
    private ImageView mArt, mPlayBtn;
    private TextView mTitle, mArtist, mElapsed, mRemain;
    private View mFill;

    // volume HUD
    private VSlider hud;
    private final Runnable hideHud = new Runnable() {
        @Override public void run() {
            hud.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (hud.getAlpha() == 0f) hud.setVisibility(View.GONE);
            });
        }
    };

    private final MediaController.Callback mcCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { updateMedia(); }
        @Override public void onMetadataChanged(MediaMetadata m) { updateMedia(); }
        @Override public void onSessionDestroyed() { pickController(null); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            list -> pickController(list);

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Calendar cal = Calendar.getInstance();
            if (cal.get(Calendar.HOUR) == 0 && cal.get(Calendar.MINUTE) == 17) {
                spawnHeart();
                if (rnd.nextBoolean()) spawnHeart();
            }
            if (mediaCard != null && mediaCard.getVisibility() == View.VISIBLE) updateProgress();
            handler.postDelayed(this, 300);
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable glassFlat(int radiusDp, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Drawable glass(int radiusDp, int fill) {
        int lo = (fill & 0x00FFFFFF) | (((fill >>> 24) * 6 / 10) << 24);
        return new GlassDrawable(dp(radiusDp), dp(1), fill, lo);
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ================= Liquid-glass squircle icon =================
    private Drawable iosIcon(Drawable src) {
        try {
            final int s = 224;
            final int inset = Math.round(s * 0.075f);
            final int s2 = s - 2 * inset;

            Bitmap art = Bitmap.createBitmap(s2, s2, Bitmap.Config.ARGB_8888);
            Canvas ac = new Canvas(art);
            if (src instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable ad = (AdaptiveIconDrawable) src;
                int o = s2 / 4;
                Drawable bg = ad.getBackground();
                Drawable fg = ad.getForeground();
                if (bg != null) { bg.setBounds(-o, -o, s2 + o, s2 + o); bg.draw(ac); }
                if (fg != null) { fg.setBounds(-o, -o, s2 + o, s2 + o); fg.draw(ac); }
            } else {
                Paint bgp = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgp.setShader(new LinearGradient(0, 0, 0, s2, 0xFF30323C, 0xFF14151B, Shader.TileMode.CLAMP));
                ac.drawRect(0, 0, s2, s2, bgp);
                int pad = s2 / 7;
                src.setBounds(pad, pad, s2 - pad, s2 - pad);
                src.draw(ac);
            }
            Paint shade = new Paint();
            shade.setShader(new LinearGradient(0, 0, 0, s2, 0x00000000, 0x33000000, Shader.TileMode.CLAMP));
            ac.drawRect(0, 0, s2, s2, shade);
            float r2 = s2 * 0.2237f;
            Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
            m.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            ac.drawRoundRect(new RectF(0, 0, s2, s2), r2, r2, m);

            Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            float r = s * 0.2237f;
            RectF full = new RectF(0, 0, s, s);
            Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
            body.setShader(new LinearGradient(0, 0, 0, s, 0x55FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(full, r, r, body);
            c.drawBitmap(art, inset, inset, null);
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(0, 0, 0, s * 0.55f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(full, r, r, sheen);
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(3.5f);
            rim.setShader(new LinearGradient(0, 0, s, s, new int[]{0xCCFFFFFF, 0x10FFFFFF, 0x88FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(1.75f, 1.75f, s - 1.75f, s - 1.75f), r, r, rim);
            Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
            inner.setStyle(Paint.Style.STROKE);
            inner.setStrokeWidth(2f);
            inner.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(inset, inset, s - inset, s - inset), r2, r2, inner);
            return new BitmapDrawable(getResources(), bmp);
        } catch (Throwable t) {
            return src;
        }
    }

    // ================= Create =================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

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
        hearts = new FrameLayout(this);
        buildPanel();
        buildHud();

        frame.addView(home, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame.addView(hearts, new FrameLayout.LayoutParams(MATCH, MATCH));
        frame.addView(panel, new FrameLayout.LayoutParams(MATCH, MATCH));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dp(64), dp(230),
                Gravity.START | Gravity.CENTER_VERTICAL);
        hl.leftMargin = dp(14);
        frame.addView(hud, hl);
        panel.setVisibility(View.GONE);
        hud.setVisibility(View.GONE);

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

    // ================= Home screen =================
    private void buildHome() {
        // Music card on top (appears only while something is playing)
        mediaCard = buildMediaCard();
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        mlp.bottomMargin = dp(8);
        home.addView(mediaCard, mlp);

        // Lock-screen-like date + big glass clock
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER_HORIZONTAL);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEE d MMM");
        date.setFormat12Hour("EEE d MMM");
        date.setTextColor(0xEEFFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        date.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        date.setShadowLayer(8, 0, 2, 0x55000000);
        header.addView(date, new LinearLayout.LayoutParams(WRAP, WRAP));

        clockView = new TextClock(this);
        clockView.setFormat24Hour("HH:mm");
        clockView.setFormat12Hour("hh:mm");
        clockView.setTextColor(0xB8FFFFFF);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92);
        clockView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clockView.setLetterSpacing(-0.02f);
        clockView.setIncludeFontPadding(false);
        clockView.setShadowLayer(18, 0, 4, 0x66000000);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(WRAP, WRAP);
        clp.bottomMargin = dp(10);
        header.addView(clockView, clp);

        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { openPanel(); return true; }
            @Override public void onLongPress(MotionEvent e) {
                for (int i = 0; i < 14; i++) handler.postDelayed(() -> spawnHeart(), i * 80L);
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (e1 != null && e2.getY() - e1.getY() > dp(50)) { openPanel(); return true; }
                return false;
            }
        });
        header.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
        home.addView(header, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Search pill
        search = new EditText(this);
        search.setHint("Поиск");
        search.setHintTextColor(0xAAFFFFFF);
        search.setTextColor(Color.WHITE);
        search.setSingleLine(true);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(glass(26, 0x40FFFFFF));
        search.setPadding(dp(18), dp(10), dp(18), dp(10));
        Drawable mag = new Glyph(Glyph.SEARCH, 0xCCFFFFFF);
        mag.setBounds(0, 0, dp(18), dp(18));
        search.setCompoundDrawables(mag, null, null, null);
        search.setCompoundDrawablePadding(dp(10));
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
        slp.bottomMargin = dp(8);
        home.addView(search, slp);

        // Horizontal pages
        pager = new HorizontalScrollView(this);
        pager.setHorizontalScrollBarEnabled(false);
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.HORIZONTAL);
        pager.addView(pages, new FrameLayout.LayoutParams(WRAP, MATCH));
        pager.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
            int w = r - l, h = b - t;
            if (w > 0 && h > 0 && (w != pageW || h != pageH)) {
                pageW = w;
                pageH = h;
                pager.post(this::rebuildPages);
            }
        });
        pager.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            if (pageW > 0) setDot(Math.round(x / (float) pageW));
        });
        pager.setOnTouchListener(new View.OnTouchListener() {
            VelocityTracker vt;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (vt == null) vt = VelocityTracker.obtain();
                vt.addMovement(e);
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    vt.computeCurrentVelocity(1000);
                    float vx = vt.getXVelocity();
                    vt.recycle();
                    vt = null;
                    int target = pageW > 0 ? Math.round(pager.getScrollX() / (float) pageW) : 0;
                    if (vx < -600) target = curPage + 1;
                    else if (vx > 600) target = curPage - 1;
                    snapTo(target);
                    return true;
                }
                return false;
            }
        });
        home.addView(pager, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        home.addView(dots, new LinearLayout.LayoutParams(MATCH, dp(20)));

        // Dock with the real default apps
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackground(glass(36, 0x50FFFFFF));
        dock.setPadding(dp(8), dp(12), dp(8), dp(12));
        dock.addView(dockIcon(new Intent(Intent.ACTION_DIAL), "📞"));
        dock.addView(dockIcon(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), "💬"));
        dock.addView(dockIcon(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")), "🌐"));
        dock.addView(dockIcon(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "📷"));
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(MATCH, WRAP);
        klp.topMargin = dp(4);
        home.addView(dock, klp);
    }

    private View dockIcon(final Intent probe, String emoji) {
        Drawable ic = null;
        try {
            ResolveInfo ri = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                ic = iosIcon(ri.loadIcon(pm));
            }
        } catch (Exception ignored) { }
        LinearLayout cell = new LinearLayout(this);
        cell.setGravity(Gravity.CENTER);
        cell.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP, 1f));
        View icon;
        if (ic != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(ic);
            icon = iv;
        } else {
            TextView t = new TextView(this);
            t.setText(emoji);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            t.setGravity(Gravity.CENTER);
            t.setBackground(glass(18, 0x55FFFFFF));
            icon = t;
        }
        cell.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        cell.setOnClickListener(v -> go(probe));
        return cell;
    }

    // ---------- pages ----------
    private View makeCell(final AppItem a) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(2), dp(6), dp(2), dp(2));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = new TextView(this);
        t.setText(a.label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(4, 0, 1, 0x88000000);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tlp.topMargin = dp(6);
        cell.addView(t, tlp);
        cell.setOnClickListener(v -> launch(a));
        cell.setOnLongClickListener(v -> {
            go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
            return true;
        });
        return cell;
    }

    private void rebuildPages() {
        pages.removeAllViews();
        if (pageW <= 0 || pageH <= 0) return;
        final int cols = 4;
        int rowH = dp(104);
        int rows = Math.max(2, pageH / rowH);
        int per = cols * rows;
        int n = Math.max(1, (shown.size() + per - 1) / per);
        for (int p = 0; p < n; p++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            for (int r = 0; r < rows; r++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < cols; c++) {
                    int idx = p * per + r * cols + c;
                    View cell = idx < shown.size() ? makeCell(shown.get(idx)) : new View(this);
                    row.addView(cell, new LinearLayout.LayoutParams(0, rowH, 1f));
                }
                page.addView(row, new LinearLayout.LayoutParams(MATCH, rowH));
            }
            pages.addView(page, new LinearLayout.LayoutParams(pageW, MATCH));
        }
        curPage = Math.min(curPage, n - 1);
        final int target = curPage;
        pager.post(() -> pager.scrollTo(target * pageW, 0));
        buildDots(n);
        setDot(curPage);
    }

    private void buildDots(int n) {
        dots.removeAllViews();
        dots.setVisibility(n > 1 ? View.VISIBLE : View.INVISIBLE);
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(d, lp);
        }
    }

    private void setDot(int idx) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i == idx ? 0xFFFFFFFF : 0x66FFFFFF);
            dots.getChildAt(i).setBackground(g);
        }
    }

    private void snapTo(int p) {
        int n = Math.max(1, pages.getChildCount());
        curPage = Math.max(0, Math.min(n - 1, p));
        pager.smoothScrollTo(curPage * pageW, 0);
        setDot(curPage);
    }

    // ================= Music card =================
    private LinearLayout buildMediaCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(new GlassDrawable(dp(30), dp(1), 0xC4000000, 0xA6000000));
        card.setPadding(dp(14), dp(12), dp(14), dp(6));
        card.setVisibility(View.GONE);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        mArt = new ImageView(this);
        mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mArt.setBackground(glass(10, 0x33FFFFFF));
        mArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        mArt.setClipToOutline(true);
        top.addView(mArt, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        mTitle = tv("", 17, Color.WHITE, true);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        mArtist = tv("", 15, 0xAAFFFFFF, false);
        mArtist.setSingleLine(true);
        mArtist.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(mTitle);
        col.addView(mArtist);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        cl.leftMargin = dp(12);
        top.addView(col, cl);
        top.setOnClickListener(v -> {
            if (mc == null) return;
            try {
                PendingIntent pi = mc.getSessionActivity();
                if (pi != null) pi.send();
            } catch (Exception ignored) { }
        });
        card.addView(top, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout pr = new LinearLayout(this);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        mElapsed = tv("0:00", 12, 0xAAFFFFFF, false);
        FrameLayout bar = new FrameLayout(this);
        View track = new View(this);
        track.setBackground(glassFlat(3, 0x44FFFFFF));
        bar.addView(track, new FrameLayout.LayoutParams(MATCH, dp(5), Gravity.CENTER_VERTICAL));
        mFill = new View(this);
        mFill.setBackground(glassFlat(3, 0xE6FFFFFF));
        mFill.setPivotX(0f);
        mFill.setScaleX(0f);
        bar.addView(mFill, new FrameLayout.LayoutParams(MATCH, dp(5), Gravity.CENTER_VERTICAL));
        bar.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP && mc != null && mediaDur > 0 && v.getWidth() > 0) {
                float f = Math.max(0f, Math.min(1f, e.getX() / v.getWidth()));
                try { mc.getTransportControls().seekTo((long) (f * mediaDur)); } catch (Exception ignored) { }
            }
            return a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP;
        });
        mRemain = tv("−0:00", 12, 0xAAFFFFFF, false);
        pr.addView(mElapsed);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(24), 1f);
        bl.setMargins(dp(8), 0, dp(8), 0);
        pr.addView(bar, bl);
        pr.addView(mRemain);
        LinearLayout.LayoutParams prl = new LinearLayout.LayoutParams(MATCH, WRAP);
        prl.topMargin = dp(8);
        card.addView(pr, prl);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER);
        ctl.addView(mediaGlyphBtn(Glyph.PREV, v -> mediaCmd(0)));
        mPlayBtn = mediaGlyphBtn(Glyph.PLAY, v -> mediaCmd(1));
        ctl.addView(mPlayBtn);
        ctl.addView(mediaGlyphBtn(Glyph.NEXT, v -> mediaCmd(2)));
        card.addView(ctl, new LinearLayout.LayoutParams(MATCH, WRAP));
        return card;
    }

    private ImageView mediaGlyphBtn(int glyph, View.OnClickListener click) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Glyph(glyph, Color.WHITE));
        iv.setPadding(dp(14), dp(10), dp(14), dp(10));
        iv.setOnClickListener(click);
        iv.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        return iv;
    }

    private void mediaCmd(int which) {
        if (mc != null) {
            try {
                MediaController.TransportControls tc = mc.getTransportControls();
                PlaybackState ps = mc.getPlaybackState();
                boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
                if (which == 0) tc.skipToPrevious();
                else if (which == 2) tc.skipToNext();
                else if (playing) tc.pause();
                else tc.play();
                return;
            } catch (Exception ignored) { }
        }
        int code = which == 0 ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : which == 2 ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    private void attachMedia() {
        try {
            if (msm == null) msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, NotificationListener.class);
            if (!mediaListening) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
                mediaListening = true;
            }
            pickController(msm.getActiveSessions(cn));
        } catch (Exception e) {
            // no notification access yet: the card simply stays hidden
            showMediaCard(false);
        }
    }

    private void detachMedia() {
        if (msm != null && mediaListening) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
            mediaListening = false;
        }
        if (mc != null) {
            try { mc.unregisterCallback(mcCallback); } catch (Exception ignored) { }
            mc = null;
        }
    }

    private void pickController(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (best == null) {
                for (MediaController c : list) {
                    if (c.getMetadata() != null) { best = c; break; }
                }
            }
        }
        if (best != null && mc != null && best.getSessionToken().equals(mc.getSessionToken())) {
            updateMedia();
            return;
        }
        if (mc != null) {
            try { mc.unregisterCallback(mcCallback); } catch (Exception ignored) { }
        }
        mc = best;
        if (mc != null) {
            try { mc.registerCallback(mcCallback); } catch (Exception ignored) { }
        }
        updateMedia();
    }

    private void updateMedia() {
        if (mc == null) { showMediaCard(false); return; }
        MediaMetadata md = mc.getMetadata();
        PlaybackState ps = mc.getPlaybackState();
        int st = ps == null ? PlaybackState.STATE_NONE : ps.getState();
        if (md == null || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_STOPPED) {
            showMediaCard(false);
            return;
        }
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        mediaDur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        mTitle.setText(title == null ? "" : title);
        mArtist.setText(artist == null ? "" : artist);
        mArt.setImageBitmap(art);
        mPlayBtn.setImageDrawable(new Glyph(st == PlaybackState.STATE_PLAYING ? Glyph.PAUSE : Glyph.PLAY, Color.WHITE));
        showMediaCard(true);
        updateProgress();
    }

    private void showMediaCard(boolean show) {
        if (show && mediaCard.getVisibility() != View.VISIBLE) {
            mediaCard.setVisibility(View.VISIBLE);
            mediaCard.setAlpha(0f);
            mediaCard.setTranslationY(-dp(24));
            mediaCard.animate().alpha(1f).translationY(0f).setDuration(240);
        } else if (!show && mediaCard.getVisibility() == View.VISIBLE) {
            mediaCard.setVisibility(View.GONE);
        }
    }

    private long mediaPos() {
        PlaybackState ps = mc == null ? null : mc.getPlaybackState();
        if (ps == null) return 0;
        long pos = ps.getPosition();
        if (ps.getState() == PlaybackState.STATE_PLAYING) {
            long delta = SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
            pos += (long) (delta * ps.getPlaybackSpeed());
        }
        return Math.max(0, mediaDur > 0 ? Math.min(pos, mediaDur) : pos);
    }

    private String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private void updateProgress() {
        if (mc == null) return;
        long pos = mediaPos();
        mElapsed.setText(fmt(pos));
        mRemain.setText(mediaDur > 0 ? "−" + fmt(mediaDur - pos) : "");
        mFill.setScaleX(mediaDur > 0 ? Math.max(0f, Math.min(1f, pos / (float) mediaDur)) : 0f);
    }

    // ================= Volume HUD =================
    private void buildHud() {
        hud = new VSlider(this, new Glyph(Glyph.SPEAKER, 0xFF8E8E93), 0f, p -> {
            am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.round(p * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            handler.removeCallbacks(hideHud);
            handler.postDelayed(hideHud, 1800);
        });
        hud.setBackground(new GlassDrawable(dp(999), dp(1), 0x66000000, 0x40000000));
    }

    private void showVolumeHud() {
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        hud.setProgress(max == 0 ? 0f : am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max);
        hud.animate().cancel();
        if (hud.getVisibility() != View.VISIBLE) {
            hud.setVisibility(View.VISIBLE);
            hud.setAlpha(0f);
            hud.setTranslationX(-dp(16));
        }
        hud.animate().alpha(1f).translationX(0f).setDuration(180);
        handler.removeCallbacks(hideHud);
        handler.postDelayed(hideHud, 1800);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    keyCode == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            showVolumeHud();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true;
        return super.onKeyUp(keyCode, event);
    }

    // ================= Control + notification center =================
    private void buildPanel() {
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0x99101828);
        panel.setClickable(true);

        panelBox = new LinearLayout(this);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setPadding(dp(16), dp(8), dp(16), dp(16));
        panel.addView(panelBox, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout handle = new LinearLayout(this);
        handle.setGravity(Gravity.CENTER);
        View pill = new View(this);
        pill.setBackground(glassFlat(3, 0x99FFFFFF));
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

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        controls = new FrameLayout(this);
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(MATCH, dp(400));
        ctlp.bottomMargin = dp(18);
        content.addView(controls, ctlp);

        LinearLayout row = new LinearLayout(this);
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
        content.addView(row, rlp);

        notifList = new LinearLayout(this);
        notifList.setOrientation(LinearLayout.VERTICAL);
        content.addView(notifList, new LinearLayout.LayoutParams(MATCH, WRAP));

        sv.addView(content, new ViewGroup.LayoutParams(MATCH, WRAP));
        panelBox.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));
    }

    private void place(FrameLayout parent, View v, int col, int row, int cw, int ch, int u, int gap) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw * u + (cw - 1) * gap, ch * u + (ch - 1) * gap);
        lp.leftMargin = col * (u + gap);
        lp.topMargin = row * (u + gap);
        parent.addView(v, lp);
    }

    private void addAt(FrameLayout parent, View v, int left, int top, int size) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = left;
        lp.topMargin = top;
        parent.addView(v, lp);
    }

    /** mode: 0 glass, 1 white, 2 blue, 3 orange, 4 white with red glyph */
    private View circle(int glyph, int mode, int size, final Runnable click) {
        int top, bot, gc = Color.WHITE;
        switch (mode) {
            case 1: top = 0xF2FFFFFF; bot = 0xE0EEF2FA; gc = 0xFF2A2F45; break;
            case 2: top = 0xFF4A94FF; bot = 0xFF0A6CF2; break;
            case 3: top = 0xFFFFB340; bot = 0xFFFF9500; break;
            case 4: top = 0xF2FFFFFF; bot = 0xE0EEF2FA; gc = 0xFFFF5A52; break;
            default: top = 0x44FFFFFF; bot = 0x1FFFFFFF; break;
        }
        FrameLayout f = new FrameLayout(this);
        f.setBackground(new GlassDrawable(dp(999), dp(1), top, bot));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Glyph(glyph, gc));
        int gs = (int) (size * 0.46f);
        f.addView(iv, new FrameLayout.LayoutParams(gs, gs, Gravity.CENTER));
        f.setOnClickListener(v -> click.run());
        return f;
    }

    private boolean airplaneOn() {
        return Settings.Global.getInt(getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
    }

    private boolean wifiOn() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean btOn() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean rotationLocked() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
    }

    private boolean ensureWrite() {
        if (Settings.System.canWrite(this)) return true;
        Toast.makeText(this, "Разрешите изменение системных настроек", Toast.LENGTH_LONG).show();
        go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        return false;
    }

    private void toggleRotation() {
        if (!ensureWrite()) return;
        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, rotationLocked() ? 1 : 0);
        buildControls();
    }

    private void toggleSilent() {
        try {
            am.setRingerMode(am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                    ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
        } catch (SecurityException e) {
            go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        }
        buildControls();
    }

    private void toggleTorch() {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Boolean f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (f != null && f) {
                    torchOn = !torchOn;
                    cm.setTorchMode(id, torchOn);
                    buildControls();
                    return;
                }
            }
            Toast.makeText(this, "Вспышка не найдена", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            torchOn = false;
            Toast.makeText(this, "Не удалось включить фонарик", Toast.LENGTH_SHORT).show();
        }
    }

    private void buildControls() {
        controls.removeAllViews();
        final int gap = dp(10);
        final int avail = getResources().getDisplayMetrics().widthPixels - dp(32);
        final int u = (avail - 3 * gap) / 4;
        LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) controls.getLayoutParams();
        hp.height = 6 * u + 5 * gap;
        controls.setLayoutParams(hp);

        // Connectivity card
        FrameLayout conn = new FrameLayout(this);
        conn.setBackground(new GlassDrawable(dp(34), dp(1), 0x40FFFFFF, 0x1AFFFFFF));
        int cs = 2 * u + gap, pad = dp(14), g2 = dp(10), d = (cs - 2 * pad - g2) / 2;
        addAt(conn, circle(Glyph.AIRPLANE, airplaneOn() ? 3 : 0, d,
                () -> go(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))), pad, pad, d);
        addAt(conn, circle(Glyph.HOTSPOT, 0, d,
                () -> go(new Intent(Settings.ACTION_WIRELESS_SETTINGS))), pad + d + g2, pad, d);
        addAt(conn, circle(Glyph.WIFI, wifiOn() ? 2 : 0, d, () -> go(Build.VERSION.SDK_INT >= 29
                ? new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                : new Intent(Settings.ACTION_WIRELESS_SETTINGS))), pad, pad + d + g2, d);
        addAt(conn, circle(Glyph.BLUETOOTH, btOn() ? 2 : 0, d,
                () -> go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))), pad + d + g2, pad + d + g2, d);
        place(controls, conn, 0, 0, 2, 2, u, gap);

        // Music card (mirrors the active player)
        FrameLayout media = new FrameLayout(this);
        media.setBackground(new GlassDrawable(dp(34), dp(1), 0x40FFFFFF, 0x1AFFFFFF));
        String title = "Нет аудио", artist = "";
        boolean playing = false;
        if (mc != null) {
            MediaMetadata md = mc.getMetadata();
            PlaybackState ps = mc.getPlaybackState();
            if (md != null) {
                String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (t != null) title = t;
                if (a != null) artist = a;
            }
            playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        }
        LinearLayout mcol = new LinearLayout(this);
        mcol.setOrientation(LinearLayout.VERTICAL);
        mcol.setPadding(dp(14), dp(14), dp(14), dp(8));
        mcol.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        TextView mt = tv(title, 15, Color.WHITE, true);
        mt.setSingleLine(true);
        mt.setEllipsize(TextUtils.TruncateAt.END);
        mcol.addView(mt);
        TextView ma = tv(artist, 13, 0xAAFFFFFF, false);
        ma.setSingleLine(true);
        ma.setEllipsize(TextUtils.TruncateAt.END);
        mcol.addView(ma);
        LinearLayout mctl = new LinearLayout(this);
        mctl.setGravity(Gravity.CENTER);
        ImageView b1 = mediaGlyphBtn(Glyph.PREV, v -> { mediaCmd(0); });
        ImageView b2 = mediaGlyphBtn(playing ? Glyph.PAUSE : Glyph.PLAY, v -> mediaCmd(1));
        ImageView b3 = mediaGlyphBtn(Glyph.NEXT, v -> mediaCmd(2));
        mctl.addView(b1);
        mctl.addView(b2);
        mctl.addView(b3);
        mcol.addView(mctl, new LinearLayout.LayoutParams(MATCH, WRAP));
        media.addView(mcol, new FrameLayout.LayoutParams(MATCH, MATCH));
        place(controls, media, 2, 0, 2, 2, u, gap);

        // Rotation lock + silent
        place(controls, circle(Glyph.LOCKROT, rotationLocked() ? 4 : 0, u, this::toggleRotation), 0, 2, 1, 1, u, gap);
        place(controls, circle(Glyph.BELL, am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL ? 4 : 0, u,
                this::toggleSilent), 1, 2, 1, 1, u, gap);

        // Focus pill
        LinearLayout focus = new LinearLayout(this);
        focus.setGravity(Gravity.CENTER_VERTICAL);
        focus.setPadding(dp(10), dp(10), dp(10), dp(10));
        focus.setBackground(new GlassDrawable(dp(999), dp(1), 0x40FFFFFF, 0x1AFFFFFF));
        ImageView moon = new ImageView(this);
        moon.setImageDrawable(new Glyph(Glyph.MOON, Color.WHITE));
        moon.setPadding(dp(12), dp(12), dp(12), dp(12));
        moon.setBackground(new GlassDrawable(dp(999), dp(1), 0x55FFFFFF, 0x22FFFFFF));
        focus.addView(moon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView ft = tv("Фокусирование", 15, Color.WHITE, true);
        LinearLayout.LayoutParams ftl = new LinearLayout.LayoutParams(WRAP, WRAP);
        ftl.leftMargin = dp(10);
        focus.addView(ft, ftl);
        focus.setOnClickListener(v -> go(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        place(controls, focus, 0, 3, 2, 1, u, gap);

        // Brightness + volume sliders
        float bri = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
        VSlider bs = new VSlider(this, new Glyph(Glyph.SUN, 0xFFF5C542), bri, p -> {
            if (!Settings.System.canWrite(MainActivity.this)) {
                if (!writeAsked) { writeAsked = true; ensureWrite(); }
                return;
            }
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                    Math.max(1, Math.round(p * 255)));
        });
        bs.setBackground(new GlassDrawable(dp(999), dp(1), 0x40FFFFFF, 0x1AFFFFFF));
        place(controls, bs, 2, 2, 1, 2, u, gap);
        final int maxV = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        VSlider vs = new VSlider(this, new Glyph(Glyph.SPEAKER, 0xFF3FB8E8),
                am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maxV,
                p -> am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * maxV), 0));
        vs.setBackground(new GlassDrawable(dp(999), dp(1), 0x40FFFFFF, 0x1AFFFFFF));
        place(controls, vs, 3, 2, 1, 2, u, gap);

        // Quick actions
        place(controls, circle(Glyph.FLASH, torchOn ? 1 : 0, u, this::toggleTorch), 0, 4, 1, 1, u, gap);
        place(controls, circle(Glyph.TIMER, 0, u, () -> go(new Intent(AlarmClock.ACTION_SHOW_TIMERS))), 1, 4, 1, 1, u, gap);
        place(controls, circle(Glyph.CALC, 0, u, () -> go(Intent.makeMainSelectorActivity(
                Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))), 2, 4, 1, 1, u, gap);
        place(controls, circle(Glyph.CAMERA, 0, u, () -> go(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))),
                3, 4, 1, 1, u, gap);
        place(controls, circle(Glyph.GEAR, 0, u, () -> go(new Intent(Settings.ACTION_SETTINGS))), 0, 5, 1, 1, u, gap);
        place(controls, circle(Glyph.BATTERY, 0, u, () -> go(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))),
                1, 5, 1, 1, u, gap);
        place(controls, circle(Glyph.CAST, 0, u, () -> go(new Intent(Settings.ACTION_CAST_SETTINGS))), 2, 5, 1, 1, u, gap);
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        writeAsked = false;
        hideKeyboard();
        buildControls();
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
                    "Нажмите, чтобы открыть настройки. Нужно и для плеера сверху. Если пункт серый: Настройки → "
                            + "Приложения → Glass Launcher → ⋮ → «Разрешить ограниченные настройки».",
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
        c.setBackground(glass(22, 0x44FFFFFF));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            c.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
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

    // ================= Hearts (12:17) =================
    private void spawnHeart() {
        if (clockView == null || clockView.getWidth() == 0 || panelOpen) return;
        int[] c = new int[2];
        int[] f = new int[2];
        clockView.getLocationOnScreen(c);
        frame.getLocationOnScreen(f);
        final TextView h = new TextView(this);
        h.setText(HEARTS[rnd.nextInt(HEARTS.length)]);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18 + rnd.nextInt(22));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = c[0] - f[0] + (int) (clockView.getWidth() * (0.1f + 0.8f * rnd.nextFloat()));
        lp.topMargin = c[1] - f[1] + clockView.getHeight() / 2;
        hearts.addView(h, lp);
        h.setScaleX(0.3f);
        h.setScaleY(0.3f);
        h.animate()
                .translationX((rnd.nextFloat() - 0.5f) * dp(320))
                .translationY(dp(-40) + rnd.nextFloat() * dp(280))
                .scaleX(1f).scaleY(1f)
                .rotation((rnd.nextFloat() - 0.5f) * 50f)
                .alpha(0f)
                .setDuration(1800 + rnd.nextInt(1000))
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> hearts.removeView(h));
    }

    // ================= Lifecycle =================
    @Override
    protected void onResume() {
        super.onResume();
        NotificationListener.onChange = () -> runOnUiThread(() -> { if (panelOpen) refreshNotifications(); });
        loadApps();
        if (panelOpen) refreshNotifications();
        attachMedia();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        NotificationListener.onChange = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideHud);
        hud.setVisibility(View.GONE);
        detachMedia();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closePanel();
        search.setText("");
        search.clearFocus();
        hideKeyboard();
        snapTo(0);
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
        if (!q.equals(lastQuery)) {
            lastQuery = q;
            curPage = 0;
        }
        rebuildPages();
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
}
