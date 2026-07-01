package com.dot3d.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Experimental GPU render path -- ASCII glyph step.
 *
 * Two GLES 3.0 passes:
 *   A) A fragment shader raymarches the infinite heightfield ONCE PER ASCII CELL
 *      into an offscreen gridW x gridH texture (rgb = biome color, a = luminance),
 *      using the same integer-column DDA as the software Renderer. This is the key
 *      perf fix: cost scales with the ASCII grid (~6050 cells), NOT with screen
 *      pixels (the previous per-pixel version wasted millions of rays).
 *   B) A fullscreen pass reads each screen pixel's cell, picks a glyph from a
 *      palette glyph-atlas texture by luminance, and tints it -- one draw call
 *      instead of thousands of Canvas.drawBitmap. This produces the real ASCII look.
 *
 * Camera/controls/physics are reused 1:1 from the software path; collision uses
 * {@link GpuField} (matches the shader noise bit-for-bit). A transparent overlay
 * draws the joystick/jump button and an FPS readout, since a GL surface does not
 * draw the software path's Canvas UI. Guarded by Settings.gpu() (default OFF).
 */
public final class GLEngineView extends FrameLayout implements Engine {
    private final Settings settings;
    private EngineView.MenuListener menuListener;

    private final Camera cam = new Camera();
    private final Controls controls;
    private final long seed;

    private float vy = 0f;
    private boolean onGround = false;
    private static final float WALK = 6f, GRAV = -22f, JUMP = 9f, EYE = 1.6f;

    private volatile float fps = 0f;

    // loaded .obj model handed from the UI thread to the GL thread
    private final Object modelLock = new Object();
    private volatile float[] pendingModelData = null;
    private volatile boolean modelDirty = false;

    // glyph atlas (built on the main thread, uploaded on the GL thread)
    private final String ramp;
    private final int glyphN;
    private final int cellW, cellH;
    private final Bitmap atlasBitmap;

    private final GLView glView;

