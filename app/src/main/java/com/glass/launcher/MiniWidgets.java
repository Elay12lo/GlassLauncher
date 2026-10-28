package com.glass.launcher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Built-in home-screen widgets: weather, calendar, notes. */
class MiniWidgets {
    private final MainActivity a;
    private final SharedPreferences sp;
    private String city = "Погода", cond = "Нет данных", emoji = "⛅", msg = null;
    private int temp, tmin, tmax;
    private boolean has, loading;
    private long time;

    MiniWidgets(MainActivity a, SharedPreferences sp) {
        this.a = a;
        this.sp = sp;
        try {
            JSONObject j = new JSONObject(sp.getString("weather", "{}"));
            if (j.has("temp")) {
                city = j.optString("city", city);
                cond = j.optString("cond", cond);
                emoji = j.optString("emoji", emoji);
                temp = j.optInt("temp");
                tmin = j.optInt("min");
                tmax = j.optInt("max");
                time = j.optLong("time");
                has = true;
            }
        } catch (Exception ignored) { }
    }

    View build(String kind) {
        if ("weather".equals(kind)) return weather();
        if ("calendar".equals(kind)) return calendar();
        return notes();
    }

    private LinearLayout card(int top, int bottom) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(new GlassDrawable(a.dp(30), a.dp(1), top, bottom));
        c.setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14));
        return c;
    }

    private void spacer(LinearLayout c) {
        c.addView(new View(a), new LinearLayout.LayoutParams(1, 0, 1f));
    }

    private boolean locPerm() {
        return a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean calPerm() {
        return a.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------- weather ----------
    private View weather() {
        LinearLayout c = card(0xCC4E6F9C, 0xCC34527F);
        TextView t1 = a.tv(city, 15, Color.WHITE, true);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t1);
        TextView t2 = a.tv(has ? temp + "°" : "--", 44, Color.WHITE, false);
        t2.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t2.setIncludeFontPadding(false);
        c.addView(t2);
        spacer(c);
        c.addView(a.tv(emoji, 18, Color.WHITE, false));
        TextView t4 = a.tv(msg != null ? msg : cond, 13, Color.WHITE, true);
        t4.setMaxLines(2);
        t4.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t4);
        if (has) c.addView(a.tv("↓ " + tmin + "°  ↑ " + tmax + "°", 12, 0xCCFFFFFF, false));
        c.setOnClickListener(v -> {
            if (!locPerm()) a.requestPerm(Manifest.permission.ACCESS_COARSE_LOCATION, MainActivity.REQ_PERM_LOC);
            else forceRefresh();
        });
        return c;
    }

    void forceRefresh() {
        time = 0;
        refreshWeather();
    }

    void refreshWeather() {
        if (!locPerm()) {
            msg = "Нажмите, чтобы разрешить геопозицию";
            return;
        }
        if (loading || (has && System.currentTimeMillis() - time < 20 * 60 * 1000L)) return;
        loading = true;
        new Thread(() -> {
            try {
                LocationManager lm = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
                Location best = null;
                List<String> provs = lm.getProviders(true);
                for (String p : provs) {
                    try {
                        Location l = lm.getLastKnownLocation(p);
                        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                    } catch (SecurityException ignored) { }
                }
                if (best == null) {
                    msg = "Геопозиция недоступна";
                } else {
                    String name = "Погода";
                    try {
                        List<Address> ad = new Geocoder(a, Locale.getDefault())
                                .getFromLocation(best.getLatitude(), best.getLongitude(), 1);
                        if (ad != null && !ad.isEmpty() && ad.get(0).getLocality() != null) {
                            name = ad.get(0).getLocality();
                        }
                    } catch (Exception ignored) { }
                    String url = "https://api.open-meteo.com/v1/forecast?latitude="
                            + String.format(Locale.US, "%.3f", best.getLatitude())
                            + "&longitude=" + String.format(Locale.US, "%.3f", best.getLongitude())
                            + "&current=temperature_2m,weather_code"
                            + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
                    HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    JSONObject j = new JSONObject(sb.toString());
                    JSONObject cur = j.getJSONObject("current");
                    JSONObject day = j.getJSONObject("daily");
                    city = name;
                    temp = (int) Math.round(cur.getDouble("temperature_2m"));
                    tmin = (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
                    tmax = (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
                    setCode(cur.getInt("weather_code"));
                    has = true;
                    msg = null;
                    time = System.currentTimeMillis();
                    JSONObject cache = new JSONObject();
                    cache.put("city", city);
                    cache.put("cond", cond);
                    cache.put("emoji", emoji);
                    cache.put("temp", temp);
                    cache.put("min", tmin);
                    cache.put("max", tmax);
                    cache.put("time", time);
                    sp.edit().putString("weather", cache.toString()).apply();
                }
            } catch (Exception e) {
                msg = "Не удалось загрузить погоду";
            }
            loading = false;
            a.onWidgetData();
        }).start();
    }

    private void setCode(int code) {
        if (code == 0) { cond = "Ясно"; emoji = "☀️"; }
        else if (code <= 2) { cond = "Малооблачно"; emoji = "⛅"; }
        else if (code == 3) { cond = "Пасмурно"; emoji = "☁️"; }
        else if (code == 45 || code == 48) { cond = "Туман"; emoji = "🌫️"; }
        else if (code >= 51 && code <= 57) { cond = "Морось"; emoji = "🌦️"; }
        else if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) { cond = "Дождь"; emoji = "🌧️"; }
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) { cond = "Снег"; emoji = "❄️"; }
        else if (code >= 95) { cond = "Гроза"; emoji = "⛈️"; }
        else { cond = "Облачно"; emoji = "☁️"; }
    }

    // ---------- calendar ----------
    private View calendar() {
        LinearLayout c = card(0xE61C1C1E, 0xE6000000);
        String wd = new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date())
                .toUpperCase(Locale.getDefault());
        TextView t1 = a.tv(wd, 14, 0xFFFF453A, true);
        t1.setSingleLine(true);
        c.addView(t1);
        TextView t2 = a.tv(String.valueOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)), 44, Color.WHITE, false);
        t2.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        t2.setIncludeFontPadding(false);
        c.addView(t2);
        spacer(c);
        TextView t3 = a.tv(events(), 14, 0xCCFFFFFF, false);
        t3.setMaxLines(4);
        t3.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(t3);
        c.setOnClickListener(v -> {
            if (!calPerm()) a.requestPerm(Manifest.permission.READ_CALENDAR, MainActivity.REQ_PERM_CAL);
            else a.go(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis())));
        });
        return c;
    }

    private String events() {
        if (!calPerm()) return "Нажмите, чтобы разрешить доступ к календарю";
        try {
            Calendar s = Calendar.getInstance();
            s.set(Calendar.HOUR_OF_DAY, 0);
            s.set(Calendar.MINUTE, 0);
            s.set(Calendar.SECOND, 0);
            s.set(Calendar.MILLISECOND, 0);
            long start = s.getTimeInMillis();
            long end = start + 24L * 3600L * 1000L;
            Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(b, start);
            ContentUris.appendId(b, end);
            Cursor cur = a.getContentResolver().query(b.build(),
                    new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                            CalendarContract.Instances.ALL_DAY},
                    null, null, CalendarContract.Instances.BEGIN + " ASC");
            StringBuilder sb = new StringBuilder();
            int n = 0;
            if (cur != null) {
                try {
                    while (cur.moveToNext() && n < 3) {
                        String title = cur.getString(0);
                        long begin = cur.getLong(1);
                        boolean allDay = cur.getInt(2) == 1;
                        if (n > 0) sb.append("\n");
                        if (!allDay) sb.append(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(begin))).append(" ");
                        sb.append(title == null ? "Событие" : title);
                        n++;
                    }
                } finally {
                    cur.close();
                }
            }
            return n == 0 ? "Сегодня событий нет" : sb.toString();
        } catch (Exception e) {
            return "Не удалось прочитать календарь";
        }
    }

    // ---------- notes ----------
    private View notes() {
        LinearLayout c = card(0xE62A2410, 0xE6141105);
        c.addView(a.tv("Заметки", 15, 0xFFFFD60A, true));
        String text = sp.getString("note", "");
        TextView body = a.tv(text.isEmpty() ? "Нажмите, чтобы написать" : text, 14, 0xDDFFFFFF, false);
        body.setMaxLines(6);
        body.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = a.dp(6);
        c.addView(body, lp);
        c.setOnClickListener(v -> editNote());
        return c;
    }

    private void editNote() {
        final EditText et = new EditText(a);
        et.setText(sp.getString("note", ""));
        et.setMinLines(4);
        et.setGravity(Gravity.TOP);
        new AlertDialog.Builder(a)
                .setTitle("Заметка")
                .setView(et)
                .setPositiveButton("Сохранить", (d, w) -> {
                    sp.edit().putString("note", et.getText().toString()).apply();
                    a.onWidgetData();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
