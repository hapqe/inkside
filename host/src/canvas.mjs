/**
 * Agent → canvas command queue, and the canvas snapshot the agent reads.
 *
 * Agents must not place visual elements on the user's canvas (notes, images).
 * Remaining ops only open the editor / PDF document. Historical note.* /
 * image.add / view.focus files written into pending/ are rejected.
 *
 * Flow: agent writes pending/x.json → bridge validates → pushes over SSE →
 * tablet applies and acks → file moves to applied/ (or rejected/ on a bad command).
 */

import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import { createFileExclusive, realpathLoose, writeFileAtomic } from "./confine.mjs";

/** A command file is a few hundred bytes; anything big is not one. */
const MAX_COMMAND_BYTES = 64 * 1024;

/** Ops that used to place or edit canvas annotations — permanently disabled. */
const DISABLED_ELEMENT_OPS = new Set([
  "note.create",
  "note.update",
  "note.delete",
  "image.add",
  "view.focus",
]);

/**
 * Command schema. Each entry lists required and optional fields; anything else
 * is rejected so a malformed agent command fails loudly here rather than
 * half-applying on the tablet.
 */
const COMMANDS = {
  "file.open": { required: ["path"], optional: ["x", "y"] },
  // Legacy alias — scripts no longer live on the canvas; opens the editor panel.
  "script.add": { required: ["path"], optional: ["x", "y", "anchor"] },
  "pdf.open": { required: ["path"], optional: [] },
  "pdf.create": { required: [], optional: ["path"] },
  // Legacy alias — opens the PDF document (no longer places a card on a canvas).
  "pdf.add": { required: ["path"], optional: ["x", "y", "anchor"] },
};

export const COMMAND_NAMES = Object.keys(COMMANDS);

const ANCHOR_POSITIONS = new Set(["below", "above", "left", "right"]);

/** @returns {string|null} an error message, or null when the command is valid */
export function validateCommand(cmd) {
  if (!cmd || typeof cmd !== "object") return "command must be an object";
  const op = cmd.op;
  if (typeof op !== "string") return "command.op must be a string";
  if (DISABLED_ELEMENT_OPS.has(op)) {
    return `op "${op}" is disabled — agents cannot add or edit canvas elements`;
  }
  const spec = COMMANDS[op];
  if (!spec) return `unknown op "${op}" (known: ${COMMAND_NAMES.join(", ")})`;

  for (const key of spec.required) {
    if (cmd[key] === undefined || cmd[key] === null || cmd[key] === "") {
      return `op "${op}" requires "${key}"`;
    }
  }
  const allowed = new Set([...spec.required, ...spec.optional, "op", "note"]);
  for (const key of Object.keys(cmd)) {
    if (!allowed.has(key)) return `op "${op}" does not accept "${key}"`;
  }
  for (const key of ["x", "y", "width", "height", "textSize", "scale"]) {
    if (cmd[key] !== undefined && !Number.isFinite(Number(cmd[key]))) {
      return `"${key}" must be a number`;
    }
  }
  if (cmd.anchor !== undefined) {
    const a = cmd.anchor;
    if (!a || typeof a !== "object") return "anchor must be an object";
    if (!a.relativeTo) return "anchor.relativeTo is required";
    if (a.position !== undefined && !ANCHOR_POSITIONS.has(a.position)) {
      return `anchor.position must be one of ${[...ANCHOR_POSITIONS].join(", ")}`;
    }
  }
  return null;
}

export class CanvasQueue {
  /**
   * @param {string} workspace absolute workspace root
   * @param {(event: string, data: any) => number} broadcast
   */
  constructor(workspace, broadcast) {
    this.realWorkspace = realpathLoose(workspace);
    this.root = path.join(workspace, ".canvas");
    this.pendingDir = path.join(this.root, "pending");
    this.appliedDir = path.join(this.root, "applied");
    this.rejectedDir = path.join(this.root, "rejected");
    this.statePath = path.join(this.root, "state.json");
    this.broadcast = broadcast;
    /** Commands pushed but not yet acked, by id. */
    this.inFlight = new Map();
    this.seq = 0;
    this.watcher = null;
    this.rescanTimer = null;
    this.draining = false;
  }

  async init() {
    for (const dir of [this.pendingDir, this.appliedDir, this.rejectedDir]) {
      await fsp.mkdir(dir, { recursive: true });
    }
    await this.drain();

    try {
      // fs.watch misses events on some filesystems and network mounts, so a slow
      // rescan backs it up rather than being the primary mechanism.
      this.watcher = fs.watch(this.pendingDir, () => this.scheduleDrain());
    } catch {
      /* fall back to the rescan timer alone */
    }
    this.rescanTimer = setInterval(() => this.drain(), 5000);
    if (typeof this.rescanTimer.unref === "function") this.rescanTimer.unref();
  }

