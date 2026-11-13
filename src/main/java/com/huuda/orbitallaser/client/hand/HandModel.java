package com.huuda.orbitallaser.client.hand;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.huuda.orbitallaser.OrbitalLaserMod;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Sky Hand mesh: one rigid piece per bone, loaded from
 * assets/orbital_laser/models/sky_hand/ (sky_hand.obj, sky_hand_rig.json, sky_hand_poses.json).
 * <p>
 * Bone transform = parent * T(pivot) * Rest * (Ry * Rz * Rx) * Rest^-1 * T(-pivot).
 * The OBJ can be replaced by a sculpted mesh exported from Blender, as long as each object/group
 * is named after its bone (a suffix such as "_Material" is fine).
 */
public final class HandModel {
    public static final ResourceLocation TEXTURE =
            new ResourceLocation(OrbitalLaserMod.MODID, "textures/entity/sky_hand.png");
    private static final ResourceLocation OBJ =
            new ResourceLocation(OrbitalLaserMod.MODID, "models/sky_hand/sky_hand.obj");
    private static final ResourceLocation RIG =
            new ResourceLocation(OrbitalLaserMod.MODID, "models/sky_hand/sky_hand_rig.json");
    private static final ResourceLocation POSES =
            new ResourceLocation(OrbitalLaserMod.MODID, "models/sky_hand/sky_hand_poses.json");

    /** 8 floats per vertex: x y z u v nx ny nz; 3 vertices per triangle. */
    private static final int STRIDE = 8;
    private static final float[] ZERO = {0.0F, 0.0F, 0.0F};

    public static final class Bone {
        public final String name;
        public final int parent;
        public final Vector3f pivot;
        public final Quaternionf rest;
        float[] vertices = new float[0];

