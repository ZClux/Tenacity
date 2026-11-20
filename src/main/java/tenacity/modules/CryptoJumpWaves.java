package tenacity.modules;

import crypto.visual.WaveRenderer;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import voided.events.impl.WorldRenderEvent;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crypto's JumpCircle (crypto.visual.JumpWaves), ported as is: a refractive shockwave that bends the ground where a player
 * takes off (surface aligned, hidden behind blocks) plus a crisp ring, with the same expand / fade envelope.
 */
final class CryptoJumpWaves extends Module {
    private final Setting.Mode style = mode("Style", "Both", "Shockwave", "Ring", "Both", "Circle");
    private final Setting.Num maxSize = num("Circle Size", 2, 1, 2, 0.1);
    private final Setting.Num speed = num("Circle Speed", 2000, 1000, 2000, 50);
    private final Setting.Bool glow = bool("Circle Glow", true);
    private final Setting.Num lineWidth = num("Ring Width", 2, 1, 5, 0.5);
    private final Setting.Bool self = bool("Self", true);
    private final Setting.Bool others = bool("Other Players", false);
    private final Setting.Mode color = mode("Color", "White", "White", "Purple", "Ice", "Rose", "Chroma");
    private final Setting.Num radius = num("Radius", 1.5, 0.5, 2.5, 0.1);
    private final Setting.Num width = num("Distortion Width", 1, 0.05, 1.5, 0.05);
    private final Setting.Num strength = num("Strength", 2, 0.1, 2, 0.1);
    private final Setting.Num expand = num("Expand", 800, 100, 800, 25);
    private final Setting.Num fade = num("Fade", 1500, 200, 1500, 25);

    private record Ground(boolean grounded, double x, double y, double z, double previousY) {
    }

    private record Wave(Vec3d at, long born, float radius, float width, float strength, long expand, long fade, int color) {
    }

    private final Map<UUID, Ground> ground = new HashMap<>();
    private final List<Wave> waves = new ArrayList<>();
    private Object world;
    private final WaveRenderer renderer = new WaveRenderer();

    CryptoJumpWaves() {
        super("Jump Circles", "Crypto's refractive jump shockwave with a ring, surface aligned and hidden behind blocks", Category.RENDER);
    }