  scheduleDrain() {
    // Coalesce the burst of events a single file write produces.
    clearTimeout(this._debounce);
    this._debounce = setTimeout(() => this.drain(), 80);
  }

  /**
   * The queue folders are inside the workspace, where the agent can write: one swapped
   * for a symlink would have the host read and move files from wherever it points.
   */
  foldersAreReal() {
    const real = (p) => {
      try {
        return realpathLoose(p);
      } catch {
        return null;
      }
    };
    const want = path.join(this.realWorkspace, ".canvas");
    return [this.pendingDir, this.appliedDir, this.rejectedDir].every(
      (d) => real(d) === path.join(want, path.basename(d))
    );
  }

  async drain() {
    if (this.draining) return;
    this.draining = true;
    try {
      if (!this.foldersAreReal()) {
        console.warn("[canvas] queue folders are not plain folders inside the workspace; skipping");
        return;
      }
      let names;
      try {
        names = (await fsp.readdir(this.pendingDir)).filter((n) => n.endsWith(".json"));
      } catch {
        return;
      }
      names.sort();
      for (const name of names) {
        await this.ingestFile(path.join(this.pendingDir, name), name);
      }
    } finally {
      this.draining = false;
    }
  }

  async ingestFile(abs, name) {
    let raw;
    try {
      const st = await fsp.lstat(abs);
      if (!st.isFile()) {
        // A symlink or anything else: never read through it, just drop it.
        await fsp.unlink(abs).catch(() => {});
        return;
      }
      if (st.size > MAX_COMMAND_BYTES) {
        await this.reject(abs, name, "too large for a command file", "");
        return;
      }
      raw = await fsp.readFile(abs, "utf8");
    } catch {
      return;
    }
    if (!raw.trim()) return; // still being written

    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (e) {
      await this.reject(abs, name, `invalid JSON: ${e.message}`, raw);
      return;
    }

    // Accept either a single command or a batch.
    const list = Array.isArray(parsed) ? parsed : [parsed];
    const errors = [];
    list.forEach((cmd, i) => {
      const err = validateCommand(cmd);
      if (err) errors.push(`[${i}] ${err}`);
    });
    if (errors.length) {
      await this.reject(abs, name, errors.join("; "), raw);
      return;
    }

    const batchId = `cc${Date.now().toString(36)}-${++this.seq}`;
    const commands = list.map((cmd, i) => ({ ...cmd, id: cmd.id || `${batchId}.${i}` }));

    this.inFlight.set(batchId, { name, commands, at: Date.now() });
    const delivered = this.broadcast("canvas", { batchId, commands });

    // Move out of pending either way: an undelivered batch stays in inFlight and
    // is re-pushed when a client connects, so it must not be re-ingested.
    await this.moveTo(this.appliedDir, abs, name, {
      batchId,
      delivered,
      commands,
      at: new Date().toISOString(),
    });
  }

  /** Re-push anything still unacked, for a client that just connected. */
  replayUnacked() {
    let n = 0;
    for (const [batchId, entry] of this.inFlight) {
      this.broadcast("canvas", { batchId, commands: entry.commands, replay: true });
      n++;
    }
    return n;
  }

  ack(batchId, ok, message) {
    const entry = this.inFlight.get(batchId);
    if (!entry) return false;
    this.inFlight.delete(batchId);
    if (!ok) {
      console.warn(`[canvas] batch ${batchId} failed on device: ${message || "unknown"}`);
    }
    return true;
  }

  pendingCount() {
    return this.inFlight.size;
  }

  async reject(abs, name, reason, raw) {
    console.warn(`[canvas] rejected ${name}: ${reason}`);
    await this.moveTo(this.rejectedDir, abs, name, { reason, raw, at: new Date().toISOString() });
    this.broadcast("canvas_rejected", { name, reason });
  }

  async moveTo(dir, abs, name, meta) {
    const stamped = `${Date.now().toString(36)}-${name}`;
    try {
      await fsp.rename(abs, path.join(dir, stamped));
    } catch {
      try {
        await fsp.unlink(abs);
      } catch {
        /* ignore */
      }
    }
    if (meta) {
      try {
        await createFileExclusive(
          path.join(dir, `${stamped}.meta.json`),
          JSON.stringify(meta, null, 2)
        );
      } catch {
        /* ignore */
      }
    }
  }

  // ---- canvas snapshot (what the agent can see) ----

