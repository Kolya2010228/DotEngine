package com.dot3d.app;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Experimental GPU render path -- terrain step.
 *
 * A GLES 3.0 fragment shader raymarches the infinite heightfield PER SCREEN PIXEL
 * (full-resolution, not per ASCII cell) using the same integer-column DDA traversal
 * as the software {@link Renderer}. Camera, touch controls and vertical physics are
 * reused 1:1 from the software path so movement feels identical. Collision uses
 * {@link GpuField} which reproduces the shader's noise bit-for-bit, so the player
 * collides with exactly the terrain the shader draws.
 *
 * Output is colored pixels (no ASCII glyphs yet -- that is the next step). Purpose:
 * prove the GPU raymarch draws real terrain and is fast, before adding the glyph
 * pass. Guarded by Settings.gpu() (default OFF); the software renderer is untouched.
 */
public final class GLEngineView extends GLSurfaceView implements Engine {
    private final Settings settings;
    private EngineView.MenuListener menuListener;

    private final Camera cam = new Camera();
    private final Controls controls;
    private final long seed;

    // Vertical physics (mirrors Physics.java).
    private float vy = 0f;
    private boolean onGround = false;
    private static final float WALK = 6f, GRAV = -22f, JUMP = 9f, EYE = 1.6f;

    public GLEngineView(Context ctx, Settings settings) {
        super(ctx);
        this.settings = settings;
        float density = getResources().getDisplayMetrics().density;
        this.controls = new Controls(density);
        this.seed = settings.seed();
        cam.fovDeg = settings.fov();
        cam.pos = new Vec3(0.5f, GpuField.heightAt(0, 0, (int) seed) + EYE + 2f, 0.5f);
        setEGLContextClientVersion(3);
        setRenderer(new GLRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setFocusable(true);
    }

    @Override public void setMenuListener(EngineView.MenuListener l) { this.menuListener = l; }
    @Override public void setLoadedModel(Mesh m) { /* .obj on GPU is a later step */ }
    @Override public void setPaused(boolean p) { if (p) onPause(); else onResume(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        controls.setSize(w, h);
    }

    @Override public boolean onTouchEvent(MotionEvent e) { controls.onTouch(e); return true; }

    /** Advance camera + physics one step (runs on the GL thread). */
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
        float groundTop = GpuField.heightAt(gx, gz, (int) seed);
        if (cam.pos.y - EYE <= groundTop) { cam.pos.y = groundTop + EYE; vy = 0f; onGround = true; }
        else onGround = false;
    }

    private static final String VS =
            "#version 300 es\n" +
            "const vec2 verts[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));\n" +
            "void main() { gl_Position = vec4(verts[gl_VertexID], 0.0, 1.0); }\n";

    private static final String FS =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform vec3 uCamPos;\n" +
            "uniform vec3 uForward;\n" +
            "uniform vec3 uRight;\n" +
            "uniform vec3 uUp;\n" +
            "uniform float uTanHalf;\n" +
            "uniform float uAspect;\n" +
            "uniform float uMaxDist;\n" +
            "uniform uint uSeed;\n" +
            "uniform vec2 uRes;\n" +
            "out vec4 fragColor;\n" +
            "uint hash(int x, int z, uint seed){\n" +
            "  uint h = uint(x)*374761393u + uint(z)*668265263u + seed*2246822519u;\n" +
            "  h = (h ^ (h>>13u)) * 1274126177u;\n" +
            "  h = h ^ (h>>16u);\n" +
            "  return h;\n" +
            "}\n" +
            "float rand01(int x, int z, uint seed){ return float(hash(x,z,seed)&0xFFFFFFu)/float(0xFFFFFF); }\n" +
            "float vnoise(float fx, float fz, uint seed){\n" +
            "  int x0=int(floor(fx)); int z0=int(floor(fz));\n" +
            "  float tx=fx-float(x0); float tz=fz-float(z0);\n" +
            "  float n00=rand01(x0,z0,seed); float n10=rand01(x0+1,z0,seed);\n" +
            "  float n01=rand01(x0,z0+1,seed); float n11=rand01(x0+1,z0+1,seed);\n" +
            "  float sx=tx*tx*(3.0-2.0*tx); float sz=tz*tz*(3.0-2.0*tz);\n" +
            "  float a=n00+(n10-n00)*sx; float b=n01+(n11-n01)*sx;\n" +
            "  return a+(b-a)*sz;\n" +
            "}\n" +
            "float heightF(int x, int z, uint seed){\n" +
            "  float n = vnoise(float(x)/24.0, float(z)/24.0, seed)*1.0 + vnoise(float(x)/8.0, float(z)/8.0, seed)*0.4;\n" +
            "  n/=1.4;\n" +
            "  return float(int(n*12.0)+1);\n" +
            "}\n" +
            "vec3 biome(float h){\n" +
            "  if(h<=2.0) return vec3(0.247,0.435,0.690);\n" +
            "  if(h<=4.0) return vec3(0.761,0.698,0.502);\n" +
            "  if(h<=8.0) return vec3(0.298,0.604,0.165);\n" +
            "  if(h<=11.0) return vec3(0.420,0.420,0.420);\n" +
            "  return vec3(0.961,0.961,0.961);\n" +
            "}\n" +
            "void main(){\n" +
            "  vec2 ndc = 2.0*gl_FragCoord.xy/uRes - 1.0;\n" +
            "  float ax = ndc.x*uTanHalf*uAspect;\n" +
            "  float ay = ndc.y*uTanHalf;\n" +
            "  vec3 dir = normalize(uForward + uRight*ax + uUp*ay);\n" +
            "  float camx=uCamPos.x, camy=uCamPos.y, camz=uCamPos.z;\n" +
            "  int ix=int(floor(camx)); int iz=int(floor(camz));\n" +
            "  int stepX = dir.x>0.0?1:-1; int stepZ = dir.z>0.0?1:-1;\n" +
            "  float tMaxX,tDeltaX,tMaxZ,tDeltaZ;\n" +
            "  if(abs(dir.x)>1e-9){ float nx = dir.x>0.0?float(ix+1):float(ix); tMaxX=(nx-camx)/dir.x; tDeltaX=abs(1.0/dir.x);} else {tMaxX=1e30; tDeltaX=1e30;}\n" +
            "  if(abs(dir.z)>1e-9){ float nz = dir.z>0.0?float(iz+1):float(iz); tMaxZ=(nz-camz)/dir.z; tDeltaZ=abs(1.0/dir.z);} else {tMaxZ=1e30; tDeltaZ=1e30;}\n" +
            "  float t=0.0; bool hit=false; float hcol=0.0;\n" +
            "  for(int iter=0; iter<512; iter++){\n" +
            "    if(t>=uMaxDist) break;\n" +
            "    float h = heightF(ix,iz,uSeed);\n" +
            "    float tExit = min(tMaxX,tMaxZ);\n" +
            "    float tEnd = min(tExit,uMaxDist);\n" +
            "    float pyEnter = camy + dir.y*t;\n" +
            "    if(pyEnter < h){ hcol=h; hit=true; break; }\n" +
            "    if(dir.y<0.0){ float tc=(h-camy)/dir.y; if(tc>=t && tc<=tEnd){ hcol=h; hit=true; t=tc; break; } }\n" +
            "    t=tExit;\n" +
            "    if(t>=uMaxDist) break;\n" +
            "    if(tMaxX<tMaxZ){ ix+=stepX; tMaxX+=tDeltaX; } else { iz+=stepZ; tMaxZ+=tDeltaZ; }\n" +
            "  }\n" +
            "  if(!hit){ fragColor=vec4(0.02,0.03,0.05,1.0); return; }\n" +
            "  float hl=heightF(ix-1,iz,uSeed); float hr=heightF(ix+1,iz,uSeed);\n" +
            "  float hd=heightF(ix,iz-1,uSeed); float hu=heightF(ix,iz+1,uSeed);\n" +
            "  vec3 nrm = normalize(vec3(hl-hr, 2.0, hd-hu));\n" +
            "  vec3 L = vec3(-0.3578,-0.8944,-0.2683);\n" +
            "  float diff = max(0.0, -dot(nrm,L));\n" +
            "  float fog = clamp(1.0 - t/uMaxDist, 0.0, 1.0);\n" +
            "  float lum = (0.25+0.75*diff)*(0.4+0.6*fog);\n" +
            "  fragColor = vec4(biome(hcol)*lum, 1.0);\n" +
            "}\n";

    private final class GLRenderer implements GLSurfaceView.Renderer {
        private int program;
        private int uCamPos, uForward, uRight, uUp, uTanHalf, uAspect, uMaxDist, uSeed, uRes;
        private int width, height;
        private long last;

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            int vs = compile(GLES30.GL_VERTEX_SHADER, VS);
            int fs = compile(GLES30.GL_FRAGMENT_SHADER, FS);
            program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vs);
            GLES30.glAttachShader(program, fs);
            GLES30.glLinkProgram(program);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("link failed: " + GLES30.glGetProgramInfoLog(program));
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
            uCamPos = GLES30.glGetUniformLocation(program, "uCamPos");
            uForward = GLES30.glGetUniformLocation(program, "uForward");
            uRight = GLES30.glGetUniformLocation(program, "uRight");
            uUp = GLES30.glGetUniformLocation(program, "uUp");
            uTanHalf = GLES30.glGetUniformLocation(program, "uTanHalf");
            uAspect = GLES30.glGetUniformLocation(program, "uAspect");
            uMaxDist = GLES30.glGetUniformLocation(program, "uMaxDist");
            uSeed = GLES30.glGetUniformLocation(program, "uSeed");
            uRes = GLES30.glGetUniformLocation(program, "uRes");
            last = System.nanoTime();
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
            width = w; height = h;
            GLES30.glViewport(0, 0, w, h);
        }

