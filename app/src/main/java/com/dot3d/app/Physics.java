package com.dot3d.app;

/** Gravity + simple collision of the player (a point with small radius) against the cube world. */
public final class Physics {
    public float gravity = -22f;
    public float jumpSpeed = 9f;
    public float eyeHeight = 1.6f;

    public float vy = 0f;
    public boolean onGround = false;

    private final World world;

    public Physics(World world) { this.world = world; }

    /** Step vertical motion. camPos.y is the eye position; feet are eyeHeight below. */
    public void step(Camera cam, float dt) {
        vy += gravity * dt;
        cam.pos.y += vy * dt;

        int gx = (int) Math.floor(cam.pos.x);
        int gz = (int) Math.floor(cam.pos.z);
        int groundTop = world.heightAt(gx, gz); // top surface y of the column
        float feet = cam.pos.y - eyeHeight;

        if (feet <= groundTop) {
            cam.pos.y = groundTop + eyeHeight;
            vy = 0f;
            onGround = true;
        } else {
            onGround = false;
        }
    }

    public void jump() {
        if (onGround) {
            vy = jumpSpeed;
            onGround = false;
        }
    }
}