package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.equipment.EquipmentModel;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.RenderFlags;

/** Player Transparency also covers the armor: it is drawn with the translucent armor layer and the same frosted tint. */
@Mixin(EquipmentRenderer.class)
public abstract class ArmorAlphaMixin {
    @Unique
    private static boolean tenacity$glass;

    @Inject(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At("HEAD"))
    private void tenacity$begin(EquipmentModel.LayerType type, RegistryKey<EquipmentAsset> key, Model<?> model, Object state, ItemStack stack, MatrixStack matrices,
                                OrderedRenderCommandQueue queue, int light, Identifier texture, int outline, int seed, CallbackInfo ci) {
        tenacity$glass = false;
        if (RenderFlags.playerAlpha >= 1f || !(state instanceof PlayerEntityRenderState ps) || ps.invisible) return;
        var mc = MinecraftClient.getInstance();
        boolean self = mc.player != null && ps.id == mc.player.getId();
        tenacity$glass = self ? RenderFlags.alphaSelf : RenderFlags.alphaOthers;
    }

    @Redirect(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;armorCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;"))
    private RenderLayer tenacity$layer(Identifier id) {
        return tenacity$glass ? RenderLayers.armorTranslucent(id) : RenderLayers.armorCutoutNoCull(id);
    }

    @ModifyArg(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
            index = 6)
    private int tenacity$color(int color) {
        if (!tenacity$glass) return color;
        return RenderFlags.glassColor(color);
    }
}
