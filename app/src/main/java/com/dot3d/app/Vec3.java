package com.dot3d.app;

/** Minimal 3D vector with the operations the engine needs. */
public final class Vec3 {
    public float x, y, z;

    public Vec3() {}

    public Vec3(float x, float y, float z) {
        this.x = x; this.y = y; this.z = z;
    }

    public Vec3 copy() { return new Vec3(x, y, z); }

    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 scale(float s) { return new Vec3(x * s, y * s, z * s); }

    public float dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public float length() { return (float) Math.sqrt(x * x + y * y + z * z); }

    public Vec3 normalized() {
        float l = length();
        if (l < 1e-6f) return new Vec3(0, 0, 0);
        return new Vec3(x / l, y / l, z / l);
    }
}