        @Override public void onDrawFrame(GL10 gl) {
            long now = System.nanoTime();
            float dt = (now - last) / 1e9f;
            last = now;
            if (dt > 0.05f) dt = 0.05f;

            cam.fovDeg = settings.fov(); // live FOV
            update(dt);

            Vec3 f = cam.forward();
            float rlen = (float) Math.sqrt(f.z * f.z + f.x * f.x);
            if (rlen < 1e-5f) rlen = 1e-5f;
            float rx = f.z / rlen, ry = 0f, rz = -f.x / rlen;
            float ux = f.y * rz - f.z * ry;
            float uy = f.z * rx - f.x * rz;
            float uz = f.x * ry - f.y * rx;
            float tanHalf = (float) Math.tan(Math.toRadians(cam.fovDeg) / 2.0);
            float aspect = height > 0 ? (float) width / height : 1f;
            float maxDist = settings.renderDist() * World.CHUNK + World.CHUNK;
            if (maxDist < 32f) maxDist = 32f;

            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glUseProgram(program);
            GLES30.glUniform3f(uCamPos, cam.pos.x, cam.pos.y, cam.pos.z);
            GLES30.glUniform3f(uForward, f.x, f.y, f.z);
            GLES30.glUniform3f(uRight, rx, ry, rz);
            GLES30.glUniform3f(uUp, ux, uy, uz);
            GLES30.glUniform1f(uTanHalf, tanHalf);
            GLES30.glUniform1f(uAspect, aspect);
            GLES30.glUniform1f(uMaxDist, maxDist);
            GLES30.glUniform1ui(uSeed, (int) seed);
            GLES30.glUniform2f(uRes, (float) width, (float) height);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
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
}