    public GLEngineView(Context ctx, Settings settings) {
        super(ctx);
        this.settings = settings;
        float density = getResources().getDisplayMetrics().density;
        this.controls = new Controls(density);
        this.seed = settings.seed();
        cam.fovDeg = settings.fov();
        cam.pos = new Vec3(0.5f, GpuField.heightAt(0, 0, (int) seed) + EYE + 2f, 0.5f);

        // build glyph atlas bitmap from the active palette ramp
        String r = settings.palette();
        if (r == null || r.isEmpty()) r = Palette.CLASSIC;
        this.ramp = r;
        this.glyphN = r.length();
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
        gp.setTypeface(Typeface.MONOSPACE);
        gp.setTextSize(32f);
        gp.setColor(Color.WHITE);
        this.cellH = 32;
        this.cellW = Math.max(1, (int) Math.ceil(gp.measureText("M")));
        this.atlasBitmap = Bitmap.createBitmap(cellW * glyphN, cellH, Bitmap.Config.ARGB_8888);
        Canvas ac = new Canvas(atlasBitmap);
        Paint.FontMetrics fm = gp.getFontMetrics();
        float baseline = -fm.top;
        for (int i = 0; i < glyphN; i++) {
            ac.drawText(String.valueOf(ramp.charAt(i)), i * cellW, baseline, gp);
        }

        glView = new GLView(ctx);
        glView.setEGLContextClientVersion(3);
        glView.setRenderer(new GLRenderer());
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        addView(glView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        ControlOverlay overlay = new ControlOverlay(ctx, density);
        addView(overlay, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        setFocusable(true);
    }

    @Override public void setMenuListener(EngineView.MenuListener l) { this.menuListener = l; }
    @Override public void setLoadedModel(Mesh m) {
        // Emit the model to world space in front of the spawn, resting on the terrain,
        // then hand the interleaved [pos, normal] triangles to the GL thread to upload
        // as a VBO (see GLRenderer.maybeUploadModel). Previously this was a no-op, so a
        // loaded .obj never appeared in GPU mode.
        java.util.List<Tri> out = new java.util.ArrayList<>();
        int mx = 0, mz = 8;
        float ground = GpuField.heightAt(mx, mz, (int) settings.seed());
        m.emit(out, new Vec3(mx + 0.5f, ground + 4f, mz + 0.5f), 6f, 0xE0A030);
        float[] data = new float[out.size() * 3 * 6];
        int p = 0;
        for (Tri t : out) {
            Vec3[] vs = new Vec3[]{ t.a, t.b, t.c };
            for (Vec3 v : vs) {
                data[p++] = v.x; data[p++] = v.y; data[p++] = v.z;
                data[p++] = t.normal.x; data[p++] = t.normal.y; data[p++] = t.normal.z;
            }
        }
        synchronized (modelLock) { pendingModelData = data; modelDirty = true; }
    }
    @Override public void setPaused(boolean p) { if (p) glView.onPause(); else glView.onResume(); }

    /** Inner GLSurfaceView; forwards touches to the shared Controls. */
    private final class GLView extends GLSurfaceView {
        GLView(Context c) { super(c); }
        // Touch is owned by the ControlOverlay (topmost view); GLView only renders.
    }

    /** Advance camera + physics one step (called from the GL thread). */
    private void update(float dt) {
        float[] look = controls.consumeLook();
        float sens = settings.sensitivity();
        cam.addYaw(look[0] * sens);
        cam.addPitch(-look[1] * sens);

        Vec3 fwd = cam.forwardFlat();
        Vec3 right = cam.right();
        Vec3 move = fwd.scale(-controls.moveY).add(right.scale(controls.moveX));
        cam.pos.x += move.x * WALK * dt;
        cam.pos.z += move.z * WALK * dt;

        if (controls.consumeJump() && onGround) { vy = JUMP; onGround = false; }
        vy += GRAV * dt;
        cam.pos.y += vy * dt;
        int gx = (int) Math.floor(cam.pos.x), gz = (int) Math.floor(cam.pos.z);
        float groundTop = GpuField.heightAt(gx, gz, (int) settings.seed());
        if (cam.pos.y - EYE <= groundTop) { cam.pos.y = groundTop + EYE; vy = 0f; onGround = true; }
        else onGround = false;
    }

    // ---- shaders ----

    private static final String VS =
            "#version 300 es\n" +
            "const vec2 verts[3] = vec2[3](vec2(-1.0,-1.0), vec2(3.0,-1.0), vec2(-1.0,3.0));\n" +
            "void main(){ gl_Position = vec4(verts[gl_VertexID], 0.0, 1.0); }\n";

    // Pass A: raymarch heightfield -> rgb = biome color, a = luminance (a=0 = sky).
    private static final String FS_TERRAIN =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform vec3 uCamPos; uniform vec3 uForward; uniform vec3 uRight; uniform vec3 uUp;\n" +
            "uniform float uTanHalf; uniform float uAspect; uniform float uMaxDist;\n" +
            "uniform uint uSeed; uniform vec2 uRes;\n" +
            "out vec4 fragColor;\n" +
            "uint hash(int x,int z,uint seed){ uint h=uint(x)*374761393u+uint(z)*668265263u+seed*2246822519u; h=(h^(h>>13u))*1274126177u; h=h^(h>>16u); return h; }\n" +
            "float rand01(int x,int z,uint seed){ return float(hash(x,z,seed)&0xFFFFFFu)/float(0xFFFFFF); }\n" +
            "float vnoise(float fx,float fz,uint seed){ int x0=int(floor(fx)); int z0=int(floor(fz)); float tx=fx-float(x0); float tz=fz-float(z0);\n" +
            "  float n00=rand01(x0,z0,seed); float n10=rand01(x0+1,z0,seed); float n01=rand01(x0,z0+1,seed); float n11=rand01(x0+1,z0+1,seed);\n" +
            "  float sx=tx*tx*(3.0-2.0*tx); float sz=tz*tz*(3.0-2.0*tz); float a=n00+(n10-n00)*sx; float b=n01+(n11-n01)*sx; return a+(b-a)*sz; }\n" +
            "float heightF(int x,int z,uint seed){ float n=vnoise(float(x)/24.0,float(z)/24.0,seed)*1.0+vnoise(float(x)/8.0,float(z)/8.0,seed)*0.4; n/=1.4; return float(int(n*12.0)+1); }\n" +
            "vec3 biome(float h){ if(h<=2.0) return vec3(0.247,0.435,0.690); if(h<=4.0) return vec3(0.761,0.698,0.502); if(h<=8.0) return vec3(0.298,0.604,0.165); if(h<=11.0) return vec3(0.420,0.420,0.420); return vec3(0.961,0.961,0.961); }\n" +
            "void main(){\n" +
            "  vec2 ndc = 2.0*gl_FragCoord.xy/uRes - 1.0;\n" +
            "  float ax = ndc.x*uTanHalf*uAspect; float ay = ndc.y*uTanHalf;\n" +
            "  vec3 dir = normalize(uForward + uRight*ax + uUp*ay);\n" +
            "  float camx=uCamPos.x, camy=uCamPos.y, camz=uCamPos.z;\n" +
            "  int ix=int(floor(camx)); int iz=int(floor(camz));\n" +
            "  int stepX = dir.x>0.0?1:-1; int stepZ = dir.z>0.0?1:-1;\n" +
            "  float tMaxX,tDeltaX,tMaxZ,tDeltaZ;\n" +
            "  if(abs(dir.x)>1e-9){ float nx=dir.x>0.0?float(ix+1):float(ix); tMaxX=(nx-camx)/dir.x; tDeltaX=abs(1.0/dir.x);} else {tMaxX=1e30; tDeltaX=1e30;}\n" +
            "  if(abs(dir.z)>1e-9){ float nz=dir.z>0.0?float(iz+1):float(iz); tMaxZ=(nz-camz)/dir.z; tDeltaZ=abs(1.0/dir.z);} else {tMaxZ=1e30; tDeltaZ=1e30;}\n" +
            "  float t=0.0; bool hit=false; float hcol=0.0;\n" +
            "  for(int iter=0; iter<512; iter++){\n" +
            "    if(t>=uMaxDist) break;\n" +
            "    float h=heightF(ix,iz,uSeed); float tExit=min(tMaxX,tMaxZ); float tEnd=min(tExit,uMaxDist);\n" +
            "    float pyEnter=camy+dir.y*t;\n" +
            "    if(pyEnter<h){ hcol=h; hit=true; break; }\n" +
            "    if(dir.y<0.0){ float tc=(h-camy)/dir.y; if(tc>=t && tc<=tEnd){ hcol=h; hit=true; t=tc; break; } }\n" +
            "    t=tExit; if(t>=uMaxDist) break;\n" +
            "    if(tMaxX<tMaxZ){ ix+=stepX; tMaxX+=tDeltaX; } else { iz+=stepZ; tMaxZ+=tDeltaZ; }\n" +
            "  }\n" +
            "  if(!hit){ fragColor=vec4(0.0,0.0,0.0,0.0); gl_FragDepth=1.0; return; }\n" +
            "  float hl=heightF(ix-1,iz,uSeed); float hr=heightF(ix+1,iz,uSeed); float hd=heightF(ix,iz-1,uSeed); float hu=heightF(ix,iz+1,uSeed);\n" +
            "  vec3 nrm = normalize(vec3(hl-hr, 2.0, hd-hu));\n" +
            "  vec3 L = vec3(-0.3578,-0.8944,-0.2683);\n" +
            "  float diff = max(0.0, -dot(nrm,L)); float fog = clamp(1.0 - t/uMaxDist, 0.0, 1.0);\n" +
            "  float lum = (0.25+0.75*diff)*(0.4+0.6*fog);\n" +
            "  fragColor = vec4(biome(hcol), lum);\n" +
            "  float cz = t * dot(dir, uForward);\n" +
            "  gl_FragDepth = clamp(cz/uMaxDist, 0.0, 1.0);\n" +
            "}\n";

    // Pass B: read cell texture, pick a glyph from the atlas by luminance, tint it.
    private static final String FS_GLYPH =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform sampler2D uCell;\n" +
            "uniform sampler2D uAtlas;\n" +
            "uniform vec2 uRes; uniform ivec2 uGrid; uniform int uN; uniform int uColor;\n" +
            "out vec4 fragColor;\n" +
            "void main(){\n" +
            "  vec2 uv = gl_FragCoord.xy/uRes;\n" +
            "  int cx = int(floor(uv.x*float(uGrid.x)));\n" +
            "  int cy = int(floor(uv.y*float(uGrid.y)));\n" +
            "  cx = clamp(cx, 0, uGrid.x-1); cy = clamp(cy, 0, uGrid.y-1);\n" +
            "  vec4 cell = texelFetch(uCell, ivec2(cx,cy), 0);\n" +
            "  float lum = cell.a;\n" +
            "  int gi = int(lum*float(uN-1)+0.5); gi = clamp(gi, 0, uN-1);\n" +
            "  float fx = fract(uv.x*float(uGrid.x));\n" +
            "  float fy = fract(uv.y*float(uGrid.y));\n" +
            "  float au = (float(gi)+fx)/float(uN);\n" +
            "  float av = 1.0 - fy;\n" + // atlas bitmap is top-origin; flip
            "  float cov = texture(uAtlas, vec2(au, av)).a;\n" +
            "  vec3 tint = (uColor==1) ? cell.rgb*(0.4+0.6*lum) : vec3(lum);\n" +
            "  fragColor = vec4(tint*cov, 1.0);\n" +
            "}\n";

    // Model pass: project .obj triangles with the SAME pinhole camera as the
    // terrain raymarch and write linear camera-forward depth so GL depth-testing
    // composites the model against the terrain correctly.
    private static final String VS_MODEL =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec3 aPos; in vec3 aNormal;\n" +
            "uniform vec3 uCamPos; uniform vec3 uForward; uniform vec3 uRight; uniform vec3 uUp;\n" +
            "uniform float uTanHalf; uniform float uAspect;\n" +
            "out vec3 vNormal; out float vCz;\n" +
            "void main(){\n" +
            "  vec3 rel = aPos - uCamPos;\n" +
            "  float cx = dot(rel, uRight); float cy = dot(rel, uUp); float cz = dot(rel, uForward);\n" +
            "  vNormal = aNormal; vCz = cz;\n" +
            "  gl_Position = vec4(cx/(uTanHalf*uAspect), cy/uTanHalf, 0.0, cz);\n" +
            "}\n";

    private static final String FS_MODEL =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec3 vNormal; in float vCz;\n" +
            "uniform vec3 uModelColor; uniform float uMaxDist; uniform int uColorM;\n" +
            "out vec4 fragColor;\n" +
            "void main(){\n" +
            "  vec3 L = vec3(-0.3578,-0.8944,-0.2683);\n" +
            "  vec3 n = normalize(vNormal);\n" +
            "  float d = max(0.0, -dot(n, L));\n" +
            "  float lum = 0.25 + 0.75*d;\n" +
            "  vec3 col = (uColorM==1) ? uModelColor : vec3(1.0);\n" +
            "  fragColor = vec4(col, lum);\n" +
            "  gl_FragDepth = clamp(vCz/uMaxDist, 0.0, 1.0);\n" +
            "}\n";

    private final class GLRenderer implements GLSurfaceView.Renderer {
        private int progA, progB;
        // pass A uniforms
        private int uCamPos, uForward, uRight, uUp, uTanHalf, uAspect, uMaxDist, uSeed, uResA;
        // pass B uniforms
        private int uCell, uAtlas, uResB, uGrid, uN, uColor;
        private int fbo, cellTex, atlasTex, depthRb;
        private int gridW, gridH;
        private int screenW, screenH;
        private long last;

        // model (.obj) GPU pass
        private int progM;
        private int mCamPos, mForward, mRight, mUp, mTanHalf, mAspect, mModelColor, mMaxDist, mColorM;
        private int modelVao, modelVbo, modelVertCount;

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            progA = link(VS, FS_TERRAIN);
            progB = link(VS, FS_GLYPH);
            uCamPos = GLES30.glGetUniformLocation(progA, "uCamPos");
            uForward = GLES30.glGetUniformLocation(progA, "uForward");
            uRight = GLES30.glGetUniformLocation(progA, "uRight");
            uUp = GLES30.glGetUniformLocation(progA, "uUp");
            uTanHalf = GLES30.glGetUniformLocation(progA, "uTanHalf");
            uAspect = GLES30.glGetUniformLocation(progA, "uAspect");
            uMaxDist = GLES30.glGetUniformLocation(progA, "uMaxDist");
            uSeed = GLES30.glGetUniformLocation(progA, "uSeed");
            uResA = GLES30.glGetUniformLocation(progA, "uRes");
            uCell = GLES30.glGetUniformLocation(progB, "uCell");
            uAtlas = GLES30.glGetUniformLocation(progB, "uAtlas");
            uResB = GLES30.glGetUniformLocation(progB, "uRes");
            uGrid = GLES30.glGetUniformLocation(progB, "uGrid");
            uN = GLES30.glGetUniformLocation(progB, "uN");
            uColor = GLES30.glGetUniformLocation(progB, "uColor");

            progM = link(VS_MODEL, FS_MODEL);
            mCamPos = GLES30.glGetUniformLocation(progM, "uCamPos");
            mForward = GLES30.glGetUniformLocation(progM, "uForward");
            mRight = GLES30.glGetUniformLocation(progM, "uRight");
            mUp = GLES30.glGetUniformLocation(progM, "uUp");
            mTanHalf = GLES30.glGetUniformLocation(progM, "uTanHalf");
            mAspect = GLES30.glGetUniformLocation(progM, "uAspect");
            mModelColor = GLES30.glGetUniformLocation(progM, "uModelColor");
            mMaxDist = GLES30.glGetUniformLocation(progM, "uMaxDist");
            mColorM = GLES30.glGetUniformLocation(progM, "uColorM");

            gridW = Math.max(2, settings.gridW());
            gridH = Math.max(2, settings.gridH());

            // offscreen cell texture + FBO
            int[] tmp = new int[1];
            GLES30.glGenTextures(1, tmp, 0); cellTex = tmp[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cellTex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, gridW, gridH, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

            GLES30.glGenFramebuffers(1, tmp, 0); fbo = tmp[0];
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, cellTex, 0);
            // depth attachment so the .obj model pass can depth-test against terrain
            GLES30.glGenRenderbuffers(1, tmp, 0); depthRb = tmp[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, gridW, gridH);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT,
                    GLES30.GL_RENDERBUFFER, depthRb);
            int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE)
                throw new RuntimeException("FBO incomplete: " + status);

            // glyph atlas texture
            GLES30.glGenTextures(1, tmp, 0); atlasTex = tmp[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, atlasBitmap, 0);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

            // model VAO/VBO (populated lazily in maybeUploadModel when a model loads)
            GLES30.glGenVertexArrays(1, tmp, 0); modelVao = tmp[0];
            GLES30.glGenBuffers(1, tmp, 0); modelVbo = tmp[0];
            modelVertCount = 0;
            // re-upload after a GL context loss, or if a model was already handed over
            synchronized (modelLock) { if (pendingModelData != null) modelDirty = true; }

            last = System.nanoTime();
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
            screenW = w; screenH = h;
        }

