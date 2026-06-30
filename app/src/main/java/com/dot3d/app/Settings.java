package com.dot3d.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted, terminal/menu-shared engine settings. */
public final class Settings {
    private static final String PREFS = "dot3d_settings";
    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public int gridW() { return sp.getInt("gridW", 120); }
    public int gridH() { return sp.getInt("gridH", 60); }
    public void setGrid(int w, int h) { sp.edit().putInt("gridW", w).putInt("gridH", h).apply(); }

    public String palette() { return sp.getString("palette", Palette.CLASSIC); }
    public void setPalette(String p) { sp.edit().putString("palette", p).apply(); }

    public boolean color() { return sp.getBoolean("color", true); }
    public void setColor(boolean c) { sp.edit().putBoolean("color", c).apply(); }

    public long seed() { return sp.getLong("seed", 1337L); }
    public void setSeed(long s) { sp.edit().putLong("seed", s).apply(); }

    public float sensitivity() { return sp.getFloat("sens", 0.005f); }
    public void setSensitivity(float s) { sp.edit().putFloat("sens", s).apply(); }

    public float fov() { return sp.getFloat("fov", 70f); }
    public void setFov(float f) { sp.edit().putFloat("fov", f).apply(); }

    public int renderDist() { return sp.getInt("rdist", 1); }
    public void setRenderDist(int d) { sp.edit().putInt("rdist", d).apply(); }
}