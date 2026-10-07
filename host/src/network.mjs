/**
 * This computer's addresses on its networks: packed into the access token (see
 * connectToken.mjs) when no public address is set, so a device needs only the token.
 * Admission never looks at addresses: only access tokens let a device in (auth.mjs).
 */
import os from "node:os";

/** This computer's IPv4 addresses (LAN first), for the access token to carry. */
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
