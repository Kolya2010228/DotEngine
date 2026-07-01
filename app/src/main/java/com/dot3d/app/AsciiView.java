package com.dot3d.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.util.HashMap;

/**
 * Fast ASCII blit via a pre-rasterized glyph atlas. Each printable glyph is
 * drawn once into an atlas bitmap; each frame cells are blitted from the atlas
 * and tinted. Color filters are cached (the tint set is small and discrete), so
 * the hot path allocates nothing.
 */
public final class AsciiView {
    private static final int FIRST = 0x20, LAST = 0x7E;
    private final Bitmap atlas;
    private final int cellW, cellH;
    private final Paint blit = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final HashMap<Integer, PorterDuffColorFilter> filterCache = new HashMap<>();

    public AsciiView(Context ctx, int cellPx) {
        this.cellH = cellPx;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(cellPx);
        p.setColor(Color.WHITE);
        this.cellW = Math.max(1, (int) Math.ceil(p.measureText("M")));
        int count = LAST - FIRST + 1;
        atlas = Bitmap.createBitmap(cellW * count, cellH, Bitmap.Config.ARGB_8888);
        Canvas ac = new Canvas(atlas);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = -fm.top;
        for (int ch = FIRST; ch <= LAST; ch++) {
            int i = ch - FIRST;
            ac.drawText(String.valueOf((char) ch), i * cellW, baseline, p);
        }
        blit.setFilterBitmap(false);
    }

    public int cellW() { return cellW; }
    public int cellH() { return cellH; }

    private PorterDuffColorFilter filter(int tint) {
        PorterDuffColorFilter f = filterCache.get(tint);
        if (f == null) {
            f = new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN);
            filterCache.put(tint, f);
        }
        return f;
    }

    public void draw(Canvas c, Renderer r, Palette palette, boolean color, int bg) {
        c.drawColor(bg);
        int W = r.W, H = r.H;
        for (int y = 0; y < H; y++) {
            int row = y * W;
            int dy = y * cellH;
            for (int x = 0; x < W; x++) {
                int idx = row + x;
                int col = r.color[idx];
                if (col < 0) continue; // sky
                float lum = r.glyphLum[idx];
                char glyph = palette.glyph(lum);
                if (glyph == ' ') continue;
                if (glyph < FIRST || glyph > LAST) continue; // custom palette glyph outside the ASCII atlas
                int tint;
                if (color) {
                    float l = 0.4f + 0.6f * lum;
                    int rr = (int) (((col >> 16) & 0xFF) * l);
                    int gg = (int) (((col >> 8) & 0xFF) * l);
                    int bb = (int) ((col & 0xFF) * l);
                    tint = Color.rgb(rr, gg, bb);
                } else {
                    int g = (int) (255 * lum);
                    tint = Color.rgb(g, g, g);
                }
                int gi = glyph - FIRST;
                src.set(gi * cellW, 0, gi * cellW + cellW, cellH);
                int dx = x * cellW;
                dst.set(dx, dy, dx + cellW, dy + cellH);
                blit.setColorFilter(filter(tint));
                c.drawBitmap(atlas, src, dst, blit);
            }
        }
    }
}