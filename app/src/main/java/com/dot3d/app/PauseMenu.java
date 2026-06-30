package com.dot3d.app;

import android.content.Context;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Overlay pause menu shown when Back is pressed during the engine. Tunes the
 * current session live (grid, palette/color, sensitivity, fov, render distance,
 * seed) via the shared Settings, and offers Continue / Exit to terminal.
 */
public final class PauseMenu extends LinearLayout {
    public interface Listener { void onContinue(); void onExitToTerminal(); }

    public PauseMenu(Context ctx, final Settings s, final Listener l) {
        super(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(0xCC000000);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        setPadding(pad, pad, pad, pad);

        addView(title("PAUSED"));

        // grid
        final EditText gw = number("Grid width", s.gridW());
        final EditText gh = number("Grid height", s.gridH());
        addView(label("Grid size (chars)"));
        addView(gw); addView(gh);

        // palette
        final EditText pal = text("Palette ramp / name", s.palette());
        addView(label("Palette"));
        addView(pal);

        // color
        final CheckBox color = new CheckBox(ctx);
        color.setText("Color");
        color.setTextColor(0xFFFFFFFF);
        color.setChecked(s.color());
        addView(color);

        // seed
        final EditText seed = number("World seed", (int) s.seed());
        addView(label("Seed"));
        addView(seed);

        // sensitivity
        addView(label("Look sensitivity"));
        final SeekBar sens = seek((int) (s.sensitivity() * 2000), 100);
        addView(sens);

        // fov
        addView(label("FOV"));
        final SeekBar fov = seek((int) s.fov(), 120);
        addView(fov);

        // render distance
        addView(label("Render distance (chunks)"));
        final SeekBar rd = seek(s.renderDist(), 4);
        addView(rd);

        Button cont = new Button(ctx);
        cont.setText("Continue");
        cont.setOnClickListener(v -> {
            apply(s, gw, gh, pal, color, seed, sens, fov, rd);
            l.onContinue();
        });
        addView(cont);

        Button exit = new Button(ctx);
        exit.setText("Exit to terminal");
        exit.setOnClickListener(v -> {
            apply(s, gw, gh, pal, color, seed, sens, fov, rd);
            l.onExitToTerminal();
        });
        addView(exit);
    }

    private void apply(Settings s, EditText gw, EditText gh, EditText pal, CheckBox color,
                       EditText seed, SeekBar sens, SeekBar fov, SeekBar rd) {
        try { s.setGrid(parse(gw, s.gridW()), parse(gh, s.gridH())); } catch (Exception ignored) {}
        String p = pal.getText().toString().trim();
        String preset = Palette.presetByName(p);
        s.setPalette(preset != null ? preset : (p.isEmpty() ? s.palette() : p));
        s.setColor(color.isChecked());
        s.setSeed(parse(seed, (int) s.seed()));
        s.setSensitivity(Math.max(1, sens.getProgress()) / 2000f);
        s.setFov(Math.max(40, fov.getProgress()));
        s.setRenderDist(Math.max(1, rd.getProgress()));
    }

    private int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private TextView title(String t) {
        TextView tv = new TextView(getContext());
        tv.setText(t);
        tv.setTextColor(0xFF33FF66);
        tv.setTextSize(28);
        return tv;
    }

    private TextView label(String t) {
        TextView tv = new TextView(getContext());
        tv.setText(t);
        tv.setTextColor(0xFFAAAAAA);
        return tv;
    }

    private EditText number(String hint, int val) {
        EditText e = new EditText(getContext());
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setHint(hint);
        e.setText(String.valueOf(val));
        e.setTextColor(0xFFFFFFFF);
        return e;
    }

    private EditText text(String hint, String val) {
        EditText e = new EditText(getContext());
        e.setHint(hint);
        e.setText(val);
        e.setTextColor(0xFFFFFFFF);
        return e;
    }

    private SeekBar seek(int val, int max) {
        SeekBar sb = new SeekBar(getContext());
        sb.setMax(max);
        sb.setProgress(Math.min(max, Math.max(0, val)));
        return sb;
    }
}