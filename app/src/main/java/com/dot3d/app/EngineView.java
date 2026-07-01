package com.dot3d.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;

/**
 * The running engine: a SurfaceView with a dedicated render thread. Renders the
 * infinite cube world (+ optional loaded model) via the software renderer and
 * blits ASCII through AsciiView. Handles movement, gravity, look and jump.
 */
public final class EngineView extends SurfaceView implements SurfaceHolder.Callback, Runnable, Engine {
    private Thread thread;
    private volatile boolean running = false;
    private volatile boolean paused = false;

    private final Settings settings;
    private Renderer renderer;
    private AsciiView ascii;
    private Palette palette;
    private boolean colorOn;
    private int builtW = -1, builtH = -1;

    private final Camera cam = new Camera();
    private World world;
    private Physics physics;
    private final Controls controls;
    private List<Tri> modelTris = null;

    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float walkSpeed = 6f;
    private final float density;

    private float fps = 0f;
    private int lastCubes = 0;
    private float rayMs = 0f;   // EMA of raymarch+geometry time (debug HUD)
    private float blitMs = 0f;  // EMA of ASCII blit time (debug HUD)

    public interface MenuListener { void onOpenMenu(); }
    private MenuListener menuListener;

    public EngineView(Context ctx, Settings settings) {
        super(ctx);
        this.settings = settings;
        getHolder().addCallback(this);
        density = getResources().getDisplayMetrics().density;
        controls = new Controls(density);
        applySettings();
        setFocusable(true);
    }

    public void setMenuListener(MenuListener l) { this.menuListener = l; }

    public void applySettings() {
        palette = new Palette(settings.palette());
        colorOn = settings.color();
        cam.fovDeg = settings.fov();
        if (world == null) world = new World(settings.seed());
        else world.setSeed(settings.seed());
        if (physics == null) physics = new Physics(world);
        // force buffers to rebuild against possibly-changed grid size
        builtW = -1; builtH = -1;
    }

    public void setLoadedModel(Mesh m) {
        // Place the model a few steps in front of the spawn point, resting on the
        // terrain, so it is actually in view. It was previously fixed at (0,30,0) --
        // high above the player -- so a loaded model was effectively never visible.
        List<Tri> out = new ArrayList<>();
        int mx = 0, mz = 8;
        float ground = (world != null ? world.heightAt(mx, mz) : 0) + 4f;
        m.emit(out, new Vec3(mx + 0.5f, ground, mz + 0.5f), 6f, 0xE0A030);
        modelTris = out;
    }

