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
 * Hosts the terminal and the engine. Flow: terminal -> `run` launches EngineView;
 * Back during the engine opens the PauseMenu overlay; "Exit to terminal" returns
 * to the terminal.
 */
public final class MainActivity extends Activity implements Terminal.Host {
    private FrameLayout root;
    private TerminalView terminal;
    private EngineView engine;
    private PauseMenu pauseMenu;
    private Settings settings;
    private Terminal cmd;

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
        // intercept CLEAR sentinel through print
        root.addView(terminal);
        setContentView(root);
        mode = Mode.TERMINAL;
    }

    @Override public void launchEngine() {
        runOnUiThread(() -> {
            engine = new EngineView(this, settings);
            engine.setMenuListener(this::openMenu);
            if (pendingModel != null) engine.setLoadedModel(pendingModel);
            root.removeAllViews();
            root.addView(engine);
            mode = Mode.ENGINE;
        });
    }

    private void openMenu() {
        runOnUiThread(() -> {
            if (mode != Mode.ENGINE) return;
            engine.setPaused(true);
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
        if (engine != null) engine.setPaused(false);
        mode = Mode.ENGINE;
    }

    private void exitToTerminal() {
        root.removeAllViews();
        engine = null; pauseMenu = null;
        root.addView(terminal);
        mode = Mode.TERMINAL;
    }

    @Override public void onBackPressed() {
        switch (mode) {
            case ENGINE: openMenu(); break;
            case MENU: closeMenu(); break;
            case TERMINAL:
            default: super.onBackPressed(); // normal: leave app
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

    private Mesh pendingModel = null;

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