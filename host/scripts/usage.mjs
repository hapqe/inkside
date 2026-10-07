#!/usr/bin/env node
/**
 * How much each tester used a shared host: runs, tokens and cost, from the usage log the
 * host writes on this computer (<log dir>/usage.jsonl).
 *
 *   npm run usage                       [--logs DIR] [--since 2026-10-01]
 *
 * The log directory is BRIDGE_LOG_DIR, else host/logs (as the host).
 */
import path from "node:path";
import { fileURLToPath } from "node:url";
import { summarizeUsage } from "../src/usage.mjs";

const args = process.argv.slice(2);
const flag = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : null;
};
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dir = path.resolve(flag("--logs") || process.env.BRIDGE_LOG_DIR || path.join(root, "logs"));
const file = path.join(dir, "usage.jsonl");
const rows = summarizeUsage(file, { since: flag("--since") });
if (!rows.length) {
  console.log(`no usage recorded yet (${file})`);
  process.exit(0);
}
const k = (n) => (n >= 1e6 ? `${(n / 1e6).toFixed(2)}M` : n >= 1e3 ? `${(n / 1e3).toFixed(1)}k` : String(n));
const pad = (s, n) => String(s).padEnd(n);
console.log(pad("tester", 18) + pad("runs", 7) + pad("in", 9) + pad("out", 9) + pad("cache r/w", 16) + pad("cost $", 10) + "last");
let total = 0;
for (const r of rows) {
  total += r.costUsd;
  console.log(
    pad(r.tester, 18) + pad(r.runs, 7) + pad(k(r.inputTokens), 9) + pad(k(r.outputTokens), 9) +
      pad(`${k(r.cacheReadTokens)}/${k(r.cacheWriteTokens)}`, 16) + pad(r.costUsd.toFixed(2), 10) +
      String(r.last).slice(0, 16).replace("T", " ")
  );
}
console.log(`\ntotal $${total.toFixed(2)} · ${file}`);
