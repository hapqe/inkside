/**
 * Learning Mode: an adaptive tutor instead of an answer machine.
 *
 * Two things are kept apart on purpose:
 *
 *   - Conversation context stays per chat (each chat is its own agent session).
 *   - What the student KNOWS is global — one learner model shared by every chat,
 *     stored in workspace/.learning/state.json so the agent can also read it.
 *
 * The learner model tracks each concept on a ladder:
 *
 *   not_encountered → explained → recalled → applied → transferred → mastered
 *
 * Being shown an explanation only ever reaches "explained". Every step above that
 * must be backed by the student's OWN response (their words, their work) — the
 * store refuses a higher level without such evidence, and "mastered" is only
 * reachable from "transferred".
 *
 * Documents get learning goals (what the student should be able to do) that are
 * separate from tutor policy (what help the AI may give). Help on a self-solve task
 * climbs a hint ladder one rung at a time, and the student has to contribute
 * something between rungs. None of this forbids answering: it shapes the minimum
 * helpful intervention.
 */
import fsp from "node:fs/promises";
import path from "node:path";
import { createSdkMcpServer, tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";
import { realpathLoose, writeFileAtomic } from "./confine.mjs";

export const LEVELS = [
  "not_encountered",
  "explained",
  "recalled",
  "applied",
  "transferred",
  "mastered",
];

/** Hint ladder for self-solve tasks. 5 = full solution. */
export const HINT_LEVELS = [
  null,
  "ask what the student already knows / has tried",
  "conceptual hint (name the idea, not the step)",
  "suggest a possible strategy",
  "partial solution (a first step or a worked sub-part)",
  "full solution",
];

export const POLICIES = ["self-solve", "guided", "reference"];

const MAX_EVIDENCE = 20;
const MAX_CONCEPTS_IN_PROMPT = 14;
/** Goals listed in one prompt; the store itself has no limit on goals. */
const PROMPT_GOALS = 80;

function levelIndex(l) {
  const i = LEVELS.indexOf(String(l || ""));
  return i < 0 ? 0 : i;
}

export function conceptKey(name) {
  return String(name || "")
    .toLowerCase()
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 120);
}

function clip(s, n = 300) {
  const t = String(s ?? "").replace(/\s+/g, " ").trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
}

function emptyState() {
  return { version: 1, enabled: false, concepts: {}, goals: {}, pendingDocs: [] };
}

export class LearningStore {
  constructor(workspace) {
    this.realWorkspace = realpathLoose(workspace);
    this.root = path.join(workspace, ".learning");
    this.file = path.join(this.root, "state.json");
    this.state = null;
    this.chain = Promise.resolve();
  }

  async init() {
    await fsp.mkdir(this.root, { recursive: true });
    await this.load();
  }

  async load() {
    try {
      const o = JSON.parse(await fsp.readFile(this.file, "utf8"));
      this.state = { ...emptyState(), ...o };
      this.state.concepts = this.state.concepts || {};
      this.state.goals = this.state.goals || {};
    } catch {
      this.state = emptyState();
    }
    return this.state;
  }

  /** Serialised read-modify-write; the file is replaced atomically. */
  update(mutate) {
    const run = async () => {
      if (!this.state) await this.load();
      const result = await mutate(this.state);
      this.state.updatedAt = new Date().toISOString();
      await fsp.mkdir(this.root, { recursive: true });
      await writeFileAtomic(this.file, JSON.stringify(this.state, null, 2), this.realWorkspace);
      return result;
    };
    const p = this.chain.then(run, run);
    this.chain = p.catch(() => {});
    return p;
  }

  async snapshot() {
    await this.chain;
    if (!this.state) await this.load();
    return JSON.parse(JSON.stringify(this.state));
  }

  isEnabled() {
    return !!this.state?.enabled;
  }

  setEnabled(on) {
    return this.update((s) => {
      s.enabled = !!on;
      return s.enabled;
    });
  }

  reset() {
    return this.update((s) => {
      const enabled = s.enabled;
      Object.assign(s, emptyState(), { enabled });
      return true;
    });
  }

