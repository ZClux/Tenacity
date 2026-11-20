// Tenacity global chat - one Durable Object (SQLite) acting as a single room.
//
// WebSocket protocol (JSON text frames)
//   server -> {t:"hello", challenge}
//   client -> {t:"auth", user, uuid, verify:bool, anon?:bool}  (verify: client already called Mojang joinServer(challenge); anon: show as "Anonymous", no uuid, never verified)
//   server -> {t:"ready", user, uuid, verified, online, history:[msg...]}
//   client -> {t:"send", text}
//   server -> {t:"msg", m:{id,ts,user,uuid,text,v}}   broadcast
//   server -> {t:"online", n}                          broadcast on join/leave
//   server -> {t:"err", code, msg}                     code: slow|muted|banned|bad|auth
//   server -> {t:"del", id}                            message deleted by a moderator
// HTTP
//   GET  /history?since=<id>      -> {messages:[...], online}
//   GET  /challenge               -> {challenge}
//   POST /send {user,uuid,text,challenge,verify}
//   POST /admin/mute?user=&mins=  | /admin/unmute?user= | /admin/delete?id= | /admin/ban?user= | /admin/unban?user=   (Authorization: Bearer ADMIN_TOKEN)

const KEEP = 200;
const SEND_HISTORY = 50;
const MAX_TEXT = 200;
const CHALLENGE_TTL = 120_000;

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, content-type",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json; charset=utf-8", ...CORS } });

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    if (url.pathname === "/" ) return json({ name: "tenacity-chat", ok: true });
    const stub = env.ROOM.get(env.ROOM.idFromName("global"));
    return stub.fetch(request);
  },
};

/* ---------------------------------------------------------------- text helpers */

function cleanText(s) {
  if (typeof s !== "string") return "";
  // control chars, bidi overrides, zero width, minecraft section sign, html brackets
  s = s.replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u202a-\u202e\u2060-\u2064\u2066-\u2069\ufeff\u00a7<>]/g, "");
  return s.replace(/\s+/g, " ").trim().slice(0, MAX_TEXT);
}

const LEET = { "0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b", "@": "a", "$": "s", "!": "i", "|": "i", "+": "t", "(": "c", "€": "e", "£": "l" };
// list kept deliberately short and stored obfuscated-free but matched on a normalised copy
const BAD = [
  "fuck", "fuk", "fck", "shit", "bitch", "cunt", "asshole", "dick", "cock", "pussy", "whore", "slut", "bastard", "wanker", "twat",
  "nigger", "nigga", "niger", "faggot", "fagot", "fag", "retard", "tranny", "kike", "chink", "spic", "coon", "gook", "wetback", "paki",
  "rape", "rapist", "hitler", "nazi",
];
const BAD_RE = BAD.slice().sort((a, b) => b.length - a.length).join("|");

/** Replace profane words with **** while keeping the rest of the text intact. */
function censor(text) {
  // build a normalised string with a 1:1 index map (same length) so matches map back onto the original
  let norm = "";
  for (const ch of text) {
    const l = ch.toLowerCase();
    norm += LEET[l] ?? l;
    if (ch.length > 1 && norm.length < text.length) norm += " ".repeat(ch.length - 1);
  }
  norm = norm.slice(0, text.length).padEnd(text.length, " ");
  // allow separators between letters (f.u.c.k / f u c k / fuuuck)
  const re = new RegExp(`(?:${BAD.slice().sort((a, b) => b.length - a.length).map((w) => w.split("").map((c) => `${c}+`).join("[\\s._\\-*]{0,2}")).join("|")})`, "g");
  const out = text.split("");
  let m;
  while ((m = re.exec(norm))) {
    // only flag when not embedded in a longer innocent word for very short roots
    const w = m[0].replace(/[^a-z]/g, "");
    if (w.length <= 3) {
      const before = norm[m.index - 1] || " ";
      const after = norm[m.index + m[0].length] || " ";
      if (/[a-z]/.test(before) || /[a-z]/.test(after)) continue;
    }
    for (let i = m.index; i < m.index + m[0].length; i++) out[i] = "*";
    if (m[0].length === 0) re.lastIndex++;
  }
  return out.join("").replace(/\*{2,}/g, "****");
}
void BAD_RE;

const USER_RE = /^[A-Za-z0-9_]{3,16}$/;
const UUID_RE = /^[0-9a-f]{32}$/;
const normUuid = (u) => (typeof u === "string" ? u.replace(/-/g, "").toLowerCase() : "");

function randomHex(n) {
  return Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => x.toString(16).padStart(2, "0")).join("");
}

/* ---------------------------------------------------------------- Durable Object */

