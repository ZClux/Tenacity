package tenacity.gfx;

/**
 * Extra "Shader Sky" modes (index 10+). Every function takes the world-space view direction, so everything stays fixed
 * in the sky while the camera turns. Appended to WorldFx's sky shader (uses its noise helpers and uniforms).
 */
final class SkyShaders {
    private SkyShaders() {
    }

    static final int COUNT = 34;

    static final String FUNCS = """
            const float PI = 3.14159265;
            uniform float uCustom;

            vec3 h33(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }
            float h11(float n) { return fract(sin(n * 12.9898 + 4.1414) * 43758.5453); }
            float fbm4(vec3 p) { float v = 0.0, a = 0.5; for (int i = 0; i < 4; i++) { v += a * noise3(p); p = p * 2.02 + vec3(1.7, 9.2, 3.1); a *= 0.5; } return v; }
            mat2 mm2(float a) { float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }

            void basis(vec3 n, out vec3 tx, out vec3 ty) {
                vec3 up = abs(n.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                tx = normalize(cross(n, up));
                ty = cross(tx, n);
            }

            /* local tangent-plane coordinates of d around n (gnomonic), in units of r */
            vec2 local2(vec3 d, vec3 n, float r) {
                vec3 tx, ty; basis(n, tx, ty);
                return vec2(dot(d, tx), dot(d, ty)) / max(dot(d, n), 1e-3) / r;
            }

            vec3 starLayer(vec3 d, float n, float dens, float t) {
                vec3 g = d * n; vec3 c = floor(g); vec3 h = h33(c);
                if (h.x > dens) return vec3(0.0);
                vec3 p = c + 0.25 + 0.5 * h.yzx;
                float r = length(g - p);
                float sz = max(0.05 + 0.1 * h.y * h.y, n * 0.0009);
                float b = exp(-r * r / (sz * sz)) * (0.3 + 1.5 * pow(h.z, 3.0));
                b *= 0.72 + 0.28 * sin(t * (0.8 + 2.5 * h.y) + h.x * 60.0);
                return mix(vec3(0.65, 0.78, 1.0), vec3(1.0, 0.84, 0.64), h.z) * b;
            }

            vec3 stars3(vec3 d, float amt, float t) {
                if (amt <= 0.0) return vec3(0.0);
                return starLayer(d, 280.0, 0.30 * amt, t) * 0.9 + starLayer(d, 140.0, 0.10 * amt, t * 0.7) * 1.3
                     + starLayer(d, 64.0, 0.035 * amt, t * 0.5) * 1.8;
            }

            float gridLines(vec2 p, float w) {
                vec2 g = abs(fract(p - 0.5) - 0.5) / max(fwidth(p), vec2(1e-4));
                return 1.0 - smoothstep(0.0, w, min(g.x, g.y));
            }

            float checker(vec2 p) {
                vec2 w = fwidth(p) + 0.001;
                vec2 i = 2.0 * (abs(fract((p - 0.5 * w) * 0.5) - 0.5) - abs(fract((p + 0.5 * w) * 0.5) - 0.5)) / w;
                return 0.5 - 0.5 * i.x * i.y;
            }

            vec3 hue(float x) { return clamp(abs(mod(x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0); }

            vec3 ground(vec3 col, vec3 d, vec3 g) { return mix(col, g, smoothstep(0.0, -0.3, d.y)); }

            /* ---------------------------------------------------------------- 10 galaxy spiral */
            vec3 galaxySpiral(vec3 d, float t, float s) {
                vec3 n = normalize(vec3(-0.15, 0.62, -0.77));
                vec3 col = vec3(0.004, 0.006, 0.018) + vec3(0.025, 0.012, 0.05) * pow(fbm4(d * 2.5), 2.0) * 2.0;
                col += stars3(d, uStars, t);
                float w = dot(d, n);
                if (w > 0.0) {
                    vec2 q = local2(d, n, 0.55 * s);
                    q = mm2(0.5) * q;
                    q.y /= 0.5;
                    float r = length(q);
                    vec2 qr = mm2(-t * 0.03) * q;
                    float a = atan(qr.y, qr.x);
                    float spiral = a * 2.0 - log(r + 0.02) * 3.2;
                    float arms = pow(0.5 + 0.5 * cos(spiral), 2.5);
                    float n1 = fbm2(qr * 4.0);
                    float n2 = fbm2(qr * 11.0 + 3.0);
                    float disc = exp(-r * 2.6);
                    float dens = disc * (0.18 + 1.2 * arms * (0.4 + n1));
                    float dust = smoothstep(0.45, 0.72, n2) * pow(0.5 + 0.5 * cos(spiral - 0.7), 3.0) * smoothstep(0.05, 0.3, r);
                    vec3 armCol = mix(vec3(0.35, 0.5, 1.0), vec3(0.8, 0.6, 1.0), n1);
                    vec3 g = armCol * dens * 1.3 * (1.0 - 0.75 * dust);
                    g += vec3(1.0, 0.4, 0.65) * pow(n2, 5.0) * arms * disc * 7.0;
                    g += vec3(1.0, 0.82, 0.55) * (exp(-r * r * 30.0) * 2.4 + exp(-r * 6.0) * 0.5);
                    col += g * smoothstep(0.0, 0.2, w);
                }
                return ground(col, d, vec3(0.01, 0.012, 0.025));
            }

            /* ---------------------------------------------------------------- 11 milky way */
            vec3 milkyWay(vec3 d, float t, float s) {
                vec3 n = normalize(vec3(0.45, 0.35, 0.82));
                float b = dot(d, n);
                vec3 cdir = normalize(vec3(0.3, 0.45, -0.6));
                cdir = normalize(cdir - n * dot(cdir, n));
                float width = 0.17 * s;
                float band = exp(-b * b / (width * width));
                float bulge = exp(-b * b / (width * width * 2.5)) * smoothstep(0.4, 1.0, dot(d, cdir));
                vec3 p = d * 3.0 / s;
                float f = fbm4(p * 2.0 + vec3(0.0, 0.0, t * 0.005));
                float f2 = fbm4(p * 6.0 + 4.0);
                float glowv = band * (0.3 + 0.9 * f) + bulge * 1.5;
                float rift = smoothstep(0.42, 0.66, f2) * exp(-b * b / (width * width * 0.22)) * (0.55 + 0.45 * bulge);
                vec3 col = mix(vec3(0.02, 0.035, 0.07), vec3(0.004, 0.007, 0.02), smoothstep(0.0, 0.8, d.y));
                col += vec3(0.03, 0.07, 0.06) * exp(-abs(d.y) * 10.0);
                vec3 mw = mix(vec3(0.45, 0.55, 0.85), vec3(1.0, 0.82, 0.6), clamp(bulge * 1.2 + f * 0.2, 0.0, 1.0));
                col += mw * glowv * 0.32 * (1.0 - 0.85 * rift);
                col += vec3(1.0, 0.35, 0.5) * pow(f2, 6.0) * band * 1.6;
                col += stars3(d, uStars * (0.45 + 1.4 * band), t);
                return ground(col, d, vec3(0.015, 0.025, 0.04));
            }

            /* ---------------------------------------------------------------- 12 volumetric clouds */
            float cloudDen(vec3 p, float t, float s, bool hi) {
                vec3 q = p * 0.22 / s + vec3(t * 0.02, 0.0, t * 0.012);
                float h = clamp((p.y - 1.0) / 1.2, 0.0, 1.0);
                float prof = smoothstep(0.0, 0.2, h) * smoothstep(1.0, 0.4, h);
                float base = hi ? fbm4(q) : (noise3(q) * 0.55 + noise3(q * 2.02 + 1.7) * 0.28);
                if (hi) base -= 0.1 * noise3(q * 6.0 + t * 0.03);
                return clamp((base - 0.47 + prof * 0.12 - (1.0 - prof) * 0.35) * 3.5, 0.0, 1.0);
            }

            vec3 volClouds(vec3 d, float t, float s) {
                vec3 sun = normalize(vec3(0.55, 0.32, -0.77));
                float sd = max(dot(d, sun), 0.0);
                float y = max(d.y, 0.0);
                vec3 sky = mix(vec3(0.66, 0.78, 0.92), vec3(0.2, 0.4, 0.8), pow(y, 0.5));
                sky += vec3(1.0, 0.75, 0.45) * pow(sd, 8.0) * 0.3 + vec3(1.0, 0.9, 0.7) * pow(sd, 300.0) * 1.5;
                sky += vec3(1.0, 0.96, 0.88) * smoothstep(0.9993, 0.9997, sd) * 6.0;
                if (d.y < 0.012) return ground(sky, d, vec3(0.45, 0.52, 0.6));
                float t0 = 1.0 / d.y, t1 = min(2.2 / d.y, 70.0);
                if (t0 > 70.0) return sky;
                const int STEPS = 26;
                float stepL = (t1 - t0) / float(STEPS);
                float trans = 1.0;
                vec3 acc = vec3(0.0);
                float tt = t0 + stepL * hash2(gl_FragCoord.xy);
                float phase = 0.7 + 1.6 * pow(sd, 6.0);
                for (int i = 0; i < STEPS; i++) {
                    vec3 p = d * tt;
                    float den = cloudDen(p, t, s, true);
                    if (den > 0.01) {
                        float ls = cloudDen(p + sun * 0.3, t, s, false);
                        float light = exp(-ls * 3.5);
                        float powder = 1.0 - exp(-den * 5.0);
                        float hh = clamp((p.y - 1.0) / 1.2, 0.0, 1.0);
                        vec3 lc = vec3(1.0, 0.95, 0.86) * light * phase * powder * 1.5 + vec3(0.42, 0.52, 0.68) * (0.35 + 0.5 * hh);
                        float a = 1.0 - exp(-den * stepL * 6.0);
                        acc += trans * a * lc;
                        trans *= 1.0 - a;
                        if (trans < 0.02) break;
                    }
                    tt += stepL;
                }
                return mix(sky, sky * trans + acc, exp(-t0 * 0.035));
            }

            /* ---------------------------------------------------------------- 13 golden hour */
            vec3 goldenHour(vec3 d, float t, float s) {
                vec3 sun = normalize(vec3(-0.2, 0.07, -0.98));
                float sd = max(dot(d, sun), 0.0);
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(1.2, 0.66, 0.32), vec3(0.78, 0.6, 0.56), smoothstep(0.0, 0.2, yy));
                col = mix(col, vec3(0.2, 0.33, 0.6), smoothstep(0.12, 0.8, yy));
                col += vec3(1.3, 0.6, 0.2) * pow(sd, 6.0) * 0.55 + vec3(1.4, 0.9, 0.5) * pow(sd, 60.0) * 1.1;
                col += vec3(3.0, 2.2, 1.4) * smoothstep(0.99955, 0.99975, sd);
                if (d.y > 0.0) {
                    vec2 uv = d.xz / (d.y + 0.08) * 0.6 / s + vec2(t * 0.006, 0.0);
                    float c = fbm2(vec2(uv.x * 0.4, uv.y * 1.6) * 1.5 + fbm2(uv * 2.0) * 0.8);
                    float cl = smoothstep(0.5, 0.8, c) * smoothstep(0.0, 0.12, d.y);
                    vec3 cc = mix(vec3(1.1, 0.52, 0.35), vec3(1.7, 1.05, 0.6), pow(sd, 4.0));
                    cc = mix(cc, vec3(0.6, 0.45, 0.58), smoothstep(0.2, 0.7, d.y));
                    col = mix(col, cc, cl * 0.75);
                }
                return ground(col, d, vec3(0.45, 0.3, 0.24));
            }

            /* ---------------------------------------------------------------- 14 blue hour */
            vec3 blueHour(vec3 d, float t, float s) {
                vec3 sun = normalize(vec3(0.3, -0.12, -0.95));
                float sd = dot(d, sun);
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.42, 0.42, 0.66), vec3(0.17, 0.25, 0.52), smoothstep(0.0, 0.3, yy));
                col = mix(col, vec3(0.035, 0.06, 0.18), smoothstep(0.25, 1.0, yy));
                col += vec3(0.95, 0.42, 0.22) * pow(max(sd, 0.0), 3.0) * exp(-yy * 7.0) * 0.9;
                float anti = max(-sd, 0.0);
                col += vec3(0.6, 0.32, 0.48) * anti * exp(-pow((yy - 0.12) / 0.07, 2.0)) * 0.5;
                col = mix(col, col * vec3(0.55, 0.6, 0.85), anti * smoothstep(0.08, 0.0, yy) * 0.6);
                col += stars3(d, uStars * 0.6, t) * smoothstep(0.15, 0.8, yy) * 0.8;
                vec3 v = normalize(vec3(0.42, 0.2, -0.88));
                float vd = max(dot(d, v), 0.0);
                col += vec3(1.0, 0.97, 0.9) * (smoothstep(0.999985, 1.0, vd) * 3.0 + pow(vd, 3000.0) * 0.5);
                return ground(col, d, vec3(0.07, 0.08, 0.15));
            }

            /* ---------------------------------------------------------------- 15 cotton candy */
            vec3 cottonCandy(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 hor = mix(vec3(1.0, 0.72, 0.82), vec3(0.85, 0.75, 1.0), d.x * 0.5 + 0.5);
                vec3 col = mix(hor, vec3(0.58, 0.74, 1.0), smoothstep(0.0, 0.6, yy));
                col = mix(col, vec3(0.44, 0.56, 0.95), smoothstep(0.5, 1.0, yy));
                col += vec3(1.0, 0.85, 0.9) * pow(max(dot(d, normalize(vec3(0.0, 0.2, -1.0))), 0.0), 12.0) * 0.25;
                if (d.y > 0.0) {
                    vec2 uv = d.xz / (d.y + 0.15) * 0.7 / s + vec2(t * 0.012, t * 0.004);
                    float wv = fbm2(uv * 0.8 + 3.0);
                    float n = fbm2(uv * 1.4 + wv);
                    float sh = fbm2(uv * 1.4 + wv + vec2(0.07, 0.05));
                    float c = smoothstep(0.46, 0.72, n);
                    float lit = clamp((n - sh) * 7.0 + 0.6, 0.0, 1.0);
                    vec3 cc = mix(vec3(0.86, 0.62, 0.9), vec3(1.05, 0.92, 0.97), lit);
                    col = mix(col, cc, c * smoothstep(0.0, 0.18, d.y) * 0.95);
                }
                return ground(col, d, vec3(0.8, 0.66, 0.86));
            }

            /* ---------------------------------------------------------------- 16 northern lights v2 */
            float tri(float x) { return clamp(abs(fract(x) - 0.5), 0.01, 0.49); }
            vec2 tri2(vec2 p) { return vec2(tri(p.x) + tri(p.y), tri(p.y + tri(p.x))); }
            float triNoise(vec2 p, float time) {
                float z = 1.8, z2 = 2.5, rz = 0.0;
                p *= mm2(p.x * 0.06);
                vec2 bp = p;
                for (int i = 0; i < 5; i++) {
                    vec2 dg = tri2(bp * 1.85) * 0.75;
                    dg *= mm2(time * 0.06);
                    p -= dg / z2;
                    bp *= 1.3; z2 *= 0.45; z *= 0.42;
                    p *= 1.21 + (rz - 1.0) * 0.02;
                    rz += tri(p.x + tri(p.y)) * z;
                    p *= -mat2(0.95534, 0.29552, -0.29552, 0.95534);
                }
                return clamp(1.0 / pow(rz * 29.0, 1.3), 0.0, 0.55);
            }

            vec3 auroraV2(vec3 d, float t, float s) {
                vec3 col = mix(vec3(0.02, 0.04, 0.07), vec3(0.003, 0.008, 0.02), smoothstep(0.0, 0.7, d.y));
                col += vec3(0.04, 0.09, 0.1) * exp(-abs(d.y) * 6.0);
                col += stars3(d, uStars, t);
                if (d.y > 0.0) {
                    vec4 aur = vec4(0.0), avg = vec4(0.0);
                    float jit = hash2(gl_FragCoord.xy);
                    for (int k = 0; k < 40; k++) {
                        float i = float(k);
                        float pt = (0.8 + pow(i, 1.4) * 0.002) / (d.y * 2.0 + 0.4) - 0.006 * jit * smoothstep(0.0, 15.0, i);
                        vec3 bp = d * pt;
                        float rz = triNoise(bp.zx / s, t);
                        vec4 c2 = vec4((sin(1.0 - vec3(2.15, -0.5, 1.2) + i * 0.043) * 0.5 + 0.5) * rz, rz);
                        avg = mix(avg, c2, 0.5);
                        aur += avg * exp2(-i * 0.065 - 2.5) * smoothstep(0.0, 5.0, i);
                    }
                    aur *= clamp(d.y * 15.0 + 0.4, 0.0, 1.0);
                    col = col * (1.0 - aur.a) + aur.rgb * 1.9;
                }
                return ground(col, d, vec3(0.01, 0.02, 0.035));
            }

            /* ---------------------------------------------------------------- 17 black hole */
            vec3 blackHole(vec3 d, float t, float s) {
                vec3 n = normalize(vec3(0.15, 0.3, -0.94));
                float R = 0.11 * s;
                float ca = clamp(dot(d, n), -1.0, 1.0);
                float ang = acos(ca);
                vec3 side = d - n * ca; float sl = length(side);
                side = sl > 1e-5 ? side / sl : vec3(1.0, 0.0, 0.0);
                float beta = ang - R * R * 2.4 / max(ang, 1e-3);
                vec3 dl = n * cos(beta) + side * sin(beta);
                vec3 col = vec3(0.004, 0.005, 0.012) + vec3(0.06, 0.025, 0.09) * pow(fbm4(dl * 2.2), 2.0) * 1.6 + stars3(dl, uStars, t);
                if (ca > 0.0) {
                    vec2 q = local2(d, n, R);
                    float r = length(q);
                    vec2 e = vec2(q.x, q.y / 0.17);
                    float re = length(e);
                    float spin = t * 0.5 / max(re, 1.0);
                    vec2 cs = mm2(spin) * (e / max(re, 1e-3));
                    float tex = fbm2(cs * re * 1.8 + vec2(re * 0.7, 0.0));
                    float rings = 0.55 + 0.45 * sin(re * 9.0 + tex * 5.0);
                    float dop = 1.0 - 0.75 * e.x / max(re, 1.0);
                    float mask = smoothstep(1.35, 1.7, re) * smoothstep(5.2, 2.6, re);
                    vec3 hot = mix(vec3(1.0, 0.42, 0.12), vec3(1.0, 0.9, 0.75), smoothstep(4.0, 1.6, re));
                    vec3 disc = hot * mask * (0.45 + 0.9 * tex) * rings * dop * 1.8 / sqrt(re);
                    // lensed far side of the disc arcing over (and under) the shadow
                    float ra = atan(q.y, q.x);
                    vec2 cr = mm2(t * 0.35) * vec2(cos(ra), sin(ra));
                    float halo = smoothstep(1.0, 1.12, r) * smoothstep(2.0, 1.25, r) * (0.55 + 0.45 * q.y / max(r, 1e-3));
                    col += hot * halo * (0.5 + 0.8 * fbm2(cr * 2.5 + r)) * (1.0 - 0.6 * q.x / max(r, 1e-3)) * 1.2;
                    if (q.y > 0.0) col += disc;
                    col *= smoothstep(0.96, 1.04, r);
                    col += vec3(1.0, 0.85, 0.65) * exp(-pow((r - 1.05) / 0.03, 2.0)) * 1.4;
                    if (q.y <= 0.0) col += disc;
                }
                return col;
            }

            /* ---------------------------------------------------------------- 18 planet rise */
            vec3 planetRise(vec3 d, float t, float s) {
                vec3 n = normalize(vec3(-0.42, 0.14, -0.9));
                float ca = dot(d, n);
                vec3 col = vec3(0.004, 0.006, 0.016) + stars3(d, uStars, t);
                col += vec3(0.05, 0.03, 0.09) * pow(fbm4(d * 2.0 + 5.0), 2.5) * 2.0;
                vec3 L = normalize(vec3(0.75, 0.35, 0.55));
                if (ca > 0.0) {
                    vec2 q = local2(d, n, 0.3 * s);
                    float r = length(q);
                    vec2 rq = mm2(0.35) * q;
                    vec2 e = vec2(rq.x, rq.y / 0.2);
                    float re = length(e);
                    float bands = 0.6 + 0.4 * sin(re * 40.0) * sin(re * 13.0 + 1.0);
                    float ring = smoothstep(1.32, 1.4, re) * smoothstep(2.4, 2.28, re) * bands;
                    ring *= 1.0 - 0.85 * smoothstep(1.84, 1.88, re) * smoothstep(1.98, 1.94, re);
                    vec3 ringCol = mix(vec3(0.9, 0.78, 0.62), vec3(0.62, 0.56, 0.5), smoothstep(1.4, 2.3, re));
                    vec3 ringLit = ringCol * (0.2 + 0.95 * smoothstep(-1.8, 1.2, rq.x));
                    if (rq.y > 0.0) col = mix(col, ringLit, ring * 0.9);
                    if (r < 1.0) {
                        vec3 nn = vec3(q, sqrt(1.0 - r * r));
                        vec3 rn = vec3(mm2(0.35) * nn.xy, nn.z);
                        float lam = max(dot(nn, L), 0.0);
                        float w = fbm2(vec2(rn.x * 3.0 + t * 0.02, rn.y * 16.0));
                        float bnd = sin(rn.y * 21.0 + w * 3.0) * 0.5 + 0.5;
                        vec3 pc = mix(vec3(0.72, 0.52, 0.36), vec3(0.96, 0.86, 0.7), bnd);
                        pc = mix(pc, vec3(0.62, 0.34, 0.24), smoothstep(0.62, 0.8, w) * 0.6);
                        float rsh = 1.0 - 0.7 * smoothstep(0.05, 0.0, abs(rn.y - 0.16 + rn.x * 0.1)) * step(0.0, -rn.x + 0.2);
                        vec3 surf = pc * (lam * 1.25 * rsh + 0.015);
                        surf += vec3(0.45, 0.6, 1.0) * pow(1.0 - nn.z, 3.0) * smoothstep(-0.2, 0.6, dot(nn, L)) * 0.7;
                        col = mix(col, surf, smoothstep(1.0, 0.985, r));
                    }
                    float hl = clamp(dot(q / max(r, 1e-3), L.xy) * 0.6 + 0.5, 0.0, 1.0);
                    col += vec3(0.4, 0.55, 1.0) * exp(-max(r - 1.0, 0.0) * 22.0) * smoothstep(0.98, 1.0, r) * hl * 0.6;
                    if (rq.y <= 0.0) col = mix(col, ringLit, ring * 0.9);
                }
                col += vec3(0.15, 0.3, 0.6) * exp(-abs(d.y) * 60.0) * 0.4;
                return mix(col, vec3(0.008, 0.01, 0.018), smoothstep(0.0, -0.02, d.y));
            }

            /* ---------------------------------------------------------------- 19 eclipse */
            vec3 eclipse(vec3 d, float t, float s) {
                vec3 n = normalize(vec3(0.1, 0.42, -0.9));
                float ca = dot(d, n);
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.8, 0.45, 0.22), vec3(0.1, 0.12, 0.25), smoothstep(0.0, 0.12, yy));
                col = mix(col, vec3(0.012, 0.016, 0.045), smoothstep(0.1, 0.6, yy));
                col += stars3(d, uStars * 0.5, t) * smoothstep(0.1, 0.5, yy);
                if (ca > 0.0) {
                    vec2 q = local2(d, n, 0.08 * s);
                    float r = length(q);
                    vec2 cs = q / max(r, 1e-3);
                    float st = fbm2(cs * 3.0 + vec2(t * 0.03, 0.0));
                    float st2 = fbm2(cs * 9.0 - vec2(0.0, t * 0.02));
                    float fall = 0.15 + 1.1 * st * st + 0.25 * st2;
                    float dr = max(r - 1.0, 0.0);
                    vec3 cc = vec3(0.85, 0.9, 1.0) * exp(-dr / fall) * (0.6 + 0.6 * st2) * 1.2;
                    cc += vec3(1.0, 0.95, 0.85) * exp(-dr * 22.0) * 1.4;
                    cc += vec3(1.0, 0.25, 0.35) * exp(-pow((r - 1.01) / 0.015, 2.0)) * (0.4 + st2);
                    float ba = 2.3 + 0.15 * sin(t * 0.1);
                    float bd = length(q - vec2(cos(ba), sin(ba)));
                    cc += vec3(1.0, 0.97, 0.9) * (exp(-bd * bd * 250.0) * 6.0 + exp(-bd * 3.5) * 0.5);
                    vec2 bq = q - vec2(cos(ba), sin(ba));
                    cc += vec3(1.0, 0.95, 0.9) * exp(-abs(bq.y) * 120.0) * exp(-abs(bq.x) * 2.0) * 0.4;
                    col += cc;
                    col = mix(vec3(0.008, 0.009, 0.015), col, smoothstep(0.985, 1.0, r));
                }
                return ground(col, d, vec3(0.03, 0.025, 0.035));
            }

            /* ---------------------------------------------------------------- 20 meteor shower */
            vec3 meteorShower(vec3 d, float t, float s) {
                vec3 col = mix(vec3(0.03, 0.05, 0.1), vec3(0.004, 0.008, 0.025), smoothstep(0.0, 0.7, d.y));
                col += vec3(0.05, 0.07, 0.1) * exp(-abs(d.y) * 9.0);
                col += vec3(0.04, 0.05, 0.08) * pow(fbm4(d * 3.0), 3.0) * 2.0;
                col += stars3(d, uStars, t);
                vec2 uv = d.xz / (1.0 + max(d.y, 0.0));
                vec2 rad = vec2(0.2, -0.3);
                vec3 m = vec3(0.0);
                for (int i = 0; i < 9; i++) {
                    float fi = float(i);
                    float tt = t * (0.3 + 0.15 * h11(fi * 3.1)) + h11(fi * 7.7);
                    float id = floor(tt), ph = fract(tt);
                    float seed = id * 13.7 + fi * 7.3;
                    vec2 dm = normalize(vec2(h11(seed) - 0.5, h11(seed + 1.0) - 0.5) + 1e-4);
                    vec2 a = rad + dm * (0.08 + 0.3 * h11(seed + 2.0));
                    vec2 b = a + dm * (0.2 + 0.25 * h11(seed + 3.0));
                    float life = smoothstep(0.0, 0.05, ph) * smoothstep(0.4, 0.22, ph);
                    float ht = clamp(ph / 0.4, 0.0, 1.0);
                    vec2 head = mix(a, b, ht), tail = mix(a, b, max(ht - 0.4, 0.0));
                    vec2 pa = uv - tail, ba = head - tail;
                    float k = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
                    float dist = length(pa - ba * k);
                    float w = 0.0008 + 0.0014 * k;
                    m += mix(vec3(0.35, 1.0, 0.7), vec3(1.0, 0.96, 0.88), k) * exp(-dist * dist / (w * w)) * k * k * life * 2.2;
                    m += vec3(1.0, 0.95, 0.9) * exp(-length(uv - head) * 250.0) * life * 0.8;
                }
                col += m * smoothstep(0.02, 0.15, d.y);
                return ground(col, d, vec3(0.02, 0.03, 0.05));
            }

            /* ---------------------------------------------------------------- 21 synthwave */
            vec3 synthwave(vec3 d, float t, float s) {
                float y = d.y, yy = max(y, 0.0);
                vec3 col = mix(vec3(1.0, 0.36, 0.45), vec3(0.45, 0.1, 0.55), smoothstep(0.0, 0.25, yy));
                col = mix(col, vec3(0.035, 0.02, 0.11), smoothstep(0.2, 0.8, yy));
                col += stars3(d, uStars, t) * smoothstep(0.2, 0.6, yy);
                vec3 n = normalize(vec3(0.0, 0.12, -1.0));
                vec2 q = local2(d, n, 0.25 * s);
                float r = length(q);
                float band = q.y * 6.0 + t * 0.25;
                float aa = fwidth(band) + 1e-4;
                float gap = mix(0.0, 0.55, clamp((0.25 - q.y) / 1.25, 0.0, 1.0));
                float stripes = smoothstep(gap - aa, gap + aa, fract(band));
                if (dot(d, n) > 0.0) {
                    vec3 sc = mix(vec3(1.0, 0.15, 0.55), vec3(1.0, 0.86, 0.22), smoothstep(-0.9, 0.8, q.y));
                    float disc = smoothstep(1.0, 0.985, r) * stripes;
                    col += vec3(1.0, 0.3, 0.6) * exp(-max(r - 1.0, 0.0) * 2.5) * smoothstep(0.95, 1.0, r) * 0.45;
                    col = mix(col, sc * 1.35, disc);
                }
                float az = atan(d.x, d.z);
                float mh = 0.01 + 0.075 * pow(fbm2(vec2(cos(az), sin(az)) * 2.2 + 4.0), 1.8);
                float mm = smoothstep(mh + 0.0015, mh - 0.0015, y);
                vec3 mcol = mix(vec3(0.05, 0.01, 0.1), vec3(0.12, 0.02, 0.2), smoothstep(0.0, mh, y));
                mcol += vec3(1.0, 0.25, 0.85) * exp(-abs(mh - y) * 300.0) * 0.8;
                col = mix(col, mcol, mm * step(0.0, y));
                vec2 p = d.xz / max(-y, 1e-3) * 0.5 / s + vec2(0.0, -t * 1.2);
                vec2 fw = fwidth(p);
                float g = gridLines(p, 1.3) * smoothstep(-0.005, -0.1, y) * clamp(1.0 - max(fw.x, fw.y) * 3.0, 0.0, 1.0);
                vec3 fl = vec3(0.03, 0.0, 0.06) + vec3(1.0, 0.2, 0.85) * g * 1.5 + vec3(1.0, 0.3, 0.6) * exp(min(y, 0.0) * 25.0) * 0.5;
                col = y < 0.0 ? fl : col;
                col += vec3(1.0, 0.35, 0.75) * exp(-abs(y) * 45.0) * 0.45;
                return col;
            }

            /* ---------------------------------------------------------------- 22 vaporwave */
            vec3 vaporwave(vec3 d, float t, float s) {
                float y = d.y, yy = max(y, 0.0);
                vec3 col = mix(vec3(1.0, 0.56, 0.76), vec3(0.6, 0.47, 0.95), smoothstep(0.0, 0.35, yy));
                col = mix(col, vec3(0.3, 0.78, 0.95), smoothstep(0.3, 1.0, yy));
                vec2 uv = d.xz / (yy + 0.1) * 0.5 / s + vec2(t * 0.01, 0.0);
                float c = smoothstep(0.55, 0.78, fbm2(vec2(uv.x * 0.3, uv.y * 2.2)));
                col = mix(col, vec3(1.0, 0.84, 0.96), c * 0.55 * smoothstep(0.0, 0.15, y));
                vec3 n = normalize(vec3(0.15, 0.22, -1.0));
                vec2 q = local2(d, n, 0.2 * s);
                float r = length(q);
                float band = q.y * 5.0;
                float aa = fwidth(band) + 1e-4;
                float gap = clamp((0.3 - q.y) * 0.3, 0.0, 0.5);
                float stripes = smoothstep(gap - aa, gap + aa, fract(band));
                if (dot(d, n) > 0.0) {
                    vec3 sc = mix(vec3(1.0, 0.3, 0.7), vec3(1.0, 0.95, 0.45), smoothstep(-1.0, 1.0, q.y));
                    col += vec3(1.0, 0.6, 0.8) * exp(-max(r - 1.0, 0.0) * 3.0) * smoothstep(0.95, 1.0, r) * 0.3;
                    col = mix(col, sc * 1.2, smoothstep(1.0, 0.985, r) * stripes);
                }
                vec2 p = d.xz / max(-y, 1e-3) * 0.4 / s + vec2(0.0, -t * 0.6);
                float ch = checker(p);
                vec3 fl = mix(vec3(0.3, 0.08, 0.45), vec3(0.15, 0.7, 0.85), ch);
                fl = mix(fl, vec3(0.95, 0.65, 0.88), exp(min(y, 0.0) * 18.0));
                col = y < 0.0 ? fl : col;
                col += vec3(1.0, 0.65, 0.9) * exp(-abs(y) * 60.0) * 0.3;
                return col;
            }

            /* ---------------------------------------------------------------- 23 starfield warp */
            vec3 warpField(vec3 d, float t, float s) {
                vec3 n = normalize(vec3(0.0, 0.25, -1.0));
                vec3 tx, ty; basis(n, tx, ty);
                float ca = clamp(dot(d, n), -1.0, 1.0);
                float ang = acos(ca);
                float phi = atan(dot(d, ty), dot(d, tx));
                vec3 col = vec3(0.004, 0.006, 0.02) + vec3(0.15, 0.3, 0.8) * exp(-ang * 5.0) * 0.4 + vec3(0.6, 0.8, 1.0) * exp(-ang * 30.0) * 0.6;
                float u = log(ang + 0.01);
                for (int l = 0; l < 3; l++) {
                    float N = 90.0 + 70.0 * float(l);
                    float sec = (phi / (2.0 * PI) + 0.5) * N;
                    float cell = floor(sec);
                    float fx = fract(sec) - 0.5;
                    float h = hash2(vec2(cell, float(l) * 17.0));
                    float h2 = hash2(vec2(cell * 1.7, float(l) + 3.0));
                    float pos = fract(u * 0.7 - t * (0.35 + 0.5 * h) / s + h2 * 7.0);
                    float len = 0.12 + 0.2 * h2;
                    float seg = smoothstep(0.0, 0.02, pos) * smoothstep(len, 0.0, pos);
                    float wid = exp(-fx * fx / 0.02);
                    float on = step(0.45, h);
                    vec3 sc = mix(vec3(0.55, 0.7, 1.0), vec3(1.0, 0.85, 1.0), h2);
                    col += sc * seg * wid * on * smoothstep(0.04, 0.25, ang) * 1.6;
                }
                return col + stars3(d, uStars * 0.5, t) * 0.6;
            }

            /* ---------------------------------------------------------------- 24 nebula storm */
            vec3 nebulaStorm(vec3 d, float t, float s) {
                vec3 p = d * 2.2 / s;
                vec3 w = vec3(fbm4(p + vec3(0.0, t * 0.01, 0.0)), fbm4(p + vec3(5.2, 1.3, -t * 0.01)), fbm4(p + vec3(-3.1, 7.7, 2.2)));
                float nn = fbm4(p * 1.6 + w * 2.2);
                float n2 = fbm4(p * 4.0 + w * 3.0 + 11.0);
                float dens = smoothstep(0.35, 0.8, nn);
                vec3 gas = mix(vec3(0.45, 0.1, 0.75), vec3(0.05, 0.55, 0.85), smoothstep(0.3, 0.7, w.x));
                gas = mix(gas, vec3(1.0, 0.45, 0.2), smoothstep(0.55, 0.8, w.y) * 0.7);
                vec3 col = vec3(0.004, 0.004, 0.012) + stars3(d, uStars, t) * (1.0 - dens * 0.8);
                col += gas * dens * (0.45 + 0.8 * n2);
                col += vec3(1.0, 0.9, 1.0) * pow(dens * n2, 3.0) * 1.5;
                float cyc = t * 0.7, id = floor(cyc), ph = fract(cyc);
                vec3 fdir = normalize(h33(vec3(id, 3.0, 7.0)) * 2.0 - 1.0 + vec3(0.0, 0.7, 0.0));
                float flick = exp(-ph * 9.0) * (0.6 + 0.4 * sin(ph * 90.0)) * step(0.4, h11(id));
                col += mix(vec3(0.6, 0.8, 1.0), gas, 0.3) * flick * pow(max(dot(d, fdir), 0.0), 10.0) * (0.3 + dens) * 2.5;
                float v = 1.0 - abs(n2 * 2.0 - 1.0);
                col += vec3(0.6, 0.85, 1.0) * pow(v, 18.0) * dens * 1.2;
                return col;
            }

            /* ---------------------------------------------------------------- 25 deep space */
            vec3 deepSpace(vec3 d, float t, float s) {
                vec3 col = vec3(0.002, 0.003, 0.008);
                float neb = fbm4(d * 1.8 / s + 2.0), neb2 = fbm4(d * 4.0 / s - 3.0);
                col += vec3(0.06, 0.02, 0.09) * pow(neb, 3.0) * 3.0 + vec3(0.0, 0.035, 0.07) * pow(neb2, 4.0) * 3.5;
                col += stars3(d, uStars, t * 0.5) * 1.1;
                vec3 g = d * 14.0; vec3 c = floor(g); vec3 h = h33(c + 17.0);
                if (h.x < 0.14) {
                    vec3 rel = g - (c + 0.3 + 0.4 * h.yzx);
                    rel -= d * dot(rel, d);
                    vec3 ax = normalize(h33(c + 3.0) - 0.5);
                    ax = normalize(ax - d * dot(ax, d));
                    float along = dot(rel, ax);
                    float across = length(rel - ax * along) * (1.8 + 3.0 * h.y);
                    float sz = 0.035 + 0.05 * h.z;
                    float e = (along * along + across * across) / (sz * sz);
                    col += mix(vec3(1.0, 0.85, 0.7), vec3(0.7, 0.8, 1.0), h.z) * (exp(-e) * 0.5 + exp(-e * 12.0) * 0.9);
                }
                return col;
            }

            /* ---------------------------------------------------------------- 26 cherry blossom */
            vec3 cherryBlossom(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(1.0, 0.83, 0.78), vec3(0.98, 0.71, 0.79), smoothstep(0.0, 0.25, yy));
                col = mix(col, vec3(0.56, 0.66, 0.93), smoothstep(0.2, 1.0, yy));
                vec3 sun = normalize(vec3(0.4, 0.25, -0.88));
                float sd = max(dot(d, sun), 0.0);
                col += vec3(1.0, 0.86, 0.76) * (pow(sd, 10.0) * 0.4 + pow(sd, 200.0) * 0.6);
                if (d.y > 0.0) {
                    vec2 uv = d.xz / (d.y + 0.2) * 0.6 / s + t * 0.01;
                    float c = smoothstep(0.5, 0.8, fbm2(uv * 1.3));
                    col = mix(col, vec3(1.0, 0.92, 0.94), c * 0.5 * smoothstep(0.0, 0.2, d.y));
                }
                col = ground(col, d, vec3(0.85, 0.7, 0.75));
                float az = atan(d.x, d.z) / (2.0 * PI);
                float el = d.y / sqrt(max(1.0 - d.y * d.y, 0.02));
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    float N = floor((36.0 + fi * 22.0) / s);
                    vec2 p = vec2(az * N, el * N / (2.0 * PI) + t * (0.35 + 0.15 * fi));
                    p.x += sin(p.y * 0.7 + t * 0.8 + fi) * 0.35;
                    vec2 cell = floor(p), f = fract(p) - 0.5;
                    cell.x = mod(cell.x, N);
                    vec3 h = h33(vec3(cell, fi + 4.0));
                    if (h.x < 0.3) {
                        vec2 lp = mm2(h.y * 20.0 + t * (0.6 + h.z)) * (f - (h.yz - 0.5) * 0.4);
                        lp.y *= 1.0 + 0.6 * sin(t * 2.0 + h.x * 30.0);
                        float pd = length(lp * vec2(1.0, 1.9));
                        float aa = fwidth(pd) + 1e-4;
                        float pet = smoothstep(0.15 + aa, 0.15 - aa, pd) * (1.0 - smoothstep(0.03, 0.0, abs(lp.x - 0.15)) * step(abs(lp.y), 0.02));
                        vec3 pc = mix(vec3(1.0, 0.62, 0.75), vec3(1.0, 0.9, 0.93), smoothstep(-0.15, 0.15, lp.x));
                        col = mix(col, pc, pet * (0.75 + 0.1 * fi) * smoothstep(0.9, 0.6, abs(d.y)));
                    }
                }
                return col;
            }

            /* ---------------------------------------------------------------- 27 thunderstorm */
            vec3 thunderstorm(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                float cyc = t * 0.3, id = floor(cyc), ph = fract(cyc) * 3.3;
                float on = step(0.2, h11(id * 1.7));
                float flash = (exp(-ph * 8.0) + 0.7 * exp(-max(ph - 0.16, 0.0) * 9.0) * step(0.16, ph)) * on;
                float baz = (h11(id * 3.3) - 0.5) * 1.6 + 2.6;
                vec3 fdir = normalize(vec3(sin(baz), 0.2, cos(baz)));
                vec3 col = mix(vec3(0.13, 0.14, 0.17), vec3(0.05, 0.055, 0.075), smoothstep(0.0, 0.6, yy));
                vec2 uv = d.xz / (yy + 0.12) * 0.5 / s + vec2(t * 0.03, t * 0.01);
                float n = fbm2(uv + fbm2(uv * 0.7 + t * 0.01) * 0.9);
                float n2 = fbm2(uv * 3.0 + 2.0);
                float cl = smoothstep(0.3, 0.75, n);
                vec3 cloud = mix(vec3(0.06, 0.065, 0.08), vec3(0.2, 0.21, 0.25), n2) * (0.6 + 0.6 * cl);
                col = mix(col, cloud, smoothstep(-0.02, 0.1, d.y) * 0.9);
                float near = pow(max(dot(d, fdir), 0.0), 6.0);
                col += vec3(0.65, 0.7, 1.0) * flash * (0.1 + near * 1.3 * (0.35 + n2));
                float da = mod(atan(d.x, d.z) - baz + PI, 2.0 * PI) - PI;
                float top = 0.3;
                float jag = (fbm2(vec2(d.y * 16.0, id * 7.0)) - 0.5) * 0.14 + (noise2(vec2(d.y * 80.0, id)) - 0.5) * 0.015;
                float x = da - jag;
                float bolt = exp(-x * x / 3e-6) * 3.0 + exp(-abs(x) * 70.0) * 0.35;
                float br = (fbm2(vec2(d.y * 9.0, id * 3.0 + 1.0)) - 0.5) * 0.25 + 0.04 * (top - d.y) * 10.0;
                float xb = da - jag - br * smoothstep(0.2, 0.05, d.y) * step(d.y, 0.2);
                bolt += exp(-xb * xb / 1.5e-6) * 1.5 * step(d.y, 0.2) * smoothstep(0.0, 0.1, d.y);
                col += vec3(0.85, 0.88, 1.0) * bolt * flash * smoothstep(top, top - 0.03, d.y) * smoothstep(-0.02, 0.01, d.y);
                col = mix(col, vec3(0.1, 0.11, 0.13) + flash * 0.06, smoothstep(0.08, -0.05, d.y) * 0.8);
                return ground(col, d, vec3(0.04, 0.045, 0.05));
            }

            /* ---------------------------------------------------------------- 28 moonlit clouds */
            vec3 moonlit(vec3 d, float t, float s) {
                vec3 m = normalize(vec3(-0.3, 0.42, -0.86));
                float md = dot(d, m);
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.05, 0.08, 0.14), vec3(0.01, 0.018, 0.04), smoothstep(0.0, 0.8, yy));
                col += vec3(0.25, 0.3, 0.4) * pow(max(md, 0.0), 30.0) * 0.5 + vec3(0.2, 0.25, 0.35) * pow(max(md, 0.0), 4.0) * 0.12;
                col += stars3(d, uStars, t);
                if (md > 0.0) {
                    vec2 q = local2(d, m, 0.035);
                    float r = length(q);
                    float cr = fbm2(q * 2.5 + 3.0);
                    float maria = smoothstep(0.45, 0.6, fbm2(q * 1.3 + 9.0));
                    vec3 mc = vec3(1.0, 0.98, 0.92) * (1.0 - 0.3 * maria) * (0.85 + 0.25 * cr) * (0.75 + 0.25 * sqrt(max(1.0 - r * r, 0.0)));
                    col = mix(col, mc * 1.4, smoothstep(1.0, 0.96, r));
                }
                if (d.y > 0.0) {
                    vec2 uv = d.xz / (d.y + 0.1) * 0.55 / s + vec2(t * 0.02, -t * 0.006);
                    float n = fbm2(uv + 0.6 * fbm2(uv * 0.6 + 4.0));
                    float c = smoothstep(0.42, 0.75, n);
                    float edge = smoothstep(0.42, 0.52, n) - smoothstep(0.52, 0.75, n);
                    float mg = pow(max(md, 0.0), 10.0);
                    vec3 cc = vec3(0.05, 0.06, 0.09) + vec3(0.08, 0.09, 0.12) * n + vec3(0.6, 0.66, 0.78) * mg * (0.35 + edge * 1.8);
                    col = mix(col, cc, c * 0.92 * smoothstep(0.0, 0.12, d.y));
                }
                return ground(col, d, vec3(0.03, 0.04, 0.06));
            }

            /* ---------------------------------------------------------------- 29 rainbow */
            vec3 rainbowSky(vec3 d, float t, float s) {
                vec3 sun = normalize(vec3(-0.3, 0.22, 0.93));
                float ang = acos(clamp(dot(d, -sun), -1.0, 1.0));
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.74, 0.81, 0.88), vec3(0.3, 0.5, 0.86), smoothstep(0.0, 0.7, yy));
                col *= 1.0 + 0.12 * smoothstep(0.74, 0.55, ang);
                col *= 1.0 - 0.1 * smoothstep(0.74, 0.77, ang) * smoothstep(0.88, 0.85, ang);
                float x1 = (0.74 - ang) / 0.035;
                vec3 bow = hue(clamp(x1, 0.0, 1.0) * 0.78) * smoothstep(-0.15, 0.15, x1) * smoothstep(1.15, 0.85, x1);
                float x2 = (ang - 0.873) / 0.055;
                bow += hue(clamp(x2, 0.0, 1.0) * 0.78) * smoothstep(-0.15, 0.15, x2) * smoothstep(1.15, 0.85, x2) * 0.45;
                vec2 uv = d.xz / (yy + 0.12) * 0.5 / s + vec2(t * 0.015, 0.0);
                float n = fbm2(uv + fbm2(uv * 0.6) * 0.8);
                float c = smoothstep(0.5, 0.78, n) * smoothstep(-0.02, 0.15, d.y);
                col += bow * 0.5 * (1.0 - c * 0.8) * smoothstep(-0.01, 0.04, d.y);
                vec3 cc = mix(vec3(0.52, 0.55, 0.6), vec3(0.97, 0.98, 1.0), smoothstep(0.45, 0.85, fbm2(uv * 2.0 + 1.0)));
                col = mix(col, cc, c * 0.85);
                col += vec3(1.0, 0.95, 0.85) * pow(max(dot(d, sun), 0.0), 20.0) * 0.3;
                return ground(col, d, vec3(0.42, 0.47, 0.52));
            }

            /* ---------------------------------------------------------------- 30 lava sky */
            vec3 lavaSky(vec3 d, float t, float s) {
                float yy = max(d.y, 0.0);
                vec3 col = mix(vec3(0.9, 0.26, 0.05), vec3(0.25, 0.04, 0.02), smoothstep(0.0, 0.25, yy));
                col = mix(col, vec3(0.035, 0.01, 0.01), smoothstep(0.2, 0.9, yy));
                vec2 uv = d.xz / (yy + 0.15) * 0.5 / s + vec2(t * 0.02, t * 0.012);
                float n = fbm2(uv + fbm2(uv * 0.7 - t * 0.01) * 1.2);
                float n2 = fbm2(uv * 3.0 - t * 0.03);
                float smoke = smoothstep(0.3, 0.8, n);
                vec3 sc = mix(vec3(0.05, 0.02, 0.02), vec3(0.15, 0.06, 0.04), n2);
                vec3 glowc = vec3(1.4, 0.42, 0.08) * pow(1.0 - smoothstep(0.0, 0.5, yy), 2.0) * (0.3 + n2);
                col = mix(col, sc + glowc * (1.0 - smoothstep(0.4, 0.9, n)) * 0.6, smoothstep(-0.02, 0.1, d.y) * smoke * 0.9);
                col += vec3(1.0, 0.35, 0.08) * exp(-abs(d.y) * 12.0) * 0.45;
                col = ground(col, d, vec3(0.15, 0.03, 0.01));
                float az = atan(d.x, d.z) / (2.0 * PI);
                float el = d.y / sqrt(max(1.0 - d.y * d.y, 0.02));
                for (int i = 0; i < 2; i++) {
                    float fi = float(i);
                    float N = 90.0 + 60.0 * fi;
                    vec2 p = vec2(az * N, el * N / (2.0 * PI) - t * (0.5 + 0.3 * fi));
                    p.x += sin(p.y * 0.5 + t) * 0.3;
                    vec2 cell = floor(p), f = fract(p) - 0.5;
                    cell.x = mod(cell.x, N);
                    vec3 h = h33(vec3(cell, fi + 9.0));
                    if (h.x < 0.22) {
                        vec2 o = f - (h.yz - 0.5) * 0.6;
                        float e = exp(-dot(o, o) / 0.004) * (0.4 + h.y) * (0.6 + 0.4 * sin(t * 6.0 + h.z * 40.0));
                        col += vec3(1.0, 0.5, 0.15) * e * smoothstep(0.85, 0.5, abs(d.y));
                    }
                }
                return col;
            }

            /* ---------------------------------------------------------------- 31 underwater */
            vec3 underwater(vec3 d, float t, float s) {
                float y = d.y;
                vec3 col = mix(vec3(0.0, 0.07, 0.13), vec3(0.0, 0.28, 0.4), smoothstep(-0.5, 0.6, y));
                vec3 sun = normalize(vec3(0.25, 0.92, -0.3));
                vec2 p = d.xz / max(y, 0.05) * 0.6 / s;
                float w1 = fbm2(p * 1.5 + vec2(t * 0.25, t * 0.18));
                float w2 = fbm2(p * 1.5 - vec2(t * 0.2, -t * 0.22) + 5.0);
                vec2 nrm = vec2(w1 - w2, w1 + w2 - 1.0) * 0.18;
                if (y > 0.0) {
                    float yd = y + nrm.x * 0.4 + nrm.y * 0.3;
                    float win = smoothstep(0.62, 0.7, yd);
                    vec3 above = mix(vec3(0.55, 0.85, 0.95), vec3(0.85, 0.97, 1.0), smoothstep(0.7, 1.0, yd));
                    float sd = max(dot(normalize(d + vec3(nrm.x, 0.0, nrm.y) * 0.4), sun), 0.0);
                    above += vec3(1.0, 0.95, 0.8) * (pow(sd, 60.0) * 2.0 + pow(sd, 8.0) * 0.3);
                    vec3 tir = mix(vec3(0.0, 0.2, 0.3), vec3(0.04, 0.38, 0.48), w1);
                    tir += vec3(0.4, 0.85, 0.85) * pow(clamp(1.0 - abs(w1 - w2) * 5.0, 0.0, 1.0), 10.0) * 0.35;
                    col = mix(col, mix(tir, above, win), smoothstep(0.0, 0.25, y));
                }
                vec3 tx, ty; basis(sun, tx, ty);
                float ra = atan(dot(d, ty), dot(d, tx));
                float rays = pow(noise2(vec2(cos(ra), sin(ra)) * 7.0 + t * 0.15), 3.0) + 0.5 * pow(noise2(vec2(cos(ra), sin(ra)) * 15.0 - t * 0.1), 4.0);
                col += vec3(0.45, 0.9, 0.9) * rays * smoothstep(-0.3, 0.95, dot(d, sun)) * 0.35;
                vec3 g = d * 90.0 + vec3(0.0, t * 0.6, 0.0);
                vec3 c = floor(g); vec3 h = h33(c);
                float sn = h.x < 0.08 ? exp(-dot(g - c - 0.3 - 0.4 * h.yzx, g - c - 0.3 - 0.4 * h.yzx) / 0.01) : 0.0;
                col += vec3(0.6, 0.85, 0.85) * sn * 0.35;
                return col;
            }

            /* ---------------------------------------------------------------- 32 matrix rain */
            vec3 matrixRain(vec3 d, float t, float s) {
                float az = atan(d.x, d.z) / (2.0 * PI) + 0.5;
                float el = d.y / sqrt(max(1.0 - d.y * d.y, 0.04));
                float N = floor(150.0 / s);
                vec2 p = vec2(az * N, el * N / (2.0 * PI) / 1.5);
                vec2 cell = floor(p), f = fract(p);
                cell.x = mod(cell.x, N);
                float ch = h11(cell.x * 1.37 + 0.5);
                float len = 18.0 + 22.0 * ch;
                float head = -t * (4.0 + 7.0 * ch) + ch * 300.0;
                float dr = mod(cell.y - head, len * 1.6);
                float trail = exp(-dr * 0.13) * step(dr, len);
                vec2 g = floor(f * vec2(5.0, 7.0));
                float gi = floor(t * (0.7 + ch * 2.0) + h11(dot(cell, vec2(1.3, 7.1))) * 10.0);
                float bit = step(0.48, hash2(g * 1.31 + cell * 7.0 + gi * 3.1));
                float inside = step(0.1, f.x) * step(f.x, 0.9) * step(0.07, f.y) * step(f.y, 0.93);
                float glyph = bit * inside;
                vec3 col = vec3(0.0, 0.012, 0.006) + vec3(0.0, 0.07, 0.03) * exp(-abs(d.y) * 5.0);
                float fade = smoothstep(0.93, 0.65, abs(d.y));
                col += vec3(0.1, 1.0, 0.38) * glyph * trail * 0.85 * fade;
                col += vec3(0.75, 1.0, 0.85) * glyph * exp(-dr * 1.5) * fade;
                return col;
            }

            /* ---------------------------------------------------------------- 33 cyber grid */
            vec3 cyberGrid(vec3 d, float t, float s) {
                float y = d.y;
                vec3 col = mix(vec3(0.02, 0.0, 0.055), vec3(0.0, 0.015, 0.045), smoothstep(-0.3, 0.6, y));
                col += vec3(0.0, 0.5, 0.9) * exp(-abs(y) * 14.0) * 0.45 + vec3(0.9, 0.1, 0.8) * exp(-abs(y) * 45.0) * 0.4;
                float ay = max(abs(y), 1e-3);
                vec2 base = d.xz / ay * 1.4 / s;
                vec2 p = base + vec2(0.0, -t * 0.8);
                vec2 fw = fwidth(p);
                float line = gridLines(p, 1.2) * clamp(1.0 - max(fw.x, fw.y) * 4.0, 0.0, 1.0);
                float fade = smoothstep(0.03, 0.35, ay);
                vec3 lc = y > 0.0 ? vec3(0.1, 0.8, 1.0) : vec3(1.0, 0.15, 0.8);
                float rr = length(base);
                float pulse = exp(-pow((fract(rr * 0.12 - t * 0.2) - 0.5) * 8.0, 2.0));
                col += lc * line * fade * (0.9 + 1.4 * pulse) + lc * 0.04 * fade;
                float hb = hash2(floor(p) + (y > 0.0 ? 0.0 : 31.0));
                col += lc * 0.12 * step(0.93, hb) * (0.5 + 0.5 * sin(t * 3.0 + hb * 40.0)) * fade;
                col += stars3(d, uStars * 0.3, t) * smoothstep(0.5, 0.9, y) * 0.6;
                return col;
            }

            vec3 premiumSky(int m, vec3 d, float t, float s) {
                if (m == 10) return galaxySpiral(d, t, s);
                if (m == 11) return milkyWay(d, t, s);
                if (m == 12) return volClouds(d, t, s);
                if (m == 13) return goldenHour(d, t, s);
                if (m == 14) return blueHour(d, t, s);
                if (m == 15) return cottonCandy(d, t, s);
                if (m == 16) return auroraV2(d, t, s);
                if (m == 17) return blackHole(d, t, s);
                if (m == 18) return planetRise(d, t, s);
                if (m == 19) return eclipse(d, t, s);
                if (m == 20) return meteorShower(d, t, s);
                if (m == 21) return synthwave(d, t, s);
                if (m == 22) return vaporwave(d, t, s);
                if (m == 23) return warpField(d, t, s);
                if (m == 24) return nebulaStorm(d, t, s);
                if (m == 25) return deepSpace(d, t, s);
                if (m == 26) return cherryBlossom(d, t, s);
                if (m == 27) return thunderstorm(d, t, s);
                if (m == 28) return moonlit(d, t, s);
                if (m == 29) return rainbowSky(d, t, s);
                if (m == 30) return lavaSky(d, t, s);
                if (m == 31) return underwater(d, t, s);
                if (m == 32) return matrixRain(d, t, s);
                return cyberGrid(d, t, s);
            }

            /* highlight roll-off, keeps the designed mid tones */
            vec3 rolloff(vec3 c) {
                vec3 k = max(c - 0.75, 0.0);
                return min(c, 0.75) + 0.25 * (1.0 - exp(-k / 0.25));
            }

            vec3 customTint(vec3 c) {
                if (uCustom < 0.5) return c;
                float l = dot(c, vec3(0.299, 0.587, 0.114));
                vec3 tint = mix(uSecondary, uPrimary, clamp(l * 1.4, 0.0, 1.0)) * l * 1.6;
                return mix(c, tint, 0.6);
            }

            float dither() { return (hash2(gl_FragCoord.xy) + hash2(gl_FragCoord.yx + 17.13) - 1.0) / 255.0; }
            """;
}
