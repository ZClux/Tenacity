package tenacity.gfx;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the shader sources shipped in assets/tenacity/shaders/core so they can be compiled through Glass.program.
 * The files were imported verbatim from the reference client, so their uniform layout is theirs: a caller that wants to
 * use one has to bind the names that file declares (Sampler0, HandData, ...) rather than ours.
 */
public final class ShaderLib {
    private ShaderLib() {
    }

    /** Source of assets/tenacity/shaders/core/&lt;name&gt;, or null when it is not packaged. */
    public static String load(String name) {
        String file = name.endsWith(".fsh") || name.endsWith(".vsh") ? name : name + ".fsh";
        try (InputStream in = ShaderLib.class.getResourceAsStream("/assets/tenacity/shaders/core/" + file)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean exists(String name) {
        return load(name) != null;
    }
}