  /**
   * Record where the student stands on a concept.
   *
   * source "tutor" may only claim "explained" (you explained it; that says nothing
   * about understanding). Anything higher needs source "student" and evidence — a
   * short summary of what the student said or did. "mastered" requires the concept
   * to be at "transferred" already. Lower levels are allowed (a failed recall).
   */
  recordEvidence({ concept, level, evidence = "", source = "student", chatId = "", goalId = "" }) {
    return this.update((s) => {
      const key = conceptKey(concept);
      if (!key) return { accepted: false, message: "concept is required" };
      if (!LEVELS.includes(level)) {
        return { accepted: false, message: `level must be one of ${LEVELS.join(", ")}` };
      }
      const c = s.concepts[key] || {
        name: clip(concept, 120),
        level: "not_encountered",
        evidence: [],
        createdAt: new Date().toISOString(),
      };
      const prev = c.level;
      const target = levelIndex(level);
      const current = levelIndex(prev);
      const ev = clip(evidence, 400);

      if (target > levelIndex("explained")) {
        if (source !== "student" || !ev) {
          return {
            accepted: false,
            concept: c.name,
            level: prev,
            message:
              `"${level}" must be verified through the student's own response: ` +
              "record it with source \"student\" and evidence summarising what they said or did. " +
              "An explanation alone only reaches \"explained\".",
          };
        }
      }
      if (level === "mastered" && current < levelIndex("transferred")) {
        return {
          accepted: false,
          concept: c.name,
          level: prev,
          message:
            "\"mastered\" needs the concept at \"transferred\" first — ask a transfer question " +
            "(the same idea in a new situation) and record that.",
        };
      }
      // An explanation never lowers a level the student has already shown.
      const next = level === "explained" && current > target ? prev : level;
      c.level = next;
      c.updatedAt = new Date().toISOString();
      c.evidence.push({
        at: c.updatedAt,
        level,
        source,
        note: ev,
        chatId: String(chatId || ""),
        goalId: String(goalId || ""),
      });
      if (c.evidence.length > MAX_EVIDENCE) c.evidence = c.evidence.slice(-MAX_EVIDENCE);
      s.concepts[key] = c;
      return {
        accepted: true,
        concept: c.name,
        previous: prev,
        level: c.level,
        regressed: levelIndex(c.level) < current,
      };
    });
  }

  /**
   * Learning goals for a document: what the student should be able to do, plus the
   * tutor policy for each (how much help is appropriate). There is no cap on how many
   * a document can have, and existing goals are never dropped: a call updates the
   * tasks it names (their hint progress and status stay) and adds the new ones, so
   * goals can be defined in several batches.
   */
  setGoals({ document, goals }) {
    return this.update((s) => {
      const doc = String(document || "").trim();
      if (!doc) return { accepted: false, message: "document is required" };
      if (!Array.isArray(goals) || !goals.length) {
        return { accepted: false, message: "goals must be a non-empty list" };
      }
      const prior = s.goals[doc]?.items || [];
      const items = prior.map((p) => ({ ...p }));
      const byTask = new Map(items.map((it) => [it.task, it]));
      // Ids stay unique and never reuse a number an earlier goal had.
      let next = 1 + items.reduce((m, it) => Math.max(m, parseInt(String(it.id).replace(/\D/g, ""), 10) || 0), 0);
      let added = 0;
      let updated = 0;
      for (const g of goals) {
        const task = clip(g?.task, 200);
        if (!task) continue;
        const policy = POLICIES.includes(g.policy) ? g.policy : null;
        const concepts = (Array.isArray(g.concepts) ? g.concepts : []).map((c) => clip(c, 80)).slice(0, 8);
        const old = byTask.get(task);
        if (old) {
          if (g.objective) old.objective = clip(g.objective, 300);
          if (concepts.length) old.concepts = concepts;
          if (policy) old.policy = policy;
          updated++;
          continue;
        }
        const it = {
          id: `g${next++}`,
          task,
          objective: clip(g.objective || "The student should be able to solve this independently.", 300),
          concepts,
          policy: policy || "self-solve",
          hintLevel: 0,
          awaitingStudent: false,
          status: "open",
          hints: [],
        };
        items.push(it);
        byTask.set(task, it);
        added++;
      }
      s.goals[doc] = { setAt: s.goals[doc]?.setAt || new Date().toISOString(), updatedAt: new Date().toISOString(), items };
      s.pendingDocs = (s.pendingDocs || []).filter((x) => x !== doc);
      return {
        accepted: true,
        document: doc,
        added,
        updated,
        total: items.length,
        goals: items.map(({ hints, ...g }) => g),
      };
    });
  }

