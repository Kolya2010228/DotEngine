package com.dot3d.app;

/** Camera with yaw/pitch orientation and a perspective FOV. */
public final class Camera {
    public Vec3 pos = new Vec3(0, 20, 0);
    public float yaw = 0f;     // radians, around Y
    public float pitch = 0f;   // radians, around X (clamped)
    public float fovDeg = 70f;

    public Vec3 forward() {
        float cp = (float) Math.cos(pitch);
        return new Vec3(
                (float) Math.sin(yaw) * cp,
                (float) Math.sin(pitch),
                (float) Math.cos(yaw) * cp).normalized();
    }

    /** Forward projected onto the horizontal plane (for walking). */
    public Vec3 forwardFlat() {
        return new Vec3((float) Math.sin(yaw), 0, (float) Math.cos(yaw)).normalized();
    }

    public Vec3 right() {
        return forwardFlat().cross(new Vec3(0, 1, 0)).normalized();
    }

    public void addYaw(float d) { yaw += d; }

    public void addPitch(float d) {
        pitch += d;
        float lim = (float) (Math.PI / 2 - 0.05);
        if (pitch > lim) pitch = lim;
        if (pitch < -lim) pitch = -lim;
    }
}