        Bone(String name, int parent, Vector3f pivot, Quaternionf rest) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rest = rest;
        }
    }

    private static HandModel cached;
    private static boolean failed;

    public final List<Bone> bones;
    private final Map<String, Integer> index;
    private final Map<String, Map<String, float[]>> poses;

    private HandModel(List<Bone> bones, Map<String, Integer> index, Map<String, Map<String, float[]>> poses) {
        this.bones = bones;
        this.index = index;
        this.poses = poses;
    }

    /** Loaded lazily; returns null if the files are missing or broken (the rest of the effect still runs). */
    @Nullable
    public static HandModel get() {
        if (cached == null && !failed) {
            try {
                cached = load(Minecraft.getInstance().getResourceManager());
            } catch (Exception e) {
                failed = true;
                OrbitalLaserMod.LOGGER.error("[orbital_laser] could not load the sky hand model", e);
            }
        }
        return cached;
    }

    /** Called on resource reload (F3+T), so an edited OBJ shows up without restarting. */
    public static void invalidate() {
        cached = null;
        failed = false;
    }

    public int boneIndex(String name) {
        Integer i = index.get(name);
        return i == null ? -1 : i;
    }

    public Map<String, float[]> pose(String name) {
        Map<String, float[]> p = poses.get(name);
        return p != null ? p : Map.of();
    }

    // ================================================================= posing and drawing

    /** Fills world matrices and normal matrices for every bone. */
    public void computeBones(Matrix4f base, Matrix3f baseNormal, Map<String, float[]> pose,
                             Matrix4f[] outPose, Matrix3f[] outNormal) {
        for (int i = 0; i < bones.size(); i++) {
            Bone b = bones.get(i);
            float[] e = pose.getOrDefault(b.name, ZERO);
            Quaternionf r = new Quaternionf()
                    .rotationY((float) Math.toRadians(e[1]))
                    .rotateZ((float) Math.toRadians(e[2]))
                    .rotateX((float) Math.toRadians(e[0]));
            Quaternionf q = new Quaternionf(b.rest).mul(r).mul(new Quaternionf(b.rest).conjugate());
            Matrix4f parent = b.parent < 0 ? base : outPose[b.parent];
            Matrix3f parentN = b.parent < 0 ? baseNormal : outNormal[b.parent];
            outPose[i] = new Matrix4f(parent)
                    .translate(b.pivot)
                    .rotate(q)
                    .translate(-b.pivot.x(), -b.pivot.y(), -b.pivot.z());
            outNormal[i] = new Matrix3f(parentN).rotate(q);
        }
    }

    /** Emits every triangle as a degenerate quad (entity render types use QUADS). */
    public void render(Matrix4f[] pose, Matrix3f[] normal, VertexConsumer vc,
                       float r, float g, float b, float a, int light) {
        Vector4f p = new Vector4f();
        Vector3f n = new Vector3f();
        for (int i = 0; i < bones.size(); i++) {
            float[] d = bones.get(i).vertices;
            Matrix4f m = pose[i];
            Matrix3f nm = normal[i];
            for (int t = 0; t + STRIDE * 3 <= d.length; t += STRIDE * 3) {
                for (int k = 0; k < 4; k++) {
                    int o = t + Math.min(k, 2) * STRIDE;
                    p.set(d[o], d[o + 1], d[o + 2], 1.0F);
                    m.transform(p);
                    n.set(d[o + 5], d[o + 6], d[o + 7]);
                    nm.transform(n);
                    float len = n.length();
                    if (len > 1.0E-6F) {
                        n.div(len);
                    }
                    vc.vertex(p.x(), p.y(), p.z(), r, g, b, a, d[o + 3], d[o + 4],
                            OverlayTexture.NO_OVERLAY, light, n.x(), n.y(), n.z());
                }
            }
        }
    }

    // ================================================================= loading

    private static BufferedReader open(ResourceManager rm, ResourceLocation loc) throws IOException {
        Resource res = rm.getResource(loc).orElseThrow(() -> new FileNotFoundException(loc.toString()));
        return new BufferedReader(new InputStreamReader(res.open(), StandardCharsets.UTF_8));
    }

    private static HandModel load(ResourceManager rm) throws IOException {
        // ---- rig
        List<Bone> bones = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        try (BufferedReader r = open(rm, RIG)) {
            JsonObject rig = JsonParser.parseReader(r).getAsJsonObject();
            for (JsonElement el : rig.getAsJsonArray("bones")) {
                JsonObject o = el.getAsJsonObject();
                String name = o.get("name").getAsString();
                int parent = -1;
                if (o.has("parent") && !o.get("parent").isJsonNull()) {
                    Integer pi = index.get(o.get("parent").getAsString());
                    if (pi == null) {
                        throw new IOException("bone " + name + " listed before its parent");
                    }
                    parent = pi;
                }
                JsonArray pv = o.getAsJsonArray("pivot");
                JsonArray q = o.getAsJsonArray("rest");
                Bone b = new Bone(name, parent,
                        new Vector3f(pv.get(0).getAsFloat(), pv.get(1).getAsFloat(), pv.get(2).getAsFloat()),
                        new Quaternionf(q.get(0).getAsFloat(), q.get(1).getAsFloat(), q.get(2).getAsFloat(),
                                q.get(3).getAsFloat()).normalize());
                index.put(name, bones.size());
                bones.add(b);
            }
        }

        // ---- poses
        Map<String, Map<String, float[]>> poses = new HashMap<>();
        try (BufferedReader r = open(rm, POSES)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("poses");
            for (Map.Entry<String, JsonElement> pe : root.entrySet()) {
                Map<String, float[]> pose = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> be : pe.getValue().getAsJsonObject().entrySet()) {
                    JsonArray a = be.getValue().getAsJsonArray();
                    pose.put(be.getKey(), new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()});
                }
                poses.put(pe.getKey(), pose);
            }
        }

        // ---- mesh
        List<float[]> v = new ArrayList<>();
        List<float[]> vt = new ArrayList<>();
        List<float[]> vn = new ArrayList<>();
        Map<Integer, FloatArrayList> data = new HashMap<>();
        int current = 0;
        try (BufferedReader r = open(rm, OBJ)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] p = line.split("\\s+");
                switch (p[0]) {
                    case "o", "g" -> {
                        if (p.length > 1) {
                            int b = matchBone(p[1], index);
                            if (b >= 0) {
                                current = b;
                            }
                        }
                    }
                    case "v" -> v.add(new float[]{f(p[1]), f(p[2]), f(p[3])});
                    case "vt" -> vt.add(new float[]{f(p[1]), 1.0F - (p.length > 2 ? f(p[2]) : 0.0F)});
                    case "vn" -> vn.add(new float[]{f(p[1]), f(p[2]), f(p[3])});
                    case "f" -> {
                        int n = p.length - 1;
                        int[][] corners = new int[n][];
                        for (int i = 0; i < n; i++) {
                            corners[i] = corner(p[i + 1], v.size(), vt.size(), vn.size());
                        }
                        FloatArrayList out = data.computeIfAbsent(current, k -> new FloatArrayList());
                        for (int i = 1; i + 1 < n; i++) {
                            emit(out, v, vt, vn, corners[0], corners[i], corners[i + 1]);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        int total = 0;
        for (Map.Entry<Integer, FloatArrayList> e : data.entrySet()) {
            bones.get(e.getKey()).vertices = e.getValue().toFloatArray();
            total += e.getValue().size() / STRIDE / 3;
        }
        OrbitalLaserMod.LOGGER.info("[orbital_laser] sky hand loaded: {} bones, {} triangles, {} poses",
                bones.size(), total, poses.size());
        return new HandModel(bones, index, poses);
    }

    private static int matchBone(String group, Map<String, Integer> index) {
        Integer exact = index.get(group);
        if (exact != null) {
            return exact;
        }
        String best = null;
        for (String name : index.keySet()) {
            if (group.startsWith(name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best == null ? -1 : index.get(best);
    }

    private static float f(String s) {
        return Float.parseFloat(s);
    }

    /** "v", "v/t", "v//n" or "v/t/n", 1-based or negative (relative). Missing parts are -1. */
    private static int[] corner(String token, int nv, int nt, int nn) {
        String[] parts = token.split("/", -1);
        int vi = idx(parts[0], nv);
        int ti = parts.length > 1 && !parts[1].isEmpty() ? idx(parts[1], nt) : -1;
        int ni = parts.length > 2 && !parts[2].isEmpty() ? idx(parts[2], nn) : -1;
        return new int[]{vi, ti, ni};
    }

    private static int idx(String s, int count) {
        int i = Integer.parseInt(s);
        return i < 0 ? count + i : i - 1;
    }

    private static void emit(FloatArrayList out, List<float[]> v, List<float[]> vt, List<float[]> vn,
                             int[] a, int[] b, int[] c) {
        float[] pa = v.get(a[0]);
        float[] pb = v.get(b[0]);
        float[] pc = v.get(c[0]);
        float[] faceN = null;
        for (int[] k : new int[][]{a, b, c}) {
            float[] pos = v.get(k[0]);
            float[] uv = k[1] >= 0 ? vt.get(k[1]) : new float[]{0.0F, 0.0F};
            float[] nrm;
            if (k[2] >= 0) {
                nrm = vn.get(k[2]);
            } else {
                if (faceN == null) {
                    Vector3f e1 = new Vector3f(pb[0] - pa[0], pb[1] - pa[1], pb[2] - pa[2]);
                    Vector3f e2 = new Vector3f(pc[0] - pa[0], pc[1] - pa[1], pc[2] - pa[2]);
                    Vector3f fn = e1.cross(e2);
                    if (fn.lengthSquared() > 0) {
                        fn.normalize();
                    }
                    faceN = new float[]{fn.x(), fn.y(), fn.z()};
                }
                nrm = faceN;
            }
            out.add(pos[0]);
            out.add(pos[1]);
            out.add(pos[2]);
            out.add(uv[0]);
            out.add(uv[1]);
            out.add(nrm[0]);
            out.add(nrm[1]);
            out.add(nrm[2]);
        }
    }
}
