package com.dot3d.app;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Experimental GPU render path -- STEP 1: scaffold + test pattern.
 *
 * Purpose of this step: prove that a GLES 3.0 context is created, that shaders
 * compile/link, and that a fullscreen draw reaches the screen at high frame rate
 * on the real device -- BEFORE porting the heightfield raymarch into a shader.
 * It renders only an animated fullscreen gradient (a live, smooth gradient = GL
 * works; a black/frozen screen = something is wrong, and we learn that safely).
 *
 * Guarded by {@link Settings#gpu()} (default OFF); the software {@link EngineView}
 * is completely unchanged, so this cannot regress the working game.
 */
public final class GLEngineView extends GLSurfaceView implements Engine {
    private final Settings settings;
    private EngineView.MenuListener menuListener;

    public GLEngineView(Context ctx, Settings settings) {
        super(ctx);
        this.settings = settings;
        setEGLContextClientVersion(3);
        setRenderer(new GLRenderer());
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    @Override public void setMenuListener(EngineView.MenuListener l) { this.menuListener = l; }

    // .obj model rendering on the GPU path comes in a later step (see the plan).
    @Override public void setLoadedModel(Mesh m) { /* not yet on the GPU path */ }

    @Override public void setPaused(boolean p) {
        // GLSurfaceView.onPause/onResume must be called on the UI thread; MainActivity
        // toggles pause on the UI thread, so this is safe.
        if (p) onPause(); else onResume();
    }

    @Override public boolean onTouchEvent(android.view.MotionEvent e) {
        // Scaffold step has no camera/controls yet; just consume touches. Back still
        // opens the pause menu via the Activity.
        return true;
    }

    /** GLES 3.0 renderer drawing an animated fullscreen gradient (attribute-less). */
    private static final class GLRenderer implements GLSurfaceView.Renderer {
        private int program;
        private int uTime, uRes;
        private long startNs;
        private int width, height;

        // Attribute-less fullscreen triangle: positions come from gl_VertexID, so
        // no VBO/VAO setup is needed (GLES 3.0 supplies a default vertex array).
        private static final String VS =
                "#version 300 es\n" +
                "const vec2 verts[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));\n" +
                "void main() { gl_Position = vec4(verts[gl_VertexID], 0.0, 1.0); }\n";

        private static final String FS =
                "#version 300 es\n" +
                "precision mediump float;\n" +
                "uniform float uTime;\n" +
                "uniform vec2 uRes;\n" +
                "out vec4 fragColor;\n" +
                "void main() {\n" +
                "  vec2 uv = gl_FragCoord.xy / uRes;\n" +
                "  float w = 0.5 + 0.5 * sin(uTime + uv.x * 6.2831853);\n" +
                "  fragColor = vec4(uv.x, uv.y, w, 1.0);\n" +
                "}\n";

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            startNs = System.nanoTime();
            int vs = compile(GLES30.GL_VERTEX_SHADER, VS);
            int fs = compile(GLES30.GL_FRAGMENT_SHADER, FS);
            program = GLES30.glCreateProgram();
            GLES30.glAttachShader(program, vs);
            GLES30.glAttachShader(program, fs);
            GLES30.glLinkProgram(program);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES30.glGetProgramInfoLog(program);
                throw new RuntimeException("program link failed: " + log);
            }
            GLES30.glDeleteShader(vs);
            GLES30.glDeleteShader(fs);
            uTime = GLES30.glGetUniformLocation(program, "uTime");
            uRes = GLES30.glGetUniformLocation(program, "uRes");
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
            width = w; height = h;
            GLES30.glViewport(0, 0, w, h);
        }

        @Override public void onDrawFrame(GL10 gl) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glUseProgram(program);
            float t = (System.nanoTime() - startNs) / 1e9f;
            GLES30.glUniform1f(uTime, t);
            GLES30.glUniform2f(uRes, (float) width, (float) height);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        }

        private static int compile(int type, String src) {
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
