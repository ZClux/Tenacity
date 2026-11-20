package tenacity.ui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import tenacity.core.Config;

/** The click gui screen. All drawing happens in {@link Overlay}; this class only forwards input. */
public final class TenacityScreen extends Screen {
    public TenacityScreen() {
        super(Text.literal("Tenacity"));
    }

    @Override
    protected void init() {
        ClickGui.onOpen();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // No world behind a custom screen opened from the title menu: keep the panorama alive so the glass has a scene to refract.
        if (client != null && client.world == null) renderPanoramaBackground(context, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }

    @Override
    protected void applyBlur(DrawContext context) {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (ClickGui.captureMouse(click.button())) return true;
        switch (click.button()) {
            case 0 -> {
                ClickGui.down = true;
                ClickGui.pressed = true;
            }
            case 1 -> ClickGui.rpressed = true;
            case 2 -> ClickGui.mpressed = true;
            default -> {
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            ClickGui.down = false;
            ClickGui.released = true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        ClickGui.scroll += (float) verticalAmount;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        ClickGui.keys.add(input.key());
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar()) ClickGui.typed.appendCodePoint(input.codepoint());
        return true;
    }

    @Override
    public void removed() {
        ClickGui.resetInput();
        Config.save();
    }
}
