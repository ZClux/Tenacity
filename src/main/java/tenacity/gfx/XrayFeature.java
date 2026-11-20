package tenacity.gfx;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the posed matrices of a player's model parts (head, body, arms, legs) every frame, camera relative, so the X-Ray
 * skeleton can be drawn exactly inside the real model.
 */
public final class XrayFeature extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    /** Entity id -> part matrices (head, body, right arm, left arm, right leg, left leg); null entries are hidden parts. */
    public static final Map<Integer, Matrix4f[]> CAPTURES = new ConcurrentHashMap<>();
    public static volatile boolean enabled, self;
    public static volatile double rangeSq = 96 * 96;

    public XrayFeature(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light, PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        if (!enabled) return;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null && state.id == mc.player.getId() && (!self || mc.options.getPerspective().isFirstPerson())) return;
        if (state.squaredDistanceToCamera > rangeSq || state.invisible) return;
        PlayerEntityModel model = getContextModel();
        var parts = new net.minecraft.client.model.ModelPart[]{model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
        Matrix4f[] out = new Matrix4f[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].visible) continue;
            matrices.push();
            parts[i].applyTransform(matrices);
            matrices.scale(0.0625f, 0.0625f, 0.0625f);
            out[i] = new Matrix4f(matrices.peek().getPositionMatrix());
            matrices.pop();
        }
        CAPTURES.put(state.id, out);
    }
}
