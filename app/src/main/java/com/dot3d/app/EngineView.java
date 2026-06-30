package com.dot3d.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The running engine: a SurfaceView with a dedicated render thread. Builds the
 * scene (infinite cube world + optional loaded model) each frame, runs the
 * software renderer, and blits ASCII via AsciiView. Handles movement, gravity,
 * look and jump from Controls.
 */
public final class EngineView extends SurfaceView implements SurfaceHolder.Callback, Runnable {
    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean paused = false;

    private final Settings settings;
    private Renderer renderer;
    private AsciiView ascii;
    private Palette palette;
    private boolean colorOn;

    private final Camera cam = new Camera();
    private World world;
    private Physics physics;
    private final Controls controls;
    private final Mesh cubeMesh;
    private Mesh loadedModel = null;
    private Vec3 modelPos = new Vec3(0, 30, 0);

    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int gridW, gridH;
    private float walkSpeed = 6f;

    public interface MenuListener { void onOpenMenu(); }
    private MenuListener menuListener;

    public EngineView(Context ctx, Settings settings) {
        super(ctx);
        this.settings = settings;
        getHolder().addCallback(this);
        float density = getResources().getDisplayMetrics().density;
        controls = new Controls(density);
        cubeMesh = Cube.unitCube();
        applySettings();
        setFocusable(true);
    }

    public void setMenuListener(MenuListener l) { this.menuListener = l; }

    public void applySettings() {
        gridW = settings.gridW();
        gridH = settings.gridH();
        palette = new Palette(settings.palette());
        colorOn = settings.color();
        cam.fovDeg = settings.fov();
        if (world == null) world = new World(settings.seed());
        else world.setSeed(settings.seed());
        if (physics == null) physics = new Physics(world);
    }

    public void setLoadedModel(Mesh m) { this.loadedModel = m; }

    public void setPaused(boolean p) {
        this.paused = p;
        if (!p) applySettings(); // settings may have changed in the menu
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this, "dot3d-render");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        controls.setSize(w, h);
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        try { if (thread != null) thread.join(500); } catch (InterruptedException ignored) {}
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!paused) controls.onTouch(e);
        return true;
    }

    @Override public void run() {
        // place camera above terrain at spawn
        cam.pos = new Vec3(0.5f, world.heightAt(0, 0) + physics.eyeHeight + 2, 0.5f);
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            if (dt > 0.05f) dt = 0.05f;
            last = now;

            if (!paused) {
                update(dt);
                drawFrame();
            } else {
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void ensureBuffers() {
        if (renderer == null || renderer.W != gridW || renderer.H != gridH) {
            renderer = new Renderer(gridW, gridH);
        }
        if (ascii == null) {
            int cell = Math.max(6, getHeight() / Math.max(1, gridH));
            ascii = new AsciiView(getContext(), cell);
        }
    }

    private void update(float dt) {
        // look
        float[] look = controls.consumeLook();
        float sens = settings.sensitivity();
        cam.addYaw(look[0] * sens);
        cam.addPitch(-look[1] * sens);

        // walk
        Vec3 fwd = cam.forwardFlat();
        Vec3 right = cam.right();
        Vec3 move = fwd.scale(-controls.moveY).add(right.scale(controls.moveX));
        cam.pos.x += move.x * walkSpeed * dt;
        cam.pos.z += move.z * walkSpeed * dt;

        // jump + gravity
        if (controls.consumeJump()) physics.jump();
        physics.step(cam, dt);
    }

    private void drawFrame() {
        ensureBuffers();
        // build scene
        List<Tri> tris = new ArrayList<>();
        world.emitNear(tris, cam.pos, settings.renderDist(), cubeMesh);
        if (loadedModel != null) {
            loadedModel.emit(tris, modelPos, 6f, 0xE0A030);
        }
        renderer.render(tris, cam);

        SurfaceHolder h = getHolder();
        Canvas c = h.lockCanvas();
        if (c == null) return;
        try {
            ascii.draw(c, renderer, palette, colorOn, Color.BLACK);
            drawUi(c);
        } finally {
            h.unlockCanvasAndPost(c);
        }
    }

    private void drawUi(Canvas c) {
        // joystick
        if (controls.joyActive) {
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(4);
            ui.setColor(0x66FFFFFF);
            c.drawCircle(controls.joyCxOut, controls.joyCyOut, 120 * getResources().getDisplayMetrics().density, ui);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0x99FFFFFF);
            c.drawCircle(controls.joyKnobX, controls.joyKnobY, 40 * getResources().getDisplayMetrics().density, ui);
        }
        // jump button
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x55FFFFFF);
        c.drawCircle(controls.jumpButtonX(), controls.jumpButtonY(), controls.jumpButtonR(), ui);
        ui.setColor(0xFFFFFFFF);
        ui.setTextSize(30);
        ui.setTextAlign(Paint.Align.CENTER);
        c.drawText("JUMP", controls.jumpButtonX(), controls.jumpButtonY() + 10, ui);
    }
}