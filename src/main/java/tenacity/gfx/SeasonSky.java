package tenacity.gfx;

/**
 * Christmas (100..107) and Halloween (108..115) sky modes. Appended to WorldFx's sky shader after SkyShaders.FUNCS, so it
 * can use hash2/noise2/fbm2/fbm4/h33/h11/mm2/stars3/local2/basis/ground from there. Extra uniforms (declared here) carry the
 * Seasonal Sky module options; every function takes the world space view direction.
 */
final class SeasonSky {
    private SeasonSky() {
    }

    static final int FIRST = 100, COUNT = 16;

    /** Non-constant on purpose: keeps javac from folding the whole sky shader into one over-long constant. */
    static String funcs() {
        return FUNCS + FUNCS2;
    }

    static final String FUNCS = """
            uniform float uSClouds, uSMoon, uSPart, uSGlow, uSAurora, uSFog, uSBright;

            /* smooth 3 stop vertical gradient, eased so no visible bands */
            vec3 sGrad(vec3 d, vec3 hor, vec3 mid, vec3 top) {
                float y = max(d.y, 0.0);
                float a = smoothstep(0.0, 0.42, pow(y, 0.8));
                float b = smoothstep(0.25, 1.0, y);
                vec3 c = mix(hor, mid, a);
                return mix(c, top, b * b * (3.0 - 2.0 * b));
            }

            float sAng(vec3 d, vec3 n) { return acos(clamp(dot(d, n), -1.0, 1.0)); }

            /* soft glow around a direction: wide halo + tight core */
            float sHalo(vec3 d, vec3 n, float wide, float tight) {
                float a = sAng(d, n);
                return exp(-a * wide) * 0.5 + exp(-a * a * tight * tight) * 0.5;
            }

            /* moon disc with limb darkening, maria and craters; xyz = colour to composite, a = disc coverage */
            vec4 sMoon(vec3 d, vec3 dir, float rad, vec3 tintc, float t) {
                float md = dot(d, dir);
                vec4 o = vec4(0.0);
                if (md > 0.0) {
                    vec2 q = local2(d, dir, rad);
                    float r = length(q);
                    float aa = fwidth(r) * 1.2 + 0.004;
                    if (r < 1.2) {
                        float z = sqrt(max(1.0 - r * r, 0.0));
                        vec3 nrm = normalize(vec3(q, z + 0.001));
                        float lit = 0.68 + 0.32 * dot(nrm, normalize(vec3(-0.45, 0.35, 0.82)));
                        lit *= 0.8 + 0.2 * pow(z, 0.5);
                        float maria = smoothstep(0.42, 0.62, fbm2(q * 1.5 + 9.0));
                        float cr = fbm2(q * 4.0 + 3.0);
                        float fine = noise2(q * 14.0);
                        vec3 mc = tintc * (1.0 - 0.3 * maria) * (0.82 + 0.26 * cr + 0.06 * fine) * lit;
                        mc *= 0.8 + 0.3 * z;
                        o.rgb = mc * 1.05;
                        o.a = 1.0 - smoothstep(1.0 - aa - 0.015, 1.0 + aa, r);
                    }
                }
                return o;
            }

            /* smooth glow around the moon (added before the disc so the limb never gets a bright ring) */
            vec3 sMoonGlow(vec3 d, vec3 dir, float rad, vec3 tintc) {
                float a = sAng(d, dir);
                float rr = a / max(rad, 1e-3);
                return tintc * (exp(-rr * rr * 0.06) * 0.16 + exp(-rr * 0.22) * 0.08 + exp(-a * 2.2) * 0.05) * uSGlow;
            }

            vec3 sMoonComp(vec3 col, vec3 d, vec3 dir, float rad, vec3 tintc, float t) {
                col += sMoonGlow(d, dir, rad, tintc);
                vec4 m = sMoon(d, dir, rad, tintc, t);
                return mix(col, m.rgb, m.a);
            }

            /* fast drifting density field on a flat cloud deck */
            vec2 sDeck(vec3 d, float t, float s, float seed, float h) {
                return d.xz / (d.y + h) * 0.62 / s + vec2(t * 0.012, -t * 0.004) + seed;
            }

            /* volumetric looking cloud: returns x = coverage, y = lighting (0 shadowed base .. 1 lit rim) */
            vec2 sCloud(vec3 d, vec3 ldir, float t, float s, float seed, float cover) {
                if (d.y <= 0.0) return vec2(0.0);
                vec2 uv = sDeck(d, t, s, seed, 0.16);
                vec2 w = vec2(fbm2(uv * 0.55 + 4.0), fbm2(uv * 0.55 + 11.0));
                float n = fbm2(uv + (w - 0.5) * 1.1);
                float c = smoothstep(cover, cover + 0.3, n);
                // light: sample density a little towards the light, thinner side is brighter
                vec2 lo = normalize(ldir.xz + 1e-4) * 0.06;
                float n2 = fbm2(uv + lo + (w - 0.5) * 1.1);
                float lit = clamp(0.5 + (n - n2) * 9.0, 0.0, 1.0);
                lit = mix(lit, 1.0, 0.25 * (1.0 - c));
                float fade = smoothstep(0.0, 0.2, d.y);
                return vec2(c * fade, lit);
            }

            vec3 sCloudCol(vec3 col, vec2 cl, vec3 shade, vec3 lite, float amt) {
                vec3 cc = mix(shade, lite, cl.y);
                return mix(col, cc, clamp(cl.x * amt, 0.0, 1.0));
            }

            /* soft bokeh snow in three depth layers; big layers blur and dim, far layers are tiny and sharp */
            float sSnow(vec3 d, float t, float amt, float fall, float sz) {
                if (amt <= 0.0) return 0.0;
                float az = atan(d.x, d.z);
                float el = asin(clamp(d.y, -1.0, 1.0));
                float o = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float N = 34.0 + fi * 34.0;
                    float spd = (0.45 - 0.12 * fi) * fall;
                    vec2 p = vec2(az * N / (2.0 * PI), el * N / (2.0 * PI) + t * spd);
                    p.x += sin(p.y * 0.45 + t * 0.5 + fi * 2.0) * 0.55 + t * 0.03 * (1.0 + fi);
                    vec2 cell = floor(p), f = fract(p) - 0.5;
                    cell.x = mod(cell.x, N);
                    vec3 h = h33(vec3(cell, fi + 21.0));
                    if (h.x < 0.34 * amt) {
                        vec2 q = f - (h.yz - 0.5) * 0.6;
                        float rr = (0.06 + 0.05 * h.y) * sz * (1.0 - 0.25 * fi);
                        float r = length(q);
                        // soft round flake: gaussian body with a tiny bright core, no hard rim
                        float body = exp(-r * r / (rr * rr));
                        float core = exp(-r * r / (rr * rr * 0.12));
                        o += (body * 0.55 + core * 0.45) * (0.6 + 0.4 * sin(t * 1.3 + h.z * 30.0)) * (1.0 - 0.22 * fi);
                    }
                }
                return o * smoothstep(0.97, 0.45, abs(d.y));
            }

            /* embers: warm, rise, tiny hot core */
            float sEmber(vec3 d, float t, float amt, float sz) {
                if (amt <= 0.0) return 0.0;
                float az = atan(d.x, d.z);
                float el = asin(clamp(d.y, -1.0, 1.0));
                float o = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float N = 40.0 + fi * 28.0;
                    vec2 p = vec2(az * N / (2.0 * PI), el * N / (2.0 * PI) - t * (0.3 + 0.12 * fi));
                    p.x += sin(p.y * 0.8 + t * 0.9 + fi) * 0.5;
                    vec2 cell = floor(p), f = fract(p) - 0.5;
                    cell.x = mod(cell.x, N);
                    vec3 h = h33(vec3(cell, fi + 71.0));
                    if (h.x < 0.22 * amt) {
                        vec2 q = f - (h.yz - 0.5) * 0.6;
                        float e = exp(-dot(q, q) / (sz * sz * (0.5 + h.y)));
                        o += e * (0.4 + 0.6 * pow(0.5 + 0.5 * sin(t * 2.5 + h.z * 40.0), 2.0)) * (1.0 - 0.2 * fi);
                    }
                }
                return o * smoothstep(0.95, 0.3, abs(d.y));
            }

            /* richer stars: varied size and colour, with a soft milky way band */
            vec3 sStars(vec3 d, float amt, float t) {
                return stars3(d, amt, t);
            }

            vec3 sMilky(vec3 d, vec3 axis, float amt, vec3 tintc) {
                if (amt <= 0.0) return vec3(0.0);
                float h = dot(d, axis);
                float band = exp(-h * h * 22.0);
                float n = fbm4(d * 5.0);
                float dust = smoothstep(0.35, 0.7, fbm4(d * 9.0 + 3.0));
                float core = band * (0.45 + 0.9 * n) * (1.0 - 0.55 * dust * band);
                vec3 c = mix(tintc, vec3(1.0, 0.92, 0.85), band * band * 0.4);
                vec3 fine = stars3(d * 1.7 + 3.0, amt * 2.2, 0.0) * band * 0.45;
                return c * core * 0.16 * amt + fine;
            }

            /* horizon haze glow */
            vec3 sHaze(vec3 col, vec3 d, vec3 hc, float amt, float width) {
                float h = exp(-max(d.y, 0.0) * width);
                return col + hc * h * amt;
            }

            /* layered aurora curtains with smooth colour shifts and vertical rays */
            vec3 sAuroraBand(vec3 d, float t, float s, vec3 c1, vec3 c2) {
                if (d.y <= 0.015) return vec3(0.0);
                vec3 acc = vec3(0.0);
                float jit = hash2(gl_FragCoord.xy);
                for (int k = 0; k < 16; k++) {
                    float i = float(k);
                    float pt = (0.8 + pow(i, 1.4) * 0.0045) / (d.y * 2.0 + 0.4) - 0.01 * jit;
                    vec3 bp = d * pt;
                    float rz = triNoise(bp.zx / s, t);
                    float ph = 0.5 + 0.5 * sin(i * 0.22 + t * 0.07 + bp.x * 0.4);
                    vec3 c = mix(c1, c2, ph * ph * (3.0 - 2.0 * ph));
                    // vertical rays
                    float ray = 0.75 + 0.25 * noise2(vec2(bp.x * 3.0 / s, bp.z * 0.3 + t * 0.2));
                    acc += c * rz * ray * exp2(-i * 0.12 - 1.5);
                }
                float edge = smoothstep(0.015, 0.2, d.y);
                return acc * edge * 2.2 * (0.7 + 0.3 * smoothstep(0.7, 0.1, d.y));
            }

            /* snow covered ground with a soft horizon; keeps the lower half calm and believable */
            vec3 sSnowGround(vec3 col, vec3 d, vec3 g, float t) {
                if (d.y >= 0.0) return col;
                float k = smoothstep(0.0, -0.1, d.y);
                float far = 1.0 - exp(d.y * 6.0);
                vec3 gc = g * (0.82 + 0.22 * noise2(d.xz / max(-d.y, 0.05) * 3.0)) * (0.7 + 0.3 * far);
                return mix(col, gc, k);
            }

            /* pine tree line silhouette: returns coverage; h = base height, color blends in the caller */
            float sPines(vec3 d, float seed, float base, float sizeK) {
                float az = atan(d.x, d.z);
                float cov = 0.0;
                for (int L = 0; L < 2; L++) {
                    float fl = float(L);
                    float N = 26.0 + fl * 17.0;
                    float u = az / (2.0 * PI) * N + fl * 0.37 + seed;
                    float id = floor(u);
                    float fu = fract(u) - 0.5;
                    float hh = (0.05 + 0.07 * h11(id + fl * 13.0 + seed)) * sizeK * (1.0 - 0.3 * fl);
                    float y = d.y - base + fl * 0.004;
                    float tt = clamp(y / hh, 0.0, 1.0);
                    // tiered christmas tree edge
                    float tier = fract(tt * 4.0);
                    float halfW = (1.0 - tt) * (0.42 - 0.18 * tier * tier) * (0.7 + 0.3 * h11(id + 5.0));
                    float aa = fwidth(u) * 1.0 + 0.01;
                    float inside = (1.0 - smoothstep(halfW - aa, halfW + aa, abs(fu))) * step(0.0, y) * (1.0 - smoothstep(0.96, 1.0, tt));
                    cov = max(cov, inside);
                }
                // low ridge under the trees
                float ridge = 1.0 - smoothstep(0.0, 0.006, d.y - (base + 0.004 + 0.012 * noise2(vec2(az * 6.0, seed))));
                return max(cov, ridge * step(-0.5, d.y));
            }
            """;

