package tenacity.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.NameCore;

/** Marks the chat layout/draw so the scrambler's "Chat Only" scope knows where it is. */
@Mixin(ChatHud.class)
public abstract class NameChatMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V", at = @At("HEAD"))
    private void tenacity$chatStart(CallbackInfo ci) {
        NameCore.inChat = true;
    }

    @Inject(method = "render(Lnet/minecraft/client/gui/hud/ChatHud$Backend;IIZ)V", at = @At("RETURN"))
    private void tenacity$chatEnd(CallbackInfo ci) {
        NameCore.inChat = false;
    }
}