  /**
   * Record that help at `level` (1–5) is being given on a self-solve goal. Rungs
   * cannot be skipped without a reason, and between rungs the student must have
   * contributed something (studentContribution) — consuming hints is not learning.
   */
  hintStep({ document, goalId, level, studentContribution = "", reason = "", chatId = "" }) {
    return this.update((s) => {
      const g = s.goals[String(document || "")]?.items?.find((x) => x.id === goalId);
      if (!g) return { accepted: false, message: "unknown goal — call set_goals for this document first" };
      const lvl = Number(level);
      if (!(lvl >= 1 && lvl <= 5)) return { accepted: false, message: "level must be 1–5" };
      const contrib = clip(studentContribution, 400);
      if (g.awaitingStudent && lvl > g.hintLevel && !contrib) {
        return {
          accepted: false,
          goal: g.id,
          hintLevel: g.hintLevel,
          message:
            "The student has not responded to the last hint yet. Ask them to try the next step " +
            "themselves; pass what they did as studentContribution before giving more help.",
        };
      }
      if (g.policy === "self-solve" && lvl > g.hintLevel + 1 && !clip(reason, 300)) {
        return {
          accepted: false,
          goal: g.id,
          hintLevel: g.hintLevel,
          message:
            `Next rung is ${g.hintLevel + 1} (${HINT_LEVELS[g.hintLevel + 1]}). Skipping ahead needs a reason ` +
            "(e.g. the student has worked on it and is stuck on a prerequisite, or asked explicitly after trying).",
        };
      }
      g.hintLevel = Math.max(g.hintLevel, lvl);
      g.awaitingStudent = lvl < 5;
      if (lvl === 5 && g.status === "open") g.status = "solution_shown";
      g.hints.push({
        at: new Date().toISOString(),
        level: lvl,
        contribution: contrib,
        reason: clip(reason, 300),
        chatId: String(chatId || ""),
      });
      if (g.hints.length > 30) g.hints = g.hints.slice(-30);
      return {
        accepted: true,
        goal: g.id,
        hintLevel: g.hintLevel,
        gives: HINT_LEVELS[lvl],
        next:
          lvl < 5
            ? "Stop after this hint and hand the next step to the student."
            : "After the full solution, ask a short understanding or transfer question.",
      };
    });
  }

  /** A goal was reached — by the student themselves, or with the solution shown. */
  completeGoal({ document, goalId, by = "student", evidence = "" }) {
    return this.update((s) => {
      const g = s.goals[String(document || "")]?.items?.find((x) => x.id === goalId);
      if (!g) return { accepted: false, message: "unknown goal" };
      if (by === "student" && !clip(evidence)) {
        return { accepted: false, message: "say what the student did to solve it (evidence)" };
      }
      g.status = by === "student" ? "solved_by_student" : "solution_shown";
      g.awaitingStudent = false;
      g.completedAt = new Date().toISOString();
      g.evidence = clip(evidence, 400);
      return { accepted: true, goal: g.id, status: g.status };
    });
  }

  /** A PDF arrived (upload or chat attachment): it should get learning goals. */
  notePendingDocument(doc) {
    const d = String(doc || "").trim();
    if (!d || !/\.pdf$/i.test(d)) return Promise.resolve(false);
    return this.update((s) => {
      s.pendingDocs = (s.pendingDocs || []).filter((x) => x !== d);
      if (!s.goals[d]) s.pendingDocs.push(d);
      // Only a runaway queue is trimmed; a document waiting for goals is not forgotten.
      if (s.pendingDocs.length > 500) s.pendingDocs = s.pendingDocs.slice(-500);
      return true;
    });
  }

  /** Counts per level, for the app's settings line. */
  async summary() {
    const s = await this.snapshot();
    const counts = Object.fromEntries(LEVELS.map((l) => [l, 0]));
    for (const c of Object.values(s.concepts)) counts[c.level] = (counts[c.level] || 0) + 1;
    const goals = Object.values(s.goals).reduce((n, d) => n + (d.items?.length || 0), 0);
    return {
      enabled: !!s.enabled,
      concepts: Object.keys(s.concepts).length,
      counts,
      documentsWithGoals: Object.keys(s.goals).length,
      goals,
      updatedAt: s.updatedAt || null,
    };
  }
}

