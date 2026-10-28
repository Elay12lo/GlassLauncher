package com.glass.launcher;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.SharedPreferences;
import android.media.session.MediaSession;
import android.service.notification.NotificationListenerService;
import android.view.HapticFeedbackConstants;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
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

public class MainActivity extends Activity implements DragFrame.Host {

    static class AppItem {
        String label;
        String pkg;
        Intent intent;
        Drawable icon;
        String key;
        String category = "Утилиты";
        long installed;
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

    static final int REQ_BIND = 11, REQ_CONFIG = 12, REQ_PERM_LOC = 21, REQ_PERM_CAL = 22, REQ_PERM_BT = 23;
    private final HomeLayout layout = new HomeLayout();
    private final Map<String, AppItem> byKey = new HashMap<>();
    private final List<CellRef> cellRefs = new ArrayList<>();
    private final List<View> popups = new ArrayList<>();
    private List<HomeLayout.Place> places = new ArrayList<>();
    private SharedPreferences prefs;
    private MiniWidgets mini;
    private FrameLayout overlay;
    private AppWidgetHost widgetHost;
    private AppWidgetManager awm;
    private int pendingWid = -1;
    private boolean dragging;
    private HomeLayout.Entry dragEntry;
    private View dragSrc, hoverView;
    private ImageView ghost;
    private float lastRawX, lastRawY, startRawX, startRawY;
    private int edgeDir;
    private BluetoothProfile a2dp;
    private final List<BluetoothDevice> btDevs = new ArrayList<>();
    private boolean btNeedPerm;
    private String mediaStatus = "";

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

