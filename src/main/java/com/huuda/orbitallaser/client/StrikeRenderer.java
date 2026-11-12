package com.huuda.orbitallaser.client;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.config.ClientConfig;
import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.network.StrikeSyncPacket;
import com.huuda.orbitallaser.registry.ModItems;
import com.huuda.orbitallaser.strike.StrikeManager;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * Custom rendering for the strike:
 * <ul>
 *   <li>CHARGE: glass-like cracks spread across the sky above the target, the sky darkens behind them,
 *       and a dark spinning core with orbiting debris grows at the center.</li>
 *   <li>FIRE: the core falls to the target.</li>
 *   <li>IMPACT / STRIKING: a layered beam from the crack to the bottom of the crater and a shockwave ring.</li>
 *   <li>AFTERMATH: cracks and beam fade (the dust column is particles, see {@link ClientStrikes}).</li>
 * </ul>
 * Everything is drawn with the vanilla position-color shader, so no textures or shaders are needed.
 */
@Mod.EventBusSubscriber(modid = OrbitalLaserMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StrikeRenderer {
    private static final double MAX_RENDER_DISTANCE = 2048.0;

    private StrikeRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        boolean effects = ClientConfig.RENDER_EFFECTS.get() && !ClientStrikes.isEmpty();
        boolean preview = ClientConfig.TARGET_PREVIEW.get() && holdingLaser(mc.player);
        if (!effects && !preview) {
            return;
        }

        float partialTick = event.getPartialTick();
        double now = level.getGameTime() + partialTick;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();

        if (effects) {
            for (ClientStrike s : ClientStrikes.all()) {
                if (s.level != level) {
                    continue;
                }
                double dx = s.centerX() - cam.x;
                double dz = s.centerZ() - cam.z;
                if (dx * dx + dz * dz > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) {
                    continue;
                }
                renderStrike(ps, cam, s, now);
            }
        }
        if (preview) {
            renderPreview(ps, cam, mc.player, partialTick, now);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
    }

    private static boolean holdingLaser(Player player) {
        return player.getMainHandItem().is(ModItems.ORBITAL_LASER.get())
                || player.getOffhandItem().is(ModItems.ORBITAL_LASER.get());
    }

    // ================================================================= strike

    private static void renderStrike(PoseStack ps, Vec3 cam, ClientStrike s, double now) {
        boolean charging = s.phase == StrikeSyncPacket.CHARGING;
        double charge = charging ? clamp((now - s.phaseStart) / (s.chargeTicks * 0.85)) : 1.0;

        float fade = 1.0F;
        float beamFade = 1.0F;
        if (s.ended()) {
            double age = now - s.endTime;
            fade = (float) clamp(1.0 - age / (s.cancelled ? ClientStrike.CANCEL_FADE_TICKS : 80.0));
            beamFade = (float) clamp(1.0 - age / 20.0);
        }
        if (fade <= 0.0F) {
            return;
        }

        double skyY = s.skyY();
        float groundY = (float) (s.target.getY() + 1.0 - skyY);
        float bottomY = (float) (s.target.getY() - s.depth - skyY);
        float size = s.crackSize();
        float flicker = 0.85F + 0.15F * (float) Math.sin(now * 0.9);

        ps.pushPose();
        ps.translate(s.centerX() - cam.x, skyY - cam.y, s.centerZ() - cam.z);
        Matrix4f m = ps.last().pose();
        Quads q = new Quads();

        // 1. The sky darkens where it cracks (normal blending, drawn first, behind the cracks).
        RenderSystem.defaultBlendFunc();
        disc(q.get(), m, 0.5F, size * 0.95F, 48, 0.04F, 0.02F, 0.09F,
                (float) (0.45 * easeOut(charge)) * fade, 0.0F);
        q.draw();

        // 2. Cracks, glowing (additive).
        additive();
        float reveal = (float) (easeOut(charge) * size);
        for (CrackGeometry.Segment seg : s.cracks()) {
            if (seg.reveal() > reveal) {
                continue;
            }
            float grow = (float) clamp((reveal - seg.reveal()) / (size * 0.06F));
            float x2 = seg.x1() + (seg.x2() - seg.x1()) * grow;
            float z2 = seg.z1() + (seg.z2() - seg.z1()) * grow;
            hSeg(q.get(), m, seg.x1(), seg.z1(), x2, z2, 0.0F, seg.width() * 3.5F,
                    0.45F, 0.85F, 1.0F, 0.22F * fade * flicker);
            hSeg(q.get(), m, seg.x1(), seg.z1(), x2, z2, -0.05F, seg.width(),
                    0.92F, 0.98F, 1.0F, 0.95F * fade);
        }
        q.draw();

        // 3. Dark core: grows while charging, falls on fire.
        double coreScale = s.radius * 0.35 * easeOut(charge);
        float coreY = -2.0F;
        boolean showCore = true;
        if (!charging) {
            double fall = clamp((now - s.phaseStart) / StrikeManager.CORE_FALL_TICKS);
            coreY = (float) (-2.0 + (groundY + 2.0) * fall * fall);
            showCore = fall < 1.0;
        }
        if (showCore && coreScale > 0.05) {
            float h = (float) coreScale * 0.5F;
            float coreAlpha = 0.97F * fade;

            RenderSystem.defaultBlendFunc();
            ps.pushPose();
            ps.translate(0.0, coreY, 0.0);
            for (int i = 0; i < 3; i++) {
                ps.pushPose();
                ps.mulPose(Axis.YP.rotationDegrees((float) (now * (2.0 + i)) + i * 30.0F));
                ps.mulPose(Axis.XP.rotationDegrees((float) (now * (1.3 + i * 0.4)) + i * 45.0F));
                ps.mulPose(Axis.ZP.rotationDegrees(i * 20.0F));
                cube(q.get(), ps.last().pose(), h * (1.0F - i * 0.12F), 0.07F, 0.03F, 0.12F, coreAlpha);
                ps.popPose();
            }
            // Debris orbiting the core.
            Random rnd = new Random(s.seed);
            for (int i = 0; i < 24; i++) {
                double a0 = rnd.nextDouble() * Math.PI * 2.0;
                double orbit = coreScale * (0.9 + rnd.nextDouble() * 1.1);
                double yOff = (rnd.nextDouble() - 0.5) * coreScale * 0.9;
                double speed = 0.01 + rnd.nextDouble() * 0.03;
                float piece = (float) (coreScale * (0.04 + rnd.nextDouble() * 0.08));
                float shade = 0.45F + rnd.nextFloat() * 0.35F;
                double a = a0 + now * speed;
                ps.pushPose();
                ps.translate(Math.cos(a) * orbit, yOff, Math.sin(a) * orbit);
                ps.mulPose(Axis.YP.rotationDegrees((float) (now * 7.0 + i * 37.0)));
                ps.mulPose(Axis.XP.rotationDegrees((float) (now * 5.0 + i * 11.0)));
                cube(q.get(), ps.last().pose(), piece, shade * 0.85F, shade * 0.9F, shade, 0.95F * fade);
                ps.popPose();
            }
            q.draw();

            // Purple halo around the core.
            additive();
            cube(q.get(), ps.last().pose(), h * 1.45F, 0.55F, 0.25F, 0.9F, 0.18F * fade * flicker);
            cube(q.get(), ps.last().pose(), h * 1.9F, 0.35F, 0.6F, 1.0F, 0.07F * fade);
            q.draw();
            ps.popPose();
        }

        // 4. Beam and impact.
        double since = s.ticksSinceImpact(now);
        if (!charging && !s.cancelled && since >= 0.0 && beamFade > 0.0F) {
            additive();
            float flare = since < 8.0 ? (float) (1.0 + (8.0 - since) / 8.0 * 1.5) : 1.0F;
            float rb = s.radius * 0.32F * (1.0F + 0.06F * (float) Math.sin(now * 1.7)) * flare;
            cylinder(q.get(), m, rb * 1.7F, bottomY, 0.0F, 24, 0.35F, 0.75F, 1.0F, 0.16F * beamFade);
            cylinder(q.get(), m, rb, bottomY, 0.0F, 24, 0.6F, 0.9F, 1.0F, 0.35F * beamFade);
            cylinder(q.get(), m, rb * 0.38F, bottomY, 0.0F, 16, 1.0F, 1.0F, 1.0F, 0.85F * beamFade);
            // Molten glow on the ground.
            disc(q.get(), m, groundY + 0.2F, s.radius * 0.7F, 32, 1.0F, 0.75F, 0.45F,
                    0.4F * beamFade, 0.0F);

            if (since < 45.0) {
                float t = (float) (since / 45.0);
                float outer = s.radius * (0.6F + (float) since * 0.12F);
                float width = 1.5F + (float) since * 0.25F;
                ring(q.get(), m, groundY + 0.6F, Math.max(0.0F, outer - width), outer, 64,
                        1.0F, 0.95F, 0.85F, 0.7F * (1.0F - t));
            }
            q.draw();
        }

        ps.popPose();
    }

    // ================================================================= target preview

    private static void renderPreview(PoseStack ps, Vec3 cam, Player player, float partialTick, double now) {
        HitResult hit = player.pick(ServerConfig.rangeOrDefault(), partialTick, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        float r = ServerConfig.radiusOrDefault();
        float pulse = 0.5F + 0.5F * (float) Math.sin(now * 0.3);

        ps.pushPose();
        ps.translate(pos.getX() + 0.5 - cam.x, pos.getY() + 1.05 - cam.y, pos.getZ() + 0.5 - cam.z);
        Matrix4f m = ps.last().pose();
        Quads q = new Quads();

        additive();
        RenderSystem.disableDepthTest();
        ring(q.get(), m, 0.0F, r - 0.35F, r + 0.35F, 72, 0.35F, 0.85F, 1.0F, 0.35F + 0.3F * pulse);
        ring(q.get(), m, 0.0F, 0.9F, 1.4F, 24, 1.0F, 0.4F, 0.4F, 0.8F);
        float arm = Math.max(2.0F, r * 0.25F);
        hSeg(q.get(), m, -arm, 0.0F, arm, 0.0F, 0.0F, 0.2F, 1.0F, 0.4F, 0.4F, 0.7F);
        hSeg(q.get(), m, 0.0F, -arm, 0.0F, arm, 0.0F, 0.2F, 1.0F, 0.4F, 0.4F, 0.7F);
        q.draw();
        RenderSystem.enableDepthTest();

        ps.popPose();
    }

    // ================================================================= geometry helpers

    /** Lazily begins a QUADS / POSITION_COLOR batch and only draws if something was added. */
    private static final class Quads {
        private BufferBuilder builder;

        BufferBuilder get() {
            if (builder == null) {
                builder = Tesselator.getInstance().getBuilder();
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            }
            return builder;
        }

        void draw() {
            if (builder != null) {
                Tesselator.getInstance().end();
                builder = null;
            }
        }
    }

    private static void additive() {
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
    }

    private static void v(BufferBuilder b, Matrix4f m, float x, float y, float z, float r, float g, float bl, float a) {
        b.vertex(m, x, y, z).color(r, g, bl, a).endVertex();
    }

    /** Flat strip in the horizontal plane at height y. */
    private static void hSeg(BufferBuilder b, Matrix4f m, float x1, float z1, float x2, float z2, float y, float w,
                             float r, float g, float bl, float a) {
        float dx = x2 - x1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4F) {
            return;
        }
        float nx = -dz / len * w * 0.5F;
        float nz = dx / len * w * 0.5F;
        v(b, m, x1 + nx, y, z1 + nz, r, g, bl, a);
        v(b, m, x2 + nx, y, z2 + nz, r, g, bl, a);
        v(b, m, x2 - nx, y, z2 - nz, r, g, bl, a);
        v(b, m, x1 - nx, y, z1 - nz, r, g, bl, a);
    }

    /** Horizontal annulus. */
    private static void ring(BufferBuilder b, Matrix4f m, float y, float r0, float r1, int segs,
                             float r, float g, float bl, float a) {
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0 / segs;
            double a1 = (i + 1) * Math.PI * 2.0 / segs;
            float c0 = (float) Math.cos(a0);
            float s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1);
            float s1 = (float) Math.sin(a1);
            v(b, m, c0 * r0, y, s0 * r0, r, g, bl, a);
            v(b, m, c0 * r1, y, s0 * r1, r, g, bl, a);
            v(b, m, c1 * r1, y, s1 * r1, r, g, bl, a);
            v(b, m, c1 * r0, y, s1 * r0, r, g, bl, a);
        }
    }

    /** Horizontal disc with alpha fading from the center to the edge. */
    private static void disc(BufferBuilder b, Matrix4f m, float y, float radius, int segs,
                             float r, float g, float bl, float aCenter, float aEdge) {
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0 / segs;
            double a1 = (i + 1) * Math.PI * 2.0 / segs;
            v(b, m, 0.0F, y, 0.0F, r, g, bl, aCenter);
            v(b, m, (float) Math.cos(a0) * radius, y, (float) Math.sin(a0) * radius, r, g, bl, aEdge);
            v(b, m, (float) Math.cos(a1) * radius, y, (float) Math.sin(a1) * radius, r, g, bl, aEdge);
            v(b, m, 0.0F, y, 0.0F, r, g, bl, aCenter);
        }
    }

    /** Open vertical cylinder around the local Y axis. */
    private static void cylinder(BufferBuilder b, Matrix4f m, float radius, float y0, float y1, int segs,
                                 float r, float g, float bl, float a) {
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0 / segs;
            double a1 = (i + 1) * Math.PI * 2.0 / segs;
            float x0 = (float) Math.cos(a0) * radius;
            float z0 = (float) Math.sin(a0) * radius;
            float x1 = (float) Math.cos(a1) * radius;
            float z1 = (float) Math.sin(a1) * radius;
            v(b, m, x0, y0, z0, r, g, bl, a);
            v(b, m, x1, y0, z1, r, g, bl, a);
            v(b, m, x1, y1, z1, r, g, bl, a);
            v(b, m, x0, y1, z0, r, g, bl, a);
        }
    }

    /** Cube of half-size h centered on the origin, with simple per-face shading. */
    private static void cube(BufferBuilder b, Matrix4f m, float h, float r, float g, float bl, float a) {
        float[][] faces = {
                {-h, -h, -h, h, -h, -h, h, -h, h, -h, -h, h, 0.6F},   // bottom
                {-h, h, -h, -h, h, h, h, h, h, h, h, -h, 1.0F},       // top
                {-h, -h, -h, -h, h, -h, h, h, -h, h, -h, -h, 0.8F},   // north
                {-h, -h, h, h, -h, h, h, h, h, -h, h, h, 0.8F},       // south
                {-h, -h, -h, -h, -h, h, -h, h, h, -h, h, -h, 0.7F},   // west
                {h, -h, -h, h, h, -h, h, h, h, h, -h, h, 0.7F}        // east
        };
        for (float[] f : faces) {
            float k = f[12];
            v(b, m, f[0], f[1], f[2], r * k, g * k, bl * k, a);
            v(b, m, f[3], f[4], f[5], r * k, g * k, bl * k, a);
            v(b, m, f[6], f[7], f[8], r * k, g * k, bl * k, a);
            v(b, m, f[9], f[10], f[11], r * k, g * k, bl * k, a);
        }
    }

    private static double clamp(double v) {
        return v < 0.0 ? 0.0 : Math.min(1.0, v);
    }

    private static double easeOut(double x) {
        double inv = 1.0 - clamp(x);
        return 1.0 - inv * inv * inv;
    }
}
