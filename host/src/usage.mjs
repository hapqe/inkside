/**
 * Usage log for a shared host: one JSON line per agent run, saying which tester ran it
 * (their access token's name), the model, tokens in and out, the cost the SDK reports,
 * and how long it took. Written only to a file on this computer (usage.jsonl in the log
 * directory) — nothing serves it over HTTP. `npm run usage` sums it up per tester.
 */
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";

export class UsageLog {
  constructor(dir) {
    this.file = path.join(dir, "usage.jsonl");
    this.chain = Promise.resolve();
  }

  /** Records one finished run. `result` is the SDK's result message. */
  record({ tester = "", chatId = "", model = "", result = null, kind = "chat" }) {
    const u = result?.usage || {};
    const entry = {
      at: new Date().toISOString(),
      tester: tester || "local",
      kind,
      chatId: String(chatId || ""),
      model: model || "",
      inputTokens: Number(u.input_tokens || 0),
      outputTokens: Number(u.output_tokens || 0),
      cacheReadTokens: Number(u.cache_read_input_tokens || 0),
      cacheWriteTokens: Number(u.cache_creation_input_tokens || 0),
      costUsd: Number(result?.total_cost_usd || 0),
      durationMs: Number(result?.duration_ms || 0),
      turns: Number(result?.num_turns || 0),
      error: Boolean(result?.is_error),
    };
    const line = JSON.stringify(entry) + "\n";
    this.chain = this.chain
      .then(async () => {
        await fsp.mkdir(path.dirname(this.file), { recursive: true });
        await fsp.appendFile(this.file, line, { mode: 0o600 });
      })
      .catch((e) => console.warn("usage log:", e?.message || e));
    return this.chain;
  }
}

/** Per-tester totals from a usage file, optionally only entries at or after `since`. */
export function summarizeUsage(file, { since = null } = {}) {
  let text = "";
  try {
    text = fs.readFileSync(file, "utf8");
  } catch {
    return [];
  }
  const by = new Map();
  for (const raw of text.split("\n")) {
    if (!raw.trim()) continue;
    let e;
    try {
      e = JSON.parse(raw);
    } catch {
      continue;
    }
    if (since && String(e.at) < since) continue;
    const k = e.tester || "local";
    const t = by.get(k) || {
      tester: k, runs: 0, inputTokens: 0, outputTokens: 0, cacheReadTokens: 0,
      cacheWriteTokens: 0, costUsd: 0, durationMs: 0, first: e.at, last: e.at,
    };
    t.runs++;
    t.inputTokens += e.inputTokens || 0;
    t.outputTokens += e.outputTokens || 0;
    t.cacheReadTokens += e.cacheReadTokens || 0;
    t.cacheWriteTokens += e.cacheWriteTokens || 0;
    t.costUsd += e.costUsd || 0;
    t.durationMs += e.durationMs || 0;
    if (String(e.at) < String(t.first)) t.first = e.at;
    if (String(e.at) > String(t.last)) t.last = e.at;
    by.set(k, t);
  }
  return [...by.values()].sort((a, b) => b.costUsd - a.costUsd);
}
