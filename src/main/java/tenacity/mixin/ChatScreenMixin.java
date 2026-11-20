package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.hud.Hud;
import tenacity.ui.Overlay;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    /** Scrolling over a HUD widget while the chat is open resizes it instead of scrolling the chat. */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void tenacity$resize(double mouseX, double mouseY, double h, double v, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float s = Overlay.hudScale();
        if (Hud.scroll((float) mc.mouse.getX() / s, (float) mc.mouse.getY() / s, v)) cir.setReturnValue(true);
    }
}
