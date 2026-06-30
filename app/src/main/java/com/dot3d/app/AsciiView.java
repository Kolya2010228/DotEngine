package com.dot3d.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

/**
 * Renders the character framebuffer fast using a pre-rasterized glyph atlas.
 * Each printable ASCII glyph (0x20..0x7E) is drawn once into an atlas bitmap;
 * each frame we blit cells from the atlas and tint by color. This avoids a
 * Canvas.drawText call per cell.
 */
public final class AsciiView {
    private static final int FIRST = 0x20, LAST = 0x7E;
    private final Bitmap atlas;          // white glyphs on transparent
    private final int cellW, cellH;
    private final Paint blit = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();

    public AsciiView(Context ctx, int cellPx) {
        this.cellH = cellPx;
        // monospace: measure width
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.MONOSPACE);
        p.setTextSize(cellPx);
        p.setColor(Color.WHITE);
        this.cellW = (int) Math.ceil(p.measureText("M"));
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

    /** Draw the framebuffer to the canvas. */
    public void draw(Canvas c, Renderer r, Palette palette, boolean color, int bg) {
        c.drawColor(bg);
        for (int y = 0; y < r.H; y++) {
            for (int x = 0; x < r.W; x++) {
                int idx = y * r.W + x;
                int col = r.color[idx];
                char glyph;
                int tint;
                if (col < 0) {
                    continue; // sky: leave background
                }
                glyph = palette.glyph(r.glyphLum[idx]);
                if (glyph == ' ') continue;
                if (color) {
                    // modulate base color by luminance
                    float l = 0.4f + 0.6f * r.glyphLum[idx];
                    int rr = (int) (((col >> 16) & 0xFF) * l);
                    int gg = (int) (((col >> 8) & 0xFF) * l);
                    int bb = (int) ((col & 0xFF) * l);
                    tint = Color.rgb(rr, gg, bb);
                } else {
                    int g = (int) (255 * r.glyphLum[idx]);
                    tint = Color.rgb(g, g, g);
                }
                int gi = glyph - FIRST;
                src.set(gi * cellW, 0, gi * cellW + cellW, cellH);
                int dx = x * cellW, dy = y * cellH;
                dst.set(dx, dy, dx + cellW, dy + cellH);
                blit.setColorFilter(new android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN));
                c.drawBitmap(atlas, src, dst, blit);
            }
        }
    }
}