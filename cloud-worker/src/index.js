// Tenacity cloud configs - Cloudflare Worker + KV.
// KV layout:
//   cfg:<id>    full record (meta + config body)        JSON
//   meta:<id>   metadata only (no body)                  JSON
//   index       array of metadata (newest first, capped) JSON
//   rl:<ip>:<bucket>:<minute>  rate limit counters (ttl)
//   like:<id>:<ipHash>         one like per ip

const MAX_CONFIG = 256 * 1024;
const MAX_ICON = 16 * 1024;
const MAX_INDEX = 5000;
const PAGE = 20;

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8" } });
const err = (msg, status = 400) => json({ error: msg }, status);

function clean(s, max) {
  if (typeof s !== "string") return "";
  // strip control chars and minecraft formatting codes, collapse whitespace
  return s.replace(/[\u0000-\u001f\u007f\u00a7<>]/g, "").replace(/\s+/g, " ").trim().slice(0, max);
}

function newId() {
  const a = "abcdefghijkmnpqrstuvwxyz23456789";
  const b = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(b, (x) => a[x % a.length]).join("");
}

async function sha(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(d).slice(0, 8), (x) => x.toString(16).padStart(2, "0")).join("");
}

async function limited(env, ip, bucket, max) {
  const minute = Math.floor(Date.now() / 60000);
  const key = `rl:${ip}:${bucket}:${minute}`;
  const n = parseInt((await env.CONFIGS.get(key)) || "0", 10);
  if (n >= max) return true;
  await env.CONFIGS.put(key, String(n + 1), { expirationTtl: 120 });
  return false;
}

function validIcon(b64) {
  if (!b64) return "";
  if (typeof b64 !== "string") return null;
  b64 = b64.replace(/^data:image\/png;base64,/, "");
  if (b64.length > Math.ceil(MAX_ICON / 3) * 4 + 4 || !/^[A-Za-z0-9+/=]+$/.test(b64)) return null;
  let bin;
  try { bin = atob(b64); } catch { return null; }
  if (bin.length > MAX_ICON || bin.length < 24) return null;
  // PNG signature + IHDR size check (<= 64x64)
  const sig = [137, 80, 78, 71, 13, 10, 26, 10];
  for (let i = 0; i < 8; i++) if (bin.charCodeAt(i) !== sig[i]) return null;
  const u32 = (o) => ((bin.charCodeAt(o) << 24) | (bin.charCodeAt(o + 1) << 16) | (bin.charCodeAt(o + 2) << 8) | bin.charCodeAt(o + 3)) >>> 0;
  const w = u32(16), h = u32(20);
  if (!w || !h || w > 64 || h > 64) return null;
  return b64;
}

async function getIndex(env) {
  return (await env.CONFIGS.get("index", { type: "json", cacheTtl: 30 })) || [];
}

async function updateMeta(env, id, fn) {
  const meta = await env.CONFIGS.get(`meta:${id}`, "json");
  if (!meta) return null;
  fn(meta);
  await env.CONFIGS.put(`meta:${id}`, JSON.stringify(meta));
  const full = await env.CONFIGS.get(`cfg:${id}`, "json");
  if (full) { Object.assign(full, meta); await env.CONFIGS.put(`cfg:${id}`, JSON.stringify(full)); }
  const idx = await getIndex(env);
  const i = idx.findIndex((m) => m.id === id);
  if (i >= 0) { idx[i] = meta; await env.CONFIGS.put("index", JSON.stringify(idx)); }
  return meta;
}