    static final String FUNCS2 = """
            /* ============================================================ CHRISTMAS ============================================================ */

            /* 100 snowy night with aurora */
            vec3 xmasSnowyAurora(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.1, 0.17, 0.3), vec3(0.025, 0.055, 0.14), vec3(0.005, 0.012, 0.04));
                col += sMilky(d, normalize(vec3(0.4, 0.2, 0.9)), uStars, vec3(0.55, 0.65, 0.95));
                col += sStars(d, uStars, t) * smoothstep(0.08, 0.5, d.y);
                col += sAuroraBand(d, t, s, vec3(0.18, 1.0, 0.6), vec3(0.4, 0.5, 1.0)) * uSAurora;
                vec3 mdir = normalize(vec3(-0.4, 0.34, -0.85));
                col = sMoonComp(col, d, mdir, 0.04 * uSMoon, vec3(0.92, 0.95, 1.0), t);
                vec2 cl = sCloud(d, mdir, t, s, 3.0, 0.5);
                col = sCloudCol(col, cl, vec3(0.04, 0.06, 0.12), vec3(0.3, 0.38, 0.55), 0.55 * uSClouds);
                col = sHaze(col, d, vec3(0.1, 0.18, 0.3), 0.7, 5.0);
                col += vec3(0.9, 0.95, 1.0) * sSnow(d, t, uSPart, 1.0, 1.0) * 0.8;
                float pn = sPines(d, 0.0, -0.005, 1.0);
                col = mix(col, vec3(0.008, 0.016, 0.03) + col * 0.05, pn * 0.97);
                return sSnowGround(col, d, vec3(0.34, 0.4, 0.52), t);
            }

            /* 101 starry winter sky with falling snow */
            vec3 xmasStarrySnow(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.1, 0.16, 0.3), vec3(0.02, 0.045, 0.13), vec3(0.003, 0.008, 0.03));
                col += sMilky(d, normalize(vec3(-0.5, 0.15, 0.85)), uStars * 1.3, vec3(0.6, 0.68, 1.0));
                col += sStars(d, uStars * 1.3, t) * 1.1 * smoothstep(0.05, 0.4, d.y);
                vec2 cl = sCloud(d, vec3(0.0, 1.0, 0.0), t, s, 7.0, 0.62);
                col = sCloudCol(col, cl, vec3(0.03, 0.05, 0.1), vec3(0.14, 0.2, 0.32), 0.45 * uSClouds);
                col = sHaze(col, d, vec3(0.12, 0.2, 0.34), 0.8, 5.5);
                col += vec3(1.0) * sSnow(d, t, uSPart, 1.0, 1.0) * 0.9;
                col += vec3(1.0) * sSnow(d.zyx, t * 0.8, uSPart * 0.5, 1.2, 1.4) * 0.45;
                float pn = sPines(d, 3.0, -0.004, 1.15);
                col = mix(col, vec3(0.006, 0.012, 0.025) + col * 0.05, pn * 0.97);
                return sSnowGround(col, d, vec3(0.32, 0.38, 0.5), t);
            }

            /* 102 santa sleigh over the moon */
            vec3 xmasSleighMoon(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.09, 0.13, 0.26), vec3(0.025, 0.045, 0.13), vec3(0.005, 0.01, 0.035));
                col += sStars(d, uStars, t) * smoothstep(0.05, 0.4, d.y);
                vec3 mdir = normalize(vec3(0.1, 0.38, -0.9));
                float rad = 0.085 * uSMoon;
                col += vec3(1.0, 0.92, 0.7) * sHalo(d, mdir, 3.0, 5.0) * 0.12 * uSGlow;
                col = sMoonComp(col, d, mdir, rad, vec3(1.0, 0.96, 0.84), t);
                vec2 cl = sCloud(d, mdir, t, s, 8.0, 0.58);
                float moonLight = pow(max(dot(d, mdir), 0.0), 6.0);
                col = sCloudCol(col, cl, vec3(0.04, 0.05, 0.1) + vec3(0.25, 0.22, 0.18) * moonLight, vec3(0.45, 0.45, 0.5) + vec3(0.5, 0.42, 0.3) * moonLight, 0.65 * uSClouds);
                // sleigh and reindeer silhouette crossing the moon
                vec2 q = local2(d, mdir, rad);
                float ph = fract(t * 0.022);
                float vis = smoothstep(0.0, 0.05, ph) * smoothstep(1.0, 0.95, ph);
                vec2 p = q - vec2(mix(-5.5, 5.5, ph), 0.32 * sin(ph * 6.2831) + 0.15);
                p.x *= -1.0;
                p *= 1.0;
                float aa = fwidth(q.x) * 1.5 + 0.004;
                // sleigh body: curved box
                float body = 1.0 - smoothstep(0.0, aa, max(abs(p.x) - 0.2, abs(p.y + 0.02 + 0.16 * p.x * p.x * p.x * 0.0) - 0.055 - 0.07 * smoothstep(0.0, 0.2, p.x)));
                float runner = 1.0 - smoothstep(0.0, aa, abs(p.y + 0.14 - 0.05 * p.x * p.x * 3.0) - 0.012);
                runner *= step(abs(p.x), 0.26);
                float sil = max(body, runner);
                // reindeer: body + legs + antlers, bobbing
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    vec2 rp = p - vec2(0.45 + 0.34 * fi, 0.04 * sin(t * 4.0 + fi * 1.7) + 0.02 * fi);
                    float rb = 1.0 - smoothstep(0.0, aa, length(rp * vec2(1.0, 2.3)) - 0.09);
                    float hd = 1.0 - smoothstep(0.0, aa, length((rp - vec2(0.1, 0.05)) * vec2(1.3, 1.6)) - 0.04);
                    float leg = 1.0 - smoothstep(0.0, aa, max(abs(abs(rp.x) - 0.05 + 0.0 * rp.y) - 0.008, abs(rp.y + 0.04) - 0.045));
                    float ant = 1.0 - smoothstep(0.0, aa, abs(rp.y - 0.1 - 0.5 * (rp.x - 0.11) - 0.0) - 0.006);
                    ant *= step(0.1, rp.x) * step(rp.x, 0.16) * step(0.08, rp.y);
                    sil = max(sil, max(max(rb, hd), max(leg, ant)));
                }
                // harness line
                sil = max(sil, (1.0 - smoothstep(0.0, aa, abs(p.y - 0.01 - 0.02 * (p.x - 0.3)) - 0.004)) * step(0.2, p.x) * step(p.x, 1.1));
                sil *= vis;
                col = mix(col, vec3(0.012, 0.01, 0.02), sil * 0.97);
                // warm sparkle trail
                float trail = exp(-pow(max(-(p.x - 0.25) - 0.5, 0.0) * 0.5, 2.0)) * step(p.x, 0.0);
                col += vec3(1.0, 0.8, 0.35) * exp(-abs(p.y + 0.05) * 14.0) * exp(-max(-p.x, 0.0) * 1.4) * step(p.x, 0.0) * vis * 0.12 * (0.6 + 0.4 * noise2(p * 22.0 + t * 3.0));
                col = sHaze(col, d, vec3(0.1, 0.16, 0.28), 0.6, 5.5);
                col += vec3(1.0) * sSnow(d, t, uSPart * 0.5, 1.0, 1.0) * 0.6;
                float pn = sPines(d, 7.0, -0.004, 1.0);
                col = mix(col, vec3(0.006, 0.01, 0.022) + col * 0.05, pn * 0.97);
                return sSnowGround(col, d, vec3(0.3, 0.36, 0.48), t);
            }

            /* 103 warm red and green dusk */
            vec3 xmasWarmDusk(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 hor = vec3(1.0, 0.36, 0.2), low = vec3(0.6, 0.12, 0.2), mid = vec3(0.1, 0.24, 0.2), top = vec3(0.02, 0.07, 0.09);
                vec3 col = mix(hor, low, smoothstep(0.0, 0.18, yy));
                col = mix(col, mid, smoothstep(0.14, 0.55, yy));
                col = mix(col, top, smoothstep(0.45, 1.0, yy));
                vec3 sun = normalize(vec3(-0.15, 0.06, -1.0));
                float sd = max(dot(d, sun), 0.0);
                col += vec3(1.3, 0.5, 0.22) * pow(sd, 6.0) * 0.45 * uSGlow + vec3(1.5, 0.9, 0.45) * pow(sd, 60.0) * 0.8 * uSGlow;
                float disc = smoothstep(0.9993, 0.9997, sd);
                col = mix(col, vec3(1.6, 1.1, 0.7), disc * smoothstep(0.0, 0.03, d.y + 0.01));
                col += sStars(d, uStars * 0.7, t) * smoothstep(0.35, 0.8, yy);
                vec2 cl = sCloud(d, sun, t, s, 1.0, 0.5);
                vec3 shade = mix(vec3(0.35, 0.07, 0.16), vec3(0.07, 0.2, 0.18), smoothstep(0.1, 0.5, d.y));
                vec3 lite = mix(vec3(1.25, 0.55, 0.3), vec3(0.5, 0.85, 0.6), smoothstep(0.08, 0.5, d.y));
                lite = mix(lite, vec3(1.5, 0.85, 0.5), pow(sd, 3.0));
                col = sCloudCol(col, cl, shade, lite, 0.8 * uSClouds);
                col += vec3(1.0, 0.9, 0.75) * sSnow(d, t, uSPart * 0.7, 1.0, 1.0) * 0.6;
                float pn = sPines(d, 11.0, -0.004, 1.2);
                col = mix(col, vec3(0.04, 0.012, 0.02) + col * 0.04, pn * 0.97);
                return sSnowGround(col, d, vec3(0.5, 0.26, 0.28), t);
            }

            /* 104 frosted glass dome: white frost, pale facets, cold light */
            vec3 xmasFrostDome(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                // luminous white-silver gradient: warm silver at the horizon, clean pearl overhead
                vec3 col = mix(vec3(0.9, 0.93, 0.96), vec3(0.4, 0.5, 0.64), smoothstep(0.0, 0.95, pow(yy, 0.75)));
                vec2 p = d.xz / (yy + 0.3) * 1.7 / s;
                vec2 ip = floor(p), fp = fract(p);
                float md = 8.0, md2 = 8.0; vec2 mid = vec2(0.0);
                for (int j = -1; j <= 1; j++)
                    for (int i = -1; i <= 1; i++) {
                        vec2 g = vec2(float(i), float(j));
                        vec2 o = vec2(hash2(ip + g), hash2(ip + g + 17.3));
                        o = 0.5 + 0.4 * sin(t * 0.18 + 6.2831 * o);
                        float dd = length(g + o - fp);
                        if (dd < md) { md2 = md; md = dd; mid = ip + g; }
                        else if (dd < md2) md2 = dd;
                    }
                float facet = hash2(mid);
                // pane shading: each ice pane slightly lighter or darker, neutral grey-white
                col *= 0.9 + 0.12 * facet;
                float edge = 1.0 - smoothstep(0.0, 0.028, md2 - md);
                col = mix(col, vec3(0.97, 0.98, 0.99), edge * 0.42 * uSGlow);
                col *= 0.88 + 0.12 * smoothstep(0.0, 0.5, md);
                // frost crystals: feathery growth, dense near the horizon and at pane edges
                vec2 fq = p * 4.0 + facet * 11.0;
                float f1 = fbm2(fq);
                float f2 = fbm2(fq * 2.3 + 5.0);
                float fern = smoothstep(0.4, 0.8, f1 * 0.65 + f2 * 0.45);
                float dens = 0.45 + 0.55 * smoothstep(0.7, 0.0, yy);
                col = mix(col, vec3(0.97, 0.98, 1.0), fern * dens * 0.8 * uSClouds);
                col = mix(col, col * 0.85, (1.0 - fern) * 0.3 * smoothstep(0.2, 0.9, yy));
                // radial light: low pale sun with soft long halo and sparkling ice glints
                vec3 sun = normalize(vec3(0.3, 0.38, -0.87));
                float sd = max(dot(d, sun), 0.0);
                col += vec3(1.0, 0.97, 0.9) * (pow(sd, 10.0) * 0.4 + pow(sd, 60.0) * 0.5 + pow(sd, 700.0) * 2.0) * uSGlow;
                float gl = h33(vec3(floor(p * 9.0), 3.0)).x;
                float glint = smoothstep(0.975, 1.0, gl) * (0.5 + 0.5 * sin(t * 3.0 + gl * 90.0));
                col += vec3(1.0) * glint * 0.9 * uSGlow * (0.4 + 0.6 * fern);
                col += vec3(1.0) * sSnow(d, t, uSPart * 0.7, 0.6, 1.0) * 0.4;
                // darker frosted rim around the dome base, and a pearl ground
                col *= 1.0 - 0.1 * smoothstep(0.3, 0.0, yy);
                col = mix(col, vec3(0.86, 0.9, 0.95), smoothstep(0.0, -0.08, d.y));
                return col;
            }

            /* 105 candy sky: pink / mint swirls with soft peppermint ribbons */
            vec3 xmasCandy(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                // cotton candy gradient: warm pink horizon, lilac mid, mint top
                vec3 col = mix(vec3(1.0, 0.7, 0.78), vec3(0.86, 0.72, 0.95), smoothstep(0.0, 0.35, yy));
                col = mix(col, vec3(0.6, 0.9, 0.86), smoothstep(0.3, 1.0, yy));
                vec2 p = d.xz / (yy + 0.22) * 1.0 / s;
                float ang = atan(p.y, p.x), r = length(p);
                float sw = (ang * 3.0 + r * 2.6 - t * 0.28 + fbm2(p * 1.2) * 1.4) / 6.2831 * 2.0;
                float ph = abs(fract(sw) - 0.5) * 2.0;
                // soft peppermint ribbons, anti-aliased band
                float rib = smoothstep(0.30, 0.46, ph) * (1.0 - smoothstep(0.62, 0.8, ph));
                col = mix(col, vec3(1.0, 0.32, 0.46), rib * 0.5 * smoothstep(0.02, 0.25, d.y));
                col = mix(col, vec3(1.0), (1.0 - rib) * smoothstep(0.55, 0.0, abs(ph - 0.1)) * 0.12);
                vec3 sun = normalize(vec3(0.2, 0.3, -0.93));
                vec2 cl = sCloud(d, sun, t, s, 6.0, 0.46);
                col = sCloudCol(col, cl, vec3(0.93, 0.68, 0.84), vec3(1.0, 0.98, 1.0), 0.92 * uSClouds);
                float sd = max(dot(d, sun), 0.0);
                col += vec3(1.0, 0.88, 0.94) * (pow(sd, 10.0) * 0.3 + pow(sd, 120.0) * 0.4 + pow(sd, 900.0) * 1.8) * uSGlow;
                col = sHaze(col, d, vec3(1.0, 0.82, 0.9), 0.35, 5.0);
                // pastel sprinkle bokeh
                float sp = sSnow(d, t, uSPart, 0.7, 1.0);
                vec3 pc = mix(vec3(1.0, 0.4, 0.55), vec3(0.35, 0.95, 0.75), smoothstep(0.4, 0.6, hash2(floor(d.xz * 30.0 + 7.0))));
                col += pc * sp * 0.6;
                return mix(col, vec3(0.97, 0.84, 0.9), smoothstep(0.0, -0.12, d.y));
            }

            /* 106 silent night: deep calm blue, huge soft moon, still stars */
            vec3 xmasSilentNight(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.1, 0.18, 0.34), vec3(0.025, 0.06, 0.18), vec3(0.004, 0.01, 0.04));
                col += sMilky(d, normalize(vec3(0.5, 0.1, 0.85)), uStars * 1.2, vec3(0.55, 0.68, 1.0));
                col += sStars(d, uStars * 1.1, t * 0.4) * smoothstep(0.05, 0.4, d.y);
                vec3 mdir = normalize(vec3(-0.25, 0.42, -0.87));
                col += vec3(0.28, 0.42, 0.8) * sHalo(d, mdir, 2.4, 3.0) * 0.28 * uSGlow;
                col = sMoonComp(col, d, mdir, 0.07 * uSMoon, vec3(0.93, 0.96, 1.0), t);
                vec2 cl = sCloud(d, mdir, t * 0.4, s, 11.0, 0.6);
                float ml = pow(max(dot(d, mdir), 0.0), 6.0);
                col = sCloudCol(col, cl, vec3(0.03, 0.05, 0.11), vec3(0.14, 0.2, 0.34) + vec3(0.35, 0.42, 0.6) * ml, 0.6 * uSClouds);
                col = sHaze(col, d, vec3(0.1, 0.18, 0.34), 0.6, 5.0);
                col += vec3(0.9, 0.95, 1.0) * sSnow(d, t * 0.55, uSPart * 0.7, 0.7, 1.0) * 0.7;
                // a single warm star with soft cross
                vec3 sdir = normalize(vec3(0.5, 0.58, -0.64));
                vec2 q = local2(d, sdir, 0.02);
                float sr = length(q);
                float tw = 0.88 + 0.12 * sin(t * 1.3);
                float vis = step(0.0, dot(d, sdir));
                col += vec3(1.0, 0.88, 0.6) * (exp(-sr * sr * 5.0) * 1.1 + exp(-sr * 1.2) * 0.12 + exp(-abs(q.x) * 20.0) * exp(-abs(q.y) * 1.6) * 0.5 + exp(-abs(q.y) * 20.0) * exp(-abs(q.x) * 1.6) * 0.5) * tw * vis * uSGlow;
                float pn = sPines(d, 21.0, -0.004, 1.0);
                col = mix(col, vec3(0.006, 0.012, 0.028) + col * 0.05, pn * 0.97);
                return sSnowGround(col, d, vec3(0.26, 0.34, 0.5), t);
            }

            /* 107 northern lights, big and bright over snowy dark */
            vec3 xmasNorthern(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.04, 0.09, 0.13), vec3(0.008, 0.025, 0.055), vec3(0.002, 0.005, 0.018));
                col += sMilky(d, normalize(vec3(-0.6, 0.2, 0.8)), uStars, vec3(0.5, 0.75, 0.9));
                col += sStars(d, uStars, t) * smoothstep(0.05, 0.4, d.y);
                col += sAuroraBand(d, t, s, vec3(0.12, 1.0, 0.5), vec3(0.7, 0.3, 1.0)) * 1.35 * uSAurora;
                col += sAuroraBand(d.zyx * vec3(1.0, 1.0, -1.0), t * 0.8, s * 1.3, vec3(0.2, 0.9, 0.85), vec3(0.3, 1.0, 0.4)) * 0.6 * uSAurora;
                col = sHaze(col, d, vec3(0.06, 0.16, 0.18), 0.7, 6.0);
                col += vec3(1.0) * sSnow(d, t, uSPart * 0.5, 0.8, 1.0) * 0.5;
                float pn = sPines(d, 31.0, -0.004, 1.1);
                col = mix(col, vec3(0.004, 0.012, 0.016) + col * 0.05, pn * 0.97);
                return sSnowGround(col, d, vec3(0.14, 0.26, 0.28), t);
            }

            /* ============================================================ HALLOWEEN ============================================================ */

            /* 108 blood moon */
            vec3 halBloodMoon(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.3, 0.04, 0.05), vec3(0.09, 0.012, 0.03), vec3(0.01, 0.003, 0.014));
                col += sStars(d, uStars * 0.7, t) * vec3(1.0, 0.7, 0.65) * smoothstep(0.05, 0.4, d.y);
                vec3 mdir = normalize(vec3(-0.15, 0.3, -0.94));
                col += vec3(1.0, 0.1, 0.04) * sHalo(d, mdir, 2.6, 3.4) * 0.38 * uSGlow;
                col += sMoonGlow(d, mdir, 0.1 * uSMoon, vec3(1.0, 0.16, 0.06)) * 1.6;
                vec4 m = sMoon(d, mdir, 0.1 * uSMoon, vec3(1.0, 0.2, 0.08), t);
                col = mix(col, m.rgb * vec3(1.0, 0.8, 0.78), m.a);
                // clouds: dark bodies with blood-red rims facing the moon
                vec2 cl = sCloud(d, mdir, t, s, 2.0, 0.5);
                float ml = pow(max(dot(d, mdir), 0.0), 5.0);
                col = sCloudCol(col, cl, vec3(0.04, 0.008, 0.016), vec3(0.2, 0.03, 0.04) + vec3(1.0, 0.14, 0.07) * ml, 0.8 * uSClouds);
                col = sHaze(col, d, vec3(0.5, 0.06, 0.05), 0.5, 6.0);
                col += vec3(1.0, 0.35, 0.15) * sEmber(d, t, uSPart * 0.6, 0.05) * 0.5;
                float pn = sPines(d, 41.0, -0.004, 0.9);
                col = mix(col, vec3(0.02, 0.004, 0.008) + col * 0.05, pn * 0.97);
                return ground(col, d, vec3(0.03, 0.008, 0.012));
            }

            /* 109 haunted purple sky with bats */
            vec3 halHauntedBats(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.26, 0.1, 0.38), vec3(0.08, 0.035, 0.2), vec3(0.012, 0.006, 0.045));
                col += sMilky(d, normalize(vec3(0.5, 0.2, 0.85)), uStars * 0.9, vec3(0.7, 0.55, 1.0));
                col += sStars(d, uStars * 0.8, t) * smoothstep(0.05, 0.4, d.y);
                vec3 mdir = normalize(vec3(0.3, 0.4, -0.86));
                col += vec3(0.55, 0.28, 0.95) * sHalo(d, mdir, 2.6, 3.0) * 0.3 * uSGlow;
                col = sMoonComp(col, d, mdir, 0.07 * uSMoon, vec3(0.97, 0.92, 1.0), t);
                vec2 cl = sCloud(d, mdir, t, s, 5.0, 0.52);
                float ml = pow(max(dot(d, mdir), 0.0), 6.0);
                col = sCloudCol(col, cl, vec3(0.04, 0.02, 0.09), vec3(0.2, 0.1, 0.36) + vec3(0.55, 0.35, 0.85) * ml, 0.75 * uSClouds);
                col = sHaze(col, d, vec3(0.34, 0.12, 0.42), 0.55, 6.0);
                // bats: flapping silhouettes drifting across the sky
                float az = atan(d.x, d.z) / (2.0 * PI);
                float el = d.y / sqrt(max(1.0 - d.y * d.y, 0.03));
                float bats = 0.0;
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float N = 8.0 + fi * 5.0;
                    vec2 p = vec2(az * N + t * (0.05 + 0.03 * fi) * (1.0 + fi), el * N / (2.0 * PI) * 1.4);
                    vec2 cell = floor(p), f = fract(p) - 0.5;
                    cell.x = mod(cell.x, N);
                    vec3 h = h33(vec3(cell, fi + 41.0));
                    if (h.x < 0.33 * uSPart) {
                        vec2 q = (f - (h.yz - 0.5) * 0.4) * (3.2 + fi);
                        float flap = sin(t * (6.0 + 3.0 * h.y) + h.z * 20.0);
                        float ax = abs(q.x);
                        // wing: scalloped lower edge, swept upper edge, flapping
                        float up = 0.05 + 0.34 * ax * (0.35 + 0.65 * flap) - 0.12 * ax * ax;
                        float scal = 0.05 * abs(sin(ax * 14.0));
                        float lo = -0.04 - 0.1 * ax + scal * smoothstep(0.1, 0.5, ax);
                        float aaw = fwidth(q.x) * 1.4 + 0.01;
                        float wing = (1.0 - smoothstep(0.0, aaw, q.y - up)) * (1.0 - smoothstep(0.0, aaw, lo - q.y)) * (1.0 - smoothstep(0.5, 0.58, ax));
                        float bd = 1.0 - smoothstep(0.0, aaw, length(q * vec2(1.4, 0.9)) - 0.1);
                        float ear = 1.0 - smoothstep(0.0, aaw, length(vec2(abs(q.x) - 0.06, q.y - 0.12)) - 0.03);
                        bats += max(max(wing, bd), ear) * smoothstep(0.0, 0.18, d.y);
                    }
                }
                col = mix(col, vec3(0.01, 0.005, 0.018), clamp(bats, 0.0, 1.0) * 0.96);
                float pn = sPines(d, 51.0, -0.004, 0.85);
                col = mix(col, vec3(0.012, 0.006, 0.022) + col * 0.05, pn * 0.97);
                return ground(col, d, vec3(0.025, 0.012, 0.045));
            }

            /* 110 thunder storm, orange and menacing */
            vec3 halThunderOrange(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(1.0, 0.42, 0.08), vec3(0.34, 0.12, 0.06), smoothstep(0.0, 0.28, yy));
                col = mix(col, vec3(0.06, 0.03, 0.03), smoothstep(0.22, 0.85, yy));
                float cyc = t * 0.33, id = floor(cyc), ph = fract(cyc) * 3.4;
                float on = step(0.08, h11(id * 1.9));
                float flash = (exp(-ph * 6.0) + 0.7 * exp(-max(ph - 0.2, 0.0) * 7.0) * step(0.2, ph)) * on;
                float baz = (h11(id * 3.1) - 0.5) * 1.2 + 2.9;
                vec3 fdir = normalize(vec3(sin(baz), 0.2, cos(baz)));
                // two cloud decks for depth: far soft deck, near contrasty deck
                vec2 uvA = d.xz / (yy + 0.14) * 0.42 / s + vec2(t * 0.02, t * 0.008);
                vec2 uvB = d.xz / (yy + 0.14) * 0.9 / s + vec2(t * 0.045, t * 0.015) + 5.0;
                vec2 wA = vec2(fbm2(uvA * 0.7 + 3.0), fbm2(uvA * 0.7 + 8.0));
                float nA = fbm2(uvA + (wA - 0.5) * 1.4);
                float nA2 = fbm2(uvA + vec2(0.05, 0.035) + (wA - 0.5) * 1.4);
                float nB = fbm2(uvB + (wA - 0.5) * 0.8);
                float nB2 = fbm2(uvB + vec2(0.05, 0.035) + (wA - 0.5) * 0.8);
                float litA = clamp(0.5 + (nA - nA2) * 10.0, 0.0, 1.0);
                float litB = clamp(0.5 + (nB - nB2) * 10.0, 0.0, 1.0);
                float cA = smoothstep(0.3, 0.7, nA) * mix(0.5, 1.0, uSClouds);
                float cB = smoothstep(0.42, 0.72, nB) * mix(0.4, 1.0, uSClouds);
                float fade = smoothstep(-0.02, 0.09, d.y);
                vec3 glowSrc = vec3(1.2, 0.5, 0.12) * pow(1.0 - smoothstep(0.0, 0.5, yy), 1.6);
                vec3 deckA = mix(vec3(0.11, 0.04, 0.03), vec3(0.62, 0.26, 0.09), litA) + glowSrc * 0.45 * uSGlow;
                col = mix(col, deckA, cA * fade * 0.92);
                vec3 deckB = mix(vec3(0.03, 0.014, 0.014), vec3(0.45, 0.19, 0.07), litB * 0.9) + glowSrc * 0.3 * uSGlow;
                col = mix(col, deckB, cB * fade * 0.88);
                col += vec3(1.1, 0.42, 0.08) * pow(1.0 - smoothstep(0.0, 0.3, yy), 3.0) * 0.55 * uSGlow * (1.0 - 0.5 * cB);
                // lightning illuminates the clouds near the strike
                float near = pow(max(dot(d, fdir), 0.0), 4.0);
                col += vec3(1.0, 0.8, 0.62) * flash * (0.1 + near * 1.0 * (0.4 + nA + 0.5 * nB)) * smoothstep(-0.02, 0.1, d.y);
                // branching bolt: trunk plus forks, thin hot core with soft glow
                float da = mod(atan(d.x, d.z) - baz + PI, 2.0 * PI) - PI;
                float jag = (fbm2(vec2(d.y * 14.0, id * 7.0)) - 0.5) * 0.2 + (noise2(vec2(d.y * 70.0, id)) - 0.5) * 0.02;
                float x = da - jag;
                float bolt = exp(-x * x / 6e-6) * 3.0 + exp(-abs(x) * 38.0) * 0.5;
                for (int k = 0; k < 3; k++) {
                    float fk = float(k);
                    float y0 = 0.14 + 0.06 * h11(id * 5.0 + fk * 3.0);
                    float dirn = (mod(fk, 2.0) < 1.0 ? 1.0 : -1.0);
                    float by = d.y - y0 + 0.07 * fk;
                    float bx = x - dirn * max(-by, 0.0) * (0.45 + 0.1 * fk) - (noise2(vec2(d.y * 55.0 + fk * 9.0, id)) - 0.5) * 0.015;
                    bolt += (exp(-bx * bx / 2.5e-6) * 1.4 + exp(-abs(bx) * 70.0) * 0.18) * step(by, 0.0) * smoothstep(-0.12, 0.0, by);
                }
                float bm = smoothstep(0.34, 0.28, d.y) * smoothstep(-0.02, 0.01, d.y);
                col += vec3(1.0, 0.93, 0.82) * bolt * flash * bm;
                col += vec3(1.0, 0.45, 0.12) * sEmber(d, t, uSPart * 0.5, 0.05) * 0.55;
                float pn = sPines(d, 61.0, -0.004, 0.9);
                col = mix(col, vec3(0.03, 0.012, 0.01), pn * 0.97);
                return ground(col, d, vec3(0.04, 0.016, 0.012));
            }

/* 111 graveyard fog: pale moon, ground mist, dead trees, fence */
            vec3 halGraveyardFog(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.2, 0.26, 0.28), vec3(0.05, 0.085, 0.1), vec3(0.01, 0.018, 0.03));
                col += sStars(d, uStars * 0.5, t) * smoothstep(0.1, 0.5, d.y);
                vec3 mdir = normalize(vec3(-0.35, 0.3, -0.89));
                col += vec3(0.4, 0.62, 0.52) * sHalo(d, mdir, 2.8, 3.2) * 0.26 * uSGlow;
                col = sMoonComp(col, d, mdir, 0.06 * uSMoon, vec3(0.86, 0.95, 0.9), t);
                vec2 cl = sCloud(d, mdir, t, s, 7.0, 0.55);
                float ml = pow(max(dot(d, mdir), 0.0), 6.0);
                col = sCloudCol(col, cl, vec3(0.04, 0.06, 0.07), vec3(0.14, 0.2, 0.2) + vec3(0.32, 0.42, 0.38) * ml, 0.6 * uSClouds);
                float az = atan(d.x, d.z);
                // distant mist behind the trees
                vec2 mp = d.xz / (abs(d.y) + 0.08) * 0.35 / s + vec2(t * 0.04, 0.0);
                float mist = fbm2(mp * 2.0 + fbm2(mp) * 1.0);
                float band = exp(-abs(d.y) * (7.0 / max(0.4, uSFog)));
                vec3 mistCol = vec3(0.4, 0.52, 0.5) * (0.5 + 0.7 * mist) + vec3(0.2, 0.3, 0.26) * ml;
                col = mix(col, mistCol, clamp(band * (0.4 + 0.7 * mist) * uSFog * 0.8, 0.0, 0.85));
                // dead tree silhouettes: trunk, forking branches
                float treeH = 0.0;
                for (int i = 0; i < 6; i++) {
                    float a0 = float(i) * 1.047 + h11(float(i)) * 0.4;
                    float da = mod(az - a0 + PI, 2.0 * PI) - PI;
                    float h = 0.1 + 0.08 * h11(float(i) + 5.0);
                    float yy = d.y / h;
                    float w = mix(0.012, 0.0025, clamp(yy, 0.0, 1.0));
                    float aa = fwidth(da) * 1.2 + 0.001;
                    float trunk = (1.0 - smoothstep(w, w + aa, abs(da))) * step(0.0, d.y) * (1.0 - smoothstep(0.98, 1.0, yy));
                    float br = 0.0;
                    for (int k = 0; k < 3; k++) {
                        float fk = float(k);
                        float y0 = 0.35 + 0.2 * fk;
                        float sd = (mod(float(i) + fk, 2.0) < 1.0 ? 1.0 : -1.0);
                        float bx = da - sd * (yy - y0) * 0.09;
                        br = max(br, (1.0 - smoothstep(0.0025, 0.0025 + aa, abs(bx))) * step(y0, yy) * step(yy, y0 + 0.4 - 0.1 * fk) * step(0.0, d.y));
                    }
                    treeH = max(treeH, max(trunk, br));
                }
                // iron fence in front: posts with spear tips and two rails
                float fu = az / (2.0 * PI) * 90.0;
                float fx = abs(fract(fu) - 0.5);
                float aaF = fwidth(fu) * 1.2 + 0.01;
                float post = (1.0 - smoothstep(0.05, 0.05 + aaF, fx)) * step(0.0, d.y) * (1.0 - smoothstep(0.028, 0.03, d.y));
                float tip = (1.0 - smoothstep(0.0, aaF + 0.04, fx + (d.y - 0.03) * 12.0 - 0.0)) * step(0.03, d.y) * step(d.y, 0.042);
                float rail = (1.0 - smoothstep(0.0012, 0.0024, abs(d.y - 0.012))) + (1.0 - smoothstep(0.0012, 0.0024, abs(d.y - 0.024)));
                float fence = clamp(post + tip * 0.8 + rail * step(0.0, d.y), 0.0, 1.0);
                col = mix(col, vec3(0.01, 0.016, 0.018), max(treeH, fence) * 0.96);
                // foreground mist rolling over the fence
                float fm = fbm2(d.xz / (abs(d.y) + 0.05) * 0.2 / s + vec2(t * 0.06, 3.0));
                col = mix(col, vec3(0.42, 0.52, 0.5) * (0.5 + 0.7 * fm), clamp(exp(-abs(d.y) * 14.0) * (0.3 + fm) * uSFog * 0.7, 0.0, 0.8));
                return mix(col, vec3(0.1, 0.13, 0.13) * (0.5 + mist), smoothstep(0.0, -0.1, d.y));
            }

            /* 112 giant pumpkin moon */
            vec3 halPumpkinMoon(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.42, 0.13, 0.03), vec3(0.13, 0.045, 0.03), vec3(0.018, 0.009, 0.022));
                col += sStars(d, uStars * 0.8, t) * smoothstep(0.1, 0.5, d.y);
                vec3 mdir = normalize(vec3(0.0, 0.3, -0.95));
                float md = dot(d, mdir);
                float rad = 0.17 * uSMoon;
                col += vec3(1.0, 0.42, 0.07) * sHalo(d, mdir, 2.4, 2.6) * 0.45 * uSGlow;
                if (md > 0.0) {
                    vec2 q = local2(d, mdir, rad);
                    vec2 e = vec2(q.x, q.y * 0.9);
                    float re = length(e);
                    float ang = atan(e.y, e.x);
                    float lobes = 0.94 + 0.045 * cos(ang * 10.0);
                    float aa = fwidth(re) * 1.3 + 0.004;
                    float disc = 1.0 - smoothstep(lobes - aa, lobes + aa, re);
                    // rib shading: shaded grooves, lit ridges, soft spherical falloff
                    float ribs = 0.72 + 0.28 * (0.5 + 0.5 * cos(ang * 10.0)) * (1.0 - re * 0.2);
                    float sph = sqrt(max(1.0 - re * re * 0.9, 0.0));
                    vec3 pc = mix(vec3(1.0, 0.45, 0.05), vec3(0.72, 0.16, 0.015), smoothstep(0.2, 1.0, re)) * ribs * (0.7 + 0.45 * sph);
                    pc *= 0.9 + 0.15 * noise2(q * 9.0);
                    float flick = 0.85 + 0.15 * sin(t * 9.0) * sin(t * 5.3);
                    float aF = fwidth(q.x) * 1.3 + 0.004;
                    // triangular eyes
                    vec2 le = vec2(abs(q.x) - 0.33, q.y - 0.25);
                    float eyes = 1.0 - smoothstep(0.0, aF, max(abs(le.x) * 1.1 + le.y * 0.9, -le.y) - 0.16);
                    eyes *= step(le.y, 0.0) * step(-0.28, le.y);
                    // nose
                    float nose = 1.0 - smoothstep(0.0, aF, max(abs(q.x) * 1.5 + (q.y + 0.02) * 0.9, -(q.y + 0.02)) - 0.1);
                    nose *= step(q.y + 0.02, 0.0) * step(-0.14, q.y + 0.02);
                    // jagged grin
                    float mouth = 1.0 - smoothstep(0.0, aF, abs(q.y + 0.42 + 0.35 * q.x * q.x) - 0.1);
                    float teeth = smoothstep(0.0, 0.05, abs(fract(q.x * 3.6) - 0.5) - 0.16 * (0.5 + 0.5 * step(0.0, q.y + 0.42 + 0.35 * q.x * q.x)));
                    mouth *= step(abs(q.x), 0.58) * mix(1.0, 0.0, (1.0 - teeth) * step(0.0, -(q.y + 0.42 + 0.35 * q.x * q.x) + 0.02));
                    float face = clamp(eyes + nose + mouth, 0.0, 1.0);
                    // inner candle glow: bright core, softer at the cut edges
                    vec3 glowC = mix(vec3(1.7, 0.9, 0.22), vec3(1.9, 1.3, 0.5), 0.5 + 0.5 * noise2(q * 6.0 + t * 2.0)) * flick;
                    pc = mix(pc, glowC, face);
                    col = mix(col, pc * 1.05, disc);
                    // face light spill onto the rind
                    col += vec3(1.0, 0.5, 0.1) * exp(-re * 3.0) * 0.05 * flick * disc;
                    // stem and curly vine
                    vec2 sp = vec2(q.x - 0.05, q.y - 0.97);
                    float stem = (1.0 - smoothstep(0.0, aF, abs(sp.x + sp.y * 0.3) - 0.07)) * step(abs(sp.y), 0.16);
                    col = mix(col, vec3(0.16, 0.26, 0.05), stem);
                }
                vec2 cl = sCloud(d, mdir, t, s, 4.0, 0.52);
                float ml = pow(max(md, 0.0), 6.0);
                col = sCloudCol(col, cl, vec3(0.06, 0.025, 0.025), vec3(0.2, 0.08, 0.04) + vec3(0.8, 0.3, 0.06) * ml, 0.7 * uSClouds);
                col = sHaze(col, d, vec3(0.6, 0.2, 0.04), 0.5, 6.0);
                col += vec3(1.0, 0.5, 0.1) * sEmber(d, t, uSPart * 0.5, 0.05) * 0.5;
                float pn = sPines(d, 71.0, -0.004, 0.9);
                col = mix(col, vec3(0.025, 0.01, 0.006), pn * 0.97);
                return ground(col, d, vec3(0.04, 0.016, 0.008));
            }

            /* 113 witch night: green flames in the sky, moon with a flying witch */
            vec3 halWitchNight(vec3 d, float t, float s) {
                vec3 col = sGrad(d, vec3(0.09, 0.2, 0.1), vec3(0.035, 0.065, 0.09), vec3(0.006, 0.008, 0.03));
                col += sStars(d, uStars, t) * smoothstep(0.05, 0.4, d.y);
                vec2 uv = d.xz / (max(d.y, 0.0) + 0.2) * 0.7 / s + vec2(t * 0.02, 0.0);
                vec2 w = vec2(fbm2(uv * 1.1 + 2.0 + t * 0.02), fbm2(uv * 1.1 + 7.0));
                float n = fbm2(uv + (w - 0.5) * 1.4);
                float n3 = fbm2(uv + vec2(0.05, 0.04) + (w - 0.5) * 1.4);
                float sw = smoothstep(0.42, 0.78, n) * smoothstep(0.0, 0.2, d.y) * uSClouds;
                float lit = clamp(0.5 + (n - n3) * 9.0, 0.0, 1.0);
                vec3 brew = mix(vec3(0.04, 0.16, 0.07), vec3(0.4, 0.95, 0.3), lit * 0.7 + 0.15);
                col = mix(col, brew * 0.75, sw * 0.7);
                col += vec3(0.35, 1.0, 0.3) * pow(max(fbm2(uv * 2.0 + t * 0.05) - 0.5, 0.0), 2.0) * 2.5 * sw * uSGlow;
                vec3 mdir = normalize(vec3(-0.25, 0.42, -0.87));
                col += vec3(0.3, 0.9, 0.2) * sHalo(d, mdir, 2.6, 3.0) * 0.28 * uSGlow;
                col = sMoonComp(col, d, mdir, 0.07 * uSMoon, vec3(0.82, 1.0, 0.72), t);
                // witch on a broom crossing the moon
                vec2 q = local2(d, mdir, 0.07 * uSMoon);
                float ph = fract(t * 0.028);
                float vis = smoothstep(0.0, 0.05, ph) * smoothstep(1.0, 0.95, ph);
                vec2 p = q - vec2(mix(-4.5, 4.5, ph), 0.22 * sin(ph * 6.2831 * 2.0));
                float aa = fwidth(q.x) * 1.5 + 0.004;
                float broom = (1.0 - smoothstep(0.0, aa, abs(p.y + 0.1 * p.x) - 0.014)) * step(abs(p.x + 0.0), 0.5) * step(-0.45, p.x);
                float bristle = 1.0 - smoothstep(0.0, aa, length((p - vec2(-0.55, -0.055)) * vec2(1.0, 2.6)) - 0.11);
                float body = 1.0 - smoothstep(0.0, aa, length((p - vec2(0.0, 0.1)) * vec2(1.6, 1.0)) - 0.1);
                float head = 1.0 - smoothstep(0.0, aa, length(p - vec2(0.05, 0.27)) - 0.045);
                float hatC = (1.0 - smoothstep(0.0, aa, abs(p.x - 0.05 + (p.y - 0.3) * 0.12) - clamp(0.07 - (p.y - 0.3) * 0.3, 0.0, 0.07))) * step(0.3, p.y) * step(p.y, 0.5);
                float brim = (1.0 - smoothstep(0.0, aa, abs(p.y - 0.3) - 0.012)) * step(abs(p.x - 0.05), 0.13);
                float cape = (1.0 - smoothstep(0.0, aa, abs(p.y - 0.1 + 0.25 * (p.x + 0.1)) - 0.03)) * step(p.x, -0.05) * step(-0.3, p.x);
                float sil = clamp(broom + bristle + body + head + hatC + brim + cape, 0.0, 1.0) * vis;
                col = mix(col, vec3(0.008, 0.01, 0.012), sil * 0.97);
                col += vec3(0.4, 1.0, 0.3) * exp(-length((p - vec2(-0.55, -0.05)) * vec2(0.7, 1.4)) * 3.5) * vis * 0.3 * (0.6 + 0.4 * noise2(p * 20.0 + t * 4.0));
                col = sHaze(col, d, vec3(0.12, 0.3, 0.12), 0.5, 6.0);
                col += vec3(0.4, 1.0, 0.3) * sEmber(d, t, uSPart * 0.7, 0.055) * 0.6;
                float pn = sPines(d, 81.0, -0.004, 0.9);
                col = mix(col, vec3(0.008, 0.016, 0.012), pn * 0.97);
                return ground(col, d, vec3(0.018, 0.035, 0.026));
            }

            /* 114 eclipse: black sun with a pale corona over a bruised sky */
            vec3 halEclipse(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.45, 0.16, 0.1), vec3(0.07, 0.045, 0.13), smoothstep(0.0, 0.38, yy));
                col = mix(col, vec3(0.01, 0.009, 0.026), smoothstep(0.3, 1.0, yy));
                col += sStars(d, uStars * 0.8, t) * smoothstep(0.1, 0.5, yy);
                vec3 n = normalize(vec3(0.05, 0.45, -0.89));
                float ca = dot(d, n);
                if (ca > 0.0) {
                    vec2 q = local2(d, n, 0.075 * uSMoon);
                    float r = length(q);
                    vec2 cs = q / max(r, 1e-3);
                    float st = fbm2(cs * 3.0 + vec2(t * 0.03, 0.0));
                    float st2 = fbm2(cs * 9.0 - vec2(0.0, t * 0.02));
                    float fall = 0.14 + 1.0 * st * st + 0.22 * st2;
                    float dr = max(r - 1.0, 0.0);
                    vec3 cc = vec3(0.95, 0.9, 1.0) * exp(-dr / fall) * (0.55 + 0.6 * st2) * 1.05 * uSGlow;
                    cc += vec3(1.0, 0.55, 0.2) * exp(-dr * 16.0) * 1.0 * uSGlow;
                    cc += vec3(1.0, 0.2, 0.1) * exp(-pow((r - 1.01) / 0.02, 2.0)) * (0.35 + st2) * 0.8;
                    // soft diamond ring bead
                    float ba = 2.3 + 0.2 * sin(t * 0.1);
                    vec2 bq = q - vec2(cos(ba), sin(ba));
                    cc += vec3(1.0, 0.92, 0.82) * (exp(-dot(bq, bq) * 220.0) * 4.0 + exp(-length(bq) * 3.5) * 0.35);
                    col += cc;
                    float aa = fwidth(r) * 1.3 + 0.004;
                    col = mix(vec3(0.004, 0.003, 0.008), col, smoothstep(1.0 - aa, 1.0 + aa, r));
                }
                vec2 cl = sCloud(d, n, t, s, 9.0, 0.55);
                float ml = pow(max(ca, 0.0), 8.0);
                col = sCloudCol(col, cl, vec3(0.03, 0.015, 0.04), vec3(0.14, 0.07, 0.15) + vec3(0.6, 0.25, 0.12) * ml, 0.6 * uSClouds);
                col = sHaze(col, d, vec3(0.55, 0.2, 0.1), 0.45, 6.0);
                float pn = sPines(d, 91.0, -0.004, 0.55);
                col = mix(col, vec3(0.012, 0.006, 0.014), pn * 0.97);
                return ground(col, d, vec3(0.018, 0.01, 0.018));
            }

            /* 115 green mist: eerie phosphorescent fog with drifting wisps */
            vec3 halGreenMist(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.1, 0.3, 0.17), vec3(0.02, 0.085, 0.07), smoothstep(0.0, 0.5, yy));
                col = mix(col, vec3(0.004, 0.016, 0.02), smoothstep(0.4, 1.0, yy));
                col += sStars(d, uStars * 0.6, t) * smoothstep(0.2, 0.6, yy);
                // soft domain-warped fog banks, drifting slowly
                vec2 uv = d.xz / (yy + 0.35) * 0.9 / s + vec2(t * 0.018, -t * 0.006);
                vec2 w = vec2(fbm2(uv * 0.8 + 2.0), fbm2(uv * 0.8 + 9.0));
                float m = fbm2(uv + (w - 0.5) * 1.2);
                float m2 = fbm2(uv + vec2(0.05, 0.04) + (w - 0.5) * 1.2);
                float lit = clamp(0.5 + (m - m2) * 9.0, 0.0, 1.0);
                float mist = smoothstep(0.3, 0.75, m) * uSFog * smoothstep(-0.05, 0.1, d.y);
                vec3 mc = mix(vec3(0.02, 0.1, 0.07), vec3(0.3, 0.72, 0.4), lit * 0.55 + m * 0.2);
                col = mix(col, mc * 0.7, clamp(mist * 0.7, 0.0, 0.85));
                col += vec3(0.3, 0.9, 0.38) * pow(max(m - 0.55, 0.0), 2.0) * 1.2 * uSGlow * smoothstep(-0.05, 0.1, d.y);
                // a pale sickly moon glowing through the mist
                vec3 mdir = normalize(vec3(-0.3, 0.36, -0.88));
                col += vec3(0.4, 0.95, 0.5) * sHalo(d, mdir, 2.5, 2.4) * 0.22 * uSGlow;
                col += sMoonGlow(d, mdir, 0.05 * uSMoon, vec3(0.5, 1.0, 0.55));
                vec4 mo = sMoon(d, mdir, 0.05 * uSMoon, vec3(0.78, 1.0, 0.8), t);
                col = mix(col, mo.rgb * (1.0 - 0.5 * mist), mo.a);
                // slow drifting wisps: round, soft spirits (no stripes)
                float az = atan(d.x, d.z);
                float el = asin(clamp(d.y, -1.0, 1.0));
                float wisp = 0.0;
                for (int i = 0; i < 2; i++) {
                    float fi = float(i);
                    float N = 16.0 + fi * 10.0;
                    vec2 q = vec2(az * N / (2.0 * PI) + sin(t * 0.2 + fi) * 0.6, el * N / (2.0 * PI) - t * 0.08 * (1.0 + fi));
                    vec2 cell = floor(q), f = fract(q) - 0.5;
                    cell.x = mod(cell.x, N);
                    vec3 h = h33(vec3(cell, fi + 61.0));
                    if (h.x < 0.3 * uSPart) {
                        vec2 o = f - (h.yz - 0.5) * 0.5;
                        o.x += 0.12 * sin(o.y * 6.0 + t * 1.5 + h.z * 9.0);
                        wisp += exp(-dot(o, o) / 0.03) * (0.55 + 0.45 * sin(t * 2.0 + h.z * 30.0));
                    }
                }
                col += vec3(0.55, 1.0, 0.6) * wisp * 0.55;
                // low ground fog bank
                float gf = fbm2(d.xz / (abs(d.y) + 0.06) * 0.25 / s + vec2(t * 0.05, 1.0));
                col = mix(col, vec3(0.12, 0.32, 0.2) * (0.6 + gf), clamp(exp(-abs(d.y) * 9.0 / max(0.4, uSFog)) * uSFog * (0.4 + 0.5 * gf), 0.0, 0.88));
                float pn = sPines(d, 101.0, -0.004, 0.8);
                col = mix(col, vec3(0.01, 0.03, 0.022), pn * 0.9);
                return mix(col, vec3(0.05, 0.14, 0.09) * (0.5 + m), smoothstep(0.0, -0.1, d.y));
            }

vec3 seasonSky(int m, vec3 d, float t, float s) {
                if (m == 100) return xmasSnowyAurora(d, t, s);
                if (m == 101) return xmasStarrySnow(d, t, s);
                if (m == 102) return xmasSleighMoon(d, t, s);
                if (m == 103) return xmasWarmDusk(d, t, s);
                if (m == 104) return xmasFrostDome(d, t, s);
                if (m == 105) return xmasCandy(d, t, s);
                if (m == 106) return xmasSilentNight(d, t, s);
                if (m == 107) return xmasNorthern(d, t, s);
                if (m == 108) return halBloodMoon(d, t, s);
                if (m == 109) return halHauntedBats(d, t, s);
                if (m == 110) return halThunderOrange(d, t, s);
                if (m == 111) return halGraveyardFog(d, t, s);
                if (m == 112) return halPumpkinMoon(d, t, s);
                if (m == 113) return halWitchNight(d, t, s);
                if (m == 114) return halEclipse(d, t, s);
                return halGreenMist(d, t, s);
            }
            """;
}
