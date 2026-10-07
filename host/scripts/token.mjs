#!/usr/bin/env node
/**
 * Prints this host's access token as the app takes it: the owner's token with where to
 * reach the host packed in (see src/connectToken.mjs). Reads the same settings as the
 * host: host/.env, BRIDGE_TOKEN or <state dir>/owner-token, INKSIDE_PUBLIC_URL.
 *
 *   npm run token                [--state DIR] [--url https://example.org/inkside]
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { encodeConnectToken } from "../src/connectToken.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
try {
  for (const line of fs.readFileSync(path.join(root, ".env"), "utf8").split("\n")) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/^["']|["']$/g, "");
  }
} catch {
  /* no .env */
}
const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
};
const stateDir = path.resolve(
  flag("--state") || process.env.INKSIDE_STATE_DIR ||
    path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"), "inkside-host")
);
let token = String(process.env.BRIDGE_TOKEN || "").trim();
if (!token) {
  try {
    token = fs.readFileSync(path.join(stateDir, "owner-token"), "utf8").trim();
  } catch {
    console.error("no token yet: start the host once (it makes one), or set BRIDGE_TOKEN");
    process.exit(1);
  }
}
const urls = String(flag("--url") || process.env.INKSIDE_PUBLIC_URL || "")
  .split(",").map((u) => u.trim()).filter(Boolean);
if (!urls.length) {
  console.error("tell it where devices reach this host: INKSIDE_PUBLIC_URL or --url");
  process.exit(1);
}
console.log(encodeConnectToken(token, urls));
