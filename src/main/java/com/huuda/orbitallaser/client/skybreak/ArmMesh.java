package com.huuda.orbitallaser.client.skybreak;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.skybreak.ArmRig;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Sky Breaks' sculpted arm: 18 rigid pieces (models/skybreak/skybreak_arm.obj, made by
 * tools/skybreak_arm_mesh.py from Stink-O's Orbital Laser, MIT). Each piece in its own frame; ArmRig places them.
 * Stored flat: 8 floats a vertex (x y z u v nx ny nz), 3 vertices a triangle.
 */
public final class ArmMesh {
    public static final ResourceLocation OBJ =
            new ResourceLocation(OrbitalLaserMod.MODID, "models/skybreak/skybreak_arm.obj");
    public static final ResourceLocation TEXTURE =
            new ResourceLocation(OrbitalLaserMod.MODID, "textures/entity/sky_hand.png");

    public final float[][] pieces = new float[ArmRig.PIECES][];

    private static ArmMesh cached;
    private static boolean failed;

    @Nullable
    public static ArmMesh get() {
        if (cached == null && !failed) {
            try {
                cached = load();
            } catch (Exception e) {
                failed = true;
                OrbitalLaserMod.LOGGER.error("[orbital_laser] could not load the Sky Breaks arm", e);
            }
        }
        return cached;
    }

    public static void invalidate() {
        cached = null;
        failed = false;
    }

    private static ArmMesh load() throws Exception {
        Resource res = Minecraft.getInstance().getResourceManager().getResource(OBJ)
                .orElseThrow(() -> new FileNotFoundException(OBJ.toString()));
        List<float[]> v = new ArrayList<>();
        List<float[]> vt = new ArrayList<>();
        List<float[]> vn = new ArrayList<>();
        FloatArrayList[] out = new FloatArrayList[ArmRig.PIECES];
        for (int i = 0; i < out.length; i++) {
            out[i] = new FloatArrayList();
        }
        int cur = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(res.open(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] p = line.trim().split("\\s+");
                switch (p[0]) {
                    case "g", "o" -> {
                        int us = p.length > 1 ? p[1].lastIndexOf('_') : -1;
                        if (us >= 0) {
                            try {
                                cur = Math.max(0, Math.min(ArmRig.PIECES - 1, Integer.parseInt(p[1].substring(us + 1))));
                            } catch (NumberFormatException ignored) {
                                // keep the current piece
                            }
                        }
                    }
                    case "v" -> v.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                    case "vt" -> vt.add(new float[]{Float.parseFloat(p[1]), 1.0F - Float.parseFloat(p[2])});
                    case "vn" -> vn.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                    case "f" -> {
                        for (int k = 1; k <= 3; k++) {
                            String[] c = p[k].split("/");
                            float[] pos = v.get(Integer.parseInt(c[0]) - 1);
                            float[] uv = c.length > 1 && !c[1].isEmpty() ? vt.get(Integer.parseInt(c[1]) - 1) : new float[]{0, 0};
                            float[] n = c.length > 2 ? vn.get(Integer.parseInt(c[2]) - 1) : new float[]{0, 1, 0};
                            FloatArrayList o = out[cur];
                            o.add(pos[0]);
                            o.add(pos[1]);
                            o.add(pos[2]);
                            o.add(uv[0]);
                            o.add(uv[1]);
                            o.add(n[0]);
                            o.add(n[1]);
                            o.add(n[2]);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        ArmMesh m = new ArmMesh();
        int tris = 0;
        for (int i = 0; i < out.length; i++) {
            m.pieces[i] = out[i].toFloatArray();
            tris += m.pieces[i].length / 24;
        }
        OrbitalLaserMod.LOGGER.info("[orbital_laser] Sky Breaks arm loaded: {} triangles", tris);
        return m;
    }
}
