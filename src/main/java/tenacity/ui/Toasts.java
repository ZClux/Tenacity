package tenacity.ui;

import tenacity.gfx.Icons;
import tenacity.gfx.Nvg;

import java.util.ArrayList;
import java.util.List;

/** Small glass notifications at the top of the screen. */
public final class Toasts {
    private static final class Toast {
        String title, body, icon;
        long born = System.currentTimeMillis();
        long life = 2800;
        float anim;
        boolean dead;
    }

    private static final List<Toast> LIST = new ArrayList<>();

    private Toasts() {
    }

    public static void push(String icon, String title, String body) {
        synchronized (LIST) {
            for (Toast t : LIST) if (t.title.equals(title) && t.body.equals(body) && !t.dead) {
                t.born = System.currentTimeMillis();
                return;
            }
            Toast t = new Toast();
            t.icon = icon;
            t.title = title;
            t.body = body;
            LIST.add(t);
            if (LIST.size() > 5) LIST.remove(0);
        }
    }

    public static boolean any() {
        synchronized (LIST) {
            return !LIST.isEmpty();
        }
    }

    public static void render(float dt) {
        // mentions from the global chat arrive on a network thread; turn them into toasts here
        while (!tenacity.core.Chat.MENTIONS.isEmpty()) {
            String[] m = tenacity.core.Chat.MENTIONS.remove(0);
            String t = m[1].length() > 48 ? m[1].substring(0, 47) + "..." : m[1];
            push("chat", m[0] + " mentioned you", t);
        }
        synchronized (LIST) {
            if (LIST.isEmpty()) return;
            long now = System.currentTimeMillis();
            float y = 52;
            for (var it = LIST.iterator(); it.hasNext(); ) {
                Toast t = it.next();
                boolean alive = now - t.born < t.life;
                t.anim = Anim.approach(t.anim, alive ? 1f : 0f, 9f);
                if (!alive && t.anim < 0.01f) {
                    it.remove();
                    continue;
                }
                float tw = Math.max(Nvg.textWidth(t.title, 12.5f, Nvg.SEMI), Nvg.textWidth(t.body, 11f, Nvg.REG));
                float w = tw + 62, h = 44;
                float e = Anim.easeOut(t.anim);
                float x = Nvg.w / 2f - w / 2f;
                float yy = y - (1 - e) * 24;
                Theme.panel(x, yy, w, h, 14, e, 14);
                Nvg.save();
                Nvg.fade(e);
                if (t.icon.equals("chat")) ChatPanel.drawBubble(x + 14, yy + h / 2f - 10, 20, Theme.text());
                else Icons.draw(t.icon, x + 14, yy + h / 2f - 10, 20, Theme.text());
                Nvg.text(t.title, x + 44, yy + 17, 12.5f, Nvg.SEMI, Nvg.LEFT, Theme.text());
                Nvg.text(t.body, x + 44, yy + 31, 11f, Nvg.REG, Nvg.LEFT, Theme.dim());
                Nvg.restore();
                y += (h + 8) * e;
            }
        }
    }
}
