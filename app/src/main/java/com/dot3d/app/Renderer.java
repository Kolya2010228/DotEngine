package com.dot3d.app;

import java.util.List;

/**
 * Software 3D renderer into a W x H character framebuffer with a per-cell depth
 * buffer. Allocation-free hot path: the camera basis is computed once per frame,
 * cubes are rendered by projecting their 8 corners into reusable arrays and
 * rasterizing only the camera-facing faces.
 */
public final class Renderer {
    public int W, H;
    public float[] depth;      // z-buffer (camera-space depth)
    public float[] glyphLum;   // luminance per cell [0,1]
    public int[] color;        // 0xRRGGBB per cell, -1 = sky
    public float aspect = 1f;  // pixel aspect of the displayed grid (set by EngineView)

    // light direction (normalized): (-0.4,-1,-0.3)
    private static final float LX = -0.3578f, LY = -0.8944f, LZ = -0.2683f;

    private float camx, camy, camz;
    private float fx, fy, fz, rx, ry, rz, ux, uy, uz, tanHalf;

    // reusable corner buffers
    private final float[] csx = new float[8];
    private final float[] csy = new float[8];
    private final float[] cdep = new float[8];
    private final boolean[] cok = new boolean[8];

    // cube corner offsets
    private static final float[][] OFF = {
            {-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f},
            {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}
    };

    // precomputed luminance per cube face: 0=+X 1=-X 2=+Y 3=-Y 4=+Z 5=-Z
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
        // right = normalize(cross(f, (0,1,0))) = normalize((-fz,0,fx))
        float rlen = (float) Math.sqrt(fz * fz + fx * fx);
        if (rlen < 1e-5f) rlen = 1e-5f;
        rx = -fz / rlen; ry = 0; rz = fx / rlen;
        // up = cross(right, f)
        ux = ry * fz - rz * fy;
        uy = rz * fx - rx * fz;
        uz = rx * fy - ry * fx;
        tanHalf = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2.0);
    }

    private void projCorner(int i, float wx, float wy, float wz) {
        float dx = wx - camx, dy = wy - camy, dz = wz - camz;
        float cz = dx * fx + dy * fy + dz * fz;
        if (cz <= 0.05f) { cok[i] = false; return; }
        float cx = dx * rx + dy * ry + dz * rz;
        float cy = dx * ux + dy * uy + dz * uz;
        float ndcX = cx / (cz * tanHalf * aspect);
        float ndcY = cy / (cz * tanHalf);
        csx[i] = (ndcX * 0.5f + 0.5f) * W;
        csy[i] = (1f - (ndcY * 0.5f + 0.5f)) * H;
        cdep[i] = cz;
        cok[i] = true;
    }

    /** Render one axis-aligned unit cube centered at (cx,cy,cz). */
    public void renderCube(float cx, float cy, float cz, int col) {
        for (int i = 0; i < 8; i++) {
            projCorner(i, cx + OFF[i][0], cy + OFF[i][1], cz + OFF[i][2]);
        }
        if (camx > cx + 0.5f) face(1, 5, 6, 2, faceLum[0], col);
        if (camx < cx - 0.5f) face(0, 4, 7, 3, faceLum[1], col);
        if (camy > cy + 0.5f) face(3, 2, 6, 7, faceLum[2], col);
        if (camy < cy - 0.5f) face(0, 1, 5, 4, faceLum[3], col);
        if (camz > cz + 0.5f) face(4, 5, 6, 7, faceLum[4], col);
        if (camz < cz - 0.5f) face(0, 1, 2, 3, faceLum[5], col);
    }

    private void face(int a, int b, int c, int d, float lum, int col) {
        if (!cok[a] || !cok[b] || !cok[c] || !cok[d]) return;
        rasterize(csx[a], csy[a], cdep[a], csx[b], csy[b], cdep[b], csx[c], csy[c], cdep[c], lum, col);
        rasterize(csx[a], csy[a], cdep[a], csx[c], csy[c], cdep[c], csx[d], csy[d], cdep[d], lum, col);
    }

    /** Render a list of world-space triangles (used for loaded .obj models). */
    public void renderTris(List<Tri> tris) {
        for (int i = 0; i < tris.size(); i++) {
            Tri t = tris.get(i);
            float vx = camx - t.a.x, vy = camy - t.a.y, vz = camz - t.a.z;
            if (t.normal.x * vx + t.normal.y * vy + t.normal.z * vz <= 0) continue;
            projCorner(0, t.a.x, t.a.y, t.a.z);
            projCorner(1, t.b.x, t.b.y, t.b.z);
            projCorner(2, t.c.x, t.c.y, t.c.z);
            if (!cok[0] || !cok[1] || !cok[2]) continue;
            float d = -(t.normal.x * LX + t.normal.y * LY + t.normal.z * LZ);
            if (d < 0) d = 0;
            float lum = 0.25f + 0.75f * d;
            rasterize(csx[0], csy[0], cdep[0], csx[1], csy[1], cdep[1], csx[2], csy[2], cdep[2], lum, t.color);
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