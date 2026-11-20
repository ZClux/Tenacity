package tenacity.gfx;

import org.lwjgl.system.MemoryUtil;
import tenacity.spotify.AlbumArt;

import java.nio.ByteBuffer;

/** Holds the current Spotify album art as one NanoVG image, re-uploaded when the track changes. */
public final class SpotifyArt {
    private AlbumArt uploaded;
    private int image;
    private long ctx;

    public boolean update(AlbumArt art) {
        if (Nvg.ctx == 0) return false;
        if (ctx != Nvg.ctx) {
            image = 0;
            uploaded = null;
            ctx = Nvg.ctx;
        }
        if (art == null) {
            clear();
            return false;
        }
        if (art == uploaded) return image > 0;
        clear();
        uploaded = art;
        try {
            ByteBuffer pixels = MemoryUtil.memAlloc(art.byteSize());
            try {
                art.copyTo(pixels);
                pixels.flip();
                image = Nvg.rgbaImage(art.size(), art.size(), pixels);
            } finally {
                MemoryUtil.memFree(pixels);
            }
        } catch (Throwable t) {
            image = 0;
        }
        return image > 0;
    }

    public void draw(float x, float y, float size, float radius, float alpha) {
        Nvg.image(image, x, y, size, size, radius, alpha);
    }

    public void clear() {
        if (image > 0 && ctx == Nvg.ctx) Nvg.deleteImage(image);
        image = 0;
        uploaded = null;
    }
}
