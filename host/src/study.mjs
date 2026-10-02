/**
 * Study data mirrored from the tablet.
 *
 * The tablet holds the authoritative store (SQLite) so reviews keep working with
 * the bridge down. This is a read-mostly mirror under `workspace/.study/`, kept
 * as plain JSON so the agent can read it with its own filesystem tools — which is
 * the whole point: it is how the agent learns what the user keeps getting wrong.
 *
 * The bridge never schedules reviews itself; it only stores what it is told and
 * summarises it for the prompt.
 */

import fsp from "node:fs/promises";
import path from "node:path";
import { appendFileNoFollow, realpathLoose, writeFileAtomic } from "./confine.mjs";

export class StudyStore {
  constructor(workspace) {
    this.realWorkspace = realpathLoose(workspace);
    this.root = path.join(workspace, ".study");
    this.decksPath = path.join(this.root, "decks.json");
    this.reviewsPath = path.join(this.root, "reviews.jsonl");
    this.statsPath = path.join(this.root, "stats.json");
  }

  async init() {
    await fsp.mkdir(this.root, { recursive: true });
  }

  async writeAtomic(file, text) {
    await writeFileAtomic(file, text, this.realWorkspace);
  }

  /**
   * Replaces the deck mirror. `decks` is the tablet's full card set; a partial
   * sync would silently lose cards, so this is deliberately a whole-set write.
   */
  async putDecks(decks) {
    await this.init();
    const payload = {
      at: new Date().toISOString(),
      decks: Array.isArray(decks) ? decks : [],
    };
    await this.writeAtomic(this.decksPath, JSON.stringify(payload, null, 2));
    return payload;
  }

  async getDecks() {
    try {
      return JSON.parse(await fsp.readFile(this.decksPath, "utf8"));
    } catch {
      return { at: null, decks: [] };
    }
  }

  /**
   * Appends review events. JSONL because this is an append-only log the agent
   * reads to spot patterns, and appending must not rewrite the whole history.
   */
  async appendReviews(reviews) {
    if (!Array.isArray(reviews) || !reviews.length) return 0;
    await this.init();
    const lines = reviews
      .filter((r) => r && typeof r === "object")
      .map((r) => JSON.stringify({ at: new Date().toISOString(), ...r }));
    if (!lines.length) return 0;
    await appendFileNoFollow(this.reviewsPath, lines.join("\n") + "\n");
    return lines.length;
  }

  async readReviews(limit = 500) {
    try {
      const raw = await fsp.readFile(this.reviewsPath, "utf8");
      const lines = raw.split("\n").filter(Boolean);
      return lines
        .slice(-limit)
        .map((l) => {
          try {
            return JSON.parse(l);
          } catch {
            return null;
          }
        })
        .filter(Boolean);
    } catch {
      return [];
    }
  }

  /** Rolls reviews up into the weak-spot view the prompt and the agent consume. */
  async recomputeStats() {
    const reviews = await this.readReviews(5000);
    /** @type {Map<string, {topic: string, seen: number, lapses: number, lastAt: string|null}>} */
    const byTopic = new Map();

    for (const r of reviews) {
      const topic = String(r.topic || r.deck || "untagged");
      const entry = byTopic.get(topic) || { topic, seen: 0, lapses: 0, lastAt: null };
      entry.seen += 1;
      // Anything below a 3 on the SM-2 scale counts as a failed recall.
      const grade = Number(r.grade);
      if (Number.isFinite(grade) ? grade < 3 : r.correct === false) entry.lapses += 1;
      if (!entry.lastAt || String(r.at) > entry.lastAt) entry.lastAt = String(r.at || "");
      byTopic.set(topic, entry);
    }

    const topics = [...byTopic.values()].map((t) => ({
      ...t,
      lapseRate: t.seen ? Number((t.lapses / t.seen).toFixed(3)) : 0,
    }));
    // Weakest first: the agent should see what is failing before what is fine.
    topics.sort((a, b) => b.lapseRate - a.lapseRate || b.lapses - a.lapses);

    const stats = {
      at: new Date().toISOString(),
      totalReviews: reviews.length,
      topics,
    };
    await this.init();
    await this.writeAtomic(this.statsPath, JSON.stringify(stats, null, 2));
    return stats;
  }

  async getStats() {
    try {
      return JSON.parse(await fsp.readFile(this.statsPath, "utf8"));
    } catch {
      return { at: null, totalReviews: 0, topics: [] };
    }
  }
}

/** Compact weak-spot summary for the prompt. Empty string when there is nothing to say. */
export function formatStudyStats(stats, limit = 8) {
  if (!stats || !Array.isArray(stats.topics) || !stats.topics.length) return "";
  const weak = stats.topics.filter((t) => t.seen >= 3 && t.lapseRate > 0).slice(0, limit);
  if (!weak.length) return "";
  const lines = [
    "",
    `[Study history — ${stats.totalReviews} reviews, weakest topics first]`,
  ];
  for (const t of weak) {
    lines.push(
      `- ${t.topic}: ${t.lapses}/${t.seen} failed (${Math.round(t.lapseRate * 100)}%)`
    );
  }
  lines.push(
    "Use this to decide what to reinforce; prefer these topics when suggesting what to study."
  );
  return lines.join("\n");
}