export class ChatRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.sql = state.storage.sql;
    this.challenges = new Map(); // challenge -> expiry
    this.ipHits = new Map(); // ip -> [timestamps]
    this.sql.exec(`CREATE TABLE IF NOT EXISTS messages (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, user TEXT NOT NULL, uuid TEXT NOT NULL, text TEXT NOT NULL, v INTEGER NOT NULL DEFAULT 0)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS mods (user TEXT PRIMARY KEY, kind TEXT NOT NULL, until INTEGER NOT NULL)`);
  }

  /* -- helpers */

  online(except) {
    return this.state.getWebSockets().filter((w) => w !== except && w.readyState === 1 && w.deserializeAttachment()?.ready).length;
  }

  newChallenge() {
    const now = Date.now();
    for (const [k, v] of this.challenges) if (v < now) this.challenges.delete(k);
    if (this.challenges.size > 2000) this.challenges.clear();
    const c = randomHex(16);
    this.challenges.set(c, now + CHALLENGE_TTL);
    return c;
  }

  takeChallenge(c) {
    const exp = this.challenges.get(c);
    if (!exp) return false;
    this.challenges.delete(c);
    return exp >= Date.now();
  }

  modState(user) {
    const row = this.sql.exec("SELECT kind, until FROM mods WHERE user = ?", user.toLowerCase()).toArray()[0];
    if (!row) return null;
    if (row.kind === "mute" && row.until < Date.now()) {
      this.sql.exec("DELETE FROM mods WHERE user = ?", user.toLowerCase());
      return null;
    }
    return row;
  }

  rows(sinceId, limit) {
    return this.sql
      .exec("SELECT id, ts, user, uuid, text, v FROM messages WHERE id > ? ORDER BY id DESC LIMIT ?", sinceId, limit)
      .toArray()
      .reverse();
  }

  history(since) {
    return since > 0 ? this.rows(since, KEEP) : this.rows(0, SEND_HISTORY);
  }

  /** Verify with Mojang. Returns {ok, user, uuid} (canonical name+uuid from Mojang) or null. */
  async mojangVerify(user, challenge) {
    try {
      const r = await fetch(
        `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(user)}&serverId=${encodeURIComponent(challenge)}`,
        { headers: { "user-agent": "tenacity-chat" } },
      );
      if (r.status !== 200) return null;
      const j = await r.json();
      if (!j || !j.id || !j.name) return null;
      return { user: j.name, uuid: String(j.id).toLowerCase() };
    } catch {
      return null;
    }
  }

  /** Rate limit: 1/s and 8 per 20 s. state is a plain object holding timestamps. */
  rate(list) {
    const now = Date.now();
    while (list.length && now - list[0] > 20_000) list.shift();
    if (list.length && now - list[list.length - 1] < 1000) return true;
    if (list.length >= 8) return true;
    list.push(now);
    return false;
  }

  rateIp(ip) {
    let l = this.ipHits.get(ip);
    if (!l) {
      if (this.ipHits.size > 5000) this.ipHits.clear();
      l = [];
      this.ipHits.set(ip, l);
    }
    // a bit looser than per-user so several players behind one NAT still work: 12 per 20 s, 1 per 0.4 s
    const now = Date.now();
    while (l.length && now - l[0] > 20_000) l.shift();
    if ((l.length && now - l[l.length - 1] < 400) || l.length >= 12) return true;
    l.push(now);
    return false;
  }

  /** Common send path. Returns {error:{code,msg}} or {msg}. */
  post(ident, text, rateList, ip) {
    const mod = this.modState(ident.user);
    if (mod?.kind === "ban") return { error: { code: "banned", msg: "You are banned from chat" } };
    if (mod?.kind === "mute") return { error: { code: "muted", msg: `You are muted for ${Math.max(1, Math.ceil((mod.until - Date.now()) / 60000))} more min` } };
    let t = cleanText(text);
    if (t.length < 1) return { error: { code: "bad", msg: "Message is empty" } };
    if (this.rate(rateList) || this.rateIp(ip)) return { error: { code: "slow", msg: "Slow down" } };
    t = censor(t);
    const ts = Date.now();
    const id = this.sql
      .exec("INSERT INTO messages (ts, user, uuid, text, v) VALUES (?, ?, ?, ?, ?) RETURNING id", ts, ident.user, ident.uuid, t, ident.verified ? 1 : 0)
      .one().id;
    this.sql.exec("DELETE FROM messages WHERE id <= ?", id - KEEP);
    const msg = { id, ts, user: ident.user, uuid: ident.uuid, text: t, v: ident.verified ? 1 : 0 };
    this.broadcast({ t: "msg", m: msg });
    return { msg };
  }

  broadcast(obj) {
    const s = JSON.stringify(obj);
    for (const ws of this.state.getWebSockets()) {
      if (!ws.deserializeAttachment()?.ready) continue;
      try { ws.send(s); } catch { /* closed */ }
    }
  }

  isAdmin(request) {
    const h = request.headers.get("authorization") || "";
    const tok = h.replace(/^Bearer\s+/i, "");
    const want = this.env.ADMIN_TOKEN;
    if (!want || tok.length !== want.length) return false;
    let d = 0;
    for (let i = 0; i < tok.length; i++) d |= tok.charCodeAt(i) ^ want.charCodeAt(i);
    return d === 0;
  }

  /* -- http */

  async fetch(request) {
    const url = new URL(request.url);
    const ip = request.headers.get("cf-connecting-ip") || "0";

    if (url.pathname === "/ws") {
      if (request.headers.get("upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(server);
      const att = { ready: false, ip, challenge: this.newChallenge(), hits: [] };
      server.serializeAttachment(att);
      server.send(JSON.stringify({ t: "hello", challenge: att.challenge }));
      return new Response(null, { status: 101, webSocket: client });
    }

    if (url.pathname === "/history" && request.method === "GET") {
      const since = parseInt(url.searchParams.get("since") || "0", 10) || 0;
      return json({ messages: this.history(since), online: this.online() });
    }

    if (url.pathname === "/challenge" && request.method === "GET") return json({ challenge: this.newChallenge() });

    if (url.pathname === "/send" && request.method === "POST") {
      let b;
      try { b = await request.json(); } catch { return json({ error: "bad json" }, 400); }
      const user = typeof b.user === "string" ? b.user : "";
      if (!USER_RE.test(user)) return json({ error: "bad username", code: "auth" }, 400);
      let uuid = normUuid(b.uuid);
      if (!UUID_RE.test(uuid)) uuid = "0".repeat(32);
      let verified = false;
      let name = user;
      const anon = b.anon === true;
      if (anon) { name = "Anonymous"; uuid = "0".repeat(32); }
      else if (b.verify && typeof b.challenge === "string" && this.takeChallenge(b.challenge)) {
        const v = await this.mojangVerify(user, b.challenge);
        if (v) { verified = true; name = v.user; uuid = v.uuid; }
      }
      // an unverified user may not take the name of someone who verified recently
      if (!anon && !verified && this.nameReserved(name)) return json({ error: "That name belongs to a verified player", code: "auth" }, 403);
      if (verified) this.reserve(name, uuid);
      const hits = this.httpHits(ip + ":" + (anon ? "anon" : name.toLowerCase()));
      const r = this.post({ user: name, uuid, verified }, b.text, hits, ip);
      if (r.error) return json({ error: r.error.msg, code: r.error.code }, r.error.code === "slow" ? 429 : 403);
      return json({ ok: true, message: r.msg, verified });
    }

    if (url.pathname.startsWith("/admin/")) {
      if (request.method !== "POST") return json({ error: "POST only" }, 405);
      if (!this.isAdmin(request)) return json({ error: "forbidden" }, 403);
      return this.admin(url);
    }

    return json({ error: "not found" }, 404);
  }

  httpHits(key) {
    if (!this.hh) this.hh = new Map();
    if (this.hh.size > 5000) this.hh.clear();
    let l = this.hh.get(key);
    if (!l) this.hh.set(key, (l = []));
    return l;
  }

  /** Verified names are reserved for 30 days so unverified clients cannot impersonate them. */
  reserve(name, uuid) {
    this.sql.exec("CREATE TABLE IF NOT EXISTS names (user TEXT PRIMARY KEY, uuid TEXT NOT NULL, seen INTEGER NOT NULL)");
    this.sql.exec("INSERT OR REPLACE INTO names (user, uuid, seen) VALUES (?, ?, ?)", name.toLowerCase(), uuid, Date.now());
  }

  nameReserved(name) {
    this.sql.exec("CREATE TABLE IF NOT EXISTS names (user TEXT PRIMARY KEY, uuid TEXT NOT NULL, seen INTEGER NOT NULL)");
    const r = this.sql.exec("SELECT seen FROM names WHERE user = ?", name.toLowerCase()).toArray()[0];
    return !!r && Date.now() - r.seen < 30 * 86400_000;
  }

  admin(url) {
    const p = url.pathname;
    const user = (url.searchParams.get("user") || "").toLowerCase();
    if (p === "/admin/mute") {
      const mins = Math.max(1, Math.min(60 * 24 * 30, parseInt(url.searchParams.get("mins") || "10", 10) || 10));
      if (!USER_RE.test(user)) return json({ error: "bad user" }, 400);
      this.sql.exec("INSERT OR REPLACE INTO mods (user, kind, until) VALUES (?, 'mute', ?)", user, Date.now() + mins * 60000);
      return json({ ok: true, muted: user, mins });
    }
    if (p === "/admin/ban") {
      if (!USER_RE.test(user)) return json({ error: "bad user" }, 400);
      this.sql.exec("INSERT OR REPLACE INTO mods (user, kind, until) VALUES (?, 'ban', ?)", user, 9e15);
      for (const ws of this.state.getWebSockets()) {
        const a = ws.deserializeAttachment();
        if (a?.user && a.user.toLowerCase() === user) { try { ws.close(4003, "banned"); } catch { /* */ } }
      }
      return json({ ok: true, banned: user });
    }
    if (p === "/admin/unmute" || p === "/admin/unban") {
      this.sql.exec("DELETE FROM mods WHERE user = ?", user);
      return json({ ok: true, cleared: user });
    }
    if (p === "/admin/delete") {
      const id = parseInt(url.searchParams.get("id") || "", 10);
      if (!id) return json({ error: "bad id" }, 400);
      this.sql.exec("DELETE FROM messages WHERE id = ?", id);
      this.broadcast({ t: "del", id });
      return json({ ok: true, deleted: id });
    }
    if (p === "/admin/list") return json({ mods: this.sql.exec("SELECT user, kind, until FROM mods").toArray() });
    return json({ error: "not found" }, 404);
  }

  /* -- websocket (hibernation api) */

  async webSocketMessage(ws, data) {
    if (typeof data !== "string" || data.length > 2048) return;
    let m;
    try { m = JSON.parse(data); } catch { return; }
    const att = ws.deserializeAttachment() || {};

    if (m.t === "ping") { ws.send('{"t":"pong"}'); return; }

    if (m.t === "auth") {
      const user = typeof m.user === "string" ? m.user : "";
      if (!USER_RE.test(user)) { ws.send(JSON.stringify({ t: "err", code: "auth", msg: "Invalid username" })); return; }
      let uuid = normUuid(m.uuid);
      if (!UUID_RE.test(uuid)) uuid = "0".repeat(32);
      let verified = false;
      let name = user;
      const anon = m.anon === true;
      if (anon) { name = "Anonymous"; uuid = "0".repeat(32); }
      else if (m.verify && att.challenge && this.takeChallenge(att.challenge)) {
        const v = await this.mojangVerify(user, att.challenge);
        if (v) { verified = true; name = v.user; uuid = v.uuid; }
      }
      if (!anon && !verified && this.nameReserved(name)) {
        ws.send(JSON.stringify({ t: "err", code: "auth", msg: "That name belongs to a verified player - sign in with a premium account" }));
        // still allow reading, but as a spectator
        att.spectator = true;
        att.ready = true;
        att.user = "";
        ws.serializeAttachment(att);
        ws.send(JSON.stringify({ t: "ready", user: name, uuid, verified: false, spectator: true, online: this.online(), history: this.rows(0, SEND_HISTORY) }));
        return;
      }
      if (verified) this.reserve(name, uuid);
      const mod = this.modState(name);
      if (mod?.kind === "ban") { ws.send(JSON.stringify({ t: "err", code: "banned", msg: "You are banned from chat" })); ws.close(4003, "banned"); return; }
      att.ready = true;
      att.user = name;
      att.uuid = uuid;
      att.verified = verified;
      att.spectator = false;
      ws.serializeAttachment(att);
      ws.send(JSON.stringify({ t: "ready", user: name, uuid, verified, online: this.online(), history: this.rows(0, SEND_HISTORY) }));
      this.broadcast({ t: "online", n: this.online() });
      return;
    }

    if (!att.ready) return;

    if (m.t === "send") {
      if (att.spectator || !att.user) { ws.send(JSON.stringify({ t: "err", code: "auth", msg: "You cannot send with this name" })); return; }
      const hits = (att.hits ||= []);
      const r = this.post({ user: att.user, uuid: att.uuid, verified: att.verified }, m.text, hits, att.ip || "0");
      ws.serializeAttachment(att);
      if (r.error) ws.send(JSON.stringify({ t: "err", code: r.error.code, msg: r.error.msg }));
    }
  }

  async webSocketClose(ws, code, reason) {
    try { ws.close(code, reason); } catch { /* */ }
    this.broadcast({ t: "online", n: this.online(ws) });
  }

  async webSocketError(ws) {
    try { ws.close(1011, "error"); } catch { /* */ }
    this.broadcast({ t: "online", n: this.online(ws) });
  }
}
