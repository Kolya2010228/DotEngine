package com.dot3d.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.InputStream;

/**
 * Hosts the terminal and the engine. Flow: terminal -> `run` launches the engine;
 * Back during the engine opens the PauseMenu overlay; "Exit" returns to the terminal.
 *
 * Settings application: when the pause menu closes, "structural" settings (grid size,
 * seed, palette) that the GPU path only reads at creation are detected via a signature
 * captured when the menu opened; if they changed under GPU, the engine view is rebuilt
 * so the change actually takes effect (the software EngineView already re-applies
 * everything on unpause). Live-read settings (fov/color/sensitivity/render distance)
 * need no rebuild. Forced landscape + immersive fullscreen.
 */
public final class MainActivity extends Activity implements Terminal.Host {
    private FrameLayout root;
    private TerminalView terminal;
    private View engineView;
    private Engine engine;
    private PauseMenu pauseMenu;
    private Settings settings;
    private Terminal cmd;
    private Mesh pendingModel = null;
    private String menuSig = "";

    private enum Mode { TERMINAL, ENGINE, MENU }
    private Mode mode = Mode.TERMINAL;

    private static final int REQ_PICK_OBJ = 1001;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        settings = new Settings(this);
        cmd = new Terminal(this);

        root = new FrameLayout(this);
        terminal = new TerminalView(this);
        terminal.setSink(line -> {
            if ("\u0001CLEAR".equals(line)) return;
            cmd.exec(line);
        });
        root.addView(terminal);
        setContentView(root);
        mode = Mode.TERMINAL;
        hideSystemUi();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void launchEngine() {
        runOnUiThread(() -> {
            buildEngine();
            root.removeAllViews();
            root.addView(engineView);
            mode = Mode.ENGINE;
        });
    }

    /** Create the engine view for the current settings.gpu() flag and wire it up. */
    private void buildEngine() {
        if (settings.gpu()) {
            GLEngineView gl = new GLEngineView(this, settings);
            engine = gl; engineView = gl;
        } else {
            EngineView sw = new EngineView(this, settings);
            engine = sw; engineView = sw;
        }
        engine.setMenuListener(this::openMenu);
        if (pendingModel != null) engine.setLoadedModel(pendingModel);
    }

    /**
     * Signature of the settings a running engine reads ONLY at creation time
     * (grid size, seed, palette). If any change while the menu is open, the GPU path
     * must be rebuilt to reflect them; live-read settings do not need a rebuild.
     */
    private String settingsSig() {
        return settings.gridW() + "x" + settings.gridH() + "|" + settings.seed() + "|" + settings.palette();
    }

    private void openMenu() {
        runOnUiThread(() -> {
            if (mode != Mode.ENGINE) return;
            engine.setPaused(true);
            menuSig = settingsSig();
            pauseMenu = new PauseMenu(this, settings, new PauseMenu.Listener() {
                @Override public void onContinue() { closeMenu(); }
                @Override public void onExitToTerminal() { exitToTerminal(); }
            });
            root.addView(pauseMenu);
            mode = Mode.MENU;
        });
    }

    private void closeMenu() {
        if (pauseMenu != null) root.removeView(pauseMenu);
        pauseMenu = null;
        boolean structuralChanged = !settingsSig().equals(menuSig);
        if (engine != null) {
            if (structuralChanged && settings.gpu()) {
                // GPU reads grid/seed/palette only at creation -> rebuild to apply them.
                if (engineView != null) root.removeView(engineView);
                buildEngine();
                root.addView(engineView);
            } else {
                // Software EngineView.applySettings() runs on unpause and rebuilds
                // everything from Settings; GPU live-read settings need no rebuild.
                engine.setPaused(false);
            }
        }
        mode = Mode.ENGINE;
    }

    private void exitToTerminal() {
        root.removeAllViews();
        engine = null; engineView = null; pauseMenu = null;
        root.addView(terminal);
        mode = Mode.TERMINAL;
    }

    @Override public void onBackPressed() {
        switch (mode) {
            case ENGINE: openMenu(); break;
            case MENU: closeMenu(); break;
            case TERMINAL:
            default: super.onBackPressed();
        }
    }

    @Override public void loadModel(String nameOrNull) {
        if (nameOrNull == null) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_OBJ);
            print("opening file picker...");
        } else {
            String file = nameOrNull.endsWith(".obj") ? nameOrNull : nameOrNull + ".obj";
            try (InputStream in = getAssets().open("models/" + file)) {
                Mesh m = ObjLoader.load(in, 0xE0A030);
                pendingModel = m;
                print("loaded model: " + file + " (" + m.tris.size() + " tris). 'run' to view.");
            } catch (Exception e) {
                print("load failed: " + e.getMessage());
            }
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_OBJ && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Mesh m = ObjLoader.load(in, 0xE0A030);
                pendingModel = m;
                print("loaded model (" + m.tris.size() + " tris). 'run' to view.");
            } catch (Exception e) {
                print("load failed: " + e.getMessage());
            }
        }
    }

    @Override public void listModels() {
        try {
            String[] files = getAssets().list("models");
            if (files == null || files.length == 0) { print("no bundled models"); return; }
            StringBuilder sb = new StringBuilder("bundled models:");
            for (String f : files) sb.append("\n  ").append(f);
            print(sb.toString());
        } catch (Exception e) {
            print("ls failed: " + e.getMessage());
        }
    }

    @Override public void print(String s) {
        if ("\u0001CLEAR".equals(s)) { terminal.clear(); return; }
        terminal.println(s);
    }

    @Override public Settings settings() { return settings; }
}
