/**
 * Access tokens are all a device needs to connect. A plain token means the Inkside test
 * computer; a connection token also carries where its host is, so self-hosted computers
 * work the same way: "ink1." + base64url of {"u": [urls], "t": token}. The app decodes it
 * and tries the addresses in order.
 */
const PREFIX = "ink1.";

export function encodeConnectToken(token, urls) {
  const u = (urls || []).map((x) => String(x).trim().replace(/\/+$/, "")).filter(Boolean);
  if (!u.length) return String(token);
  return PREFIX + Buffer.from(JSON.stringify({ u, t: String(token) }), "utf8").toString("base64url");
}

/** {token, urls} from what a user pasted; urls empty for a plain token. */
export function decodeConnectToken(raw) {
  const s = String(raw || "").trim();
  if (!s.startsWith(PREFIX)) return { token: s, urls: [] };
  try {
    const o = JSON.parse(Buffer.from(s.slice(PREFIX.length), "base64url").toString("utf8"));
    return { token: String(o.t || ""), urls: Array.isArray(o.u) ? o.u.map(String) : [] };
  } catch {
    return { token: "", urls: [] };
  }
}
