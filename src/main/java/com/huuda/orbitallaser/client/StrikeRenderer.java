package com.huuda.orbitallaser.client;

import com.huuda.orbitallaser.OrbitalLaserMod;
import com.huuda.orbitallaser.config.ClientConfig;
import com.huuda.orbitallaser.config.ServerConfig;
import com.huuda.orbitallaser.client.hand.HandAnimator;
import com.huuda.orbitallaser.client.hand.HandModel;
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
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Custom rendering for the strike:
 * <ul>
 *   <li>CHARGE: the Sky Hand punches the sky from behind three times; glass-like cracks spread with each
 *       punch, the third one bursts the glass, the arm comes through the hole, opens its palm and the
 *       dark core gathers in it (see {@link HandAnimator}).</li>
 *   <li>FIRE: the core falls from the palm to the target.</li>
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

    /** Tilt of the Sky Hand's arm from vertical, toward the back of the hand. */
    private static final float ARM_TILT = 18.0F;

    private static void prepareFx() {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
    }

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

        HandModel model = HandModel.get();
        HandAnimator.Frame frame = model != null ? HandAnimator.compute(s, model, now) : null;

        double skyY = s.skyY();
        double sx = s.centerX() - cam.x;
        double sy = skyY - cam.y;
        double sz = s.centerZ() - cam.z;
        float size = s.crackSize();
        float flicker = 0.85F + 0.15F * (float) Math.sin(now * 0.9);
        float handScale = s.handScale();
        float holeR = frame != null ? frame.hole * handScale * 0.95F : 0.0F;
        float reveal01 = frame != null ? frame.crackReveal : (float) easeOut(charge);

        // The hand is far away and huge: no distance fog on anything drawn here.
        float oldFogStart = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);

        Matrix4f view = ps.last().pose();
        ps.pushPose();
        ps.translate(sx, sy, sz);
        Matrix4f m = ps.last().pose();
        Quads q = new Quads();

        // 1. The sky darkens where it cracks, and the hole shows the void behind it.
        RenderSystem.defaultBlendFunc();
        float darkness = (float) Math.max(easeOut(charge) * 0.35, reveal01) * 0.45F;
        disc(q.get(), m, 0.5F, size * 0.95F, 48, 0.04F, 0.02F, 0.09F, darkness * fade, 0.0F);
        if (holeR > 0.01F) {
            jaggedDisc(q.get(), m, 0.3F, holeR * 1.05F, s.seed, 0.02F, 0.0F, 0.05F, 0.93F * fade);
        }
        q.draw();

        // 2. Cracks, glowing (additive), plus the glowing rim of the hole.
        additive();
        float reveal = reveal01 * size;
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
        if (holeR > 0.01F) {
            ring(q.get(), m, -0.1F, holeR * 0.92F, holeR * 1.2F, 48, 0.55F, 0.9F, 1.0F, 0.5F * fade * flicker);
        }
        q.draw();

        // 3. The Sky Hand.
        Vector3f palm = null;
        if (frame != null && frame.visible) {
            palm = renderHand(ps, s, model, frame, handScale, fade, holeR, sx, sy, sz);
            prepareFx();
        }

        // 4. Glass shards falling from the hole.
        if (frame != null && !Double.isNaN(frame.breakTime)) {
            renderShards(q, ps.last().pose(), s, now - frame.breakTime, handScale, fade);
        }
        ps.popPose();

        // 5. Dark core: gathers in the palm, falls on fire.
        Vector3f origin = palm != null ? palm : new Vector3f((float) sx, (float) sy - 2.0F, (float) sz);
        Vector3f ground = new Vector3f((float) sx, (float) (s.target.getY() + 1.0 - cam.y), (float) sz);
        double coreGrow = frame != null ? frame.coreGrow : easeOut(charge);
        Vector3f coreAt = new Vector3f(origin);
        boolean showCore = true;
        if (!charging) {
            double fall = clamp((now - s.phaseStart) / StrikeManager.CORE_FALL_TICKS);
            coreAt.lerp(ground, (float) (fall * fall));
            showCore = fall < 1.0;
        }
        double coreScale = s.radius * (palm != null ? 0.3 : 0.35) * coreGrow;
        if (showCore && coreScale > 0.05) {
            renderCore(ps, q, s, coreAt, coreScale, fade, flicker, now);
        }

        // 6. Beam from the palm to the bottom of the crater, and the impact.
        double since = s.ticksSinceImpact(now);
        if (!charging && !s.cancelled && since >= 0.0 && beamFade > 0.0F) {
            additive();
            Vector3f bottom = new Vector3f((float) sx, (float) (s.target.getY() - s.depth - cam.y), (float) sz);
            float flare = since < 8.0 ? (float) (1.0 + (8.0 - since) / 8.0 * 1.5) : 1.0F;
            float rb = s.radius * 0.32F * (1.0F + 0.06F * (float) Math.sin(now * 1.7)) * flare;
            beam(q.get(), view, origin, bottom, rb * 1.7F, 24, 0.35F, 0.75F, 1.0F, 0.16F * beamFade);
            beam(q.get(), view, origin, bottom, rb, 24, 0.6F, 0.9F, 1.0F, 0.35F * beamFade);
            beam(q.get(), view, origin, bottom, rb * 0.38F, 16, 1.0F, 1.0F, 1.0F, 0.85F * beamFade);
            // Muzzle glow in the palm.
            ps.pushPose();
            ps.translate(origin.x(), origin.y(), origin.z());
            cube(q.get(), ps.last().pose(), rb * 0.9F, 0.7F, 0.95F, 1.0F, 0.35F * beamFade * flicker);
            ps.popPose();
            q.draw();

            ps.pushPose();
            ps.translate(ground.x(), ground.y(), ground.z());
            Matrix4f g = ps.last().pose();
            disc(q.get(), g, 0.2F, s.radius * 0.7F, 32, 1.0F, 0.75F, 0.45F, 0.4F * beamFade, 0.0F);
            if (since < 45.0) {
                float t = (float) (since / 45.0);
                float outer = s.radius * (0.6F + (float) since * 0.12F);
                float width = 1.5F + (float) since * 0.25F;
                ring(q.get(), g, 0.6F, Math.max(0.0F, outer - width), outer, 64,
                        1.0F, 0.95F, 0.85F, 0.7F * (1.0F - t));
            }
            q.draw();
            ps.popPose();
        }

        RenderSystem.setShaderFogStart(oldFogStart);
    }

    /**
     * Draws the hand in two passes so it looks like it is behind a glass sky:
     * a translucent pass (seen through the glass), then a depth-only plane with a hole in it,
     * then the opaque pass, which therefore only shows the part that came through the hole.
     * Returns the palm center in camera-relative world space (for the core and the beam).
     */
    @Nullable
    private static Vector3f renderHand(PoseStack ps, ClientStrike s, HandModel model, HandAnimator.Frame frame,
                                      float scale, float fade, float holeR, double sx, double sy, double sz) {
        Quaternionf armRot = new Quaternionf()
                .rotationY((float) Math.toRadians(s.handYaw()))
                .rotateX((float) Math.toRadians(ARM_TILT));
        Vector3f d = armRot.transform(new Vector3f(0.0F, -1.0F, 0.0F));
        float e = frame.ext * scale;
        int n = model.bones.size();
        Matrix4f[] mats = new Matrix4f[n];
        Matrix3f[] norms = new Matrix3f[n];

        ps.pushPose();
        ps.translate(d.x() * e, d.y() * e, d.z() * e);
        ps.mulPose(armRot);
        ps.scale(scale, scale, scale);
        model.computeBones(new Matrix4f(ps.last().pose()), new Matrix3f(ps.last().normal()), frame.pose, mats, norms);
        ps.popPose();

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        int light = LightTexture.FULL_BRIGHT;

        // a) The hand behind the glass, faint and tinted like the sky.
        if (frame.ghostAlpha * fade > 0.01F) {
            RenderType ghost = RenderType.entityTranslucent(HandModel.TEXTURE);
            model.render(mats, norms, buffers.getBuffer(ghost), 0.62F, 0.72F, 0.92F, frame.ghostAlpha * fade, light);
            buffers.endBatch(ghost);
        }

        // b) Invisible sky plane (depth only) everywhere except the hole.
        prepareFx();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        Quads q = new Quads();
        ring(q.get(), ps.last().pose(), 0.0F, holeR, 6000.0F, 64, 0.0F, 0.0F, 0.0F, 1.0F);
        q.draw();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(false);

        // c) The hand itself, solid, only where it broke through.
        if (holeR > 0.01F) {
            RenderType solid = RenderType.entityCutoutNoCull(HandModel.TEXTURE);
            model.render(mats, norms, buffers.getBuffer(solid), 1.0F, 1.0F, 1.0F, 1.0F, light);
            buffers.endBatch(solid);
        }

        int hand = model.boneIndex("hand");
        if (hand < 0) {
            return null;
        }
        Matrix4f world = new Matrix4f()
                .translation((float) (sx + d.x() * e), (float) (sy + d.y() * e), (float) (sz + d.z() * e))
                .rotate(armRot)
                .scale(scale);
        Matrix4f[] wm = new Matrix4f[n];
        Matrix3f[] wn = new Matrix3f[n];
        model.computeBones(world, new Matrix3f(), frame.pose, wm, wn);
        return wm[hand].transformPosition(new Vector3f(0.0F, -0.55F, 0.34F));
    }

    private static void renderCore(PoseStack ps, Quads q, ClientStrike s, Vector3f at, double coreScale,
                                   float fade, float flicker, double now) {
        float h = (float) coreScale * 0.5F;
        float coreAlpha = 0.97F * fade;

        RenderSystem.defaultBlendFunc();
        ps.pushPose();
        ps.translate(at.x(), at.y(), at.z());
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

    /** Glass shards from the burst: fall with gravity, spin, and fade out. Sky-plane local space. */
    private static void renderShards(Quads q, Matrix4f m, ClientStrike s, double t, float scale, float fade) {
        if (t < 0.0 || t > 90.0) {
            return;
        }
        additive();
        Random r = new Random(s.seed ^ 0x9E3779B97F4A7C15L);
        float alpha = (float) (0.55 * (1.0 - t / 90.0)) * fade;
        Vector3f c0 = new Vector3f();
        Vector3f c1 = new Vector3f();
        Vector3f c2 = new Vector3f();
        for (int i = 0; i < 56; i++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            double d0 = Math.sqrt(r.nextDouble()) * scale * 1.1;
            double speed = 0.15 + r.nextDouble() * 0.55;
            double vy = -(0.1 + r.nextDouble() * 0.45);
            float size = (float) (1.0 + r.nextDouble() * scale * 0.22);
            float spinX = (r.nextFloat() - 0.5F) * 24.0F;
            float spinZ = (r.nextFloat() - 0.5F) * 24.0F;
            float a1 = 1.8F + r.nextFloat() * 0.9F;
            float a2 = 3.7F + r.nextFloat() * 0.9F;
            float k1 = 0.6F + r.nextFloat() * 0.6F;
            float k2 = 0.6F + r.nextFloat() * 0.6F;

            float x = (float) (Math.cos(a) * (d0 + speed * t));
            float z = (float) (Math.sin(a) * (d0 + speed * t));
            float y = (float) (-0.5 + vy * t - 0.5 * 0.045 * t * t);
            Quaternionf rot = new Quaternionf().rotationXYZ(
                    (float) Math.toRadians(spinX * t + i * 40), (float) a, (float) Math.toRadians(spinZ * t));
            rot.transform(c0.set(size, 0.0F, 0.0F));
            rot.transform(c1.set((float) Math.cos(a1) * size * k1, 0.0F, (float) Math.sin(a1) * size * k1));
            rot.transform(c2.set((float) Math.cos(a2) * size * k2, 0.0F, (float) Math.sin(a2) * size * k2));
            BufferBuilder b = q.get();
            v(b, m, x + c0.x(), y + c0.y(), z + c0.z(), 0.75F, 0.9F, 1.0F, alpha);
            v(b, m, x + c1.x(), y + c1.y(), z + c1.z(), 0.75F, 0.9F, 1.0F, alpha);
            v(b, m, x + c2.x(), y + c2.y(), z + c2.z(), 0.75F, 0.9F, 1.0F, alpha);
            v(b, m, x + c2.x(), y + c2.y(), z + c2.z(), 0.75F, 0.9F, 1.0F, alpha);
        }
        q.draw();
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

    /** Disc with a jagged, broken-glass edge. */
    private static void jaggedDisc(BufferBuilder b, Matrix4f m, float y, float radius, long seed,
                                   float r, float g, float bl, float a) {
        Random rnd = new Random(seed * 13L + 5L);
        int segs = 30;
        float[] rad = new float[segs];
        for (int i = 0; i < segs; i++) {
            rad[i] = radius * (0.72F + rnd.nextFloat() * 0.5F);
        }
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % segs;
            double a0 = i * Math.PI * 2.0 / segs;
            double a1 = j * Math.PI * 2.0 / segs;
            v(b, m, 0.0F, y, 0.0F, r, g, bl, a);
            v(b, m, (float) Math.cos(a0) * rad[i], y, (float) Math.sin(a0) * rad[i], r, g, bl, a);
            v(b, m, (float) Math.cos(a1) * rad[j], y, (float) Math.sin(a1) * rad[j], r, g, bl, a);
            v(b, m, 0.0F, y, 0.0F, r, g, bl, a);
        }
    }

    /** Open cylinder between two arbitrary points. */
    private static void beam(BufferBuilder b, Matrix4f m, Vector3f p0, Vector3f p1, float radius, int segs,
                             float r, float g, float bl, float a) {
        Vector3f dir = new Vector3f(p1).sub(p0);
        if (dir.lengthSquared() < 1.0E-6F) {
            return;
        }
        dir.normalize();
        Vector3f ref = Math.abs(dir.y()) < 0.99F ? new Vector3f(0.0F, 1.0F, 0.0F) : new Vector3f(1.0F, 0.0F, 0.0F);
        Vector3f u = new Vector3f(dir).cross(ref).normalize();
        Vector3f w = new Vector3f(dir).cross(u).normalize();
        for (int i = 0; i < segs; i++) {
            double a0 = i * Math.PI * 2.0 / segs;
            double a1 = (i + 1) * Math.PI * 2.0 / segs;
            float c0 = (float) Math.cos(a0) * radius;
            float s0 = (float) Math.sin(a0) * radius;
            float c1 = (float) Math.cos(a1) * radius;
            float s1 = (float) Math.sin(a1) * radius;
            float ox0 = u.x() * c0 + w.x() * s0;
            float oy0 = u.y() * c0 + w.y() * s0;
            float oz0 = u.z() * c0 + w.z() * s0;
            float ox1 = u.x() * c1 + w.x() * s1;
            float oy1 = u.y() * c1 + w.y() * s1;
            float oz1 = u.z() * c1 + w.z() * s1;
            v(b, m, p0.x() + ox0, p0.y() + oy0, p0.z() + oz0, r, g, bl, a);
            v(b, m, p0.x() + ox1, p0.y() + oy1, p0.z() + oz1, r, g, bl, a);
            v(b, m, p1.x() + ox1, p1.y() + oy1, p1.z() + oz1, r, g, bl, a);
            v(b, m, p1.x() + ox0, p1.y() + oy0, p1.z() + oz0, r, g, bl, a);
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