/**
 * Per-turn prompt block while Learning Mode is on: the tutor policy plus a compact
 * view of the global learner model and the open document's goals. Per turn (not in
 * the session preamble) so switching the mode takes effect in every chat at once.
 */
export function formatLearningContext(state, { openFile = "" } = {}) {
  const lines = [
    "[Learning Mode is ON — adaptive tutoring]",
    "Goal: give enough help for learning without replacing the student's own thinking.",
    "Never refuse; choose the minimum helpful intervention:",
    "- Decide what the student is expected to learn, what they already demonstrably know,",
    "  which prerequisite is missing, and the smallest step that unblocks them.",
    "- Learning goals (what the student should be able to do) are separate from tutor",
    "  policy (how much help to give). You may teach a missing prerequisite fully",
    "  without revealing the solution to the original task.",
    "- Self-solve tasks climb a hint ladder, one rung at a time, and the student must",
    "  contribute between rungs: 1 ask what they know/tried → 2 conceptual hint →",
    "  3 possible strategy → 4 partial solution → 5 full solution (only when appropriate).",
    "  Record each rung with mcp__learning__hint_step.",
    "- After an explanation or a finished task, ask one short understanding or transfer",
    "  question (\"Why the chain rule here?\", \"Apply it to …\") and let the answer decide",
    "  the next step.",
    "- Exposure is not understanding. Record levels with mcp__learning__record_evidence:",
    "  you may claim \"explained\"; recalled/applied/transferred/mastered only from the",
    "  student's own responses (source \"student\", with evidence).",
    "- If the open document has no goals yet and it contains tasks, identify them as",
    "  learning objectives with mcp__learning__set_goals before tutoring on them.",
  ];

  const concepts = Object.values(state.concepts || {});
  if (concepts.length) {
    const active = concepts
      .filter((c) => c.level !== "mastered")
      .sort((a, b) => String(b.updatedAt || "").localeCompare(String(a.updatedAt || "")))
      .slice(0, MAX_CONCEPTS_IN_PROMPT);
    const mastered = concepts.filter((c) => c.level === "mastered").length;
    lines.push("", `Learner model (global across chats; ${concepts.length} concepts, ${mastered} mastered):`);
    for (const c of active) lines.push(`- ${c.name}: ${c.level}`);
    if (concepts.length > active.length + mastered) {
      lines.push("- … more in mcp__learning__learner_state");
    }
  } else {
    lines.push("", "Learner model: nothing recorded yet.");
  }

  const doc = String(openFile || "").split(",")[0].trim();
  if (doc) {
    const g = state.goals?.[doc];
    if (g?.items?.length) {
      const open = g.items.filter((it) => it.status === "open");
      const rest = g.items.filter((it) => it.status !== "open");
      lines.push("", `Learning goals for ${doc} (${g.items.length}, ${open.length} open):`);
      // Open goals first; a long list is cut only in this prompt, never in the store.
      const shown = [...open, ...rest].slice(0, PROMPT_GOALS);
      for (const it of shown) {
        lines.push(
          `- ${it.id} [${it.policy}, hint ${it.hintLevel}/5, ${it.status}${it.awaitingStudent ? ", waiting for the student" : ""}]: ${it.task}`
        );
      }
      if (g.items.length > shown.length) {
        lines.push(`- … ${g.items.length - shown.length} more in mcp__learning__learner_state`);
      }
    } else if (/\.pdf$/i.test(doc)) {
      lines.push("", `No learning goals yet for ${doc}.`);
    }
  }
  const pending = (state.pendingDocs || []).filter((d) => d !== doc && !state.goals?.[d]).slice(-5);
  if (pending.length) {
    lines.push("", "New documents without learning goals (identify their tasks when they come up):");
    for (const d of pending) lines.push(`- ${d}`);
  }
  return lines.join("\n");
}

