package com.dot3d.app;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal Wavefront OBJ parser: vertices (v) and faces (f), polygons triangulated as a fan. */
public final class ObjLoader {

    public static Mesh load(InputStream in, int color) throws Exception {
        List<Vec3> verts = new ArrayList<>();
        Mesh mesh = new Mesh();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] tok = line.split("\\s+");
            if (tok.length == 0) continue;
            if (tok[0].equals("v") && tok.length >= 4) {
                verts.add(new Vec3(
                        Float.parseFloat(tok[1]),
                        Float.parseFloat(tok[2]),
                        Float.parseFloat(tok[3])));
            } else if (tok[0].equals("f") && tok.length >= 4) {
                int[] idx = new int[tok.length - 1];
                for (int i = 1; i < tok.length; i++) {
                    // handle formats: v, v/vt, v/vt/vn, v//vn
                    String vpart = tok[i].split("/")[0];
                    int vi = Integer.parseInt(vpart);
                    if (vi < 0) vi = verts.size() + vi + 1; // negative indices
                    idx[i - 1] = vi - 1;
                }
                // fan triangulation
                for (int i = 1; i + 1 < idx.length; i++) {
                    Vec3 a = verts.get(idx[0]);
                    Vec3 b = verts.get(idx[i]);
                    Vec3 c = verts.get(idx[i + 1]);
                    mesh.add(new Tri(a, b, c, color));
                }
            }
        }
        r.close();
        return mesh;
    }
}