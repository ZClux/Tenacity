package tenacity.mixin;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

@Mixin(World.class)
public abstract class WorldWeatherMixin {
    @Inject(method = "getRainGradient", at = @At("HEAD"), cancellable = true)
    private void tenacity$rain(float delta, CallbackInfoReturnable<Float> cir) {
        if (RenderFlags.noWeather && ((World) (Object) this).isClient()) cir.setReturnValue(0f);
    }

    @Inject(method = "getThunderGradient", at = @At("HEAD"), cancellable = true)
    private void tenacity$thunder(float delta, CallbackInfoReturnable<Float> cir) {
        if (RenderFlags.noWeather && ((World) (Object) this).isClient()) cir.setReturnValue(0f);
    }
}