/** Per-run MCP server exposing the learner model to the agent. */
export function createLearningMcpServer(store, { chatId = "", openFile = "" } = {}) {
  const docDefault = String(openFile || "").split(",")[0].trim();
  const ok = (o) => ({ content: [{ type: "text", text: JSON.stringify(o, null, 2) }] });
  const fail = (o) => ({ content: [{ type: "text", text: JSON.stringify(o, null, 2) }], isError: true });
  return createSdkMcpServer({
    name: "learning",
    version: "1.0.0",
    tools: [
      tool(
        "learner_state",
        "The student's global learner model (all chats): concept levels with evidence, and the " +
          "learning goals of a document. Filter concepts with `query`.",
        {
          query: z.string().optional().describe("substring to filter concept names"),
          document: z.string().optional().describe("document whose goals to include (default: the open one)"),
        },
        async (args) => {
          const s = await store.snapshot();
          const q = conceptKey(args?.query || "");
          const concepts = Object.entries(s.concepts)
            .filter(([k]) => !q || k.includes(q))
            .slice(0, 80)
            .map(([, c]) => ({ name: c.name, level: c.level, evidence: c.evidence.slice(-3) }));
          const doc = args?.document || docDefault;
          return ok({ levels: LEVELS, concepts, document: doc || null, goals: doc ? s.goals[doc]?.items || [] : [] });
        }
      ),
      tool(
        "record_evidence",
        "Record where the student stands on a concept. You may claim 'explained' (source tutor). " +
          "'recalled', 'applied', 'transferred', 'mastered' need source 'student' and evidence: what the " +
          "student themselves said or did. 'mastered' only after 'transferred'. Lower levels are fine when " +
          "the student could not recall or apply it.",
        {
          concept: z.string().describe("concept name, e.g. 'chain rule'"),
          level: z.enum(LEVELS),
          source: z.enum(["student", "tutor"]),
          evidence: z.string().optional().describe("the student's own response, summarised"),
          goalId: z.string().optional(),
        },
        async (args) => {
          const r = await store.recordEvidence({ ...args, chatId });
          return r.accepted ? ok(r) : fail(r);
        }
      ),
      tool(
        "set_goals",
        "Define learning goals for a document (worksheet, exercise sheet, exam): one per task, stating what " +
          "the student should be able to do, the concepts involved, and the tutor policy — 'self-solve' " +
          "(exercises: hint ladder), 'guided' (worked examples: explain alongside), 'reference' (lecture " +
          "notes: explain freely). Goals, not prohibitions. There is no limit on how many goals a document has; " +
          "calling this again adds new goals and updates the ones you name, it never removes existing goals.",
        {
          document: z.string().optional().describe("workspace path (default: the open document)"),
          goals: z
            .array(
              z.object({
                task: z.string().describe("short task label, e.g. 'Exercise 2b'"),
                objective: z.string().optional(),
                concepts: z.array(z.string()).optional(),
                policy: z.enum(POLICIES).optional(),
              })
            )
            .min(1),
        },
        async (args) => {
          const r = await store.setGoals({ document: args?.document || docDefault, goals: args?.goals });
          return r.accepted ? ok(r) : fail(r);
        }
      ),
      tool(
        "hint_step",
        "Record the help you are about to give on a goal: 1 ask what they know, 2 conceptual hint, 3 strategy, " +
          "4 partial solution, 5 full solution. One rung at a time; skipping needs a reason. Between rungs " +
          "pass what the student contributed (studentContribution).",
        {
          goalId: z.string(),
          level: z.number().int().min(1).max(5),
          document: z.string().optional(),
          studentContribution: z.string().optional(),
          reason: z.string().optional(),
        },
        async (args) => {
          const r = await store.hintStep({ ...args, document: args?.document || docDefault, chatId });
          return r.accepted ? ok(r) : fail(r);
        }
      ),
      tool(
        "complete_goal",
        "Mark a goal done: by 'student' (with evidence of their own solution) or 'tutor' (solution shown).",
        {
          goalId: z.string(),
          by: z.enum(["student", "tutor"]),
          evidence: z.string().optional(),
          document: z.string().optional(),
        },
        async (args) => {
          const r = await store.completeGoal({ ...args, document: args?.document || docDefault });
          return r.accepted ? ok(r) : fail(r);
        }
      ),
    ],
  });
}
