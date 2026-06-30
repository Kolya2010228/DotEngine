package com.dot3d.app;

import java.util.List;

/**
 * Software 3D renderer into a W x H character framebuffer with a per-cell depth
 * buffer (camera-space forward distance).
 *
 * The infinite voxel world is drawn by per-cell heightfield RAYMARCHING
 * ({@link #raymarchTerrain}) which fills every cell and gives correct depth,
 * shading and distance fog. Loaded .obj models are drawn by triangle
 * rasterization ({@link #renderTris}) on top, depth-tested against the same
 * buffer. Allocation-free hot path: camera basis computed once per frame.
 */
public final class Renderer {
    public int W, H;
    public float[] depth;      // z-buffer (camera-space forward distance)
    public float[] glyphLum;   // luminance per cell [0,1]
    public int[] color;        // 0xRRGGBB per cell, -1 = sky
    public float aspect = 1f;

    // Light direction (normalized, pointing down-ish).
    private static final float LX = -0.3578f, LY = -0.8944f, LZ = -0.2683f;

    private float camx, camy, camz;
    private float fx, fy, fz, rx, ry, rz, ux, uy, uz, tanHalf;

    // reusable triangle projection buffers (for renderTris)
    private final float[] qx = new float[3];
    private final float[] qy = new float[3];
    private final float[] qd = new float[3];
    private final boolean[] qok = new boolean[3];

    public Renderer(int w, int h) { resize(w, h); }

    public void resize(int w, int h) {
        this.W = w; this.H = h;
        int n = w * h;
        depth = new float[n];
        glyphLum = new float[n];
        color = new int[n];
    }