    public void setPaused(boolean p) {
        this.paused = p;
        if (!p) applySettings();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this, "dot3d-render");
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        controls.setSize(w, h);
        builtW = -1; builtH = -1;
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
        cam.pos = new Vec3(0.5f, world.heightAt(0, 0) + physics.eyeHeight + 2, 0.5f);
        long last = System.nanoTime();
        boolean fpsInit = false;
        while (running) {
            long now = System.nanoTime();
            float rawDt = (now - last) / 1e9f; // real frame time (used for FPS)
            last = now;

            // Clamp ONLY the simulation step, so a slow/long frame doesn't make
            // physics teleport. The FPS readout must NOT use this clamped value.
            float dt = rawDt;
            if (dt > 0.05f) dt = 0.05f;

            if (!paused) {
                // FPS from the REAL frame time. Skip the first frame and absurdly
                // tiny deltas (< 0.1 ms): otherwise 1/rawDt on the very first frame
                // is in the hundreds-of-millions and the EMA shows "billions" for a
                // long time. Using rawDt (not the 0.05 s clamp) also lets the HUD
                // actually display low frame rates like 3 FPS instead of bottoming
                // out at 20.
                if (rawDt > 1e-4f) {
                    float inst = 1f / rawDt;
                    if (!fpsInit) { fps = inst; fpsInit = true; }
                    else fps += (inst - fps) * 0.1f;
                }
                update(dt);
                drawFrame();
            } else {
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void ensureBuffers() {
        int gw = settings.gridW(), gh = settings.gridH();
        if (renderer != null && builtW == gw && builtH == gh && ascii != null) return;
        renderer = new Renderer(gw, gh);
        int sw = getWidth(), sh = getHeight();
        if (sw <= 0) sw = getResources().getDisplayMetrics().widthPixels;
        if (sh <= 0) sh = getResources().getDisplayMetrics().heightPixels;
        int cellPx = Math.max(5, Math.min(sh / gh, (int) (sw / (gw * 0.62f))));
        ascii = new AsciiView(getContext(), cellPx);
        renderer.aspect = (gw * (float) ascii.cellW()) / (gh * (float) ascii.cellH());
        builtW = gw; builtH = gh;
    }

    private void update(float dt) {
        float[] look = controls.consumeLook();
        float sens = settings.sensitivity();
        cam.addYaw(look[0] * sens);
        cam.addPitch(-look[1] * sens);

        Vec3 fwd = cam.forwardFlat();
        Vec3 right = cam.right();
        Vec3 move = fwd.scale(-controls.moveY).add(right.scale(controls.moveX));
        cam.pos.x += move.x * walkSpeed * dt;
        cam.pos.z += move.z * walkSpeed * dt;

        if (controls.consumeJump()) physics.jump();
        physics.step(cam, dt);
    }

    private void drawFrame() {
        ensureBuffers();
        long t0 = System.nanoTime();
        renderer.beginFrame(cam);
        lastCubes = world.emitNear(renderer, cam.pos, settings.renderDist());
        if (modelTris != null) renderer.renderTris(modelTris);
        long t1 = System.nanoTime();
        rayMs += (((t1 - t0) / 1e6f) - rayMs) * 0.1f;

        SurfaceHolder h = getHolder();
        Canvas c = h.lockCanvas();
        if (c == null) return;
        try {
            long b0 = System.nanoTime();
            ascii.draw(c, renderer, palette, colorOn, Color.BLACK);
            long b1 = System.nanoTime();
            blitMs += (((b1 - b0) / 1e6f) - blitMs) * 0.1f;
            drawUi(c);
            if (settings.debug()) drawHud(c);
        } finally {
            h.unlockCanvasAndPost(c);
        }
    }

    private void drawUi(Canvas c) {
        if (controls.joyActive) {
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(4);
            ui.setColor(0x66FFFFFF);
            c.drawCircle(controls.joyCxOut, controls.joyCyOut, 120 * density, ui);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(0x99FFFFFF);
            c.drawCircle(controls.joyKnobX, controls.joyKnobY, 40 * density, ui);
        }
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x55FFFFFF);
        c.drawCircle(controls.jumpButtonX(), controls.jumpButtonY(), controls.jumpButtonR(), ui);
        ui.setColor(0xFFFFFFFF);
        ui.setTextSize(28 * (density > 0 ? density : 1));
        ui.setTextAlign(Paint.Align.CENTER);
        c.drawText("JUMP", controls.jumpButtonX(), controls.jumpButtonY() + 8, ui);
    }

    private void drawHud(Canvas c) {
        ui.setStyle(Paint.Style.FILL);
        ui.setTextAlign(Paint.Align.LEFT);
        ui.setColor(0xFF33FF66);
        float ts = 22 * (density > 0 ? density : 1);
        ui.setTextSize(ts);
        float y = ts + 6;
        c.drawText(String.format("FPS %.0f", fps), 12, y, ui);
        y += ts + 4;
        c.drawText("cubes " + lastCubes + "  grid " + renderer.W + "x" + renderer.H, 12, y, ui);
        y += ts + 4;
        c.drawText(String.format("ray %.1fms  blit %.1fms  cores %d", rayMs, blitMs, Runtime.getRuntime().availableProcessors()), 12, y, ui);
        y += ts + 4;
        c.drawText(String.format("pos %.1f %.1f %.1f", cam.pos.x, cam.pos.y, cam.pos.z), 12, y, ui);
    }
}