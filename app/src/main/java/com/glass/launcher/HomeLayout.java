package com.glass.launcher;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered home-screen content (apps, folders, widgets) plus its persistence and grid flow. */
class HomeLayout {
    static final int APP = 0, FOLDER = 1, WIDGET_SYS = 2, WIDGET_BUILTIN = 3;

    static class Entry {
        int type;
        String key;
        String name = "Папка";
        List<String> keys = new ArrayList<>();
        int wid = -1;

        Entry() { }
        Entry(int type, String key) { this.type = type; this.key = key; }

        int spanW() { return type == WIDGET_BUILTIN ? 2 : (type == WIDGET_SYS ? 4 : 1); }
        int spanH() { return (type == WIDGET_BUILTIN || type == WIDGET_SYS) ? 2 : 1; }
    }

    static class Place {
        Entry e;
        int idx, page, row, col, cw, rh;
    }

    final List<Entry> entries = new ArrayList<>();

    boolean saved(SharedPreferences sp) { return sp.contains("layout"); }

    void load(SharedPreferences sp) {
        entries.clear();
        String s = sp.getString("layout", null);
        if (s == null) return;
        try {
            JSONArray arr = new JSONArray(s);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.type = o.getInt("t");
                e.key = o.has("k") ? o.getString("k") : null;
                e.name = o.optString("n", "Папка");
                JSONArray ks = o.optJSONArray("ks");
                if (ks != null) for (int j = 0; j < ks.length(); j++) e.keys.add(ks.getString(j));
                e.wid = o.optInt("id", -1);
                entries.add(e);
            }
        } catch (Exception ex) {
            entries.clear();
        }
    }

    void save(SharedPreferences sp) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("t", e.type);
                if (e.key != null) o.put("k", e.key);
                o.put("n", e.name);
                JSONArray ks = new JSONArray();
                for (String k : e.keys) ks.put(k);
                o.put("ks", ks);
                o.put("id", e.wid);
                arr.put(o);
            }
            sp.edit().putString("layout", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Drops apps that no longer exist and appends new ones. Returns true if anything changed. */
    boolean sync(Map<String, ?> apps, List<String> ordered) {
        Set<String> used = new HashSet<>();
        boolean changed = false;
        Iterator<Entry> it = entries.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.type == APP) {
                if (e.key == null || !apps.containsKey(e.key) || used.contains(e.key)) {
                    it.remove();
                    changed = true;
                } else {
                    used.add(e.key);
                }
            } else if (e.type == FOLDER) {
                Iterator<String> ki = e.keys.iterator();
                while (ki.hasNext()) {
                    String k = ki.next();
                    if (!apps.containsKey(k) || used.contains(k)) {
                        ki.remove();
                        changed = true;
                    } else {
                        used.add(k);
                    }
                }
            }
        }
        for (String k : ordered) {
            if (!used.contains(k)) {
                entries.add(new Entry(APP, k));
                changed = true;
            }
        }
        return changed;
    }

    /** Empty folders disappear, one-app folders turn back into the app. */
    void normalize() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.type != FOLDER) continue;
            if (e.keys.isEmpty()) entries.remove(i);
            else if (e.keys.size() == 1) entries.set(i, new Entry(APP, e.keys.get(0)));
        }
    }

    private static boolean free(boolean[][] occ, int row, int col, int cw, int rh) {
        for (int r = row; r < row + rh; r++)
            for (int c = col; c < col + cw; c++)
                if (occ[r][c]) return false;
        return true;
    }

    /** Flows entries onto pages of 4 columns x rows, widgets span several cells. */
    List<Place> computePlaces(int rows) {
        List<Place> out = new ArrayList<>();
        List<boolean[][]> occ = new ArrayList<>();
        occ.add(new boolean[rows][4]);
        int page = 0, row = 0, col = 0;
        for (int idx = 0; idx < entries.size(); idx++) {
            Entry e = entries.get(idx);
            int cw = Math.min(4, e.spanW());
            int rh = Math.min(rows, e.spanH());
            while (true) {
                if (row + rh > rows) {
                    page++;
                    row = 0;
                    col = 0;
                    if (page >= occ.size()) occ.add(new boolean[rows][4]);
                    continue;
                }
                if (col + cw > 4) {
                    row++;
                    col = 0;
                    continue;
                }
                if (free(occ.get(page), row, col, cw, rh)) break;
                col++;
            }
            boolean[][] g = occ.get(page);
            for (int r = row; r < row + rh; r++)
                for (int c = col; c < col + cw; c++) g[r][c] = true;
            Place p = new Place();
            p.e = e; p.idx = idx; p.page = page; p.row = row; p.col = col; p.cw = cw; p.rh = rh;
            out.add(p);
            col += cw;
        }
        return out;
    }
}
