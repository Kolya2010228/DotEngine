package com.dot3d.app;

import android.content.Context;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Overlay pause menu shown when Back is pressed during the engine. Tunes the
 * current session live (grid, palette/color, sensitivity, fov, render distance,
 * seed) via the shared Settings.
 *
 * Layout: a fixed title, a scrollable body of fields (so nothing is clipped on a
 * short landscape screen), and a pinned bottom button row. Sliders show their live
 * value in the label. "Apply settings" commits the fields and confirms with a toast
 * without leaving the menu; Continue / Exit also commit before acting.
 */
public final class PauseMenu extends LinearLayout {
    public interface Listener { void onContinue(); void onExitToTerminal(); }

    private interface Fmt { String f(int v); }

    public PauseMenu(Context ctx, final Settings s, final Listener l) {
        super(ctx);
        setOrientation(VERTICAL);
        setBackgroundColor(0xEE000000);
        final float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        setPadding(pad, pad, pad, pad);

        addView(title("PAUSED"));

        // scrollable body so long content is never cut off on landscape
        ScrollView scroll = new ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(VERTICAL);
        scroll.addView(body);
        addView(scroll);

        // grid
        body.addView(label("Grid size (chars)"));
        final EditText gw = number("Grid width", s.gridW());
        final EditText gh = number("Grid height", s.gridH());
        body.addView(gw); body.addView(gh);

        // palette
        body.addView(label("Palette"));
        final EditText pal = text("Palette ramp / name", s.palette());
        body.addView(pal);

        // color
        final CheckBox color = new CheckBox(ctx);
        color.setText("Color");
        color.setTextColor(0xFFFFFFFF);
        color.setChecked(s.color());
        body.addView(color);

        // seed
        body.addView(label("Seed"));
        final EditText seed = new EditText(ctx);
        seed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        seed.setHint("World seed");
        seed.setText(String.valueOf(s.seed()));
        seed.setTextColor(0xFFFFFFFF);
        body.addView(seed);

        // sensitivity (live value)
        final TextView sensLbl = label("Look sensitivity");
        body.addView(sensLbl);
        final SeekBar sens = seek((int) (s.sensitivity() * 2000), 100);
        bindLabel(sens, sensLbl, "Look sensitivity", new Fmt() {
            public String f(int v) { return String.format("%.4f", Math.max(1, v) / 2000f); }
        });
        body.addView(sens);

        // fov (live value)
        final TextView fovLbl = label("FOV");
        body.addView(fovLbl);
        final SeekBar fov = seek((int) s.fov(), 120);
        bindLabel(fov, fovLbl, "FOV", new Fmt() {
            public String f(int v) { return String.valueOf(Math.max(40, v)); }
        });
        body.addView(fov);

        // render distance (live value)
        final TextView rdLbl = label("Render distance (chunks)");
        body.addView(rdLbl);
        final SeekBar rd = seek(s.renderDist(), 4);
        bindLabel(rd, rdLbl, "Render distance (chunks)", new Fmt() {
            public String f(int v) { return String.valueOf(Math.max(1, v)); }
        });
        body.addView(rd);

        // pinned bottom button row
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        int gap = (int) (6 * d);

        Button apply = new Button(ctx);
        apply.setText("Apply settings");
        apply.setOnClickListener(v -> {
            apply(s, gw, gh, pal, color, seed, sens, fov, rd);
            Toast.makeText(getContext(), "Settings applied \u2713", Toast.LENGTH_SHORT).show();
        });

        Button cont = new Button(ctx);
        cont.setText("Continue");
        cont.setOnClickListener(v -> {
            apply(s, gw, gh, pal, color, seed, sens, fov, rd);
            l.onContinue();
        });

        Button exit = new Button(ctx);
        exit.setText("Exit");
        exit.setOnClickListener(v -> {
            apply(s, gw, gh, pal, color, seed, sens, fov, rd);
            l.onExitToTerminal();
        });

        row.addView(apply, btnLp(gap));
        row.addView(cont, btnLp(gap));
        row.addView(exit, btnLp(gap));
        addView(row);
    }

    private LinearLayout.LayoutParams btnLp(int gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(gap, gap, gap, 0);
        return lp;
    }

    private void bindLabel(final SeekBar sb, final TextView tv, final String base, final Fmt fmt) {
        tv.setText(base + ": " + fmt.f(sb.getProgress()));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) { tv.setText(base + ": " + fmt.f(p)); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    private void apply(Settings s, EditText gw, EditText gh, EditText pal, CheckBox color,
                       EditText seed, SeekBar sens, SeekBar fov, SeekBar rd) {
        try {
            int w = parse(gw, s.gridW());
            int h = parse(gh, s.gridH());
            if (w >= 10 && w <= 200 && h >= 10 && h <= 200) {
                s.setGrid(w, h);
            }
        } catch (Exception ignored) {}
        String p = pal.getText().toString().trim();
        String preset = Palette.presetByName(p);
        s.setPalette(preset != null ? preset : (p.isEmpty() ? s.palette() : p));
        s.setColor(color.isChecked());
        try {
            s.setSeed(Long.parseLong(seed.getText().toString().trim()));
        } catch (Exception e) {
            // keep existing seed on parse error
        }
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
