package tenacity.ui;

/**
 * Keeps dragged things where you put them when the window (or the ui scale) changes size: something near the left / top edge
 * keeps its distance to that edge, something near the right / bottom edge keeps its distance to that edge, and something in
 * the middle keeps its relative position.
 */
public final class Anchor {
    private Anchor() {
    }

    /** New start coordinate of an item of length {@code size} at {@code pos} when the screen goes from oldTotal to newTotal. */
    public static float remap(float pos, float size, float oldTotal, float newTotal) {
        if (oldTotal <= 1 || newTotal <= 1 || Math.abs(oldTotal - newTotal) < 0.01f) return pos;
        float centre = pos + size / 2f;
        if (centre < oldTotal / 3f) return pos;                                   // hugs the left / top edge
        if (centre > oldTotal * 2f / 3f) return newTotal - (oldTotal - pos);       // hugs the right / bottom edge
        return centre / oldTotal * newTotal - size / 2f;                           // in the middle: stays proportional
    }
}