    int dp(int v) {
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

    TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ================= Liquid-glass squircle icon =================
    private float avgLuma(Bitmap b) {
        long sum = 0;
        int cnt = 0;
        for (int y = 0; y < b.getHeight(); y += 8) {
            for (int x = 0; x < b.getWidth(); x += 8) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                sum += (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000;
                cnt++;
            }
        }
        return cnt == 0 ? -1f : sum / (255f * cnt);
    }

    private void whiteTile(Canvas c, int s) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, s, 0xFFFFFFFF, 0xFFE8ECF3, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
    }

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
                Bitmap bgB = Bitmap.createBitmap(s2, s2, Bitmap.Config.ARGB_8888);
                Bitmap fgB = Bitmap.createBitmap(s2, s2, Bitmap.Config.ARGB_8888);
                Drawable bg = ad.getBackground();
                Drawable fg = ad.getForeground();
                if (bg != null) { bg.setBounds(-o, -o, s2 + o, s2 + o); bg.draw(new Canvas(bgB)); }
                if (fg != null) { fg.setBounds(-o, -o, s2 + o, s2 + o); fg.draw(new Canvas(fgB)); }
                float bl = avgLuma(bgB);
                float fl = avgLuma(fgB);
                boolean white = bl < 0f || (bl < 0.40f && fl < 0.85f);
                if (white) whiteTile(ac, s2);
                else ac.drawBitmap(bgB, 0, 0, null);
                ac.drawBitmap(fgB, 0, 0, null);
            } else {
                whiteTile(ac, s2);
                int pad = s2 / 7;
                src.setBounds(pad, pad, s2 - pad, s2 - pad);
                src.draw(ac);
            }
            Paint shade = new Paint();
            shade.setShader(new LinearGradient(0, 0, 0, s2, 0x00000000, 0x14000000, Shader.TileMode.CLAMP));
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
            body.setShader(new LinearGradient(0, 0, 0, s, 0x66FFFFFF, 0x26FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(full, r, r, body);
            c.drawBitmap(art, inset, inset, null);
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(0, 0, 0, s * 0.55f, 0x44FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(full, r, r, sheen);
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(3.5f);
            rim.setShader(new LinearGradient(0, 0, s, s, new int[]{0xDDFFFFFF, 0x18FFFFFF, 0x99FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(1.75f, 1.75f, s - 1.75f, s - 1.75f), r, r, rim);
            Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
            inner.setStyle(Paint.Style.STROKE);
            inner.setStrokeWidth(2f);
            inner.setColor(0x44FFFFFF);
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
        prefs = getSharedPreferences("glass", Context.MODE_PRIVATE);
        layout.load(prefs);
        if (!layout.saved(prefs)) {
            layout.entries.add(new HomeLayout.Entry(HomeLayout.WIDGET_BUILTIN, "weather"));
            layout.entries.add(new HomeLayout.Entry(HomeLayout.WIDGET_BUILTIN, "calendar"));
            layout.save(prefs);
        }
        mini = new MiniWidgets(this, prefs);
        awm = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, 1024);

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
        overlay = new FrameLayout(this);
        frame.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));
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
        LinearLayout srow = new LinearLayout(this);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        srow.addView(search, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(46), dp(46));
        plp.leftMargin = dp(8);
        srow.addView(circle(Glyph.PLUS, 0, dp(46), this::showWidgetPicker), plp);
        home.addView(srow, slp);

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


    private void rebuildPages() {
        pages.removeAllViews();
        cellRefs.clear();
        if (pageW <= 0 || pageH <= 0) return;
        final int cols = 4;
        final int rowH = dp(96);
        final int rows = Math.max(2, pageH / rowH);
        final int cellW = pageW / cols;
        int n;
        if (!lastQuery.isEmpty()) {
            int per = cols * rows;
            n = Math.max(1, (shown.size() + per - 1) / per);
            for (int p = 0; p < n; p++) {
                FrameLayout page = new FrameLayout(this);
                for (int i = 0; i < per; i++) {
                    int idx = p * per + i;
                    if (idx >= shown.size()) break;
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cellW, rowH);
                    lp.leftMargin = (i % cols) * cellW;
                    lp.topMargin = (i / cols) * rowH;
                    page.addView(makeAppCell(shown.get(idx), null), lp);
                }
                pages.addView(page, new LinearLayout.LayoutParams(pageW, MATCH));
            }
        } else {
            places = layout.computePlaces(rows);
            int np = 1;
            for (HomeLayout.Place pl : places) np = Math.max(np, pl.page + 1);
            FrameLayout[] pg = new FrameLayout[np];
            for (int i = 0; i < np; i++) pg[i] = new FrameLayout(this);
            for (HomeLayout.Place pl : places) {
                View v = viewFor(pl.e);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pl.cw * cellW, pl.rh * rowH);
                lp.leftMargin = pl.col * cellW;
                lp.topMargin = pl.row * rowH;
                pg[pl.page].addView(v, lp);
                CellRef ref = new CellRef();
                ref.v = v;
                ref.e = pl.e;
                cellRefs.add(ref);
            }
            for (FrameLayout f : pg) pages.addView(f, new LinearLayout.LayoutParams(pageW, MATCH));
            pages.addView(buildLibraryPage(), new LinearLayout.LayoutParams(pageW, MATCH));
            n = np + 1;
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
        mediaStatus = "";
        if (!listenerEnabled()) {
            mediaStatus = "Нет доступа к уведомлениям";
            showMediaCard(false);
            return;
        }
        ComponentName cn = new ComponentName(this, NotificationListener.class);
        try {
            if (msm == null) msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (!mediaListening) {
                msm.addOnActiveSessionsChangedListener(sessionsListener, cn);
                mediaListening = true;
            }
            List<MediaController> list = new ArrayList<>(msm.getActiveSessions(cn));
            addNotificationControllers(list);
            if (list.isEmpty()) mediaStatus = "Нет активных плееров";
            pickController(list);
        } catch (SecurityException e) {
            mediaStatus = "Сервис уведомлений не подключён";
            if (NotificationListener.instance == null) {
                try { NotificationListenerService.requestRebind(cn); } catch (Exception ignored) { }
            }
            List<MediaController> list = new ArrayList<>();
            addNotificationControllers(list);
            pickController(list);
        } catch (Exception e) {
            mediaStatus = "Ошибка: " + e.getClass().getSimpleName();
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
        final boolean showHp = btNeedPerm || !btDevs.isEmpty();
        LinearLayout.LayoutParams hp = (LinearLayout.LayoutParams) controls.getLayoutParams();
        int nRows = showHp ? 7 : 6;
        hp.height = nRows * u + (nRows - 1) * gap;
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
        if (mc == null) artist = mediaStatus;
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

        if (showHp) {
            LinearLayout hpCard = new LinearLayout(this);
            hpCard.setGravity(Gravity.CENTER_VERTICAL);
            hpCard.setPadding(dp(12), dp(10), dp(16), dp(10));
            hpCard.setBackground(new GlassDrawable(dp(999), dp(1), 0x40FFFFFF, 0x1AFFFFFF));
            ImageView hi = new ImageView(this);
            hi.setImageDrawable(new Glyph(Glyph.HEADSET, Color.WHITE));
            hi.setPadding(dp(13), dp(13), dp(13), dp(13));
            hi.setBackground(new GlassDrawable(dp(999), dp(1), 0x55FFFFFF, 0x22FFFFFF));
            hpCard.addView(hi, new LinearLayout.LayoutParams(dp(52), dp(52)));
            LinearLayout hc = new LinearLayout(this);
            hc.setOrientation(LinearLayout.VERTICAL);
            String hn, hs;
            if (btNeedPerm || btDevs.isEmpty()) {
                hn = "Наушники";
                hs = "Нажмите, чтобы разрешить доступ к Bluetooth";
            } else {
                BluetoothDevice d0 = btDevs.get(0);
                hn = btName(d0);
                int bat = btBattery(d0);
                hs = bat >= 0 ? "Заряд " + bat + "%" : "Заряд недоступен";
                if (btDevs.size() > 1) hs += " · ещё " + (btDevs.size() - 1);
            }
            hc.addView(tv(hn, 16, Color.WHITE, true));
            hc.addView(tv(hs, 13, 0xAAFFFFFF, false));
            LinearLayout.LayoutParams hcl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            hcl.leftMargin = dp(12);
            hpCard.addView(hc, hcl);
            hpCard.setOnClickListener(v -> {
                if (btNeedPerm) requestPerm(Manifest.permission.BLUETOOTH_CONNECT, REQ_PERM_BT);
                else go(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            });
            place(controls, hpCard, 0, 6, 4, 1, u, gap);
        }
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        writeAsked = false;
        hideKeyboard();
        queryHeadphones();
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

    // ================= Home layout, drag & drop, folders, library, widgets =================
    static class CellRef {
        View v;
        HomeLayout.Entry e;
    }

    private String categoryOf(ApplicationInfo ai) {
        if ((ai.flags & ApplicationInfo.FLAG_IS_GAME) != 0 || ai.category == ApplicationInfo.CATEGORY_GAME) return "Игры";
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_SOCIAL: return "Общение";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
            case ApplicationInfo.CATEGORY_IMAGE:
            case ApplicationInfo.CATEGORY_NEWS: return "Развлечения";
            case ApplicationInfo.CATEGORY_MAPS: return "Карты";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "Продуктивность";
            default: return "Утилиты";
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setShadowLayer(4, 0, 1, 0x88000000);
        return t;
    }

    private LinearLayout.LayoutParams labelLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        return lp;
    }

    private View makeAppCell(final AppItem a, final HomeLayout.Entry entry) {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setPadding(dp(2), dp(6), dp(2), dp(2));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        inner.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        inner.addView(label(a.label), labelLp());
        View root;
        if (entry == null) {
            root = inner;
            inner.setOnLongClickListener(v -> { showAppMenu(a); return true; });
        } else {
            DragFrame df = new DragFrame(this, this);
            df.setTag(entry);
            df.addView(inner, new FrameLayout.LayoutParams(MATCH, MATCH));
            root = df;
        }
        root.setOnClickListener(v -> launch(a));
        return root;
    }

    private View makeFolderCell(final HomeLayout.Entry e) {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        inner.setPadding(dp(2), dp(6), dp(2), dp(2));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(new GlassDrawable(dp(16), dp(1), 0x66FFFFFF, 0x33FFFFFF));
        int mini = dp(25);
        for (int i = 0; i < Math.min(4, e.keys.size()); i++) {
            AppItem a = byKey.get(e.keys.get(i));
            if (a == null) continue;
            ImageView m = new ImageView(this);
            m.setImageDrawable(a.icon);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(mini, mini);
            lp.leftMargin = dp(5) + (i % 2) * (mini + dp(3));
            lp.topMargin = dp(5) + (i / 2) * (mini + dp(3));
            box.addView(m, lp);
        }
        inner.addView(box, new LinearLayout.LayoutParams(dp(64), dp(64)));
        inner.addView(label(e.name), labelLp());
        DragFrame df = new DragFrame(this, this);
        df.setTag(e);
        df.addView(inner, new FrameLayout.LayoutParams(MATCH, MATCH));
        df.setOnClickListener(v -> showFolderPopup(e.name, appsOf(e), e));
        return df;
    }

    private List<AppItem> appsOf(HomeLayout.Entry f) {
        List<AppItem> out = new ArrayList<>();
        for (String k : f.keys) {
            AppItem a = byKey.get(k);
            if (a != null) out.add(a);
        }
        return out;
    }

    private View wrapWidget(View content, HomeLayout.Entry e) {
        DragFrame df = new DragFrame(this, this);
        df.setTag(e);
        df.setPadding(dp(6), dp(6), dp(6), dp(6));
        df.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));
        return df;
    }

    private View sysWidgetView(HomeLayout.Entry e) {
        try {
            AppWidgetProviderInfo info = awm.getAppWidgetInfo(e.wid);
            if (info != null) return widgetHost.createView(this, e.wid, info);
        } catch (Exception ignored) { }
        TextView t = tv("Виджет недоступен", 14, Color.WHITE, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(24, 0x44FFFFFF));
        return t;
    }

    private View viewFor(HomeLayout.Entry e) {
        switch (e.type) {
            case HomeLayout.APP: {
                AppItem a = byKey.get(e.key);
                return a == null ? new View(this) : makeAppCell(a, e);
            }
            case HomeLayout.FOLDER:
                return makeFolderCell(e);
            case HomeLayout.WIDGET_BUILTIN:
                return wrapWidget(mini.build(e.key), e);
            default:
                return wrapWidget(sysWidgetView(e), e);
        }
    }

    // ---------- app library ----------
    private Map<String, List<AppItem>> buildLibrary() {
        Map<String, List<AppItem>> m = new LinkedHashMap<>();
        List<AppItem> recent = new ArrayList<>(all);
        Collections.sort(recent, (x, y) -> Long.compare(y.installed, x.installed));
        if (recent.size() > 4) recent = new ArrayList<>(recent.subList(0, 4));
        m.put("Недавно добавленные", recent);
        String[] order = {"Общение", "Развлечения", "Игры", "Продуктивность", "Карты", "Утилиты"};
        for (String o : order) m.put(o, new ArrayList<AppItem>());
        for (AppItem a : all) {
            List<AppItem> l = m.get(a.category);
            if (l == null) l = m.get("Утилиты");
            l.add(a);
        }
        m.values().removeIf(l -> l.isEmpty());
        return m;
    }

    private View buildLibraryPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(4), dp(6), dp(12));
        TextView title = tv("Библиотека приложений", 17, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setBackground(glass(26, 0x40FFFFFF));
        title.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        tlp.bottomMargin = dp(14);
        col.addView(title, tlp);
        int cardW = (pageW - dp(12)) / 2 - dp(12);
        LinearLayout row = null;
        int i = 0;
        for (Map.Entry<String, List<AppItem>> en : buildLibrary().entrySet()) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                col.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            LinearLayout holder = new LinearLayout(this);
            holder.setGravity(Gravity.CENTER_HORIZONTAL);
            holder.setPadding(dp(6), dp(6), dp(6), dp(10));
            holder.addView(libraryCard(en.getKey(), en.getValue(), cardW));
            row.addView(holder, new LinearLayout.LayoutParams(0, WRAP, 1f));
            i++;
        }
        if (i % 2 == 1 && row != null) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        sv.addView(col, new ViewGroup.LayoutParams(MATCH, WRAP));
        return sv;
    }

    private View libraryCard(final String name, final List<AppItem> apps, int cardW) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(new GlassDrawable(dp(28), dp(1), 0x55FFFFFF, 0x26FFFFFF));
        int pad = dp(12), gap = dp(8);
        int m = (cardW - 2 * pad - gap) / 2;
        for (int i = 0; i < Math.min(4, apps.size()); i++) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(apps.get(i).icon);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(m, m);
            lp.leftMargin = pad + (i % 2) * (m + gap);
            lp.topMargin = pad + (i / 2) * (m + gap);
            box.addView(iv, lp);
        }
        col.addView(box, new LinearLayout.LayoutParams(cardW, cardW));
        TextView t = label(name);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t, new LinearLayout.LayoutParams(cardW, WRAP));
        col.setOnClickListener(v -> showFolderPopup(name, apps, null));
        return col;
    }

    // ---------- popups ----------
    private void showPopup(View content, int widthPx, Runnable onClose) {
        final FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0x88000000);
        dim.setClickable(true);
        dim.setTag(onClose);
        dim.setOnClickListener(v -> dismissTop());
        content.setClickable(true);
        dim.addView(content, new FrameLayout.LayoutParams(widthPx, WRAP, Gravity.CENTER));
        overlay.addView(dim, new FrameLayout.LayoutParams(MATCH, MATCH));
        popups.add(dim);
        if (Build.VERSION.SDK_INT >= 31 && popups.size() == 1) {
            home.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP));
        }
    }

    private void dismissTop() {
        if (popups.isEmpty()) return;
        View dim = popups.remove(popups.size() - 1);
        Object cb = dim.getTag();
        overlay.removeView(dim);
        if (cb instanceof Runnable) ((Runnable) cb).run();
        if (popups.isEmpty() && Build.VERSION.SDK_INT >= 31 && !panelOpen) home.setRenderEffect(null);
        hideKeyboard();
    }

    private void dismissAllPopups() {
        while (!popups.isEmpty()) dismissTop();
    }

    private void showMenu(String title, String[] labels, final Runnable[] actions) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new GlassDrawable(dp(30), dp(1), 0x99282830, 0x88181820));
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView t = tv(title, 15, 0xAAFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(t);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView r = tv(labels[i], 17, labels[i].startsWith("Удалить") ? 0xFFFF6B63 : Color.WHITE, false);
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            r.setOnClickListener(v -> { dismissTop(); actions[k].run(); });
            box.addView(r);
        }
        showPopup(box, dp(270), null);
    }

    private void showFolderPopup(String name, final List<AppItem> apps, final HomeLayout.Entry folder) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new GlassDrawable(dp(36), dp(1), 0x88303038, 0x77202028));
        box.setPadding(dp(14), dp(14), dp(14), dp(14));
        Runnable onClose = null;
        if (folder != null) {
            final EditText et = new EditText(this);
            et.setText(folder.name);
            et.setTextColor(Color.WHITE);
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            et.setTypeface(Typeface.DEFAULT_BOLD);
            et.setGravity(Gravity.CENTER);
            et.setSingleLine(true);
            et.setBackground(null);
            et.setImeOptions(EditorInfo.IME_ACTION_DONE);
            box.addView(et, new LinearLayout.LayoutParams(MATCH, WRAP));
            onClose = () -> {
                String nm = et.getText().toString().trim();
                if (!nm.isEmpty() && !nm.equals(folder.name)) {
                    folder.name = nm;
                    layout.save(prefs);
                    rebuildPages();
                }
            };
        } else {
            TextView t = tv(name, 22, Color.WHITE, true);
            t.setGravity(Gravity.CENTER);
            box.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        final int cols = 3;
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                grid.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            row.addView(folderAppCell(apps.get(i), folder), new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        while (row != null && row.getChildCount() < cols) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        sv.addView(grid, new ViewGroup.LayoutParams(MATCH, WRAP));
        int rowsN = Math.max(1, (apps.size() + cols - 1) / cols);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, Math.min(rowsN * dp(104), dp(380)));
        slp.topMargin = dp(8);
        box.addView(sv, slp);
        showPopup(box, getResources().getDisplayMetrics().widthPixels - dp(48), onClose);
    }

    private View folderAppCell(final AppItem a, final HomeLayout.Entry folder) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(2), dp(8), dp(2), dp(4));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.icon);
        cell.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        cell.addView(label(a.label), labelLp());
        cell.setOnClickListener(v -> { dismissAllPopups(); launch(a); });
        cell.setOnLongClickListener(v -> {
            if (folder != null) {
                showMenu(a.label, new String[]{"Убрать из папки", "Информация о приложении"},
                        new Runnable[]{() -> removeFromFolder(folder, a.key), () -> showInfo(a)});
            } else {
                showAppMenu(a);
            }
            return true;
        });
        return cell;
    }

    private void removeFromFolder(HomeLayout.Entry folder, String key) {
        folder.keys.remove(key);
        layout.entries.add(new HomeLayout.Entry(HomeLayout.APP, key));
        layout.normalize();
        layout.save(prefs);
        dismissAllPopups();
        rebuildPages();
    }

    private void showInfo(AppItem a) {
        go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
    }

    private void showAppMenu(final AppItem a) {
        showMenu(a.label, new String[]{"Информация о приложении", "Удалить приложение"},
                new Runnable[]{() -> showInfo(a),
                        () -> go(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)))});
    }

    private void showItemMenu(final HomeLayout.Entry e) {
        if (e.type == HomeLayout.APP) {
            AppItem a = byKey.get(e.key);
            if (a != null) showAppMenu(a);
        } else if (e.type == HomeLayout.FOLDER) {
            showMenu(e.name, new String[]{"Открыть папку", "Распустить папку"},
                    new Runnable[]{() -> showFolderPopup(e.name, appsOf(e), e), () -> dissolveFolder(e)});
        } else {
            showMenu("Виджет", new String[]{"Удалить виджет"}, new Runnable[]{() -> removeWidget(e)});
        }
    }

    private void dissolveFolder(HomeLayout.Entry f) {
        int i = layout.entries.indexOf(f);
        if (i < 0) return;
        layout.entries.remove(i);
        for (int k = 0; k < f.keys.size(); k++) {
            layout.entries.add(i + k, new HomeLayout.Entry(HomeLayout.APP, f.keys.get(k)));
        }
        layout.save(prefs);
        rebuildPages();
    }

    private void removeWidget(HomeLayout.Entry e) {
        if (e.type == HomeLayout.WIDGET_SYS) {
            try { widgetHost.deleteAppWidgetId(e.wid); } catch (Exception ignored) { }
        }
        layout.entries.remove(e);
        layout.save(prefs);
        rebuildPages();
    }

    // ---------- drag & drop ----------
    @Override
    public void onLongPress(DragFrame f, float rawX, float rawY) {
        Object tag = f.getTag();
        if (tag instanceof HomeLayout.Entry) startDrag(f, (HomeLayout.Entry) tag, rawX, rawY);
    }

    @Override
    public void onDragEvent(MotionEvent e) {
        lastRawX = e.getRawX();
        lastRawY = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: moveDrag(); break;
            case MotionEvent.ACTION_UP: endDrag(false); break;
            case MotionEvent.ACTION_CANCEL: endDrag(true); break;
            default: break;
        }
    }

    private int ghostW, ghostH;

    private void startDrag(View f, HomeLayout.Entry e, float rx, float ry) {
        if (f.getWidth() == 0 || f.getHeight() == 0) return;
        dragging = true;
        dragEntry = e;
        dragSrc = f;
        startRawX = rx;
        startRawY = ry;
        lastRawX = rx;
        lastRawY = ry;
        ghostW = f.getWidth();
        ghostH = f.getHeight();
        Bitmap b = Bitmap.createBitmap(ghostW, ghostH, Bitmap.Config.ARGB_8888);
        f.draw(new Canvas(b));
        ghost = new ImageView(this);
        ghost.setImageBitmap(b);
        ghost.setScaleX(1.08f);
        ghost.setScaleY(1.08f);
        ghost.setAlpha(0.92f);
        overlay.addView(ghost, new FrameLayout.LayoutParams(ghostW, ghostH));
        f.setAlpha(0.25f);
        moveGhost(rx, ry);
        f.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private void moveGhost(float rx, float ry) {
        if (ghost == null) return;
        int[] o = new int[2];
        overlay.getLocationOnScreen(o);
        ghost.setTranslationX(rx - o[0] - ghostW / 2f);
        ghost.setTranslationY(ry - o[1] - ghostH / 2f);
    }

    private final Runnable edgeRun = new Runnable() {
        @Override public void run() {
            if (!dragging || edgeDir == 0) return;
            snapTo(curPage + edgeDir);
            handler.postDelayed(this, 900);
        }
    };

    private void moveDrag() {
        moveGhost(lastRawX, lastRawY);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int dir = lastRawX < dp(40) ? -1 : (lastRawX > sw - dp(40) ? 1 : 0);
        if (dir != edgeDir) {
            edgeDir = dir;
            handler.removeCallbacks(edgeRun);
            if (dir != 0) handler.postDelayed(edgeRun, 600);
        }
        CellRef t = hitTest(lastRawX, lastRawY);
        View hv = (t != null && t.e != dragEntry && dragEntry.type == HomeLayout.APP
                && (t.e.type == HomeLayout.APP || t.e.type == HomeLayout.FOLDER)
                && isCenter(t, lastRawX, lastRawY)) ? t.v : null;
        if (hv != hoverView) {
            if (hoverView != null) hoverView.animate().scaleX(1f).scaleY(1f).setDuration(120);
            hoverView = hv;
            if (hv != null) hv.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120);
        }
    }

    private CellRef hitTest(float x, float y) {
        int[] loc = new int[2];
        for (CellRef r : cellRefs) {
            if (r.v.getWidth() == 0) continue;
            r.v.getLocationOnScreen(loc);
            if (x >= loc[0] && x <= loc[0] + r.v.getWidth() && y >= loc[1] && y <= loc[1] + r.v.getHeight()) return r;
        }
        return null;
    }

    private boolean isCenter(CellRef r, float x, float y) {
        int[] loc = new int[2];
        r.v.getLocationOnScreen(loc);
        float cx = loc[0] + r.v.getWidth() / 2f, cy = loc[1] + r.v.getHeight() / 2f;
        return Math.abs(x - cx) < r.v.getWidth() * 0.28f && Math.abs(y - cy) < r.v.getHeight() * 0.28f;
    }

    private boolean inPager(float x, float y) {
        int[] loc = new int[2];
        pager.getLocationOnScreen(loc);
        return x >= loc[0] && x <= loc[0] + pager.getWidth() && y >= loc[1] && y <= loc[1] + pager.getHeight();
    }

    private void mergeIntoFolder(HomeLayout.Entry src, HomeLayout.Entry dst) {
        if (dst.type == HomeLayout.FOLDER) {
            dst.keys.add(src.key);
            layout.entries.remove(src);
        } else {
            HomeLayout.Entry f = new HomeLayout.Entry();
            f.type = HomeLayout.FOLDER;
            f.name = "Папка";
            f.keys.add(dst.key);
            f.keys.add(src.key);
            int di = layout.entries.indexOf(dst);
            layout.entries.set(di, f);
            layout.entries.remove(src);
        }
    }

    private void endDrag(boolean cancel) {
        handler.removeCallbacks(edgeRun);
        edgeDir = 0;
        if (hoverView != null) {
            hoverView.setScaleX(1f);
            hoverView.setScaleY(1f);
            hoverView = null;
        }
        if (ghost != null) {
            overlay.removeView(ghost);
            ghost = null;
        }
        if (dragSrc != null) dragSrc.setAlpha(1f);
        boolean was = dragging;
        dragging = false;
        if (!was || cancel || dragEntry == null) return;
        HomeLayout.Entry src = dragEntry;
        double dist = Math.hypot(lastRawX - startRawX, lastRawY - startRawY);
        if (dist < dp(10)) {
            showItemMenu(src);
            return;
        }
        CellRef t = hitTest(lastRawX, lastRawY);
        if (t != null && t.e != src) {
            if (src.type == HomeLayout.APP
                    && (t.e.type == HomeLayout.APP || t.e.type == HomeLayout.FOLDER)
                    && isCenter(t, lastRawX, lastRawY)) {
                mergeIntoFolder(src, t.e);
            } else {
                int[] loc = new int[2];
                t.v.getLocationOnScreen(loc);
                boolean after = lastRawX > loc[0] + t.v.getWidth() / 2f;
                layout.entries.remove(src);
                int ti = layout.entries.indexOf(t.e);
                layout.entries.add(after ? ti + 1 : ti, src);
            }
        } else if (t == null && inPager(lastRawX, lastRawY) && curPage < pages.getChildCount() - 1) {
            layout.entries.remove(src);
            layout.entries.add(src);
        } else {
            return;
        }
        layout.normalize();
        layout.save(prefs);
        rebuildPages();
    }

    // ---------- widgets ----------
    private void showWidgetPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(new GlassDrawable(dp(32), dp(1), 0x99282830, 0x88181820));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        TextView title = tv("Добавить виджет", 20, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(10));
        box.addView(title);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(pickerRow("☁️  Погода", () -> { dismissTop(); addBuiltin("weather"); }));
        list.addView(pickerRow("📅  Календарь и события", () -> { dismissTop(); addBuiltin("calendar"); }));
        list.addView(pickerRow("📝  Заметки", () -> { dismissTop(); addBuiltin("notes"); }));
        try {
            List<AppWidgetProviderInfo> ps = new ArrayList<>(awm.getInstalledProviders());
            Collections.sort(ps, (x, y) -> String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm))));
            if (!ps.isEmpty()) {
                TextView h = tv("Виджеты приложений", 13, 0xAAFFFFFF, true);
                h.setPadding(dp(8), dp(14), dp(8), dp(6));
                list.addView(h);
            }
            for (AppWidgetProviderInfo p : ps) {
                final AppWidgetProviderInfo prov = p;
                list.addView(pickerRow(String.valueOf(p.loadLabel(pm)), () -> { dismissTop(); bindSys(prov); }));
            }
        } catch (Exception ignored) { }
        sv.addView(list, new ViewGroup.LayoutParams(MATCH, WRAP));
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, dp(420)));
        showPopup(box, getResources().getDisplayMetrics().widthPixels - dp(40), null);
    }

    private View pickerRow(String text, final Runnable action) {
        TextView r = tv(text, 16, Color.WHITE, false);
        r.setBackground(glass(18, 0x33FFFFFF));
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private int insertIndex() {
        int best = layout.entries.size();
        if (curPage < pages.getChildCount() - 1) {
            for (HomeLayout.Place pl : places) {
                if (pl.page == curPage) best = Math.min(best, pl.idx);
            }
        }
        return best;
    }

    private void addBuiltin(String kind) {
        layout.entries.add(insertIndex(), new HomeLayout.Entry(HomeLayout.WIDGET_BUILTIN, kind));
        layout.save(prefs);
        rebuildPages();
    }

    private void bindSys(AppWidgetProviderInfo prov) {
        int id = widgetHost.allocateAppWidgetId();
        pendingWid = id;
        boolean ok;
        try {
            ok = awm.bindAppWidgetIdIfAllowed(id, prov.provider);
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            configureOrAdd(id);
            return;
        }
        Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, prov.provider);
        try {
            startActivityForResult(i, REQ_BIND);
        } catch (Exception e) {
            widgetHost.deleteAppWidgetId(id);
            Toast.makeText(this, "Не удалось добавить виджет", Toast.LENGTH_SHORT).show();
        }
    }

    private void configureOrAdd(int id) {
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(id);
        if (info != null && info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG, null);
                return;
            } catch (Exception ignored) { }
        }
        addSysWidget(id);
    }

    private void addSysWidget(int id) {
        HomeLayout.Entry e = new HomeLayout.Entry();
        e.type = HomeLayout.WIDGET_SYS;
        e.wid = id;
        layout.entries.add(insertIndex(), e);
        layout.save(prefs);
        rebuildPages();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_BIND) {
            if (res == RESULT_OK && pendingWid >= 0) configureOrAdd(pendingWid);
            else if (pendingWid >= 0) widgetHost.deleteAppWidgetId(pendingWid);
        } else if (req == REQ_CONFIG) {
            if (res == RESULT_OK && pendingWid >= 0) addSysWidget(pendingWid);
            else if (pendingWid >= 0) widgetHost.deleteAppWidgetId(pendingWid);
        }
    }

    void onWidgetData() {
        runOnUiThread(() -> { if (!dragging) rebuildPages(); });
    }

    void requestPerm(String perm, int code) {
        requestPermissions(new String[]{perm}, code);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM_LOC) {
            mini.forceRefresh();
            rebuildPages();
        } else if (code == REQ_PERM_CAL) {
            rebuildPages();
        } else if (code == REQ_PERM_BT) {
            queryHeadphones();
            if (panelOpen) buildControls();
        }
    }

    // ---------- headphones ----------
    private final BluetoothProfile.ServiceListener btListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            a2dp = proxy;
            readBt();
        }
        @Override public void onServiceDisconnected(int profile) {
            a2dp = null;
            btDevs.clear();
        }
    };

    private void queryHeadphones() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad == null || !ad.isEnabled()) {
                btNeedPerm = false;
                btDevs.clear();
                return;
            }
            if (Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                btNeedPerm = true;
                btDevs.clear();
                return;
            }
            btNeedPerm = false;
            if (a2dp != null) readBt();
            else ad.getProfileProxy(this, btListener, BluetoothProfile.A2DP);
        } catch (Exception ignored) { }
    }

    private void readBt() {
        btDevs.clear();
        try {
            if (a2dp != null) btDevs.addAll(a2dp.getConnectedDevices());
        } catch (Exception ignored) { }
        if (panelOpen) buildControls();
    }

    private String btName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? "Наушники" : n;
        } catch (Exception e) {
            return "Наушники";
        }
    }

    private int btBattery(BluetoothDevice d) {
        try {
            Method m = d.getClass().getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            return r instanceof Integer ? (Integer) r : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private void addNotificationControllers(List<MediaController> list) {
        NotificationListener svc = NotificationListener.instance;
        if (svc == null) return;
        try {
            for (StatusBarNotification sbn : svc.getActiveNotifications()) {
                Bundle ex = sbn.getNotification().extras;
                MediaSession.Token tok = ex.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                if (tok == null) continue;
                boolean dup = false;
                for (MediaController x : list) {
                    if (x.getSessionToken().equals(tok)) dup = true;
                }
                if (!dup) list.add(new MediaController(this, tok));
            }
        } catch (Exception ignored) { }
    }

    // ================= Lifecycle =================
    @Override
    protected void onResume() {
        super.onResume();
        NotificationListener.onChange = () -> runOnUiThread(() -> {
            if (panelOpen) refreshNotifications();
            attachMedia();
        });
        if (listenerEnabled() && NotificationListener.instance == null) {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, NotificationListener.class));
            } catch (Exception ignored) { }
        }
        try { widgetHost.startListening(); } catch (Exception ignored) { }
        loadApps();
        mini.refreshWeather();
        if (panelOpen) refreshNotifications();
        attachMedia();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }


    @Override
    protected void onPause() {
        super.onPause();
        if (dragging) endDrag(true);
        NotificationListener.onChange = null;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(hideHud);
        hud.setVisibility(View.GONE);
        try { widgetHost.stopListening(); } catch (Exception ignored) { }
        detachMedia();
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissAllPopups();
        closePanel();
        search.setText("");
        search.clearFocus();
        hideKeyboard();
        snapTo(0);
    }

    @Override
    public void onBackPressed() {
        if (!popups.isEmpty()) {
            dismissTop();
            return;
        }
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
        byKey.clear();
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            AppItem a = new AppItem();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.pkg = pkg;
            a.key = pkg + "/" + ri.activityInfo.name;
            Drawable ic = iconCache.get(a.key);
            if (ic == null) {
                ic = iosIcon(ri.loadIcon(pm));
                iconCache.put(a.key, ic);
            }
            a.icon = ic;
            try {
                a.category = categoryOf(ri.activityInfo.applicationInfo);
                a.installed = pm.getPackageInfo(pkg, 0).firstInstallTime;
            } catch (Exception ignored) { }
            a.intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            all.add(a);
        }
        Collections.sort(all, (x, y) ->
                x.label.toLowerCase(Locale.getDefault()).compareTo(y.label.toLowerCase(Locale.getDefault())));
        List<String> ordered = new ArrayList<>();
        for (AppItem a : all) {
            byKey.put(a.key, a);
            ordered.add(a.key);
        }
        boolean changed = layout.sync(byKey, ordered);
        layout.normalize();
        if (changed) layout.save(prefs);
        filter();
    }


    private void filter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        if (!q.isEmpty()) {
            for (AppItem a : all) {
                if (a.label.toLowerCase(Locale.getDefault()).contains(q)) shown.add(a);
            }
        }
        if (!q.equals(lastQuery)) {
            lastQuery = q;
            curPage = 0;
        }
        if (!dragging) rebuildPages();
    }


    private void launch(AppItem a) {
        hideKeyboard();
        go(a.intent);
    }

    void go(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show();
        }
    }
}