  /**
   * Stores the tablet's view of the canvas so the agent can address real objects
   * by id instead of guessing. Kept as a plain file so the agent can also read it
   * directly with its own tools.
   */
  async putState(state) {
    const payload = {
      at: new Date().toISOString(),
      ...(state && typeof state === "object" ? state : {}),
    };
    // exportCanvasState embeds a base64 PNG per image and full sample arrays per
    // stroke. Neither is useful to the agent and both dwarf everything else, so
    // drop them here rather than trusting every caller to pre-strip.
    if (Array.isArray(payload.images)) {
      payload.images = payload.images.map(({ png, ...rest }) => rest);
    }
    if (Array.isArray(payload.strokes)) {
      payload.strokeCount = payload.strokes.length;
      payload.strokes = payload.strokes.map(({ samples, packed, ...rest }) => rest);
    }
    await fsp.mkdir(this.root, { recursive: true });
    await writeFileAtomic(this.statePath, JSON.stringify(payload, null, 2), this.realWorkspace);
    return payload;
  }

  async getState() {
    try {
      return JSON.parse(await fsp.readFile(this.statePath, "utf8"));
    } catch {
      return null;
    }
  }
}

/**
 * Compact, token-cheap rendering of the canvas for the prompt. Geometry is
 * rounded and long text is clipped — the agent needs enough to identify and
 * place things, not a faithful dump.
 */
export function formatCanvasState(state, limit = 60) {
  if (!state || typeof state !== "object") return "";
  const lines = [];
  const push = (s) => lines.push(s);

  const notes = Array.isArray(state.textFields) ? state.textFields : [];
  const images = Array.isArray(state.images) ? state.images : [];
  const documentPath = typeof state.documentPath === "string" ? state.documentPath : "";
  const pageCount = Number.isFinite(Number(state.pageCount)) ? Number(state.pageCount) : 0;
  // Legacy sessions may still carry sheets/scripts; ignore for the prompt — code
  // now lives in the editor panel, not on the canvas.
  const editorPath = typeof state.editorPath === "string" ? state.editorPath : "";
  // Placed artifacts are images carrying a livePath.
  const webBlocks = (Array.isArray(state.images) ? state.images : []).filter(
    (i) => i && typeof i.livePath === "string" && i.livePath
  );

  if (!notes.length && !images.length && !editorPath && !documentPath && !webBlocks.length) {
    return "";
  }

  const n = (v) => (Number.isFinite(Number(v)) ? Math.round(Number(v)) : "?");
  const clip = (s, max = 80) => {
    const t = String(s ?? "").replace(/\s+/g, " ").trim();
    return t.length > max ? `${t.slice(0, max - 1)}…` : t;
  };

  push("");
  push("[Document]");
  push("The tablet edits one PDF document at a time (page stack).");
  push("Annotations (ink, images, notes) are the user's — do not place or edit them.");
  push("Allowed ops (via .canvas/pending/): pdf.open, pdf.create, file.open.");
  if (webBlocks.length) {
    push("");
    push("[Legacy interactive overlays still on the document]");
    push(
      "Older sessions may still have artifact HTML live on the page. Prefer" +
        " visualizations/*.viz in the explorer for new visualizations; edit the" +
        " matching .artifacts HTML to update one — the tablet reloads it."
    );
    for (const b of webBlocks.slice(0, limit)) {
      const p = clip(b && b.livePath, 90);
      if (!p) continue;
      push(`- .artifacts/${p} (viewport ${n(b.width)}x${n(b.height)})`);
    }
  }

  if (documentPath) {
    push(`- open PDF: ${documentPath} (${pageCount || "?"} pages)`);
  }
  if (editorPath) {
    push(`- editor open: ${editorPath}`);
  }
  for (const t of notes.slice(0, limit)) {
    push(`- note id=${t.id} at (${n(t.cx)},${n(t.cy)}): "${clip(t.text)}"`);
  }
  for (const img of images.slice(0, limit)) {
    const label = img.vizPath || img.sourcePath;
    if (label) push(`- image ${label} at (${n(img.cx)},${n(img.cy)})`);
  }
  const unlabelled = images.filter((i) => !i.vizPath && !i.sourcePath).length;
  if (unlabelled) push(`- ${unlabelled} unlabelled image(s)`);

  if (state.viewCenter) {
    push(`Viewport centre: (${n(state.viewCenter[0])},${n(state.viewCenter[1])})`);
  }
  return lines.join("\n");
}

/** Prompt text: agents must not author canvas annotations. */
export function canvasInstructions(workspaceRelDir = ".canvas/pending") {
  return [
    "",
    "Canvas (read-only for annotations):",
    "- Do NOT write notes, images, or other elements onto the user's canvas.",
    `- Do NOT write note.create / note.update / note.delete / image.add / view.focus into ${workspaceRelDir}/ — those ops are rejected.`,
    `- Allowed only: open the editor or a PDF, e.g. {"op":"file.open","path":"rel/path.py"} or {"op":"pdf.open","path":"…"}.`,
    "- Answer in chat; keep ink, images, and notes for the user to place themselves.",
  ].join("\n");
}
