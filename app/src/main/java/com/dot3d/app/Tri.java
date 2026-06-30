package com.dot3d.app;

/** A single triangle in world space, with a precomputed normal and base color. */
public final class Tri {
    public final Vec3 a, b, c;
    public final Vec3 normal;
    public final int color; // 0xRRGGBB

    public Tri(Vec3 a, Vec3 b, Vec3 c, int color) {
        this.a = a; this.b = b; this.c = c;
        this.color = color;
        Vec3 n = b.sub(a).cross(c.sub(a)).normalized();
        this.normal = n;
    }
}