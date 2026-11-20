package tenacity.hud;

import com.google.gson.JsonObject;
import tenacity.core.Category;
import tenacity.core.Module;

/** A draggable heads up display widget. Positions are logical units; right anchored widgets store their right edge. */
public abstract class HudElement extends Module {
    public float x = -1, y = -1;
    public float w, h;
    public boolean anchorRight;
    /** 0..1 fade used when the widget is enabled / disabled. */
    public float shown;
    /** User size multiplier (scroll wheel in chat). */
    public float scale = 1f, scaleAnim = 1f;
    /** Screen size the position was set on (0 = unknown); used to keep the widget in place when the window resizes. */
    public float refW, refH;
    /** Smoothed draw position. */
    public float dx = Float.NaN, dy;

    protected HudElement(String name, String description) {
        super(name, description, Category.HUD);
    }

    /** Computes w and h for this frame. */
    public abstract void measure();

    /** Draws with the top-left corner at (x, y). */
    public abstract void draw(float x, float y, float alpha);

    /** Default position, called when nothing was saved yet. */
    public abstract void defaults(float screenW, float screenH);

    public float left() {
        return anchorRight ? x - sw() : x;
    }

    public float sw() {
        return w * scaleAnim;
    }

    public float sh() {
        return h * scaleAnim;
    }

    @Override
    public boolean showInList() {
        return false;
    }

    @Override
    public void saveExtra(JsonObject o) {
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("scale", scale);
        o.addProperty("refW", refW);
        o.addProperty("refH", refH);
    }

    @Override
    public void loadExtra(JsonObject o) {
        if (o.has("x")) x = o.get("x").getAsFloat();
        if (o.has("y")) y = o.get("y").getAsFloat();
        if (o.has("scale")) scale = scaleAnim = o.get("scale").getAsFloat();
        if (o.has("refW")) refW = o.get("refW").getAsFloat();
        if (o.has("refH")) refH = o.get("refH").getAsFloat();
    }
}
