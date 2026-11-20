package tenacity.gfx;

/**
 * Seasonal hand looks for HandFx (uSeason 1..12 Christmas, 13..24 Halloween). Spliced into HandFx's fragment shader after
 * h21 / vnoise / fbm / chroma, so it may use them. Every look keeps the hand's own shading (luma) so fingers and the held
 * item still read, then paints the theme on top; uSeasonCol / uSeasonAcc are the user's colour + accent, uSGlow / uSSpark /
 * uSEdge / uSPulse / uSBright the option sliders.
 */
final class SeasonHand {
    private SeasonHand() {
    }

    static final String FUNCS = """
            float sSpark(vec2 uv, float t, float dens, float sz) {
                vec2 g = uv * dens; vec2 c = floor(g); float h = h21(c);
                if (h < 0.78) return 0.0;
                vec2 f = fract(g) - 0.5 - (vec2(h21(c + 3.1), h21(c + 7.7)) - 0.5) * 0.5;
                float r = length(f);
                float tw = 0.5 + 0.5 * sin(t * (2.0 + 4.0 * h) + h * 60.0);
                float star = exp(-r * r / (sz * sz)) + 0.5 * exp(-abs(f.x) * 40.0) * exp(-abs(f.y) * 5.0) + 0.5 * exp(-abs(f.y) * 40.0) * exp(-abs(f.x) * 5.0);
                return star * tw * tw;
            }
            float sFlake(vec2 uv, float t, float dens, float sz) {
                float o = 0.0;
                for (int i = 0; i < 2; i++) {
                    float fi = float(i);
                    vec2 p = uv * dens * (1.0 + 0.7 * fi) + vec2(sin(t * 0.4 + fi * 3.0) * 0.5, -t * (0.35 + 0.2 * fi));
                    vec2 c = floor(p); float h = h21(c + fi * 11.0);
                    if (h > 0.5) continue;
                    vec2 f = fract(p) - 0.5 - (vec2(h21(c + 2.3), h21(c + 9.1)) - 0.5) * 0.5;
                    o += exp(-dot(f, f) / (sz * sz)) * (1.0 - 0.3 * fi);
                }
                return o;
            }
            float sSlope(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

            vec3 seasonal(vec3 col, float a, vec2 vUv, float eMin) {
                float t = uSeasonTime, sc = uSeasonScale;
                vec2 uv = vUv * vec2(uTexel.y / uTexel.x, 1.0) * sc;
                float L = sSlope(col);
                float edge = (1.0 - eMin) * uSEdge;
                float pul = 1.0 + uSPulse * 0.45 * sin(t * 1.9);
                vec3 A = max(uSeasonCol, vec3(0.03)), B = max(uSeasonAcc, vec3(0.03));
                vec3 o = col;
                int m = uSeason;
                float shade = 0.35 + 0.9 * L;
                if (m == 1) { // candy cane: diagonal red / white stripes that slowly crawl
                    float s = fract((uv.x + uv.y * 0.8) * 4.0 - t * 0.12);
                    float k = smoothstep(0.46, 0.5, s) - smoothstep(0.96, 1.0, s);
                    vec3 red = vec3(0.9, 0.06, 0.12), wht = vec3(0.98);
                    o = mix(wht, red, k) * shade;
                    o += pow(L, 6.0) * 0.4 + vec3(1.0) * edge * 0.5;
                } else if (m == 2) { // frosted pine: deep green, white rime on the lit edges
                    float n = fbm(uv * 9.0 + 3.0), n2 = fbm(uv * 22.0);
                    vec3 pine = mix(vec3(0.02, 0.2, 0.1), vec3(0.06, 0.38, 0.2), n) * shade * 1.2;
                    float rime = smoothstep(0.52, 0.78, n2) * (0.3 + 0.7 * L) + edge * 0.9;
                    o = mix(pine, vec3(0.97), clamp(rime, 0.0, 1.0) * 0.85);
                } else if (m == 3) { // ornament glass: glossy tinted ball with moving highlight + reflections
                    vec3 base = mix(A * vec3(0.8, 0.1, 0.1) + vec3(0.25, 0.0, 0.02), A, 0.5);
                    float hi = pow(max(0.0, sin((uv.x * 3.0 - uv.y * 2.0) * 3.0 - t * 0.7) * 0.5 + 0.5), 14.0);
                    float sp = pow(L, 3.0);
                    o = base * (0.3 + 0.9 * L) + B * pow(1.0 - L, 2.0) * 0.4 + vec3(1.0) * (hi * 0.55 + sp * 0.5) + edge * mix(B, vec3(1.0), 0.5) * 0.7;
                } else if (m == 4) { // snowglobe: cold clear glass, drifting snow, soft white rim
                    float f = sFlake(uv, t, 7.0, 0.1) + 0.5 * sFlake(uv + 5.0, t * 0.7, 13.0, 0.08);
                    vec3 glass = mix(vec3(0.82, 0.88, 0.95), vec3(1.0), L) * (0.55 + 0.5 * L);
                    o = glass + vec3(1.0) * f * (0.4 + uSSpark) + vec3(1.0) * edge * 0.7;
                    o += vec3(1.0) * pow(max(0.0, sin(uv.y * 6.0 - uv.x * 3.0 - t * 0.5) * 0.5 + 0.5), 12.0) * 0.25;
                } else if (m == 5) { // tinsel sparkle: metallic foil threads, hot glitter
                    float th = sin((uv.x * 40.0 + sin(uv.y * 11.0 + t) * 2.0)) * 0.5 + 0.5;
                    vec3 foil = mix(A, B, 0.5 + 0.5 * sin(uv.y * 5.0 + th * 2.0 - t * 0.5));
                    o = foil * (0.3 + 0.9 * L) * (0.7 + 0.5 * th) + vec3(1.0) * pow(th, 10.0) * 0.5;
                    o += vec3(1.0) * sSpark(uv + t * 0.02, t * 2.0, 26.0, 0.07) * (0.4 + 1.6 * uSSpark);
                } else if (m == 6) { // aurora frost: white ice with a slow green / teal veil
                    float n = fbm(vec2(uv.x * 2.0 + t * 0.15, uv.y * 0.8 - t * 0.04));
                    float band = smoothstep(0.1, 0.9, sin(uv.x * 3.2 + n * 6.0 + t * 0.5) * 0.5 + 0.5);
                    vec3 veil = mix(vec3(0.15, 1.0, 0.65), vec3(0.4, 0.6, 1.0), n);
                    vec3 ice = vec3(0.9, 0.95, 1.0) * (0.45 + 0.7 * L);
                    o = mix(ice, ice * 0.35 + veil * 0.9, band * 0.7) + vec3(1.0) * edge * 0.5;
                } else if (m == 7) { // gingerbread: baked brown, white icing piping + gumdrops
                    float n = fbm(uv * 14.0);
                    vec3 dough = mix(vec3(0.42, 0.2, 0.07), vec3(0.62, 0.34, 0.12), n) * (0.4 + 0.9 * L);
                    float ic = smoothstep(0.05, 0.0, abs(fract(uv.y * 3.0 + sin(uv.x * 8.0) * 0.05) - 0.5) - 0.42) ;
                    vec2 gp = fract(uv * 4.0) - 0.5; vec2 gi = floor(uv * 4.0);
                    float gum = step(0.7, h21(gi)) * smoothstep(0.17, 0.12, length(gp));
                    vec3 gc = mix(vec3(0.9, 0.1, 0.15), vec3(0.1, 0.7, 0.3), step(0.5, h21(gi + 4.0)));
                    o = dough;
                    o = mix(o, vec3(0.98, 0.96, 0.92), clamp(ic * 0.5 + edge, 0.0, 1.0) * 0.8);
                    o = mix(o, gc * (0.5 + 0.8 * L) + vec3(1.0) * pow(L, 5.0) * 0.5, gum);
                } else if (m == 8) { // holly berry: leaf green with glossy red berries
                    float n = fbm(uv * 8.0);
                    vec3 leaf = mix(vec3(0.02, 0.25, 0.08), vec3(0.08, 0.5, 0.18), n) * (0.4 + 1.0 * L);
                    float vein = smoothstep(0.03, 0.0, abs(fract(uv.x * 3.0 + n * 0.6) - 0.5) - 0.46) * 0.35;
                    vec2 gp = fract(uv * 3.5) - 0.5; vec2 gi = floor(uv * 3.5);
                    float ber = step(0.62, h21(gi + 2.0)) * smoothstep(0.15, 0.11, length(gp - (vec2(h21(gi), h21(gi + 5.0)) - 0.5) * 0.2));
                    vec3 bc = mix(vec3(0.5, 0.0, 0.03), vec3(1.0, 0.1, 0.12), L) + pow(L, 6.0) * 0.8;
                    o = leaf + vein;
                    o = mix(o, bc, ber) + B * edge * 0.25;
                } else if (m == 9) { // northern lights: deep dark hand carrying big flowing curtains
                    float n = fbm(vec2(uv.x * 1.6 + t * 0.2, uv.y * 0.5 - t * 0.06));
                    float c1 = smoothstep(0.0, 0.7, sin(uv.x * 4.0 + n * 7.0 + t * 0.6) * 0.5 + 0.5);
                    float c2 = smoothstep(0.2, 0.9, sin(uv.x * 2.4 - n * 5.0 - t * 0.4 + 2.0) * 0.5 + 0.5);
                    vec3 g1 = vec3(0.1, 1.0, 0.5), g2 = vec3(0.7, 0.3, 1.0);
                    o = vec3(0.01, 0.03, 0.06) * (0.5 + L) + g1 * c1 * (0.4 + 0.8 * n) + g2 * c2 * 0.5 * (1.0 - n) + vec3(0.3, 0.7, 1.0) * edge * 0.5;
                } else if (m == 10) { // gift wrap: paper with ribbon cross + bow shine
                    vec2 gp = fract(uv * 1.2) - 0.5;
                    float rib = max(smoothstep(0.1, 0.07, abs(gp.x)), smoothstep(0.1, 0.07, abs(gp.y)));
                    float dots = smoothstep(0.08, 0.05, length(fract(uv * 5.0 + vec2(0.5, 0.0) * step(0.5, fract(uv.y * 2.5))) - 0.5));
                    vec3 paper = mix(A, A * 0.55, dots * 0.5) * (0.4 + 0.9 * L);
                    vec3 ribbon = B * (0.5 + 0.9 * pow(L, 1.2)) + vec3(1.0) * pow(max(0.0, sin(uv.x * 8.0 + uv.y * 8.0 - t * 1.0)), 18.0) * 0.5;
                    o = mix(paper, ribbon, rib) + vec3(1.0) * edge * 0.25;
                } else if (m == 11) { // ice crystal: facets, refracted cold light, white sparkle (no blue cast)
                    vec2 p = uv * 5.0; vec2 ip = floor(p), fp = fract(p);
                    float md = 9.0; vec2 mid = vec2(0.0);
                    for (int j = -1; j <= 1; j++)
                        for (int i = -1; i <= 1; i++) {
                            vec2 g = vec2(float(i), float(j));
                            vec2 oo = 0.5 + 0.4 * sin(t * 0.4 + 6.2831 * vec2(h21(ip + g), h21(ip + g + 9.7)));
                            float dd = length(g + oo - fp);
                            if (dd < md) { md = dd; mid = ip + g; }
                        }
                    float fh = h21(mid);
                    vec3 ice = mix(vec3(0.78, 0.86, 0.94), vec3(1.0), fh) * (0.45 + 0.8 * L);
                    o = ice * (0.85 + 0.3 * sin(t * 0.8 + fh * 30.0)) + vec3(1.0) * pow(1.0 - smoothstep(0.0, 0.1, abs(md - 0.5)), 2.0) * 0.45;
                    o += vec3(1.0) * sSpark(uv, t * 1.6, 22.0, 0.07) * (0.3 + 1.4 * uSSpark) + vec3(1.0) * edge * 0.6;
                } else if (m == 12) { // warm fireplace: coals + flickering orange light from below
                    float n = fbm(vec2(uv.x * 6.0, uv.y * 4.0 - t * 0.9));
                    float n2 = fbm(vec2(uv.x * 12.0 + 4.0, uv.y * 8.0 - t * 1.6));
                    float up = clamp(1.0 - vUv.y, 0.0, 1.0);
                    float fl = clamp(n * 1.2 + n2 * 0.5 - 0.35 + up * 0.5, 0.0, 1.5) * (0.85 + 0.15 * sin(t * 9.0) * sin(t * 6.3));
                    vec3 ember = mix(vec3(0.25, 0.03, 0.0), vec3(1.0, 0.4, 0.05), clamp(fl, 0.0, 1.0));
                    ember = mix(ember, vec3(1.0, 0.85, 0.45), smoothstep(0.9, 1.4, fl));
                    o = col * vec3(1.0, 0.7, 0.5) * 0.6 + ember * (0.45 + 0.5 * L) + vec3(1.0, 0.4, 0.08) * edge * 0.8;
                } else if (m == 13) { // pumpkin glow: carved orange, lit from within
                    float n = fbm(uv * 6.0);
                    float rib = 0.75 + 0.25 * cos(uv.x * 14.0 + n * 2.0);
                    vec3 sk = mix(vec3(0.6, 0.15, 0.0), vec3(1.0, 0.45, 0.04), n) * rib;
                    float inner = 0.75 + 0.25 * sin(t * 3.0 + n * 6.0);
                    o = sk * (0.3 + 0.9 * L) + vec3(1.0, 0.55, 0.1) * pow(1.0 - L, 2.0) * 0.5 * inner * (0.5 + uSGlow) + vec3(1.0, 0.5, 0.05) * edge * 0.7;
                } else if (m == 14) { // haunted mist: cold dark hand under rolling pale fog
                    float n = fbm(uv * 3.0 + vec2(t * 0.1, 0.0) + fbm(uv * 2.0 - t * 0.07) * 1.5);
                    float mist = smoothstep(0.35, 0.85, n);
                    o = vec3(0.12, 0.14, 0.17) * (0.4 + L) + mix(vec3(0.4, 0.5, 0.5), vec3(0.78, 0.85, 0.82), n) * mist * (0.5 + uSGlow * 0.6) + vec3(0.5, 0.7, 0.6) * edge * 0.5;
                } else if (m == 15) { // witch brew: bubbling green potion with rising bubbles
                    float n = fbm(vec2(uv.x * 4.0, uv.y * 3.0 - t * 0.3) + fbm(uv * 5.0 + t * 0.2));
                    vec3 br = mix(vec3(0.03, 0.2, 0.05), vec3(0.3, 1.0, 0.2), n);
                    vec2 bp = vec2(uv.x * 6.0, uv.y * 6.0 - t * 0.8); vec2 bi = floor(bp); vec2 bf = fract(bp) - 0.5;
                    float bub = step(0.7, h21(bi)) * (smoothstep(0.3, 0.26, length(bf)) - smoothstep(0.24, 0.2, length(bf)) * 0.6);
                    o = br * (0.35 + 0.9 * L) + vec3(0.7, 1.0, 0.5) * bub * 0.8 + vec3(0.4, 1.0, 0.2) * edge * 0.7 + vec3(0.3, 1.0, 0.2) * pow(n, 3.0) * 0.4 * uSGlow;
                } else if (m == 16) { // jack-o-lantern flame: fire licking up the hand
                    float n = fbm(vec2(uv.x * 7.0, uv.y * 5.0 - t * 1.4));
                    float n2 = fbm(vec2(uv.x * 15.0 + 3.0, uv.y * 10.0 - t * 2.2));
                    float up = clamp(1.0 - vUv.y * 0.9, 0.0, 1.0);
                    float f = clamp(n * 1.3 + n2 * 0.5 - 0.3 + up * 0.55, 0.0, 1.6) * (0.88 + 0.12 * sin(t * 11.0));
                    vec3 fc = mix(vec3(0.5, 0.05, 0.0), vec3(1.0, 0.42, 0.03), clamp(f, 0.0, 1.0));
                    fc = mix(fc, vec3(1.0, 0.9, 0.5), smoothstep(1.0, 1.5, f));
                    o = col * vec3(1.0, 0.6, 0.35) * 0.5 + fc * (0.45 + 0.55 * L) + vec3(1.0, 0.5, 0.05) * edge * 0.8;
                } else if (m == 17) { // ghost wisp: translucent white spirit with trailing wisps
                    float n = fbm(uv * 3.5 + vec2(0.0, -t * 0.25) + fbm(uv * 2.0 + t * 0.1));
                    float w = smoothstep(0.3, 0.9, n);
                    o = vec3(0.92, 0.96, 0.98) * (0.45 + 0.7 * L) * (0.6 + 0.5 * w) + vec3(1.0) * edge * 0.8;
                    o += vec3(0.8, 1.0, 0.95) * pow(w, 3.0) * 0.4 * (0.5 + uSGlow);
                } else if (m == 18) { // cursed purple: dark magic, violet veins and a pulsing glow
                    float n = fbm(uv * 6.0 + t * 0.15), n2 = fbm(uv * 13.0 - t * 0.1);
                    float vein = pow(1.0 - abs(n * 2.0 - 1.0), 8.0);
                    vec3 pur = mix(vec3(0.08, 0.0, 0.16), vec3(0.45, 0.1, 0.75), n2);
                    o = pur * (0.35 + 0.9 * L) + vec3(0.8, 0.3, 1.0) * vein * 1.4 * pul + vec3(0.6, 0.2, 1.0) * edge * 0.8;
                } else if (m == 19) { // blood moon: dark crimson, slow red tide, white-hot rim
                    float n = fbm(uv * 4.0 + vec2(0.0, t * 0.1));
                    vec3 bl = mix(vec3(0.18, 0.0, 0.02), vec3(0.85, 0.05, 0.05), n);
                    o = bl * (0.3 + 1.0 * L) * pul + vec3(1.0, 0.2, 0.15) * pow(1.0 - L, 3.0) * 0.4 * uSGlow + vec3(1.0, 0.3, 0.2) * edge * 0.9;
                } else if (m == 20) { // spider web: dark hand with silver web strands that shimmer
                    vec2 wp = (vUv - vec2(0.5, 0.45)) * vec2(uTexel.y / uTexel.x, 1.0) * 2.2 / sc;
                    float r = length(wp), an = atan(wp.y, wp.x);
                    float spokes = smoothstep(0.02, 0.0, abs(fract(an / 6.2831 * 9.0 + 0.5) - 0.5) / max(r * 9.0, 0.3));
                    float rings = smoothstep(0.02, 0.0, abs(fract(r * 5.0 + sin(an * 9.0) * 0.03) - 0.5) - 0.46 + 0.0);
                    float web = clamp(spokes * 0.8 + rings, 0.0, 1.0);
                    float sh = 0.6 + 0.4 * sin(t * 1.5 + r * 8.0);
                    o = vec3(0.05, 0.05, 0.06) * (0.4 + L) + vec3(0.85, 0.88, 0.95) * web * sh + vec3(0.8, 0.85, 0.95) * edge * 0.5;
                } else if (m == 21) { // candy corn: banded yellow / orange / white
                    float s = uv.y * 2.2 + uv.x * 0.25;
                    float f = fract(s);
                    vec3 cc = f < 0.34 ? vec3(1.0, 0.82, 0.1) : f < 0.67 ? vec3(1.0, 0.45, 0.04) : vec3(0.99, 0.97, 0.9);
                    float bl = smoothstep(0.0, 0.05, abs(fract(s) - 0.34)) * smoothstep(0.0, 0.05, abs(fract(s) - 0.67));
                    o = cc * (0.4 + 0.9 * L) + vec3(1.0) * pow(L, 6.0) * 0.4 + vec3(1.0, 0.7, 0.2) * edge * 0.35;
                } else if (m == 22) { // zombie rot: sickly green flesh, mould blotches, slow twitch
                    float n = fbm(uv * 7.0), n2 = fbm(uv * 16.0 + 4.0);
                    vec3 fl = mix(vec3(0.3, 0.42, 0.22), vec3(0.5, 0.58, 0.3), n);
                    float rot = smoothstep(0.5, 0.75, n2);
                    o = mix(fl, vec3(0.2, 0.12, 0.06), rot * 0.6) * (0.35 + 0.9 * L) + vec3(0.55, 0.8, 0.25) * edge * 0.45;
                    o += vec3(0.3, 0.5, 0.1) * smoothstep(0.7, 0.9, fbm(uv * 4.0 + t * 0.1)) * 0.3 * uSGlow;
                } else if (m == 23) { // skeleton bone: ivory with cracks and a cold socket glow
                    float n = fbm(uv * 12.0), n2 = fbm(uv * 4.0);
                    float crack = smoothstep(0.03, 0.0, abs(n - 0.5) - 0.01) * smoothstep(0.4, 0.7, n2);
                    vec3 bn = mix(vec3(0.82, 0.76, 0.62), vec3(0.96, 0.93, 0.84), n);
                    o = bn * (0.4 + 0.9 * L) * (1.0 - crack * 0.7) + vec3(0.9, 0.95, 0.9) * edge * 0.5;
                } else { // 24 spooky lightning: dark storm hand, violet-white forks
                    float n = flow(uv * 5.0 - 250.0, t * 0.55, 6, 0.004, 1.2, 1.55, 10.0, 1.0);
                    float cyc = t * 0.5, id = floor(cyc), ph = fract(cyc);
                    float fl = (exp(-ph * 9.0) + 0.5 * exp(-max(ph - 0.15, 0.0) * 10.0) * step(0.15, ph)) * step(0.25, h21(vec2(id, 3.0)));
                    float core = smoothstep(0.5, 1.2, n);
                    o = vec3(0.04, 0.03, 0.08) * (0.4 + L) + vec3(0.65, 0.4, 1.0) * core * (0.7 + 1.6 * fl) + vec3(0.8, 0.7, 1.0) * edge * 0.6 + vec3(0.5, 0.35, 1.0) * fl * 0.25 * uSGlow;
                }
                // shared extras: glow lift, sparkle dust and the user's brightness
                o += B * edge * uSGlow * 0.25;
                if (m != 1 && m != 5 && m != 11 && uSSpark > 0.02) {
                    float sp = sSpark(uv + t * 0.015, t * 1.8, 20.0, 0.06);
                    vec3 sc3 = m <= 12 ? vec3(1.0, 0.97, 0.9) : mix(B, vec3(1.0), 0.45);
                    o += sc3 * sp * uSSpark * 0.9 * (0.4 + 0.6 * smoothstep(0.1, 0.9, L));
                }
                o *= uSBright * pul;
                float edgeSoft = smoothstep(0.05, 0.35, a);
                return mix(col, o, edgeSoft * uSeasonAmt);
            }
            """;
}
