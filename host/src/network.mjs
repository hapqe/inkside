/**
 * "Same network": the host only answers devices on a network this computer is on.
 * A request is local when its address lies in the subnet of one of this computer's
 * network interfaces (the home Wi-Fi, a USB tether, …). Tailscale hands out /32
 * addresses, so its range (100.64.0.0/10) counts as one network when this computer
 * is on it too. Loopback is always local.
 */
import os from "node:os";

const TAILSCALE = { base: ipv4ToInt("100.64.0.0"), bits: 10 };

function ipv4ToInt(ip) {
  const p = String(ip).split(".").map(Number);
  if (p.length !== 4 || p.some((n) => !Number.isInteger(n) || n < 0 || n > 255)) return null;
  return ((p[0] << 24) >>> 0) + (p[1] << 16) + (p[2] << 8) + p[3];
}

function inRange(ip, base, bits) {
  const mask = bits === 0 ? 0 : (~0 << (32 - bits)) >>> 0;
  return ((ip & mask) >>> 0) === ((base & mask) >>> 0);
}

/** The address a request came from, as plain IPv4 when it is IPv4-mapped. */
export function clientAddress(req) {
  const raw = String(req.socket?.remoteAddress || "");
  return raw.startsWith("::ffff:") ? raw.slice(7) : raw;
}

export function isLoopback(addr) {
  return addr === "::1" || addr.startsWith("127.");
}

/**
 * @param {string} addr the client's address
 * @param {object} [interfaces] os.networkInterfaces() (a parameter for tests)
 */
export function isSameNetwork(addr, interfaces = os.networkInterfaces()) {
  if (isLoopback(addr)) return true;
  const ip = ipv4ToInt(addr);
  if (ip === null) return false; // IPv6 from elsewhere: the app talks IPv4
  let onTailnet = false;
  for (const list of Object.values(interfaces)) {
    for (const a of list || []) {
      if (a.family !== "IPv4" && a.family !== 4) continue;
      const own = ipv4ToInt(a.address);
      if (own === null) continue;
      const bits = Number(String(a.cidr || "").split("/")[1]);
      if (Number.isInteger(bits) && bits > 0 && bits < 32 && inRange(ip, own, bits)) return true;
      if (inRange(own, TAILSCALE.base, TAILSCALE.bits)) onTailnet = true;
    }
  }
  return onTailnet && inRange(ip, TAILSCALE.base, TAILSCALE.bits);
}

/** IPv4 addresses a tablet on the same network would type in (LAN first). */
export function reachableAddresses(interfaces = os.networkInterfaces()) {
  const out = [];
  for (const list of Object.values(interfaces)) {
    for (const a of list || []) {
      if (a.internal || (a.family !== "IPv4" && a.family !== 4)) continue;
      if (a.address.startsWith("169.254.")) continue;
      out.push(a.address);
    }
  }
  const rank = (ip) => (/^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))/.test(ip) ? 0 : 1);
  return out.sort((a, b) => rank(a) - rank(b));
}