        @Override public void onDrawFrame(GL10 gl) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f; last = now;
            if (dt > 1e-4f) { float inst = 1f / dt; fps = fps <= 0f ? inst : fps + (inst - fps) * 0.1f; }
            float sdt = dt; if (sdt > 0.05f) sdt = 0.05f;

            cam.fovDeg = settings.fov();
            update(sdt);

            // Grid size can change live via the pause menu, but cellTex/FBO were
            // sized once in onSurfaceCreated. Re-specify the cell texture storage
            // when the grid changes so pass A (render target) and pass B (texelFetch)
            // agree on the size; otherwise pass B reads a mismatched-size buffer and
            // the render is garbled. The FBO attachment references cellTex by object,
            // so re-defining level 0 keeps the attachment valid.
            int gw = Math.max(2, settings.gridW());
            int gh = Math.max(2, settings.gridH());
            if (gw != gridW || gh != gridH) {
                gridW = gw; gridH = gh;
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cellTex);
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, gridW, gridH, 0,
                        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
                GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depthRb);
                GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT16, gridW, gridH);
            }

            Vec3 f = cam.forward();
            float rlen = (float) Math.sqrt(f.z * f.z + f.x * f.x); if (rlen < 1e-5f) rlen = 1e-5f;
            float rx = f.z / rlen, ry = 0f, rz = -f.x / rlen;
            float ux = f.y * rz - f.z * ry, uy = f.z * rx - f.x * rz, uz = f.x * ry - f.y * rx;
            float tanHalf = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2.0);
            float cellAspect = (gridW * (float) cellW) / (gridH * (float) cellH);
            float maxDist = settings.renderDist() * World.CHUNK + World.CHUNK; if (maxDist < 32f) maxDist = 32f;

            // upload a freshly loaded model on the GL thread
            maybeUploadModel();

            // ---- Pass A: raymarch terrain into the cell texture (writes depth) ----
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glViewport(0, 0, gridW, gridH);
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glDepthMask(true);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glDepthFunc(GLES30.GL_ALWAYS); // terrain always establishes color+depth
            GLES30.glUseProgram(progA);
            GLES30.glUniform3f(uCamPos, cam.pos.x, cam.pos.y, cam.pos.z);
            GLES30.glUniform3f(uForward, f.x, f.y, f.z);
            GLES30.glUniform3f(uRight, rx, ry, rz);
            GLES30.glUniform3f(uUp, ux, uy, uz);
            GLES30.glUniform1f(uTanHalf, tanHalf);
            GLES30.glUniform1f(uAspect, cellAspect);
            GLES30.glUniform1f(uMaxDist, maxDist);
            GLES30.glUniform1ui(uSeed, (int) settings.seed());
            GLES30.glUniform2f(uResA, (float) gridW, (float) gridH);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

            // ---- Model pass: rasterize .obj triangles into the SAME cell buffer,
            // depth-tested against the terrain (GL_LESS), same camera basis as pass A. ----
            if (modelVertCount > 0) {
                GLES30.glDepthFunc(GLES30.GL_LESS);
                GLES30.glUseProgram(progM);
                GLES30.glUniform3f(mCamPos, cam.pos.x, cam.pos.y, cam.pos.z);
                GLES30.glUniform3f(mForward, f.x, f.y, f.z);
                GLES30.glUniform3f(mRight, rx, ry, rz);
                GLES30.glUniform3f(mUp, ux, uy, uz);
                GLES30.glUniform1f(mTanHalf, tanHalf);
                GLES30.glUniform1f(mAspect, cellAspect);
                GLES30.glUniform1f(mMaxDist, maxDist);
                GLES30.glUniform1i(mColorM, settings.color() ? 1 : 0);
                GLES30.glUniform3f(mModelColor, 0xE0 / 255f, 0xA0 / 255f, 0x30 / 255f);
                GLES30.glBindVertexArray(modelVao);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, modelVertCount);
                GLES30.glBindVertexArray(0);
            }

            // ---- Pass B: glyphs to the screen ----
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            GLES30.glViewport(0, 0, screenW, screenH);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glUseProgram(progB);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cellTex);
            GLES30.glUniform1i(uCell, 0);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlasTex);
            GLES30.glUniform1i(uAtlas, 1);
            GLES30.glUniform2f(uResB, (float) screenW, (float) screenH);
            GLES30.glUniform2i(uGrid, gridW, gridH);
            GLES30.glUniform1i(uN, glyphN);
            GLES30.glUniform1i(uColor, settings.color() ? 1 : 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        }

        private void maybeUploadModel() {
            float[] data = null;
            synchronized (modelLock) {
                if (modelDirty) { data = pendingModelData; modelDirty = false; }
            }
            if (data == null) return;
            FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(data).position(0);
            GLES30.glBindVertexArray(modelVao);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, modelVbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.length * 4, fb, GLES30.GL_STATIC_DRAW);
            int stride = 6 * 4;
            int aPos = GLES30.glGetAttribLocation(progM, "aPos");
            int aNormal = GLES30.glGetAttribLocation(progM, "aNormal");
            GLES30.glEnableVertexAttribArray(aPos);
            GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, stride, 0);
            GLES30.glEnableVertexAttribArray(aNormal);
            GLES30.glVertexAttribPointer(aNormal, 3, GLES30.GL_FLOAT, false, stride, 3 * 4);
            GLES30.glBindVertexArray(0);
            modelVertCount = data.length / 6;
        }

        private int link(String vsSrc, String fsSrc) {
            int vs = compile(GLES30.GL_VERTEX_SHADER, vsSrc);
            int fs = compile(GLES30.GL_FRAGMENT_SHADER, fsSrc);
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, vs);
            GLES30.glAttachShader(p, fs);
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("link failed: " + GLES30.glGetProgramInfoLog(p));
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
            return p;
        }

        private int compile(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES30.glGetShaderInfoLog(s);
                GLES30.glDeleteShader(s);
                throw new RuntimeException("shader compile failed: " + log);
            }
            return s;
        }
    }

    /** Transparent overlay: joystick, jump button, FPS readout. Passes touches through. */
    private final class ControlOverlay extends View {
        private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        ControlOverlay(Context c, float density) {
            super(c);
            this.density = density;
            setClickable(true);
            setFocusable(true);
            setWillNotDraw(false);
        }

        // The overlay is the topmost view, so it is the reliable touch target.
        // It feeds Controls directly (joystick left / look+jump right) and returns
        // true so it receives the full gesture. The GLSurfaceView below only renders.
        @Override public boolean onTouchEvent(MotionEvent e) { controls.onTouch(e); return true; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            // CRITICAL: without this, Controls.screenW/screenH stay 0, so the
            // left-half joystick test (x < screenW/2) is never true and the jump
            // button position collapses to negative coords -> joystick never
            // activates and the button is drawn/hit off-screen. This is exactly why
            // the GPU path showed no joystick or jump button. The software EngineView
            // calls setSize in surfaceChanged; the FrameLayout rewrite dropped it.
            controls.setSize(w, h);
        }

        @Override protected void onDraw(Canvas c) {
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

            if (settings.debug()) {
                ui.setTextAlign(Paint.Align.LEFT);
                ui.setColor(0xFF33FF66);
                float ts = 22 * (density > 0 ? density : 1);
                ui.setTextSize(ts);
                c.drawText(String.format("FPS %.0f  gpu  grid %dx%d", fps, settings.gridW(), settings.gridH()), 12, ts + 6, ui);
            }
            postInvalidateOnAnimation();
        }
    }
}
