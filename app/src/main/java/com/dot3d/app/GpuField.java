package com.dot3d.app;

/**
 * Integer heightfield that matches the GLES fragment shader BIT-FOR-BIT (32-bit
 * hash / value-noise). Used for physics/collision on the GPU render path so the
 * player collides with exactly the terrain the shader draws. This is a separate,
 * 32-bit-compatible noise from World.heightAt (which uses a 64-bit long hash that
 * cannot be reproduced in GLSL ES 3.0), so the GPU terrain looks different from
 * the software terrain for the same seed -- but is internally consistent.
 */
public final class GpuField {
    private GpuField() {}

    private static int hash(int x, int z, int seed) {
        int h = x * 374761393 + z * 668265263 + seed * (int) 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return h;
    }

    private static float rand01(int x, int z, int seed) {
        return (hash(x, z, seed) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    private static float noise(float fx, float fz, int seed) {
        int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
        float tx = fx - x0, tz = fz - z0;
        float n00 = rand01(x0, z0, seed), n10 = rand01(x0 + 1, z0, seed);
        float n01 = rand01(x0, z0 + 1, seed), n11 = rand01(x0 + 1, z0 + 1, seed);
        float sx = tx * tx * (3 - 2 * tx), sz = tz * tz * (3 - 2 * tz);
        float a = n00 + (n10 - n00) * sx, b = n01 + (n11 - n01) * sx;
        return a + (b - a) * sz;
    }

    /** Column height at integer (x,z). Matches heightF() in the fragment shader. */
    public static int heightAt(int x, int z, int seed) {
        float n = noise(x / 24f, z / 24f, seed) * 1.0f + noise(x / 8f, z / 8f, seed) * 0.4f;
        n /= 1.4f;
        return (int) (n * 12) + 1;
    }
}
