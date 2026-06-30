package com.dot3d.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Software 3D renderer. Projects world-space triangles into a W x H character
 * framebuffer with a per-cell depth buffer, shades by normal dot light, and
 * outputs a glyph index + packed color per cell.
 *
 * Output buffers (size W*H, row-major):
 *  - glyphLum[i]   luminance in [0,1] (caller maps to a palette glyph)
 *  - color[i]      0xRRGGBB surface color (or -1 for empty/sky)
 */
public final class Renderer {
    public int W, H;
    public float[] depth;      // z-buffer (camera-space depth), +inf = empty
    public float[] glyphLum;   // luminance per cell
    public int[] color;        // color per cell, -1 = sky

    private final Vec3 light = new Vec3(-0.4f, -1f, -0.3f).normalized();

    public Renderer(int w, int h) { resize(w, h); }

    public void resize(int w, int h) {
        this.W = w; this.H = h;
        int n = w * h;
        depth = new float[n];
        glyphLum = new float[n];
        color = new int[n];
    }

    public void clear() {
        for (int i = 0; i < depth.length; i++) {
            depth[i] = Float.POSITIVE_INFINITY;
            glyphLum[i] = 0f;
            color[i] = -1;
        }
    }

    private static final class P {
        float sx, sy, depth; boolean ok;
    }

    /** Project a world point to screen cell coords + camera depth. */
    private P project(Vec3 wp, Camera cam, float aspect, float tanHalfFov) {
        // transform into camera space
        Vec3 rel = wp.sub(cam.pos);
        Vec3 fwd = cam.forward();
        Vec3 right = fwd.cross(new Vec3(0, 1, 0)).normalized();
        Vec3 up = right.cross(fwd).normalized();
        float cz = rel.dot(fwd);
        P p = new P();
        if (cz <= 0.05f) { p.ok = false; return p; }
        float cx = rel.dot(right);
        float cy = rel.dot(up);
        float ndcX = (cx / (cz * tanHalfFov * aspect));
        float ndcY = (cy / (cz * tanHalfFov));
        p.sx = (ndcX * 0.5f + 0.5f) * W;
        p.sy = (1f - (ndcY * 0.5f + 0.5f)) * H;
        p.depth = cz;
        p.ok = true;
        return p;
    }

    public void render(List<Tri> tris, Camera cam) {
        clear();
        float tanHalfFov = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2.0);
        float aspect = (float) W / (float) H;
        for (Tri t : tris) {
            // backface cull: skip triangles facing away
            Vec3 toCam = cam.pos.sub(t.a);
            if (t.normal.dot(toCam) <= 0) continue;

            P pa = project(t.a, cam, aspect, tanHalfFov);
            P pb = project(t.b, cam, aspect, tanHalfFov);
            P pc = project(t.c, cam, aspect, tanHalfFov);
            if (!pa.ok || !pb.ok || !pc.ok) continue;

            float lum = 0.25f + 0.75f * Math.max(0f, -t.normal.dot(light));
            rasterize(pa, pb, pc, lum, t.color);
        }
    }

    private void rasterize(P a, P b, P c, float lum, int col) {
        int minX = (int) Math.floor(Math.min(a.sx, Math.min(b.sx, c.sx)));
        int maxX = (int) Math.ceil(Math.max(a.sx, Math.max(b.sx, c.sx)));
        int minY = (int) Math.floor(Math.min(a.sy, Math.min(b.sy, c.sy)));
        int maxY = (int) Math.ceil(Math.max(a.sy, Math.max(b.sy, c.sy)));
        if (minX < 0) minX = 0;
        if (minY < 0) minY = 0;
        if (maxX >= W) maxX = W - 1;
        if (maxY >= H) maxY = H - 1;
        if (minX > maxX || minY > maxY) return;

        float area = edge(a.sx, a.sy, b.sx, b.sy, c.sx, c.sy);
        if (Math.abs(area) < 1e-5f) return;
        float invArea = 1f / area;

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = edge(b.sx, b.sy, c.sx, c.sy, px, py) * invArea;
                float w1 = edge(c.sx, c.sy, a.sx, a.sy, px, py) * invArea;
                float w2 = edge(a.sx, a.sy, b.sx, b.sy, px, py) * invArea;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float d = w0 * a.depth + w1 * b.depth + w2 * c.depth;
                int idx = y * W + x;
                if (d < depth[idx]) {
                    depth[idx] = d;
                    glyphLum[idx] = lum;
                    color[idx] = col;
                }
            }
        }
    }

    private static float edge(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }
}