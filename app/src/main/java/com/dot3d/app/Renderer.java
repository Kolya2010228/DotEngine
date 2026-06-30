package com.dot3d.app;

import java.util.List;

/**
 * Software 3D renderer into a W x H character framebuffer with a per-cell depth
 * buffer. Allocation-free hot path: camera basis computed once per frame.
 * Terrain is drawn as solid columns (top face + side walls down to the
 * neighbouring column height) so there are no see-through gaps.
 */
public final class Renderer {
    public int W, H;
    public float[] depth;      // z-buffer (camera-space depth)
    public float[] glyphLum;   // luminance per cell [0,1]
    public int[] color;        // 0xRRGGBB per cell, -1 = sky
    public float aspect = 1f;

    private static final float LX = -0.3578f, LY = -0.8944f, LZ = -0.2683f;

    private float camx, camy, camz;
    private float fx, fy, fz, rx, ry, rz, ux, uy, uz, tanHalf;

    // reusable quad projection buffers
    private final float[] qx = new float[4];
    private final float[] qy = new float[4];
    private final float[] qd = new float[4];
    private final boolean[] qok = new boolean[4];

    // face luminance: 0=+X 1=-X 2=+Y(top) 3=-Y 4=+Z 5=-Z
    private final float[] faceLum = new float[6];

    public Renderer(int w, int h) {
        resize(w, h);
        faceLum[0] = faceLuminance(1, 0, 0);
        faceLum[1] = faceLuminance(-1, 0, 0);
        faceLum[2] = faceLuminance(0, 1, 0);
        faceLum[3] = faceLuminance(0, -1, 0);
        faceLum[4] = faceLuminance(0, 0, 1);
        faceLum[5] = faceLuminance(0, 0, -1);
    }

    private static float faceLuminance(float nx, float ny, float nz) {
        float d = -(nx * LX + ny * LY + nz * LZ);
        if (d < 0) d = 0;
        return 0.25f + 0.75f * d;
    }

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

    private boolean projQ(int i, float wx, float wy, float wz) {
        float dx = wx - camx, dy = wy - camy, dz = wz - camz;
        float cz = dx * fx + dy * fy + dz * fz;
        if (cz <= 0.05f) { qok[i] = false; return false; }
        float cx = dx * rx + dy * ry + dz * rz;
        float cy = dx * ux + dy * uy + dz * uz;
        float ndcX = cx / (cz * tanHalf * aspect);
        float ndcY = cy / (cz * tanHalf);
        qx[i] = (ndcX * 0.5f + 0.5f) * W;
        qy[i] = (1f - (ndcY * 0.5f + 0.5f)) * H;
        qd[i] = cz;
        qok[i] = true;
        return true;
    }

    /** Project 4 world points (already in qx/qy/qd via projQ) into 2 triangles. */
    private void quad(float lum, int col) {
        if (!qok[0] || !qok[1] || !qok[2] || !qok[3]) return;
        rasterize(qx[0], qy[0], qd[0], qx[1], qy[1], qd[1], qx[2], qy[2], qd[2], lum, col);
        rasterize(qx[0], qy[0], qd[0], qx[2], qy[2], qd[2], qx[3], qy[3], qd[3], lum, col);
    }

    /**
     * Render a solid terrain column occupying [wx,wx+1] x [0,h] x [wz,wz+1].
     * Side walls are drawn only where this column is taller than the neighbour,
     * down to the neighbour's height, producing a watertight surface.
     */
    public void renderColumn(int wx, int wz, float h,
                             float hE, float hW, float hS, float hN, int col) {
        float x0 = wx, x1 = wx + 1, z0 = wz, z1 = wz + 1;

        // top face (+Y)
        projQ(0, x0, h, z0); projQ(1, x1, h, z0); projQ(2, x1, h, z1); projQ(3, x0, h, z1);
        quad(faceLum[2], col);

        // east wall (+X) at x1
        if (h > hE && camx > x1) {
            projQ(0, x1, h, z0); projQ(1, x1, h, z1); projQ(2, x1, hE, z1); projQ(3, x1, hE, z0);
            quad(faceLum[0], col);
        }
        // west wall (-X) at x0
        if (h > hW && camx < x0) {
            projQ(0, x0, h, z1); projQ(1, x0, h, z0); projQ(2, x0, hW, z0); projQ(3, x0, hW, z1);
            quad(faceLum[1], col);
        }
        // south wall (+Z) at z1
        if (h > hS && camz > z1) {
            projQ(0, x1, h, z1); projQ(1, x0, h, z1); projQ(2, x0, hS, z1); projQ(3, x1, hS, z1);
            quad(faceLum[4], col);
        }
        // north wall (-Z) at z0
        if (h > hN && camz < z0) {
            projQ(0, x0, h, z0); projQ(1, x1, h, z0); projQ(2, x1, hN, z0); projQ(3, x0, hN, z0);
            quad(faceLum[5], col);
        }
    }

    /** Render a list of world-space triangles (used for loaded .obj models). */
    public void renderTris(List<Tri> tris) {
        for (int i = 0; i < tris.size(); i++) {
            Tri t = tris.get(i);
            float vx = camx - t.a.x, vy = camy - t.a.y, vz = camz - t.a.z;
            if (t.normal.x * vx + t.normal.y * vy + t.normal.z * vz <= 0) continue;
            projQ(0, t.a.x, t.a.y, t.a.z);
            projQ(1, t.b.x, t.b.y, t.b.z);
            projQ(2, t.c.x, t.c.y, t.c.z);
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
        if (Math.abs(area) < 1e-7f) return;
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