    public void beginFrame(Camera cam) {
        for (int i = 0; i < depth.length; i++) {
            depth[i] = Float.POSITIVE_INFINITY;
            color[i] = -1;
        }
        camx = cam.pos.x; camy = cam.pos.y; camz = cam.pos.z;
        Vec3 f = cam.forward();
        fx = f.x; fy = f.y; fz = f.z;
        float rlen = (float) Math.sqrt(fz * fz + fx * fx);
        if (rlen < 1e-5f) rlen = 1e-5f;
        rx = -fz / rlen; ry = 0; rz = fx / rlen;
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
        tanHalf = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2.0);
    }

    /**
     * Raymarch the infinite heightfield world: one ray per character cell.
     * Fills glyphLum/color/depth for every cell the ray hits (sky cells stay
     * color=-1). Returns the number of cells that hit terrain.
     */
    public int raymarchTerrain(World w, float maxDist) {
        int hits = 0;
        for (int sy = 0; sy < H; sy++) {
            float ndcY = 1f - 2f * (sy + 0.5f) / H;
            float ay = ndcY * tanHalf;
            int rowBase = sy * W;
            for (int sx = 0; sx < W; sx++) {
                float ndcX = 2f * (sx + 0.5f) / W - 1f;
                float ax = ndcX * tanHalf * aspect;
                float dx = fx + rx * ax + ux * ay;
                float dy = fy + ry * ax + uy * ay;
                float dz = fz + rz * ax + uz * ay;
                float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dl < 1e-6f) dl = 1e-6f;
                dx /= dl; dy /= dl; dz /= dl;

                float t = 0f;
                boolean hit = false;
                float px = 0, py = 0, pz = 0;
                int ix = 0, iz = 0, hcol = 0;
                while (t < maxDist) {
                    px = camx + dx * t;
                    py = camy + dy * t;
                    pz = camz + dz * t;
                    ix = (int) Math.floor(px);
                    iz = (int) Math.floor(pz);
                    hcol = w.heightAt(ix, iz);
                    if (py < hcol) { hit = true; break; }
                    t += 0.12f + t * 0.02f; // adaptive step: cheap far away
                }
                int idx = rowBase + sx;
                if (!hit) continue; // sky

                int hl = w.heightAt(ix - 1, iz), hr = w.heightAt(ix + 1, iz);
                int hd = w.heightAt(ix, iz - 1), hu = w.heightAt(ix, iz + 1);
                float nx = (hl - hr), ny = 2f, nz = (hd - hu);
                float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl < 1e-5f) nl = 1e-5f;
                nx /= nl; ny /= nl; nz /= nl;
                float diff = -(nx * LX + ny * LY + nz * LZ);
                if (diff < 0) diff = 0;
                float fog = 1f - t / maxDist;
                if (fog < 0) fog = 0;
                float lum = (0.25f + 0.75f * diff) * (0.4f + 0.6f * fog);

                float cz = (px - camx) * fx + (py - camy) * fy + (pz - camz) * fz;
                depth[idx] = cz;
                glyphLum[idx] = lum;
                color[idx] = w.colorForHeight(hcol);
                hits++;
            }
        }
        return hits;
    }

    private boolean proj(int i, float wx, float wy, float wz) {
        float dx = wx - camx, dy = wy - camy, dz = wz - camz;
        float cz = dx * fx + dy * fy + dz * fz;
        if (cz <= 0.05f) { qok[i] = false; return false; }
        float cx = dx * rx + dy * ry + dz * rz;
        float cy = dx * ux + dy * uy + dz * uz;
        qx[i] = (cx / (cz * tanHalf * aspect) * 0.5f + 0.5f) * W;
        qy[i] = (1f - (cy / (cz * tanHalf) * 0.5f + 0.5f)) * H;
        qd[i] = cz;
        qok[i] = true;
        return true;
    }

    /** Render a list of world-space triangles (loaded .obj models), depth-tested. */
    public void renderTris(List<Tri> tris) {
        for (int i = 0; i < tris.size(); i++) {
            Tri t = tris.get(i);
            float vx = camx - t.a.x, vy = camy - t.a.y, vz = camz - t.a.z;
            if (t.normal.x * vx + t.normal.y * vy + t.normal.z * vz <= 0) continue;
            proj(0, t.a.x, t.a.y, t.a.z);
            proj(1, t.b.x, t.b.y, t.b.z);
            proj(2, t.c.x, t.c.y, t.c.z);
            if (!qok[0] || !qok[1] || !qok[2]) continue;
            float d = -(t.normal.x * LX + t.normal.y * LY + t.normal.z * LZ);
            if (d < 0) d = 0;
            float lum = 0.25f + 0.75f * d;
            rasterize(qx[0], qy[0], qd[0], qx[1], qy[1], qd[1], qx[2], qy[2], qd[2], lum, t.color);
        }
    }

    private void rasterize(float ax, float ay, float ad, float bx, float by, float bd,
                           float cx, float cy, float cd, float lum, int col) {
        int minX = (int) Math.floor(Math.min(ax, Math.min(bx, cx)));
        int maxX = (int) Math.ceil(Math.max(ax, Math.max(bx, cx)));
        int minY = (int) Math.floor(Math.min(ay, Math.min(by, cy)));
        int maxY = (int) Math.ceil(Math.max(ay, Math.max(by, cy)));
        if (minX < 0) minX = 0;
        if (minY < 0) minY = 0;
        if (maxX >= W) maxX = W - 1;
        if (maxY >= H) maxY = H - 1;
        if (minX > maxX || minY > maxY) return;

        float area = (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
        if (area < 1e-5f && area > -1e-5f) return;
        float invArea = 1f / area;

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            int row = y * W;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = ((cx - bx) * (py - by) - (cy - by) * (px - bx)) * invArea;
                float w1 = ((ax - cx) * (py - cy) - (ay - cy) * (px - cx)) * invArea;
                float w2 = 1f - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                float dep = w0 * ad + w1 * bd + w2 * cd;
                int idx = row + x;
                if (dep < depth[idx]) {
                    depth[idx] = dep;
                    glyphLum[idx] = lum;
                    color[idx] = col;
                }
            }
        }
    }
}