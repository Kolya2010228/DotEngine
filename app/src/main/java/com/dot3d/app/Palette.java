package com.dot3d.app;

/** Brightness ramp (dark -> light) used to pick an ASCII glyph for a luminance value. */
public final class Palette {
    public static final String CLASSIC = " .:-=+*#%@";
    public static final String BLOCKS = " .:oO0#";
    public static final String DENSE = " .'`^\",:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";

    private String ramp;

    public Palette(String ramp) { setRamp(ramp); }

    public void setRamp(String r) {
        if (r == null || r.isEmpty()) r = CLASSIC;
        this.ramp = r;
    }

    public String getRamp() { return ramp; }

    public static String presetByName(String name) {
        switch (name == null ? "" : name.toLowerCase()) {
            case "classic": return CLASSIC;
            case "blocks": return BLOCKS;
            case "dense": return DENSE;
            default: return null;
        }
    }

    /** lum in [0,1] -> glyph. */
    public char glyph(float lum) {
        if (lum < 0) lum = 0;
        if (lum > 1) lum = 1;
        int i = (int) (lum * (ramp.length() - 1) + 0.5f);
        return ramp.charAt(i);
    }
}