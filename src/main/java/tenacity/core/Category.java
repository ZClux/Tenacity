package tenacity.core;

public enum Category {
    COMBAT("Combat", "combat"),
    MOVEMENT("Movement", "movement"),
    RENDER("Render", "render"),
    PLAYER("Player", "player"),
    BASE("Base Finding", "base"),
    DONUT("Donut", "donut"),
    HUD("HUD", "hud");

    public final String label;
    public final String icon;

    Category(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }
}
