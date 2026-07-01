package com.dot3d.app;

/**
 * Command processor for the terminal. Real commands control engine settings and
 * launch; anything else returns a plausible stub (like a shell "command not found")
 * without doing anything.
 */
public final class Terminal {
    public interface Host {
        void launchEngine();
        void loadModel(String nameOrNull);
        void listModels();
        void print(String s);
        Settings settings();
    }

    private final Host host;

    public Terminal(Host host) { this.host = host; }

    public void exec(String line) {
        if (line.isEmpty()) return;
        String[] tok = line.split("\\s+");
        String cmd = tok[0].toLowerCase();
        Settings s = host.settings();
        switch (cmd) {
            case "help":
                host.print("Commands:\n"
                        + "  run | start        launch the 3D engine\n"
                        + "  grid <W> <H>       set ascii grid size (saved)\n"
                        + "  palette <name|chars>  classic|blocks|dense or custom ramp (saved)\n"
                        + "  seed <n>           set infinite world seed (saved)\n"
                        + "  set color on|off   toggle color (saved)\n"
                        + "  set debug on|off   toggle on-screen FPS/debug HUD (saved)\n"
                        + "  set gpu on|off     experimental GPU render backend (saved)\n"
                        + "  load [file]        load .obj model (no arg = file picker)\n"
                        + "  ls                 list bundled models\n"
                        + "  clear              clear screen\n"
                        + "  help               this help");
                break;
            case "run": case "start": case "engine":
                host.launchEngine();
                break;
            case "grid":
                if (tok.length >= 3) {
                    try {
                        int w = Integer.parseInt(tok[1]);
                        int h = Integer.parseInt(tok[2]);
                        w = Math.max(20, Math.min(400, w));
                        h = Math.max(10, Math.min(300, h));
                        s.setGrid(w, h);
                        host.print("grid set to " + w + "x" + h + " (saved)");
                    } catch (NumberFormatException e) {
                        host.print("usage: grid <W> <H>");
                    }
                } else host.print("grid is " + s.gridW() + "x" + s.gridH());
                break;
            case "palette":
                if (tok.length >= 2) {
                    String preset = Palette.presetByName(tok[1]);
                    String ramp = preset != null ? preset : line.substring(line.indexOf(tok[1]));
                    s.setPalette(ramp);
                    host.print("palette set (saved): " + ramp);
                } else host.print("palette: " + s.palette());
                break;
            case "seed":
                if (tok.length >= 2) {
                    try { long sd = Long.parseLong(tok[1]); s.setSeed(sd); host.print("seed set to " + sd + " (saved)"); }
                    catch (NumberFormatException e) { host.print("usage: seed <n>"); }
                } else host.print("seed: " + s.seed());
                break;
            case "set":
                if (tok.length >= 3 && tok[1].equalsIgnoreCase("color")) {
                    boolean on = tok[2].equalsIgnoreCase("on");
                    s.setColor(on);
                    host.print("color " + (on ? "on" : "off") + " (saved)");
                } else if (tok.length >= 3 && tok[1].equalsIgnoreCase("debug")) {
                    boolean on = tok[2].equalsIgnoreCase("on");
                    s.setDebug(on);
                    host.print("debug " + (on ? "on" : "off") + " (saved)");
                } else if (tok.length >= 3 && tok[1].equalsIgnoreCase("gpu")) {
                    boolean on = tok[2].equalsIgnoreCase("on");
                    s.setGpu(on);
                    host.print("gpu " + (on ? "on" : "off") + " (saved). relaunch engine ('run') to apply");
                } else host.print("usage: set color|debug|gpu on|off");
                break;
            case "load":
                host.loadModel(tok.length >= 2 ? tok[1] : null);
                break;
            case "ls":
                host.listModels();
                break;
            case "clear":
                host.print("\u0001CLEAR");
                break;
            case "pwd":
                host.print("/home/dot3d");
                break;
            case "whoami":
                host.print("dot3d");
                break;
            case "echo":
                host.print(line.length() > 5 ? line.substring(5) : "");
                break;
            case "cd":
                break;
            default:
                host.print(cmd + ": command not found");
        }
    }
}