async function create(req, env, ip) {
  if (await limited(env, ip, "up", 3)) return err("rate limited, try again in a minute", 429);
  const len = parseInt(req.headers.get("content-length") || "0", 10);
  if (len > MAX_CONFIG + MAX_ICON * 2 + 8192) return err("payload too large", 413);
  const raw = await req.text();
  if (raw.length > MAX_CONFIG + MAX_ICON * 2 + 8192) return err("payload too large", 413);
  let b;
  try { b = JSON.parse(raw); } catch { return err("invalid json"); }
  if (!b || typeof b !== "object") return err("invalid body");
  const name = clean(b.name, 40);
  const author = clean(b.author, 16).replace(/[^A-Za-z0-9_]/g, "");
  const description = clean(b.description, 200);
  const server = clean(b.server, 80) || "Singleplayer";
  const version = clean(b.version, 24);
  if (name.length < 1) return err("name required");
  if (!author) return err("author required");
  const icon = validIcon(b.icon);
  if (icon === null) return err("icon must be a png <= 64x64 and <= 16KB");
  let config = b.config;
  if (typeof config === "string") { try { config = JSON.parse(config); } catch { return err("config is not json"); } }
  if (!config || typeof config !== "object" || Array.isArray(config)) return err("config must be a json object");
  const body = JSON.stringify(config);
  if (body.length > MAX_CONFIG) return err("config too large (256KB max)", 413);

  const id = newId();
  const meta = { id, name, author, description, server, icon, version, downloads: 0, likes: 0, created: Date.now(), size: body.length };
  await env.CONFIGS.put(`cfg:${id}`, JSON.stringify({ ...meta, config }));
  await env.CONFIGS.put(`meta:${id}`, JSON.stringify(meta));
  const idx = await getIndex(env);
  idx.unshift(meta);
  if (idx.length > MAX_INDEX) idx.length = MAX_INDEX;
  await env.CONFIGS.put("index", JSON.stringify(idx));
  return json({ id }, 201);
}

async function list(url, env) {
  const sort = url.searchParams.get("sort") === "top" ? "top" : "new";
  const q = clean(url.searchParams.get("q") || "", 40).toLowerCase();
  const page = Math.max(0, parseInt(url.searchParams.get("page") || "0", 10) || 0);
  let idx = await getIndex(env);
  if (q) idx = idx.filter((m) => (m.name + " " + m.author + " " + m.server + " " + m.description).toLowerCase().includes(q));
  if (sort === "top") idx = idx.slice().sort((a, b) => b.downloads + b.likes * 3 - (a.downloads + a.likes * 3) || b.created - a.created);
  else idx = idx.slice().sort((a, b) => b.created - a.created);
  const items = idx.slice(page * PAGE, page * PAGE + PAGE);
  return json({ items, page, total: idx.length, more: (page + 1) * PAGE < idx.length });
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const ip = req.headers.get("cf-connecting-ip") || "0.0.0.0";
    const parts = url.pathname.replace(/\/+$/, "").split("/").filter(Boolean);
    try {
      if (parts.length === 0) return json({ service: "tenacity-configs", ok: true });
      if (parts[0] !== "configs") return err("not found", 404);
      if (parts.length === 1) {
        if (req.method === "POST") return await create(req, env, ip);
        if (req.method === "GET") {
          if (await limited(env, ip, "ls", 60)) return err("rate limited", 429);
          return await list(url, env);
        }
        return err("method not allowed", 405);
      }
      const id = parts[1].replace(/[^a-z0-9]/g, "").slice(0, 16);
      if (parts.length === 2 && req.method === "GET") {
        if (await limited(env, ip, "get", 60)) return err("rate limited", 429);
        const full = await env.CONFIGS.get(`cfg:${id}`, "json");
        return full ? json(full) : err("not found", 404);
      }
      if (parts.length === 3 && req.method === "POST" && parts[2] === "download") {
        if (await limited(env, ip, "dl", 20)) return err("rate limited", 429);
        const m = await updateMeta(env, id, (m) => (m.downloads = (m.downloads || 0) + 1));
        return m ? json({ downloads: m.downloads }) : err("not found", 404);
      }
      if (parts.length === 3 && req.method === "POST" && parts[2] === "like") {
        if (await limited(env, ip, "like", 20)) return err("rate limited", 429);
        const key = `like:${id}:${await sha(ip)}`;
        if (await env.CONFIGS.get(key)) return err("already liked", 409);
        const m = await updateMeta(env, id, (m) => (m.likes = (m.likes || 0) + 1));
        if (!m) return err("not found", 404);
        await env.CONFIGS.put(key, "1");
        return json({ likes: m.likes });
      }
      return err("not found", 404);
    } catch (e) {
      return err("server error", 500);
    }
  },
};