    @Override
    public void tick() {
        if (mc.world != world) {
            ground.clear();
            waves.clear();
            world = mc.world;
        }
        Set<UUID> present = new HashSet<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            present.add(player.getUuid());
            Ground old = ground.get(player.getUuid());
            boolean onGround = player.isOnGround();
            if (old != null && old.grounded && !onGround && (player.getY() - old.previousY > .02 || player.getVelocity().y > 0)
                    && (player == mc.player ? self.on() : others.on())) {
                waves.add(new Wave(new Vec3d(old.x, surface(old.x, old.y, old.z), old.z), System.currentTimeMillis(), radius.f(), width.f(),
                        strength.f(), expand.i(), fade.i(), VisualModules.palette(color, 0f)));
                if (waves.size() > 32) waves.removeFirst();
            }
            ground.put(player.getUuid(), new Ground(onGround, onGround || old == null ? player.getX() : old.x,
                    onGround || old == null ? player.getY() : old.y, onGround || old == null ? player.getZ() : old.z, player.getY()));
        }
        ground.keySet().retainAll(present);
    }

    private double surface(double x, double y, double z) {
        BlockPos block = BlockPos.ofFloored(x, y, z);
        double localX = x - block.getX(), localZ = z - block.getZ(), top = Double.NEGATIVE_INFINITY;
        for (int yy = block.getY() + 1; yy >= block.getY() - 2; yy--) {
            BlockPos pos = new BlockPos(block.getX(), yy, block.getZ());
            var state = mc.world.getBlockState(pos);
            for (Box box : state.getOutlineShape(mc.world, pos).getBoundingBoxes()) {
                if (localX < box.minX || localX > box.maxX || localZ < box.minZ || localZ > box.maxZ) continue;
                double candidate = yy + box.maxY;
                if ((Math.abs(candidate - y) <= .01 || (state.isOf(Blocks.SNOW) && candidate >= y - .01 && candidate <= y + .13)) && candidate > top)
                    top = candidate;
            }
        }
        return (top == Double.NEGATIVE_INFINITY ? y : top) + .01;
    }

    private static float[] envelope(long age, long expansion, long fading, float radius, float width, float strength) {
        float r, a;
        if (age < expansion) {
            float t = (float) age / expansion, s = 1 - (1 - t) * (1 - t) * (1 - t);
            r = radius * s;
            a = s;
        } else {
            float t = Math.clamp((float) (age - expansion) / fading, 0, 1);
            r = radius * (1 + t * .18f);
            a = (1 - t) * (1 - t);
        }
        float t = Math.clamp((float) age / (expansion + fading), 0, 1);
        return new float[]{r, width * (1 - .5f * t), strength * a, a};
    }

    @Override
    public void renderWorld(WorldRenderEvent event) {
        long now = System.currentTimeMillis();
        waves.removeIf(w -> now - w.born > (style.is("Circle") ? speed.i() : w.expand + w.fade));
        if (waves.isEmpty()) return;
        if (style.is("Circle")) {
            circles(event);
            return;
        }
        List<WaveRenderer.Ring> rings = new ArrayList<>();
        var camera = mc.gameRenderer.getCamera().getCameraPos();
        for (Wave w : waves) {
            if (rings.size() == 12) break;
            float[] p = envelope(now - w.born, w.expand, w.fade, w.radius, w.width, w.strength);
            if (p[0] <= .001 || p[3] <= .001) continue;
            Vec3d at = w.at.subtract(camera);
            rings.add(new WaveRenderer.Ring((float) at.x, (float) at.y, (float) at.z, p[0], p[1], p[2], w.color, p[3]));
        }
        if (!style.is("Ring")) {
            Matrix4f inverse = new Matrix4f(Render3D.lastProjMat).mul(Render3D.lastModMat).mul(Render3D.lastWorldSpaceMatrix).invert();
            renderer.render(inverse, rings);
        }
        if (!style.is("Shockwave")) for (Wave w : waves) {
            float[] p = envelope(now - w.born, w.expand, w.fade, w.radius, w.width, w.strength);
            if (p[0] < .01 || p[3] < .01) continue;
            int tint = (Math.round(255 * p[3]) << 24) | (w.color & 0xFFFFFF);
            for (int i = 0; i < 96; i++) {
                double a = i * Math.PI / 48, b = (i + 1) * Math.PI / 48;
                Render3D.drawLine(w.at.add(Math.cos(a) * p[0], .015, Math.sin(a) * p[0]), w.at.add(Math.cos(b) * p[0], .015, Math.sin(b) * p[0]), tint, lineWidth.f(), true);
            }
        }
    }

    // ---------------------------------------------------------------- Crypto's JumpCircle (voided.modules.impl.render.JumpCircle)
    private static final net.minecraft.util.Identifier CIRCLE = net.minecraft.util.Identifier.of("voided", "images/circle/circle.png");
    private static final net.minecraft.util.Identifier GLOW = net.minecraft.util.Identifier.of("voided", "images/particle/glow.png");
    private static final int SEGMENTS = 64;

    private void circles(WorldRenderEvent e) {
        long now = System.currentTimeMillis(), maxTime = speed.i();
        var stack = e.getStack() != null ? e.getStack() : new net.minecraft.client.util.math.MatrixStack();
        var immediate = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        for (Wave w : waves) {
            float life = now - w.born;
            float progress = Math.min(life / maxTime, 1f);
            if (progress >= 1f) continue;
            float scale = bounceOut(progress) * maxSize.f();
            float alpha;
            if (progress < .15f) alpha = progress / .15f;
            else if (progress >= .85f) {
                float fo = (progress - .85f) / .15f;
                alpha = 1f - fo + (float) (Math.sin((progress - .65f) / .2f * Math.PI * 3) * .3 + .3) * (1f - fo);
            } else if (progress > .65f) alpha = 1f + (float) (Math.sin((progress - .65f) / .2f * Math.PI * 3) * .3 + .3);
            else alpha = 1f;
            alpha = Math.max(0f, Math.min(1f, alpha));
            float rot = life / 1000f * .5f * 360f;
            int c1 = w.color, c2 = 0xFFFFFFFF;
            if (glow.on()) {
                float ga = alpha * .1f;
                for (int layer = 0; layer < 3; layer++) {
                    float size = scale * (1.3f + layer * .4f), la = ga * (.35f - layer * .1f), radius = size / 2f;
                    var buf = immediate.getBuffer(voided.util.render.сliemtpipeline.ClientPipelines.BLOOM_ESP.apply(GLOW));
                    for (int i = 0; i < 16; i++) {
                        float an = (float) (2 * Math.PI * i / 16);
                        int col = grad(c1, c2, ((float) i / 16 + rot / 360f) % 1f, la);
                        quad(stack, buf, w.at.add(Math.cos(an) * radius * .8f, 0, Math.sin(an) * radius * .8f), size * .4f, col, cam);
                    }
                }
                int core = lerp(mult(c1, ga * .2f), mult(c2, ga * .2f), .5f);
                quad(stack, immediate.getBuffer(voided.util.render.сliemtpipeline.ClientPipelines.BLOOM_ESP.apply(GLOW)), w.at, scale * 2.5f, core, cam);
            }
            var buf = immediate.getBuffer(voided.util.render.сliemtpipeline.ClientPipelines.BLOOM_ESP.apply(CIRCLE));
            stack.push();
            stack.translate((float) (w.at.x - cam.x), (float) (w.at.y - cam.y), (float) (w.at.z - cam.z));
            stack.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(90f));
            Matrix4f m = stack.peek().getPositionMatrix();
            float r = scale / 2f;
            for (int i = 0; i < SEGMENTS; i++) {
                float a1 = (float) (2 * Math.PI * i / SEGMENTS), a2 = (float) (2 * Math.PI * (i + 1) / SEGMENTS);
                int cc = grad(c1, c2, ((float) i / SEGMENTS + rot / 360f) % 1f, alpha);
                int nc = grad(c1, c2, ((float) (i + 1) / SEGMENTS + rot / 360f) % 1f, alpha);
                float x1 = (float) Math.cos(a1) * r, z1 = (float) Math.sin(a1) * r, x2 = (float) Math.cos(a2) * r, z2 = (float) Math.sin(a2) * r;
                float u1 = (float) (.5 + .5 * Math.cos(a1)), v1 = (float) (.5 + .5 * Math.sin(a1));
                float u2 = (float) (.5 + .5 * Math.cos(a2)), v2 = (float) (.5 + .5 * Math.sin(a2));
                buf.vertex(m, 0, 0, 0).texture(.5f, .5f).color(lerp(cc, nc, .5f));
                buf.vertex(m, x1, z1, 0).texture(u1, v1).color(cc);
                buf.vertex(m, x2, z2, 0).texture(u2, v2).color(nc);
                buf.vertex(m, x2, z2, 0).texture(u2, v2).color(nc);
            }
            stack.pop();
        }
        immediate.draw();
    }

    private static void quad(net.minecraft.client.util.math.MatrixStack stack, net.minecraft.client.render.VertexConsumer buf, Vec3d pos, float size, int color, Vec3d cam) {
        stack.push();
        stack.translate((float) (pos.x - cam.x), (float) (pos.y - cam.y), (float) (pos.z - cam.z));
        stack.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(90f));
        Matrix4f m = stack.peek().getPositionMatrix();
        float h = size / 2f;
        buf.vertex(m, -h, -h, 0).texture(0, 0).color(color);
        buf.vertex(m, h, -h, 0).texture(1, 0).color(color);
        buf.vertex(m, h, h, 0).texture(1, 1).color(color);
        buf.vertex(m, -h, h, 0).texture(0, 1).color(color);
        stack.pop();
    }

    private static int grad(int c1, int c2, float t, float alpha) {
        return mult(lerp(c1, c2, t <= .5f ? t * 2f : (1f - t) * 2f), alpha);
    }

    private static int lerp(int a, int b, float t) {
        int r = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int x = (a >>> sh) & 255, y = (b >>> sh) & 255;
            r |= Math.round(x + (y - x) * t) << sh;
        }
        return r;
    }

    private static int mult(int c, float a) {
        return Math.round(((c >>> 24) & 255) * Math.max(0, Math.min(1, a))) << 24 | (c & 0xFFFFFF);
    }

    private static float bounceOut(float v) {
        float n1 = 7.5625f, d1 = 2.75f;
        if (v < 1f / d1) return n1 * v * v;
        if (v < 2f / d1) return n1 * (v -= 1.5f / d1) * v + .75f;
        if (v < 2.5f / d1) return n1 * (v -= 2.25f / d1) * v + .9375f;
        return n1 * (v -= 2.625f / d1) * v + .984375f;
    }

    @Override
    protected void onDisable() {
        waves.clear();
        ground.clear();
        renderer.close();
    }
}
