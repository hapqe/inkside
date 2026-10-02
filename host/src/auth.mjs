/**
 * Who may use the host.
 *
 * The host can read, write and run things in the workspace, so it only answers
 * devices on a network this computer is on (see network.mjs): the tablet connects by
 * typing this computer's address, nothing more. INKSIDE_OPEN=1 lifts the network
 * check (only on networks you fully trust).
 *
 * BRIDGE_TOKEN is an access token: when it is set, every request must carry it and the
 * network no longer matters, so a computer reachable over the internet can be shared with
 * someone who was given the token. Put TLS (or Tailscale) in front of such a host: the
 * token travels in a header. Accepted as `Authorization: Bearer <token>`, `X-Bridge-Token`,
 * `?token=` (web views loading artifact pages; also sets a cookie) or the `cc_token`
 * cookie. Requests from this computer itself need it too unless BRIDGE_TRUST_LOOPBACK=1:
 * a reverse proxy on the same computer makes every request look local.
 *
 * Browsers: a request carrying an Origin that is not this host's own is refused, so a web
 * page cannot drive the host from someone's browser. A request let in without a token
 * (same network, loopback, INKSIDE_OPEN) must also name the host by an address or a known
 * name, which stops DNS rebinding (a hostile name re-pointed at this computer).
 */
import crypto from "node:crypto";
import os from "node:os";
import net from "node:net";
import { clientAddress, isLoopback, isSameNetwork } from "./network.mjs";

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

/** "host:port" / "[v6]:port" → bare lowercase host name or address. */
function hostName(hostHeader) {
  const h = String(hostHeader || "").trim().toLowerCase();
  if (h.startsWith("[")) return h.slice(1, h.indexOf("]") > 0 ? h.indexOf("]") : undefined);
  const i = h.lastIndexOf(":");
  return i > 0 && h.indexOf(":") === i ? h.slice(0, i) : h;
}

/**
 * @param {{ token?: string, open?: boolean, trustLoopback?: boolean,
 *           publicPaths?: string[], sameNetwork?: (addr: string) => boolean,
 *           allowedHosts?: string[] }} opts
 */
export function createAuth(opts = {}) {
  const token = String(opts.token || "").trim();
  const open = !!opts.open;
  // With a token, loopback is not trusted unless asked for: behind a reverse proxy on
  // this computer every request arrives from 127.0.0.1.
  const trustLoopback = token ? opts.trustLoopback === true : true;
  const publicPrefixes = opts.publicPaths || [];
  const sameNetwork = opts.sameNetwork || ((addr) => isSameNetwork(addr));
  const machine = os.hostname().toLowerCase();
  const knownHosts = new Set(
    ["localhost", machine, machine.replace(/\.local$/, ""), `${machine.replace(/\.local$/, "")}.local`,
      ...(opts.allowedHosts || []).map((h) => String(h).trim().toLowerCase()).filter(Boolean)]
  );

  /** A host name a rebinding attacker cannot control: an address, or a name we know. */
  function hostAllowed(req) {
    const raw = req.headers?.host;
    if (!raw) return true; // not a browser
    const name = hostName(raw);
    if (net.isIP(name)) return true;
    if (knownHosts.has(name)) return true;
    return name.endsWith(".local") || name.endsWith(".ts.net");
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

  /**
   * "origin": a browser request from another site; "network": not on this computer's
   * network; "token": BRIDGE_TOKEN missing or wrong; "host": let in without a token but
   * addressed by a name we do not know (DNS rebinding).
   */
  function refusal(req) {
    if (crossOrigin(req)) return "origin";
    const addr = clientAddress(req);
    let admitted;
    if (token) {
      const p = presented(req);
      if (p && safeEqual(p.value, token)) return null;
      admitted = trustLoopback && isLoopback(addr);
      if (!admitted) return "token";
    } else {
      admitted = open || sameNetwork(addr);
      if (!admitted) return "network";
    }
    return hostAllowed(req) ? null : "host";
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
      const p = token ? presented(req) : null;
      if (p && p.via === "query") {
        // Let the page's own relative requests (assets, fetches) authenticate too.
        res.setHeader(
          "Set-Cookie",
          `${COOKIE}=${encodeURIComponent(p.value)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=31536000`
        );
      }
      return next();
    }
    if (why === "network") {
      return res.status(403).json({ error: "this computer only accepts devices on its own network" });
    }
    if (why === "origin") {
      return res.status(403).json({ error: "requests from web pages are not accepted" });
    }
    if (why === "host") {
      return res.status(403).json({ error: "unknown host name — use this computer's address, or add the name to INKSIDE_ALLOWED_HOSTS" });
    }
    res.setHeader("WWW-Authenticate", 'Bearer realm="inkside-host"');
    return res.status(401).json({ error: "unauthorized — this host requires BRIDGE_TOKEN" });
  }

  return { tokenRequired: token.length > 0, open, isAuthed, refusal, middleware };
}
