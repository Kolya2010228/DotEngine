package com.dot3d.app;

/** Builds a unit cube mesh (12 triangles) centered at origin, side length 1. */
public final class Cube {
    public static Mesh unitCube() {
        Mesh m = new Mesh();
        Vec3[] v = new Vec3[] {
                new Vec3(-0.5f, -0.5f, -0.5f),
                new Vec3( 0.5f, -0.5f, -0.5f),
                new Vec3( 0.5f,  0.5f, -0.5f),
                new Vec3(-0.5f,  0.5f, -0.5f),
                new Vec3(-0.5f, -0.5f,  0.5f),
                new Vec3( 0.5f, -0.5f,  0.5f),
                new Vec3( 0.5f,  0.5f,  0.5f),
                new Vec3(-0.5f,  0.5f,  0.5f),
        };
        int[][] faces = {
                {0, 1, 2, 3}, // -Z
                {5, 4, 7, 6}, // +Z
                {4, 0, 3, 7}, // -X
                {1, 5, 6, 2}, // +X
                {3, 2, 6, 7}, // +Y (top)
                {4, 5, 1, 0}, // -Y (bottom)
        };
        for (int[] f : faces) {
            m.add(new Tri(v[f[0]], v[f[1]], v[f[2]], 0xFFFFFF));
            m.add(new Tri(v[f[0]], v[f[2]], v[f[3]], 0xFFFFFF));
        }
        return m;
    }
}