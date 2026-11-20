package tenacity.gfx;

/** Vector icons on a 24 unit grid, drawn with NanoVG paths (crisp at any size). */
public final class Icons {
    private static final float SW = 1.9f;

    private Icons() {
    }

    public static void draw(String id, float x, float y, float size, int c) {
        Nvg.save();
        Nvg.translate(x, y);
        float k = size / 24f;
        Nvg.scale(k, k);
        switch (id) {
            case "combat" -> {
                // sword, pointing up-right
                Nvg.save();
                Nvg.translate(12, 12);
                Nvg.rotate((float) (Math.PI / 4));
                Nvg.beginPath();
                Nvg.moveTo(-2f, 3.2f); Nvg.lineTo(-2f, -7.6f); Nvg.lineTo(0f, -11f); Nvg.lineTo(2f, -7.6f); Nvg.lineTo(2f, 3.2f);
                Nvg.closePath();
                Nvg.strokePath(1.7f, c);
                Nvg.line(0, -7.2f, 0, 1.8f, 1.1f, Nvg.alpha(c, 0.75f));
                Nvg.beginPath(); Nvg.rrectPath(-5.6f, 3.4f, 11.2f, 2.4f, 1.2f); Nvg.fillPath(c);
                Nvg.beginPath(); Nvg.rrectPath(-1.1f, 5.6f, 2.2f, 4.4f, 0.8f); Nvg.fillPath(c);
                Nvg.circle(0, 11f, 1.7f, c);
                Nvg.restore();
            }
            case "movement" -> {
                // sneaker
                Nvg.beginPath();
                Nvg.moveTo(4.5f, 6f); Nvg.lineTo(10.5f, 6f); Nvg.lineTo(11.5f, 11f);
                Nvg.bezierTo(14f, 12.5f, 18.5f, 13f, 20f, 15.2f);
                Nvg.lineTo(20f, 18.5f); Nvg.lineTo(4.5f, 18.5f); Nvg.closePath();
                Nvg.strokePath(SW, c);
                line(4.5f, 15.5f, 20f, 15.5f, c, 1.4f);
                line(10.8f, 9.4f, 13f, 8.4f, c, 1.3f);
                line(12f, 11.2f, 14.2f, 10.2f, c, 1.3f);
            }
            case "globe" -> {
                Nvg.circleStroke(12, 12, 9f, SW, c);
                Nvg.beginPath();
                Nvg.moveTo(12, 3); Nvg.bezierTo(7.5f, 7.5f, 7.5f, 16.5f, 12, 21);
                Nvg.moveTo(12, 3); Nvg.bezierTo(16.5f, 7.5f, 16.5f, 16.5f, 12, 21);
                Nvg.strokePath(SW * 0.8f, c);
                line(3.2f, 12, 20.8f, 12, c, SW * 0.8f);
            }
            case "power" -> {
                Nvg.beginPath();
                Nvg.arcPath(12, 13, 8f, (float) Math.toRadians(-55), (float) Math.toRadians(235));
                Nvg.strokePath(SW, c);
                line(12, 3.5f, 12, 11.5f, c);
            }
            case "code" -> {
                Nvg.beginPath();
                Nvg.moveTo(8.5f, 6.5f); Nvg.lineTo(3f, 12f); Nvg.lineTo(8.5f, 17.5f);
                Nvg.moveTo(15.5f, 6.5f); Nvg.lineTo(21f, 12f); Nvg.lineTo(15.5f, 17.5f);
                Nvg.strokePath(SW, c);
                line(13.6f, 4.5f, 10.4f, 19.5f, c);
            }
            case "crown" -> {
                Nvg.beginPath();
                Nvg.moveTo(3.5f, 8f); Nvg.lineTo(8f, 12f); Nvg.lineTo(12f, 5f); Nvg.lineTo(16f, 12f); Nvg.lineTo(20.5f, 8f);
                Nvg.lineTo(18.5f, 18f); Nvg.lineTo(5.5f, 18f); Nvg.closePath();
                Nvg.strokePath(SW, c);
                line(6f, 20.5f, 18f, 20.5f, c);
            }
            case "gauge" -> {
                Nvg.beginPath();
                Nvg.arcPath(12, 14, 8.5f, (float) Math.PI, (float) (Math.PI * 2));
                Nvg.strokePath(SW, c);
                line(12, 14, 16.2f, 9.2f, c);
                dot(12, 14, 1.6f, c);
            }
            case "chip" -> {
                Nvg.beginPath();
                Nvg.rrectPath(6, 6, 12, 12, 2.5f);
                Nvg.strokePath(SW, c);
                Nvg.beginPath();
                Nvg.rrectPath(9.5f, 9.5f, 5, 5, 1);
                Nvg.fillPath(c);
                for (int i = 0; i < 3; i++) {
                    float o = 9f + i * 3f;
                    line(o, 2.8f, o, 6, c, 1.4f); line(o, 18, o, 21.2f, c, 1.4f);
                    line(2.8f, o, 6, o, c, 1.4f); line(18, o, 21.2f, o, c, 1.4f);
                }
            }
            case "memory" -> {
                Nvg.beginPath();
                Nvg.rrectPath(3, 7, 18, 9, 2);
                Nvg.strokePath(SW, c);
                for (int i = 0; i < 4; i++) {
                    Nvg.beginPath();
                    Nvg.rrectPath(5.5f + i * 3.6f, 9.5f, 2.2f, 4, 0.6f);
                    Nvg.fillPath(c);
                }
                for (int i = 0; i < 5; i++) line(5 + i * 3.5f, 16, 5 + i * 3.5f, 19, c, 1.4f);
            }
            case "clock" -> {
                Nvg.circleStroke(12, 12, 8.5f, SW, c);
                line(12, 12, 12, 7.2f, c);
                line(12, 12, 15.2f, 14, c);
            }
            case "signal" -> {
                for (int i = 0; i < 4; i++) {
                    Nvg.beginPath();
                    Nvg.rrectPath(4 + i * 4.4f, 18 - (3 + i * 3.5f), 2.8f, 3 + i * 3.5f, 0.9f);
                    Nvg.fillPath(c);
                }
            }
            case "render" -> {
                Nvg.beginPath();
                Nvg.moveTo(2f, 12f); Nvg.bezierTo(5f, 6.2f, 8.4f, 4.6f, 12f, 4.6f);
                Nvg.bezierTo(15.6f, 4.6f, 19f, 6.2f, 22f, 12f); Nvg.bezierTo(19f, 17.8f, 15.6f, 19.4f, 12f, 19.4f);
                Nvg.bezierTo(8.4f, 19.4f, 5f, 17.8f, 2f, 12f); Nvg.closePath();
                Nvg.strokePath(SW, c);
                Nvg.circleStroke(12, 12, 3.2f, SW, c);
            }
            case "player" -> {
                Nvg.circleStroke(12, 8, 4f, SW, c);
                Nvg.beginPath();
                Nvg.moveTo(4.5f, 20.5f); Nvg.bezierTo(4.5f, 15.5f, 8f, 14f, 12f, 14f);
                Nvg.bezierTo(16f, 14f, 19.5f, 15.5f, 19.5f, 20.5f);
                Nvg.strokePath(SW, c);
            }
            case "base" -> {
                Nvg.circleStroke(10.5f, 10.5f, 6.8f, SW, c);
                line(15.5f, 15.5f, 21f, 21f, c);
                Nvg.beginPath();
                Nvg.moveTo(7.4f, 11.4f); Nvg.lineTo(10.5f, 8.2f); Nvg.lineTo(13.6f, 11.4f);
                Nvg.strokePath(SW * 0.8f, c);
                Nvg.beginPath();
                Nvg.moveTo(8.5f, 11f); Nvg.lineTo(8.5f, 13.6f); Nvg.lineTo(12.5f, 13.6f); Nvg.lineTo(12.5f, 11f);
                Nvg.strokePath(SW * 0.8f, c);
            }
            case "donut" -> {
                Nvg.circleStroke(12, 12, 8.6f, SW, c);
                Nvg.circleStroke(12, 12, 3.1f, SW, c);
                line(7.5f, 7.2f, 9.2f, 8.4f, Nvg.alpha(c, 0.9f), 1.5f);
                line(15.4f, 6.6f, 16.4f, 8.4f, Nvg.alpha(c, 0.9f), 1.5f);
                line(17.2f, 14f, 15.4f, 14.8f, Nvg.alpha(c, 0.9f), 1.5f);
                line(8.4f, 16.8f, 9.6f, 15.4f, Nvg.alpha(c, 0.9f), 1.5f);
            }
            case "hud" -> {
                Nvg.beginPath();
                Nvg.rrectPath(3, 4.5f, 18, 15, 3.2f);
                Nvg.strokePath(SW, c);
                line(3, 9.5f, 21, 9.5f, c);
                line(9.5f, 9.5f, 9.5f, 19.5f, c);
            }
            case "spotify" -> {
                Nvg.beginPath();
                Nvg.moveTo(9f, 18f); Nvg.lineTo(9f, 6f); Nvg.lineTo(19f, 4f); Nvg.lineTo(19f, 16f);
                Nvg.strokePath(SW, c);
                Nvg.circleStroke(6.8f, 18f, 2.3f, SW, c);
                Nvg.circleStroke(16.8f, 16f, 2.3f, SW, c);
            }
            case "keyboard" -> {
                Nvg.beginPath();
                Nvg.rrectPath(2, 6, 20, 12, 3);
                Nvg.strokePath(SW, c);
                for (int i = 0; i < 4; i++) dot(6.2f + i * 3.9f, 10f, 0.9f, c);
                line(7.5f, 14.2f, 16.5f, 14.2f, c);
            }
            case "inventory" -> {
                Nvg.beginPath();
                Nvg.rrectPath(3, 8.5f, 18, 12, 2.5f);
                Nvg.strokePath(SW, c);
                Nvg.beginPath();
                Nvg.moveTo(5.2f, 8.5f); Nvg.lineTo(5.2f, 7f); Nvg.bezierTo(5.2f, 4.9f, 6.4f, 4f, 8.2f, 4f);
                Nvg.lineTo(15.8f, 4f); Nvg.bezierTo(17.6f, 4f, 18.8f, 4.9f, 18.8f, 7f); Nvg.lineTo(18.8f, 8.5f);
                Nvg.strokePath(SW, c);
                line(3, 13f, 21, 13f, c);
                Nvg.beginPath();
                Nvg.rrectPath(10.4f, 11.4f, 3.2f, 3.2f, 1f);
                Nvg.fillPath(c);
            }
            case "staff" -> {
                Nvg.beginPath();
                Nvg.moveTo(12, 3f); Nvg.lineTo(19.5f, 6f); Nvg.lineTo(19.5f, 12f);
                Nvg.bezierTo(19.5f, 16.8f, 16.4f, 19.8f, 12, 21.4f);
                Nvg.bezierTo(7.6f, 19.8f, 4.5f, 16.8f, 4.5f, 12f); Nvg.lineTo(4.5f, 6f); Nvg.closePath();
                Nvg.strokePath(SW, c);
                Nvg.beginPath();
                Nvg.moveTo(8.6f, 12.2f); Nvg.lineTo(11f, 14.6f); Nvg.lineTo(15.6f, 9.6f);
                Nvg.strokePath(SW, c);
            }
            case "pin" -> {
                Nvg.beginPath();
                Nvg.moveTo(12, 21.4f); Nvg.bezierTo(8f, 17.4f, 5f, 13.8f, 5f, 9.6f);
                Nvg.bezierTo(5f, 5.7f, 8.1f, 3f, 12, 3f); Nvg.bezierTo(15.9f, 3f, 19f, 5.7f, 19f, 9.6f);
                Nvg.bezierTo(19f, 13.8f, 16f, 17.4f, 12, 21.4f); Nvg.closePath();
                Nvg.strokePath(SW, c);
                Nvg.circleStroke(12, 9.6f, 2.6f, SW, c);
            }
            case "watermark" -> {
                Nvg.beginPath();
                Nvg.rrectPath(3, 3, 18, 18, 5.5f);
                Nvg.strokePath(SW, c);
                line(8f, 8.4f, 16f, 8.4f, c);
                line(12f, 8.4f, 12f, 16.4f, c);
            }
            case "list" -> {
                line(9, 6, 20, 6, c); line(5, 12, 20, 12, c); line(12, 18, 20, 18, c);
                dot(4.2f, 6, 1f, c); dot(4.2f, 18, 1f, c);
            }
            case "search" -> {
                Nvg.circleStroke(10.5f, 10.5f, 6.5f, SW, c);
                line(15.4f, 15.4f, 20.5f, 20.5f, c);
            }
            case "chevron" -> {
                Nvg.beginPath();
                Nvg.moveTo(6, 9.2f); Nvg.lineTo(12, 15f); Nvg.lineTo(18, 9.2f);
                Nvg.strokePath(SW + 0.3f, c);
            }
            case "check" -> {
                Nvg.beginPath();
                Nvg.moveTo(5, 12.6f); Nvg.lineTo(10, 17.4f); Nvg.lineTo(19, 7f);
                Nvg.strokePath(SW + 0.5f, c);
            }
            case "gear" -> {
                Nvg.circleStroke(12, 12, 3.4f, SW, c);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
                    line(12 + cx * 7.4f, 12 + cy * 7.4f, 12 + cx * 9.8f, 12 + cy * 9.8f, c, 2.4f);
                }
                Nvg.circleStroke(12, 12, 7.2f, SW, c);
            }
            case "play" -> {
                Nvg.beginPath();
                Nvg.moveTo(7f, 4.6f); Nvg.lineTo(19f, 12f); Nvg.lineTo(7f, 19.4f); Nvg.closePath();
                Nvg.fillPath(c);
            }
            case "pause" -> {
                Nvg.beginPath(); Nvg.rrectPath(6, 4.5f, 4.2f, 15, 1.4f); Nvg.fillPath(c);
                Nvg.beginPath(); Nvg.rrectPath(13.8f, 4.5f, 4.2f, 15, 1.4f); Nvg.fillPath(c);
            }
            default -> Nvg.circleStroke(12, 12, 8, SW, c);
        }
        Nvg.restore();
    }

    private static void line(float x1, float y1, float x2, float y2, int c) {
        Nvg.line(x1, y1, x2, y2, SW, c);
    }

    private static void line(float x1, float y1, float x2, float y2, int c, float w) {
        Nvg.line(x1, y1, x2, y2, w, c);
    }

    private static void dot(float x, float y, float r, int c) {
        Nvg.circle(x, y, r, c);
    }
}
