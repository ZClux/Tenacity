package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

/** Glow ESP: vanilla's outline pass draws every entity this returns true for. */
@Mixin(MinecraftClient.class)
public abstract class RenderTweaksMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void tenacity$glow(Entity e, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (tenacity.gfx.FrostFx.active() && e instanceof PlayerEntity && !e.isInvisible()
                && (e == mc.player ? RenderFlags.alphaSelf && !mc.options.getPerspective().isFirstPerson() : RenderFlags.alphaOthers)) {
            cir.setReturnValue(true);
            return;
        }
        if (e == mc.player) return;
        if ((RenderFlags.glowPlayers && e instanceof PlayerEntity) || (RenderFlags.glowMobs && e instanceof MobEntity)
                || (RenderFlags.glowItems && e instanceof ItemEntity)) cir.setReturnValue(true);
    }
}
