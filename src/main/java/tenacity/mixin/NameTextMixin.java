package tenacity.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.NameCore;

/**
 * Central text hook for Name Protect and Name Scrambler. Every Minecraft text (chat, tab list, nametags, scoreboard,
 * boss bars, titles, action bar, signs, toasts, death messages, F3, server list...) ends in one of these overloads.
 */
@Mixin(TextRenderer.class)
public abstract class NameTextMixin {
    @ModifyVariable(method = "prepare(Ljava/lang/String;FFIZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;", at = @At("HEAD"), argsOnly = true)
    private String tenacity$prepareString(String s) {
        return NameCore.anyActive() ? NameCore.string(s) : s;
    }

    @ModifyVariable(method = "prepare(Lnet/minecraft/text/OrderedText;FFIZZI)Lnet/minecraft/client/font/TextRenderer$GlyphDrawable;", at = @At("HEAD"), argsOnly = true)
    private OrderedText tenacity$prepareOrdered(OrderedText t) {
        return NameCore.anyActive() ? NameCore.ordered(t) : t;
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true)
    private String tenacity$widthString(String s) {
        return NameCore.anyActive() ? NameCore.string(s) : s;
    }

    @ModifyVariable(method = "getWidth(Lnet/minecraft/text/OrderedText;)I", at = @At("HEAD"), argsOnly = true)
    private OrderedText tenacity$widthOrdered(OrderedText t) {
        return NameCore.anyActive() ? NameCore.ordered(t) : t;
    }

    /** StringVisitable widths (wrapping, trimming) go through the ordered form so they match what is drawn. */
    @Inject(method = "getWidth(Lnet/minecraft/text/StringVisitable;)I", at = @At("HEAD"), cancellable = true)
    private void tenacity$widthVisitable(StringVisitable v, CallbackInfoReturnable<Integer> cir) {
        if (!NameCore.anyActive() || !(v instanceof Text t)) return;
        OrderedText o = NameCore.ordered(t.asOrderedText());
        if (o != t.asOrderedText()) cir.setReturnValue(((TextRenderer) (Object) this).getWidth(o));
    }
}
