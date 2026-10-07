/**
 * Who may use the host: whoever presents an access token. Nothing else counts — not the
 * network a device is on, not being this computer.
 *
 * The owner's token is BRIDGE_TOKEN, or one the host made itself on first start (kept in
 * its state directory). Testers each have their own, from a tokens file (opts.tokensFile,
 * JSON {"testers": [{"name", "token"}]}), so their use can be told apart (req.tester; the
 * owner is "owner"). The file is re-read when it changes, so tokens can be added or
 * revoked without a restart.
 *
 * A token is accepted as `Authorization: Bearer <token>`, `X-Bridge-Token`, `?token=`
 * (web views loading artifact pages; this also sets a cookie) or the `cc_token` cookie.
 * Put TLS in front of a host reachable from the internet: the token travels in a header.
 *
 * Browsers: a request carrying an Origin that is not this host's own is refused, so a web
 * page cannot drive the host from someone's browser even with a token in a cookie.
 */
import crypto from "node:crypto";
import fs from "node:fs";

const COOKIE = "cc_token";

function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ab.length !== bb.length) {
    // Still spend the comparison so length is not trivially observable.
    crypto.timingSafeEqual(ab, ab);
    return false;
  }
  return crypto.timingSafeEqual(ab, bb);
}

function cookieValue(req, name) {
  const raw = req.headers?.cookie;
  if (!raw) return null;
  for (const part of String(raw).split(";")) {
    const i = part.indexOf("=");
    if (i < 0) continue;
    if (part.slice(0, i).trim() === name) {
      try {
        return decodeURIComponent(part.slice(i + 1).trim());
      } catch {
        return null;
      }
    }
  }
  return null;
}

/**
 * Where the cookie applies: the path a proxy serves the host under (X-Forwarded-Prefix,
 * e.g. "/inkside"), so the token is not sent to other sites on the same domain.
 */
function cookiePath(req) {
  const prefix = String(req.headers?.["x-forwarded-prefix"] || "").trim();
  return /^\/[A-Za-z0-9._~\-/]*$/.test(prefix) ? prefix.replace(/\/+$/, "") || "/" : "/";
}

/**
 * @param {{ token?: string, tokensFile?: string, publicPaths?: string[] }} opts
 */
export function createAuth(opts = {}) {
  const token = String(opts.token || "").trim();
  const tokensFile = opts.tokensFile ? String(opts.tokensFile) : "";
  const publicPrefixes = opts.publicPaths || [];
  let testerCache = { at: 0, mtime: -1, list: [] };

  /** [{name, token}] from the tokens file, re-read when it changes (checked every 2s). */
  function testers() {
    if (!tokensFile) return [];
    const now = Date.now();
    if (now - testerCache.at < 2000) return testerCache.list;
    testerCache.at = now;
    let mtime = 0;
    try {
      mtime = fs.statSync(tokensFile).mtimeMs;
    } catch {
      testerCache = { at: now, mtime: 0, list: [] };
      return [];
    }
    if (mtime === testerCache.mtime) return testerCache.list;
    let list = [];
    try {
      const o = JSON.parse(fs.readFileSync(tokensFile, "utf8"));
      list = (Array.isArray(o?.testers) ? o.testers : [])
        .filter((t) => !t?.disabled)
        .map((t) => ({ name: String(t?.name || "").trim(), token: String(t?.token || "").trim() }))
        .filter((t) => t.name && t.token.length >= 16);
    } catch (e) {
      console.warn(`tokens file unreadable (${tokensFile}): ${e?.message || e}`);
    }
    testerCache = { at: now, mtime, list };
    return list;
  }

  /** An Origin from somewhere else: a web page trying to use the host. */
  function crossOrigin(req) {
    const origin = req.headers?.origin;
    if (origin === undefined) return false;
    try {
      return new URL(String(origin)).host.toLowerCase() !== String(req.headers.host || "").toLowerCase();
    } catch {
      return true; // "null" and other opaque origins
    }
  }

  function presented(req) {
    const h = req.headers || {};
    const bearer = /^Bearer\s+(.+)$/i.exec(String(h.authorization || ""));
    if (bearer) return { value: bearer[1].trim(), via: "header" };
    if (h["x-bridge-token"]) return { value: String(h["x-bridge-token"]).trim(), via: "header" };
    if (req.query && typeof req.query.token === "string") return { value: req.query.token, via: "query" };
    const c = cookieValue(req, COOKIE);
    if (c) return { value: c, via: "cookie" };
    return null;
  }

  /** "origin": a browser request from another site; "token": no valid access token. */
  function refusal(req) {
    if (crossOrigin(req)) return "origin";
    const p = presented(req);
    if (!p) return "token";
    if (token && safeEqual(p.value, token)) {
      req.tester = "owner";
      return null;
    }
    for (const t of testers()) {
      if (safeEqual(p.value, t.token)) {
        req.tester = t.name;
        return null;
      }
    }
    return "token";
  }

  /** True when this request may use the host. */
  function isAuthed(req) {
    return refusal(req) === null;
  }

  function middleware(req, res, next) {
    if (req.method === "OPTIONS") return next();
    const url = req.path || "";
    if (url === "/health" || publicPrefixes.some((pre) => url === pre || url.startsWith(pre + "/"))) {
      return next();
    }
    const why = refusal(req);
    if (!why) {
      const p = presented(req);
      if (p && p.via === "query") {
        // Let the page's own relative requests (assets, fetches) authenticate too.
        res.setHeader(
          "Set-Cookie",
          `${COOKIE}=${encodeURIComponent(p.value)}; Path=${cookiePath(req)}; HttpOnly; SameSite=Lax; Max-Age=31536000`
        );
      }
      return next();
    }
    if (why === "origin") {
      return res.status(403).json({ error: "requests from web pages are not accepted" });
    }
    res.setHeader("WWW-Authenticate", 'Bearer realm="inkside-host"');
    return res.status(401).json({ error: "unauthorized — this host requires an access token" });
  }

  return { tokenRequired: true, isAuthed, refusal, middleware, testers };
}
