#!/usr/bin/env node
/**
 * Access tokens for testers of a shared host, one per person, so their use shows up
 * separately in the usage log. Tokens live in <state dir>/tokens.json (read by the host,
 * re-read when it changes — no restart needed).
 *
 *   npm run testers -- list                       [--state DIR]
 *   npm run testers -- add <name>                 prints the new token
 *   npm run testers -- remove <name>
 *
 * The state directory is INKSIDE_STATE_DIR, else ~/.config/inkside-host (as the host).
 */
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  if (i < 0) return null;
  const v = args[i + 1];
  args.splice(i, 2);
  return v;
};
const stateDir = path.resolve(
  flag("--state") ||
    process.env.INKSIDE_STATE_DIR ||
    path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"), "inkside-host")
);
const file = process.env.INKSIDE_TOKENS_FILE || path.join(stateDir, "tokens.json");
const [cmd, name] = args;

function load() {
  try {
    const o = JSON.parse(fs.readFileSync(file, "utf8"));
    return Array.isArray(o?.testers) ? o.testers : [];
  } catch {
    return [];
  }
}

function save(list) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify({ testers: list }, null, 2) + "\n", { mode: 0o600 });
  fs.renameSync(tmp, file);
}

const list = load();
if (cmd === "add") {
  const n = String(name || "").trim();
  if (!/^[A-Za-z0-9._-]{1,40}$/.test(n)) {
    console.error("give a name: letters, digits, . _ - (e.g. anna or tester-1)");
    process.exit(1);
  }
  if (list.some((t) => t.name === n)) {
    console.error(`"${n}" already has a token (remove it first to issue a new one)`);
    process.exit(1);
  }
  const token = crypto.randomBytes(24).toString("base64url");
  list.push({ name: n, token, createdAt: new Date().toISOString() });
  save(list);
  console.log(token);
} else if (cmd === "remove") {
  const next = list.filter((t) => t.name !== name);
  if (next.length === list.length) {
    console.error(`no tester "${name}"`);
    process.exit(1);
  }
  save(next);
  console.log(`removed ${name}`);
} else if (cmd === "list" || !cmd) {
  if (!list.length) console.log(`no testers yet (${file})`);
  for (const t of list) console.log(`${t.name}\t${t.createdAt || ""}${t.disabled ? "\tdisabled" : ""}`);
} else {
  console.error("usage: testers.mjs list | add <name> | remove <name>  [--state DIR]");
  process.exit(1);
}
