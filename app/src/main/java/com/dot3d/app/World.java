package com.dot3d.app;

/**
 * Infinite procedural cube world. Height at each (x,z) column is derived
 * deterministically from the seed via hashed value-noise. Only the topmost cube
 * of each nearby column is rendered (a voxel-style surface), which keeps the
 * triangle count low. Renders directly into the Renderer with no per-frame
 * object allocation.
 */
public final class World {
    public static final int CHUNK = 16;
    private long seed;

    private static final int[] BIOME = new int[] {
            0x3F6FB0, // low: water-ish blue
            0xC2B280, // sand
            0x4C9A2A, // grass
            0x6B6B6B, // stone
            0xF5F5F5, // snow
    };

    public World(long seed) { this.seed = seed; }

    public void setSeed(long s) { this.seed = s; }
    public long getSeed() { return seed; }

    private static long hash(long x, long z, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L;
        h ^= x * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h ^= (h >>> 31);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 27);
        return h;
    }

    private static float rand01(long x, long z, long seed) {
        long h = hash(x, z, seed);
        return ((h >>> 11) & 0x1FFFFF) / (float) 0x1FFFFF;
    }

    private float noise(float fx, float fz) {
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        float tx = fx - x0;
        float tz = fz - z0;
        float n00 = rand01(x0, z0, seed);
        float n10 = rand01(x0 + 1, z0, seed);
        float n01 = rand01(x0, z0 + 1, seed);
        float n11 = rand01(x0 + 1, z0 + 1, seed);
        float sx = tx * tx * (3 - 2 * tx);
        float sz = tz * tz * (3 - 2 * tz);
        float a = n00 + (n10 - n00) * sx;
        float b = n01 + (n11 - n01) * sx;
        return a + (b - a) * sz;
    }

    public int heightAt(int x, int z) {
        float n = 0f;
        n += noise(x / 24f, z / 24f) * 1.0f;
        n += noise(x / 8f, z / 8f) * 0.4f;
        n /= 1.4f;
        return (int) (n * 12) + 1; // 1..13
    }

    private int colorForHeight(int h) {
        if (h <= 2) return BIOME[0];
        if (h <= 4) return BIOME[1];
        if (h <= 8) return BIOME[2];
        if (h <= 11) return BIOME[3];
        return BIOME[4];
    }

    /** Render the surface cubes within renderDistChunks of the camera. Returns cube count. */
    public int emitNear(Renderer r, Vec3 camPos, int renderDistChunks) {
        int ccx = (int) Math.floor(camPos.x / CHUNK);
        int ccz = (int) Math.floor(camPos.z / CHUNK);
        float maxD = (renderDistChunks * CHUNK) + CHUNK;
        float maxD2 = maxD * maxD;
        int count = 0;
        for (int cz = ccz - renderDistChunks; cz <= ccz + renderDistChunks; cz++) {
            for (int cx = ccx - renderDistChunks; cx <= ccx + renderDistChunks; cx++) {
                int bx = cx * CHUNK, bz = cz * CHUNK;
                for (int lz = 0; lz < CHUNK; lz++) {
                    for (int lx = 0; lx < CHUNK; lx++) {
                        int wx = bx + lx, wz = bz + lz;
                        float dxh = (wx + 0.5f) - camPos.x;
                        float dzh = (wz + 0.5f) - camPos.z;
                        if (dxh * dxh + dzh * dzh > maxD2) continue;
                        int h = heightAt(wx, wz);
                        r.renderCube(wx + 0.5f, h - 0.5f, wz + 0.5f, colorForHeight(h));
                        count++;
                    }
                }
            }
        }
        return count;
    }
}