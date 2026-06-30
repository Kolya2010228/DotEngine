package com.dot3d.app;

import java.util.ArrayList;
import java.util.List;

/** A collection of triangles plus a uniform color, with a model transform applied at emit time. */
public final class Mesh {
    public final List<Tri> tris = new ArrayList<>();

    public void add(Tri t) { tris.add(t); }

    /** Returns triangles translated by offset and scaled, recoloring to color. */
    public void emit(List<Tri> out, Vec3 offset, float scale, int color) {
        for (Tri t : tris) {
            Vec3 a = t.a.scale(scale).add(offset);
            Vec3 b = t.b.scale(scale).add(offset);
            Vec3 c = t.c.scale(scale).add(offset);
            out.add(new Tri(a, b, c, color));
        }
    }
}