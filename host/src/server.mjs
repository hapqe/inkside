import express from "express";
import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { execFile, execFileSync, spawn } from "node:child_process";
import { promisify } from "node:util";
import crypto from "node:crypto";
import zlib from "node:zlib";
import { query, AbortError, deleteSession } from "@anthropic-ai/claude-agent-sdk";
import { attachEventStream, broadcast, clientCount } from "./events.mjs";
import { CanvasQueue, formatCanvasState, validateCommand } from "./canvas.mjs";
import { StudyStore, formatStudyStats } from "./study.mjs";
import { LearningStore, formatLearningContext, createLearningMcpServer, learningProgress, scopeKey } from "./learning.mjs";
import { planNext, formatPlanForPrompt, pickNext } from "./planner.mjs";
import { UsageLog } from "./usage.mjs";
import { createAuth } from "./auth.mjs";
import { HostIdentity } from "./identity.mjs";
import { reachableAddresses } from "./network.mjs";
import { CaptureBroker, createCanvasMcpServer, parsePageSpec } from "./canvasTool.mjs";
import { flattenPdf, reorderPdf } from "./pdfTools.mjs";
import { PdfIndex } from "./pdfIndex.mjs";
import {
  SHARED_AGENT_TOOLS,
  agentSandbox,
  createFileExclusive,
  createToolGuard,
  createWorkspaceResolver,
  isInside,
  realpathLoose,
  sandboxDenyRead,
  scriptEnv,
  scriptSandboxSettings,
  secretEnvNames,
  writeFileAtomic,
  writeFileNoFollow,
} from "./confine.mjs";

const execFileAsync = promisify(execFile);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, "..");
const EXTRACT_PDF = path.join(__dirname, "..", "scripts", "extract_pdf.py");

function loadEnvFile() {
  const envPath = path.join(ROOT, ".env");
  if (!fs.existsSync(envPath)) return;
  const raw = fs.readFileSync(envPath, "utf8");
  for (const line of raw.split("\n")) {
    const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (!m) continue;
    const key = m[1];
    let val = m[2].trim();
    if (
      (val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))
    ) {
      val = val.slice(1, -1);
    }
    if (process.env[key] === undefined) process.env[key] = val;
  }
}

loadEnvFile();

const HOST = process.env.HOST || "0.0.0.0";
const PORT = Number(process.env.PORT || 8787);
/** Where documents and projects live; the agent works in here. */
const WORKSPACE = path.resolve(
  process.env.WORKSPACE_ROOT || path.join(os.homedir(), "Inkside")
);
fs.mkdirSync(WORKSPACE, { recursive: true });
/** Host identity (its id and name), kept outside the workspace. */
const STATE_DIR = path.resolve(
  process.env.INKSIDE_STATE_DIR ||
    path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"), "inkside-host")
);
/** Inkside repo root — used by the Improve agent (development). */
const APP_ROOT = path.resolve(
  process.env.APP_ROOT || path.join(ROOT, "..")
);
const IMPROVE_CHAT_ID = "app-improve";
/** Developer setup only: the tablet the Improve chat installs rebuilt APKs on. */
const ADB_SERIAL = process.env.ADB_SERIAL || "";
const WORKSPACE_REAL = realpathLoose(WORKSPACE);
/**
 * A shared host: other people reach it with the access token (BRIDGE_TOKEN), so they get
 * the workspace and nothing else. The agent and scripts run in the OS sandbox, developer
 * endpoints are gone, the model is fixed, and nothing about the computer is reported.
 * On whenever a token is set; INKSIDE_SHARED=0 opts out (only if you are the sole holder
 * of the token), INKSIDE_SHARED=1 turns it on without a token.
 */
const SHARED =
  process.env.INKSIDE_SHARED === "1" ||
  (process.env.INKSIDE_SHARED !== "0" && !!String(process.env.BRIDGE_TOKEN || "").trim());
/** The Improve endpoints let an agent edit this repository: a developer setup, opt-in. */
const IMPROVE_ENABLED = process.env.INKSIDE_IMPROVE === "1" && !SHARED;
/** Hosts sandboxed commands may reach (package indexes by default). */
const SANDBOX_DOMAINS = (process.env.INKSIDE_SANDBOX_DOMAINS ?? "pypi.org,files.pythonhosted.org")
  .split(",")
  .map((d) => d.trim())
  .filter(Boolean);
/** Agent runs at once on a shared host; more answer 429. */
const MAX_SHARED_RUNS = Math.max(1, Number(process.env.INKSIDE_MAX_RUNS) || 3);
/**
 * Claude auth. The SDK spawns the bundled `claude` CLI and, unless told
 * otherwise, lets it load the user's own settings — so credentials can come
 * from a router in host/.env (ANTHROPIC_AUTH_TOKEN + ANTHROPIC_BASE_URL), a
 * plain ANTHROPIC_API_KEY, the CLI's apiKeyHelper, or a `claude login`. The
 * bridge only has to know that *some* of those exist before it starts a turn.
 */
const ANTHROPIC_AUTH_TOKEN = process.env.ANTHROPIC_AUTH_TOKEN || "";
/**
 * A stray ANTHROPIC_API_KEY (shell, .env, a parent process) silently wins over
 * the `claude login` subscription and bills API credit instead. Runs use the
 * subscription unless BRIDGE_USE_API_KEY=1 explicitly asks for the key.
 */
const USE_API_KEY = process.env.BRIDGE_USE_API_KEY === "1";
const ANTHROPIC_API_KEY = USE_API_KEY ? process.env.ANTHROPIC_API_KEY || "" : "";
const CLAUDE_CONFIG_DIR =
  process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), ".claude");

/** Environment for each spawned CLI — the bridge's own, minus an unwanted key. */
function agentEnv() {
  const env = { ...process.env };
  if (!USE_API_KEY) delete env.ANTHROPIC_API_KEY;
  // The CLI has no use for the host's own access token.
  delete env.BRIDGE_TOKEN;
  // Set when the bridge was started from inside a Claude Code session; they
  // would make every run look like a nested child of that session.
  delete env.CLAUDECODE;
  delete env.CLAUDE_CODE_ENTRYPOINT;
  return env;
}

/** macOS keeps the `claude login` OAuth token in the Keychain, not on disk. */
function keychainLoginPresent() {
  if (process.platform !== "darwin") return false;
  try {
    execFileSync(
      "/usr/bin/security",
      ["find-generic-password", "-s", "Claude Code-credentials"],
      { stdio: "ignore", timeout: 3000 }
    );
    return true;
  } catch {
    return false;
  }
}

/** True when the spawned CLI can find credentials without help from us. */
function claudeAuthAvailable() {
  if (ANTHROPIC_AUTH_TOKEN || ANTHROPIC_API_KEY) return true;
  try {
    const settingsPath = path.join(CLAUDE_CONFIG_DIR, "settings.json");
    if (fs.existsSync(settingsPath)) {
      const settings = JSON.parse(fs.readFileSync(settingsPath, "utf8"));
      if (settings?.apiKeyHelper) return true;
      const env = settings?.env || {};
      if (
        env.ANTHROPIC_AUTH_TOKEN ||
        env.ANTHROPIC_API_KEY ||
        env.ANTHROPIC_BASE_URL
      ) {
        return true;
      }
    }
  } catch {
    /* unreadable settings — fall through to the credentials check */
  }
  return (
    fs.existsSync(path.join(CLAUDE_CONFIG_DIR, ".credentials.json")) ||
    keychainLoginPresent()
  );
}

/**
 * Which provider the next `claude` spawn will talk to.
 *
 * The Mac switches Claude Code between Anthropic and DeepSeek with the
 * `claude-provider` script (~/.local/bin), which rewrites ~/.claude/settings.json
 * and drops a state file. Every bridge query spawns a fresh CLI that reads that
 * file, so a switch applies to the next run without restarting anything — we only
 * report and drive the script, never hold a provider of our own.
 *
 * Key material is never read here: `keyStatus` is file existence only, because it
 * travels to the tablet.
 */
const PROVIDER_STATE_FILE = path.join(
  process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"),
  "claude-code",
  "active-provider",
);
const DEEPSEEK_KEY_FILE = path.join(
  process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"),
  "claude-code",
  "deepseek.key",
);

/** Absolute path first: launchd's PATH for the bridge has no ~/.local/bin. */
function resolveProviderScript() {
  const abs = path.join(os.homedir(), ".local", "bin", "claude-provider");
  return fs.existsSync(abs) ? abs : "claude-provider";
}

function readProviderState() {
  let provider = "";
  let model = null;
  let baseUrl = "(default anthropic)";
  try {
    provider = fs.readFileSync(PROVIDER_STATE_FILE, "utf8").trim();
  } catch {
    /* no state file — fall back to sniffing the settings below */
  }
  try {
    const settings = JSON.parse(
      fs.readFileSync(path.join(CLAUDE_CONFIG_DIR, "settings.json"), "utf8"),
    );
    const env = settings?.env || {};
    if (typeof env.ANTHROPIC_BASE_URL === "string" && env.ANTHROPIC_BASE_URL) {
      baseUrl = env.ANTHROPIC_BASE_URL;
    }
    // ANTHROPIC_MODEL is what a run actually gets; the top-level `model` is often
    // just the alias ("haiku") that the provider script maps onto a real model id.
    if (typeof env.ANTHROPIC_MODEL === "string" && env.ANTHROPIC_MODEL) {
      model = env.ANTHROPIC_MODEL;
    } else if (typeof settings?.model === "string" && settings.model) {
      model = settings.model;
    }
    if (!provider) {
      // Same detection the script's own `status` uses when its state file is gone.
      provider = baseUrl.includes("api.deepseek.com") ? "deepseek" : "claude";
    }
  } catch {
    /* unreadable settings — report what we have */
  }
  if (provider !== "claude" && provider !== "deepseek") provider = "claude";
  let keyStatus = "missing";
  if (process.env.DEEPSEEK_API_KEY) keyStatus = "set";
  else if (fs.existsSync(DEEPSEEK_KEY_FILE)) keyStatus = "set";
  return { provider, model, baseUrl, keyStatus };
}
const MODEL = process.env.CLAUDE_MODEL || ""; // unset → the CLI's own default
/**
 * Model the tablet picked for bridge runs. Aliases, not ids: the CLI resolves
 * them per provider, so on DeepSeek "opus"/"sonnet" become Pro and "haiku"
 * becomes Flash via the ANTHROPIC_DEFAULT_*_MODEL that `claude-provider` sets.
 * Unlike the provider, this is the bridge's own and never touches the Mac's CLI.
 */
const CHAT_MODELS = ["haiku", "sonnet", "opus"];
const AGENT_SETTINGS_PATH = path.join(WORKSPACE, ".canvas", "agent-settings.json");

/**
 * The speech-to-text model: Whisper v3 Turbo, for everyone (not a setting). `pkg` is what uv
 * installs for the worker; `repo` is the MLX weights on Hugging Face (downloaded on first use).
 */
const VOICE_MODELS = {
  "whisper-large-v3-turbo": {
    label: "Whisper v3 Turbo",
    engine: "whisper",
    pkg: "mlx-whisper",
    repo: "mlx-community/whisper-large-v3-turbo",
  },
};
const DEFAULT_VOICE_MODEL = "whisper-large-v3-turbo";

/**
 * INKSIDE_FIXED_MODEL=1: whoever runs this computer sets the model and provider; devices
 * cannot. On by default on a shared host (INKSIDE_FIXED_MODEL=0 lets devices pick).
 */
const FIXED_MODEL =
  process.env.INKSIDE_FIXED_MODEL === "1" || (SHARED && process.env.INKSIDE_FIXED_MODEL !== "0");


function loadAgentSettings() {
  try {
    return JSON.parse(fs.readFileSync(AGENT_SETTINGS_PATH, "utf8")) || {};
  } catch {
    return {}; // nothing saved yet
  }
}

const savedAgentSettings = loadAgentSettings();
// A fixed model (a shared host) is the one this computer was started with, whatever an
// earlier, unlocked run may have saved.
let chatModel = FIXED_MODEL && MODEL
  ? MODEL
  : CHAT_MODELS.includes(savedAgentSettings.model)
    ? savedAgentSettings.model
    : MODEL || null;
const voiceModel = DEFAULT_VOICE_MODEL;

async function saveAgentSettings() {
  await fsp.mkdir(path.dirname(AGENT_SETTINGS_PATH), { recursive: true });
  await writeFileAtomic(
    AGENT_SETTINGS_PATH,
    JSON.stringify({ model: chatModel }, null, 2) + "\n",
    WORKSPACE_REAL
  );
}

/** What a device may know about the provider: on a shared host, not where it points. */
function providerStateForDevice() {
  const state = readProviderState();
  return SHARED ? { ...state, baseUrl: null } : state;
}

async function saveChatModel(model) {
  chatModel = model;
  await saveAgentSettings();
}

/** Everything the tablet's settings show about who answers. */
function agentSettingsPayload() {
  const state = providerStateForDevice();
  return {
    ...state,
    // Unset means the Mac's own default applies — report that alias if it is one.
    chatModel: chatModel || (CHAT_MODELS.includes(state.model) ? state.model : null),
    chatModels: CHAT_MODELS,
    fixedModel: FIXED_MODEL,
    hasApiKey: claudeAuthAvailable(),
  };
}
const MAX_INLINE_CHARS = 120_000;
const IMPROVE_MODEL = "opus";
const IMPROVE_TIMEOUT_MS = 0; // no idle kill — long Improve turns are fine
/**
 * Durable Inkside chatId → Claude sessionId. Conversation memory lives in
 * the session transcript (~/.claude/projects); we only remember which session
 * belongs to which tablet chat so we can `resume` after a bridge restart.
 */
const AGENT_MAP_PATH = path.join(WORKSPACE, ".canvas", "agent-map.json");

/**
 * Detachable Improve session — the agent keeps running after the tablet
 * disconnects; clients reattach via /improve/follow.
 *
 * @type {{
 *   status: "idle"|"running"|"finished"|"error"|"cancelled",
 *   request: string,
 *   text: string,
 *   statusMessage: string,
 *   startedAt: number,
 *   finishedAt: number,
 *   autoDeploy: boolean,
 *   deploy: null|{ status: string, log: string, exitCode?: number },
 *   agentId: string|null,
 *   runId: string|null,
 *   error: string|null,
 *   listeners: Set<{ write: (obj: object) => void, end: () => void, closed: boolean }>,
 * }}
 */
const improveSession = {
  status: "idle",
  // What was asked for. Kept because the tablet has to be able to show an
  // overnight run it never sent: without it the morning shows a reply to
  // nothing.
  request: "",
  text: "",
  statusMessage: "",
  startedAt: 0,
  finishedAt: 0,
  autoDeploy: false,
  deploy: null,
  agentId: null,
  runId: null,
  error: null,
  listeners: new Set(),
};

const BRIDGE_VERSION = (() => {
  try {
    return JSON.parse(fs.readFileSync(path.join(ROOT, "package.json"), "utf8")).version || "0";
  } catch {
    return "0";
  }
})();
const STARTED_AT = Date.now();

const identity = new HostIdentity({
  stateDir: STATE_DIR,
  name: process.env.INKSIDE_HOST_NAME || "",
}).init();

// Only devices on this computer's own network may connect (see auth.mjs).
const auth = createAuth({
  token: process.env.BRIDGE_TOKEN || "",
  // Testers' own tokens (scripts/testers.mjs), so each one's use can be told apart.
  tokensFile: process.env.INKSIDE_TOKENS_FILE || path.join(STATE_DIR, "tokens.json"),
  open: process.env.INKSIDE_OPEN === "1",
  // With a token, this computer's own requests need it too unless this is "1".
  trustLoopback: process.env.BRIDGE_TRUST_LOOPBACK === "1",
  // Maths assets for artifact pages carry nothing private.
  publicPaths: ["/katex"],
  allowedHosts: String(process.env.INKSIDE_ALLOWED_HOSTS || "").split(","),
});

const app = express();
app.disable("x-powered-by");
// No CORS: the app is not a web page, and artifact pages are served from here (same
// origin). Cross-origin browser requests are refused in auth.mjs.
// Every response carries an id so a failure in the app can be matched to the log.
app.use((req, res, next) => {
  const id = crypto.randomBytes(6).toString("hex");
  req.id = id;
  res.setHeader("X-Request-Id", id);
  const t0 = Date.now();
  res.on("finish", () => {
    if (res.statusCode >= 500) {
      console.warn(`[http] ${req.method} ${req.path} → ${res.statusCode} (${Date.now() - t0}ms) id=${id}`);
    }
  });
  next();
});
app.use(auth.middleware);
// Chat messages carry attachments as base64: several large files at once.
app.use(express.json({ limit: "128mb" }));
// Improve edits this repository and rebuilds the app: developer setup only. The provider
// routes under /improve stay, for the app's settings.
app.use("/improve", (req, res, next) => {
  if (IMPROVE_ENABLED || req.path === "/provider") return next();
  res.status(404).json({ error: "Improve is not enabled on this computer (INKSIDE_IMPROVE=1)" });
});

const canvasQueue = new CanvasQueue(WORKSPACE, broadcast);
const captureBroker = new CaptureBroker({
  broadcast,
  clientCount,
  timeoutMs: Number(process.env.CANVAS_CAPTURE_TIMEOUT_MS || 30_000),
});
const studyStore = new StudyStore(WORKSPACE);
/** Learning Mode: one learner model per project (see learning.mjs). */
const learningStore = new LearningStore(WORKSPACE, {
  projectOf: async (rel) => {
    const dir = await projectDirOf(assertInsideWorkspace(rel));
    return dir ? relWorkspace(dir) : "";
  },
});
const pdfIndex = new PdfIndex(WORKSPACE);

/** Usage per tester, on this computer only (see usage.mjs; `npm run usage`). */
const usageLog = new UsageLog(process.env.BRIDGE_LOG_DIR || path.join(ROOT, "logs"));
/** Who the chat's latest turn came from: the name of the access token it carried. */
const chatTester = new Map();

/**
 * Study time is logged on the tablet; it reports this week's minutes per project when
 * it asks for a plan or sends a chat turn. Kept for the week it was reported in, so
 * the tutor's plan can use it between reports.
 */
const studyTimeReports = new Map();
const WEEK_REPORT_MS = 7 * 24 * 3600 * 1000;

function noteStudyTime(project, week) {
  if (!week || typeof week !== "object") return;
  const studiedMinutes = Number(week.studiedMinutes);
  const daysLeft = Number(week.daysLeft);
  if (!Number.isFinite(studiedMinutes) || !Number.isFinite(daysLeft)) return;
  studyTimeReports.set(scopeKey(project), {
    studiedMinutes: Math.max(0, studiedMinutes),
    daysLeft: Math.max(1, Math.min(7, daysLeft)),
    at: Date.now(),
  });
}

/** The spaced study plan for one project, with the latest study time the tablet reported. */
async function studyPlanFor(project, { sessionMinutes = null } = {}) {
  const r = studyTimeReports.get(scopeKey(project));
  const fresh = r && Date.now() - r.at < WEEK_REPORT_MS;
  // A report from earlier in the week: the days left have gone down since.
  const daysGone = fresh ? Math.floor((Date.now() - r.at) / (24 * 3600 * 1000)) : 0;
  return planNext(await learningStore.snapshot(project), {
    studiedMinutes: fresh ? r.studiedMinutes : 0,
    daysLeft: fresh ? Math.max(1, r.daysLeft - daysGone) : 7,
    sessionMinutes,
  });
}

/** @type {Map<string, { sessionId: string, cwd: string }>} */
const sessionMap = new Map();
/**
 * Runs are tracked per chat, not globally. A single shared `activeRun` meant every
 * new turn cancelled whatever else was in flight, so the agent could never do
 * background work (generating study material, say) while the user was chatting.
 *
 * One query = one `claude` CLI process; cancelling aborts it.
 *
 * @type {Map<string, { abortController: AbortController, sessionId: string }>}
 */
const runsByChat = new Map();
/** Monotonic per chat; a run whose generation has moved on is stale and stops. */
const genByChat = new Map();

function normalizeChatId(id) {
  const s = String(id || "").trim();
  return s || "default";
}

/**
 * Rows written by the Cursor era carry `agentId` and no `sessionId` — those
 * conversations cannot be migrated, so they are dropped (and the file rewritten).
 */
async function loadSessionMap() {
  let pruned = false;
  try {
    const raw = await fsp.readFile(AGENT_MAP_PATH, "utf8");
    const obj = JSON.parse(raw);
    if (!obj || typeof obj !== "object") return;
    for (const [chatId, row] of Object.entries(obj)) {
      if (!row || typeof row.sessionId !== "string" || !row.sessionId) {
        pruned = true;
        continue;
      }
      sessionMap.set(normalizeChatId(chatId), {
        sessionId: row.sessionId,
        cwd: typeof row.cwd === "string" ? row.cwd : WORKSPACE,
      });
    }
  } catch {
    /* missing / corrupt — start empty */
  }
  if (pruned) await saveSessionMap();
}

async function saveSessionMap() {
  try {
    await fsp.mkdir(path.dirname(AGENT_MAP_PATH), { recursive: true });
    const obj = {};
    for (const [chatId, row] of sessionMap) {
      obj[chatId] = { sessionId: row.sessionId, cwd: row.cwd };
    }
    await writeFileAtomic(AGENT_MAP_PATH, JSON.stringify(obj, null, 2), WORKSPACE_REAL);
  } catch (e) {
    console.warn("session map persist failed:", e?.message || e);
  }
}

function rememberSession(chatId, sessionId, cwd) {
  const id = normalizeChatId(chatId);
  if (!sessionId) return;
  sessionMap.set(id, { sessionId, cwd });
  void saveSessionMap();
}

function forgetSessionMapping(chatId) {
  const id = normalizeChatId(chatId);
  if (!sessionMap.delete(id)) return;
  void saveSessionMap();
}

function currentGen(chatId) {
  return genByChat.get(normalizeChatId(chatId)) || 0;
}

/** Invalidates any in-flight run for this chat and returns the new generation. */
function bumpGen(chatId) {
  const id = normalizeChatId(chatId);
  const next = (genByChat.get(id) || 0) + 1;
  genByChat.set(id, next);
  return next;
}

function isStale(chatId, gen) {
  return gen !== currentGen(chatId);
}

/** Abort the chat's in-flight query. The mapping survives, so the next turn resumes. */
async function cancelRunForChat(chatId) {
  const id = normalizeChatId(chatId);
  const handle = runsByChat.get(id);
  runsByChat.delete(id);
  if (!handle) return;
  try {
    handle.abortController.abort();
  } catch {
    /* already aborted */
  }
}

async function cancelAllRuns() {
  const ids = [...runsByChat.keys()];
  for (const id of ids) {
    bumpGen(id);
    await cancelRunForChat(id);
  }
}

/** Stop the current turn; keep chatId→sessionId so the conversation can resume. */
async function closeSessionForChat(chatId) {
  const id = normalizeChatId(chatId);
  bumpGen(id);
  await cancelRunForChat(id);
}

/** Stop the turn, drop the mapping, and delete the session transcript. */
async function forgetSessionForChat(chatId) {
  await closeSessionForChat(chatId);
  const id = normalizeChatId(chatId);
  const mapped = sessionMap.get(id);
  if (mapped?.sessionId) {
    try {
      await deleteSession(mapped.sessionId);
    } catch (e) {
      // Already gone (or never created) is the common case, not a problem.
      console.warn(
        `[session] deleteSession failed ${mapped.sessionId}:`,
        e?.message || e
      );
    }
  }
  forgetSessionMapping(id);
}

async function forgetAllSessions() {
  await cancelAllRuns();
  const ids = [...sessionMap.keys()];
  for (const id of ids) {
    await forgetSessionForChat(id);
  }
  sessionMap.clear();
  await saveSessionMap();
}

function assertAuth() {
  if (claudeAuthAvailable()) return;
  const err = new Error(
    "Claude auth missing. Run `claude login` on the Mac (uses your Claude " +
      "subscription), or set ANTHROPIC_AUTH_TOKEN + ANTHROPIC_BASE_URL in host/.env."
  );
  err.status = 503;
  throw err;
}

/**
 * Map a chat to a Claude session. First use mints a pinned session id; later
 * turns resume it. `fresh` means there are no prior turns for this chat, so the
 * system preamble must be sent (and snapshotted) with the first query.
 *
 * @returns {{ sessionId: string, fresh: boolean }}
 */
function ensureSession(cwd, chatId) {
  assertAuth();
  const id = normalizeChatId(chatId);
  const mapped = sessionMap.get(id);
  if (mapped && mapped.cwd === cwd && mapped.sessionId) {
    return { sessionId: mapped.sessionId, fresh: false };
  }
  if (mapped) forgetSessionMapping(id); // cwd changed — that transcript is not ours
  const sessionId = crypto.randomUUID();
  rememberSession(id, sessionId, cwd);
  return { sessionId, fresh: true };
}

function formatFailureText(result, text, cancelled) {
  if (text && text.trim()) return text.trim();
  if (cancelled) return "(stopped)";
  const errMsg = result?.error?.message?.trim();
  if (errMsg) return `Agent error: ${errMsg}`;
  return `(no text returned — status ${result?.status || "unknown"})`;
}

/**
 * The SDK raises aborts as its own AbortError, as DOMExceptions named
 * "AbortError" (user-cancel, turn-abort, …), and decorates some throws with a
 * runtime-only `errorClass`. All three are duck-typed here.
 */
function isAbortError(e) {
  return (
    e instanceof AbortError || e?.name === "AbortError" || e?.errorClass === "aborted"
  );
}

function isErrorResultThrow(e) {
  return (
    e?.errorClass === "error_result" ||
    /returned an error result/i.test(String(e?.message || ""))
  );
}

/**
 * Normalize anything escaping a query into the shape the NDJSON/HTTP layers
 * already speak.
 *
 * @returns {{ message: string, retryable: boolean, kind: string }}
 */
/**
 * The resumed transcript is gone (never written, or deleted). The CLI says "No
 * conversation found with session ID …"; older wording is "session … not found".
 */
function isMissingSessionError(text) {
  return /no conversation found|session.{0,30}(not found|does not exist|no session)/i.test(
    String(text || "")
  );
}

function mapAgentError(e) {
  if (isAbortError(e)) {
    return { message: "(stopped)", retryable: false, kind: "cancelled" };
  }
  const raw = String(e?.message || e);
  if (isErrorResultThrow(e)) {
    return {
      message: raw,
      retryable: !/max turns|budget/i.test(raw),
      kind: "error_result",
    };
  }
  if (isMissingSessionError(raw)) {
    return { message: raw, retryable: true, kind: "session_not_found" };
  }
  if (/authentication|invalid.api.key|unauthorized|\b401\b|\b403\b/i.test(raw)) {
    return {
      message:
        `Claude auth failed — check ANTHROPIC_AUTH_TOKEN / ANTHROPIC_BASE_URL ` +
        `in host/.env. (${raw})`,
      retryable: false,
      kind: "auth",
    };
  }
  if (/credit balance/i.test(raw)) {
    return {
      message:
        `Claude is billing an API key, not your subscription. Restart the ` +
        `bridge and make sure \`claude auth status\` on the Mac shows ` +
        `authMethod "claude.ai". (${raw})`,
      retryable: false,
      kind: "auth",
    };
  }
  if (/rate.?limit|overloaded|\b429\b|\b529\b/i.test(raw)) {
    return { message: `Claude is overloaded right now: ${raw}`, retryable: true, kind: "rate_limit" };
  }
  return { message: raw, retryable: true, kind: "unknown" };
}

/** The API accepts only these four image types. */
function normalizeImageMime(mimeType) {
  const m = String(mimeType || "").toLowerCase();
  if (["image/jpeg", "image/png", "image/gif", "image/webp"].includes(m)) return m;
  if (m === "image/jpg") return "image/jpeg";
  return "image/png";
}

/**
 * One user message per turn. Claude takes images as content blocks, so a turn
 * with photos is a block array; a text-only turn stays a plain string.
 */
async function* userMessageIter(prompt, imagePayload) {
  if (!imagePayload || imagePayload.length === 0) {
    yield { type: "user", message: { role: "user", content: prompt } };
    return;
  }
  const content = imagePayload.map((img) => ({
    type: "image",
    source: {
      type: "base64",
      media_type: normalizeImageMime(img.mimeType),
      data: String(img.data),
    },
  }));
  if (prompt && prompt.trim()) content.push({ type: "text", text: prompt });
  yield { type: "user", message: { role: "user", content } };
}

/**
 * Optional idle watchdog + hard ceiling. Long tool calls (builds, simulations)
 * emit no stream events for many minutes — an idle timer would kill them mid-work.
 * Pass idleMs <= 0 to disable the quiet check; hardCapMs remains the only backstop.
 */
function withIdleTimeout(promise, activity, { idleMs, hardCapMs, label }) {
  const started = Date.now();
  const useIdle = Number.isFinite(idleMs) && idleMs > 0;
  const useHard = Number.isFinite(hardCapMs) && hardCapMs > 0;
  if (!useIdle && !useHard) return promise;
  let timer;
  const timeout = new Promise((_, reject) => {
    const tick = () => {
      const now = Date.now();
      if (useIdle && now - activity.last >= idleMs) {
        const err = new Error(
          `${label} (no activity for ${Math.round(idleMs / 1000)}s)`
        );
        err.status = 504;
        return reject(err);
      }
      if (useHard && now - started >= hardCapMs) {
        const err = new Error(
          `${label} (hard cap of ${Math.round(hardCapMs / 60000)}min reached)`
        );
        err.status = 504;
        return reject(err);
      }
      timer = setTimeout(tick, 5000);
    };
    timer = setTimeout(tick, 5000);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/** Absolute ceiling for a single turn (not an idle/quiet timer). */
const RUN_HARD_CAP_MS = 3 * 60 * 60 * 1000; // 3h — full sim / long builds

/** Keep HTTP + UI alive while a tool runs with no stream events. */
const STREAM_HEARTBEAT_MS = 45_000;

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => {
      const err = new Error(label || `timed out after ${ms}ms`);
      err.status = 504;
      reject(err);
    }, ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

/**
 * A device-supplied path → absolute path in the workspace, or a 400. Symlinks are
 * resolved (a link out of the workspace is refused) and the host's own dot folders are
 * off limits; `{ internal: true }` is for paths the host built itself.
 */
const assertInsideWorkspace = createWorkspaceResolver(WORKSPACE);

const PROJECT_MARKER = ".ccproject";
/** App/system dirs that must not appear in the All Projects library. */
const HIDDEN_LIBRARY_DIRS = new Set([
  "visualizations",
  "node_modules",
  "__pycache__",
  "venv",
]);

function isHiddenLibraryName(name) {
  if (!name || name.startsWith(".")) return true;
  return HIDDEN_LIBRARY_DIRS.has(name);
}

/** The explorer's folder listing: like the library, but it shows visualizations/. */
function isHiddenFileName(name) {
  return name !== "visualizations" && isHiddenLibraryName(name);
}

async function isProjectDir(abs) {
  try {
    const st = await fsp.stat(path.join(abs, PROJECT_MARKER));
    return st.isFile();
  } catch {
    return false;
  }
}

function relWorkspace(abs) {
  return path.relative(WORKSPACE, abs) || ".";
}

async function listLibraryEntries(rel) {
  const abs = assertInsideWorkspace(rel || ".");
  const st = await fsp.stat(abs);
  if (!st.isDirectory()) {
    const err = new Error("not a directory");
    err.status = 400;
    throw err;
  }
  const entries = await fsp.readdir(abs, { withFileTypes: true });
  const out = [];
  for (const e of entries) {
    if (isHiddenLibraryName(e.name)) continue;
    if (e.name === PROJECT_MARKER) continue;
    const full = path.join(abs, e.name);
    if (e.isDirectory()) {
      const project = await isProjectDir(full);
      out.push({
        name: e.name,
        path: relWorkspace(full),
        kind: project ? "project" : "folder",
      });
    } else if (e.isFile() && /\.pdf$/i.test(e.name)) {
      out.push({
        name: e.name,
        path: relWorkspace(full),
        kind: "pdf",
      });
    }
  }
  out.sort((a, b) => {
    const rank = { folder: 0, project: 1, pdf: 2 };
    const ra = rank[a.kind] ?? 9;
    const rb = rank[b.kind] ?? 9;
    if (ra !== rb) return ra - rb;
    return a.name.localeCompare(b.name);
  });
  return {
    path: relWorkspace(abs),
    parent:
      abs === WORKSPACE ? null : relWorkspace(path.dirname(abs)),
    entries: out,
  };
}

/** Shared PDFs = PDF files not living under any project directory. */
async function listSharedPdfs() {
  const items = [];
  async function walk(dirAbs, underProject) {
    let entries;
    try {
      entries = await fsp.readdir(dirAbs, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      if (isHiddenLibraryName(e.name)) continue;
      const full = path.join(dirAbs, e.name);
      if (e.isDirectory()) {
        if (underProject) continue;
        const project = await isProjectDir(full);
        if (project) continue;
        await walk(full, false);
      } else if (!underProject && e.isFile() && /\.pdf$/i.test(e.name)) {
        items.push({
          name: e.name,
          path: relWorkspace(full),
          kind: "pdf",
        });
      }
    }
  }
  await walk(WORKSPACE, false);
  items.sort((a, b) => a.name.localeCompare(b.name));
  return { items };
}

function resolveProjectCwd(projectRel) {
  if (!projectRel || typeof projectRel !== "string") return WORKSPACE;
  const trimmed = projectRel.trim();
  if (!trimmed || trimmed === ".") return WORKSPACE;
  const abs = assertInsideWorkspace(trimmed);
  return abs;
}

async function extractDocumentText(abs, mimeType, name) {
  const mime = String(mimeType || "").toLowerCase();
  const lower = String(name || abs).toLowerCase();
  const isPdf = mime === "application/pdf" || lower.endsWith(".pdf");
  const isText =
    mime.startsWith("text/") ||
    mime === "application/json" ||
    mime === "application/xml" ||
    /\.(txt|md|markdown|csv|tsv|json|xml|html?|css|py|js|jsx|ts|tsx|java|c|cc|cpp|h|hpp|rs|go|rb|php|sh|yaml|yml|toml|ini|log|tex|bib|rtf)$/i.test(
      lower
    );

  if (isText && !isPdf) {
    try {
      const raw = await fsp.readFile(abs, "utf8");
      return raw.slice(0, MAX_INLINE_CHARS);
    } catch {
      return null;
    }
  }

  if (isPdf) {
    try {
      const { stdout } = await execFileAsync(
        "/usr/bin/python3",
        [EXTRACT_PDF, abs],
        { maxBuffer: 30_000_000, timeout: 90_000 }
      );
      const text = String(stdout || "").trim();
      if (text) return text.slice(0, MAX_INLINE_CHARS);
    } catch (e) {
      console.warn("pdf extract failed:", e?.message || e);
    }
  }
  return null;
}

async function saveInboxDocuments(documents) {
  if (!documents || !documents.length) return [];
  const inbox = path.join(WORKSPACE, ".inbox");
  await fsp.mkdir(inbox, { recursive: true });
  const saved = [];
  for (const doc of documents) {
    const rawName = String(doc.name || "attachment.bin").replace(/[/\\]/g, "_");
    const safe = rawName.slice(0, 180) || "attachment.bin";
    const stamp = `${Date.now().toString(36)}${crypto.randomBytes(3).toString("hex")}`;
    const outName = `${stamp}-${safe}`;
    const abs = path.join(inbox, outName);
    const buf = Buffer.from(String(doc.data || ""), "base64");
    if (!buf.length) continue;
    if (buf.length > 25_000_000) {
      const err = new Error(`document too large: ${safe}`);
      err.status = 413;
      throw err;
    }
    assertInsideWorkspace(abs, { internal: true });
    await createFileExclusive(abs, buf);
    const mimeType = doc.mimeType || "application/octet-stream";
    const rel = path.relative(WORKSPACE, abs);
    const extracted = await extractDocumentText(abs, mimeType, safe);
    const role = doc.role === "reference" || doc.role === "goal" ? doc.role : "";
    saved.push({
      role,
      name: safe,
      path: rel,
      mimeType,
      bytes: buf.length,
      extractedText: extracted,
      extractedChars: extracted ? extracted.length : 0,
    });
  }
  return saved;
}

/**
 * Locked tutor persona, widened to cover what this agent can actually do: the
 * canvas, the workspace and the study store. The five numbered rules are the core.
 * This is a learning tool and nothing else - rule 5 is the gate that keeps it one.
 */
function buildSystemPreamble() {
  return [
    "You are a rigorous, academic-level mathematics and informatics tutor, working",
    "inside Inkside: a stylus notebook on an Android tablet, backed by a real",
    "workspace you can read, write and run.",
    "",
    "1. Use standard LaTeX for all math formatting ($...$ for inline, $$...$$ for standalone display equations).",
    "2. When answering proof-based or computational problems, structure responses with clear definitions, stated assumptions, and step-by-step logical deductions.",
    "3. If an uploaded image or document contains handwritten mathematics, first transcribe the problem statement into LaTeX to verify accuracy before proceeding with the solution.",
    "4. When reviewing a user's work, explicitly locate and highlight the exact line where a mistake or unjustified jump in logic occurs.",
    "5. Only respond to questions that support studying mathematics, computer science and closely related subjects. For anything else, say briefly that this assistant is here for studying and steer back to the material.",
    "",
    "Answer style (every reply):",
    "- Short. Lead with the answer, or the one step that matters. No preamble, no",
    "  restating the question, no closing recap, no menu of things you could do next.",
    "  If the answer is one line, it is one line.",
    "- Rigorous maths, plain words. Put every mathematical object in LaTeX, not only",
    "  displayed equations: variables, indices, numbers with units, inequalities, sets,",
    "  function and complexity notation ($x_i$, $n \\ge 2$, $\\mathbb{R}$, $O(n \\log n)$,",
    "  $3\\,\\mathrm{V}$). Never write maths as plain text or unicode stand-ins (x^2,",
    "  sqrt(x), >=, \u2264). Display ($$...$$) the key steps; keep the rest inline.",
    "- Be exact where it counts: state definitions and assumptions the result depends on,",
    "  get quantifiers and inequality directions right, and give each nontrivial step a",
    "  one-clause reason (\"by Cauchy\u2013Schwarz\", \"since $f$ is monotone\").",
    "- More LaTeX, not harder explanations. Use the simplest correct wording and the",
    "  most elementary argument that works; do not add abstraction, generalisations or",
    "  advanced material the question did not ask for.",
    "",
    "Teaching stance:",
    "- Everything you do serves the user's understanding. You are not a general coding",
    "  assistant, a ghostwriter, or a task runner. If a request would hand over an",
    "  answer the user is meant to derive, teach the method and let them finish it.",
    "- Prefer the smallest intervention that unblocks understanding: a pointed",
    "  question, a worked analogue, the one line where the reasoning breaks.",
    "- Say plainly when something is wrong, and where. Do not soften an error into a",
    "  suggestion.",
    "",
    "Using the workspace:",
    "- Default to EXPLAIN and discuss. Do NOT edit, create, delete or rewrite files",
    "  unless the user clearly asks for a change (implement, fix, refactor, replace,",
    "  add, delete, rename, apply). Questions, explanations, summaries and",
    "  walkthroughs stay read-only.",
    "- When the user does ask for a change, apply it with your tools rather than",
    "  describing the patch, then explain what changed and why it is correct.",
    "- Running code is a teaching device: use it to show a result the user can check,",
    "  not to skip the reasoning.",
    "",
    "Seeing the user's canvas:",
    "- You cannot see the tablet unless you ask. mcp__canvas__where tells you which",
    "  document and page is open; mcp__canvas__view_pages returns screenshots of pages",
    "  (their handwriting on the PDF). Use them ONLY when the user explicitly asks you",
    "  to look at their page, notes or solution (\"check my solution\", \"page 3\").",
    "  For work spanning pages, request the whole range; transcribe what you read",
    "  before judging it (rule 3).",
    "",
    "Study history:",
    "- A study store tracks which topics the user keeps failing. When it is included",
    "  below, prefer those topics when choosing examples, and say why you picked one.",
    "",
    "Speed rules:",
    "- If attached document text is provided below, answer from that text immediately. Do not re-read the file with tools unless the extract is missing or clearly incomplete.",
    "- For summarize / TL;DR / overview requests: reply with a concise summary in the first response. Do not explore unrelated workspace files.",
    "- Keep answers short unless the user asks for detail (see Answer style).",
    "",
    // Absolute on purpose: project chats run inside a project folder, and a relative
    // ".artifacts/" there lands where the app never looks.
    `Visualizations: write the interactive HTML to ${ARTIFACTS}/filename.html — that`,
    "exact absolute path, even when your working directory is a project folder.",
    "A matching empty pointer is auto-created at visualizations/filename.viz — never",
    "create .viz files yourself. Embed in chat with [[artifact:filename.html]] or",
    "[[viz:filename.html]]. A .viz file is only an explorer picker under visualizations/;",
    "it never holds the plot data, and opening it never shows source; the app renders",
    "the HTML under .artifacts/. A visualization should make a concept legible, not",
    "decorate an answer.",
    "",
    "Keep an artifact to the visualization itself. The figure, the controls that",
    "change it, and the labels needed to read it \u2014 axis labels, units, a key when two",
    "series share a plot, a short symbol next to each control. Nothing else:",
    "- No title, no intro paragraph, no caption restating the setup.",
    "- No explanation of what the user is looking at or why it matters, and no notes",
    "  on what was wrong with an earlier version. That belongs in your chat message,",
    "  where the user can read it once; the artifact stays under .artifacts/ and the",
    "  user opens it from visualizations/*.viz in the explorer.",
    "- No live readouts of t, u, i and friends unless a number is the point of the",
    "  figure. A status line is for an error the user needs to see, nothing else.",
    "- Label controls with the symbol, not a sentence: \"$M$\", not \"Memory window",
    "  length $M$ (used only in mean mode)\".",
    "Anything you would have written as prose in the artifact, say in chat instead.",
    "",
    "The artifact runtime is already there — do not rebuild any of it:",
    "- Styling: artifact-assets/artifact.css is linked into every artifact. It carries",
    "  the reset, light/dark palette, type scale and touch-sized controls. Write no CSS",
    "  reset, no colour scheme, no font stack. Use the classes: .art-figure for a figure,",
    "  .art-caption, .art-controls with .art-control for sliders and buttons, .art-note,",
    "  .art-row/.art-col for layout, .art-muted. Add your own CSS only for what is",
    "  genuinely specific to that one visualization.",
    "- Maths: KaTeX is linked and auto-renders on load. Write $...$ and $$...$$ directly",
    "  in the markup. Do not add MathJax, a KaTeX tag, or a render call.",
    "- Tokens: build on the CSS variables (--art-fg, --art-muted, --art-accent,",
    "  --art-surface, --art-outline, --art-grid) so plots match the page in both themes.",
    "",
    "Where it ends up: the user opens visualizations/*.viz from the explorer (or Open",
    "in chat) in the side panel as a live visualization — not as source, and not",
    "placed onto the document canvas. Design for that panel width (roughly",
    "phone-portrait). Update the existing .artifacts HTML rather than writing a new",
    "file when they ask for a change; the visualizations/*.viz pointer is kept in sync",
    "automatically from the HTML stem.",
    "",
    "Do not write to the user's canvas. Never place notes, images, or other",
    "annotations via .canvas/pending/ or /canvas/command — those ops are disabled.",
    "Answer in chat, and in the workspace when a change is asked for. The canvas",
    "ink, images, and notes are theirs alone.",
  ].join("\n");
}

/**
 * Build the user message. Conversation memory is the session's job — we never
 * inject tablet transcript. The system preamble is snapshotted into a fresh
 * session once (see startAgentQuery); every turn gets ambient canvas/study
 * context plus the new request.
 */
function buildPrompt({ message, openFile, savedDocs, canvasState, studyStats, learningCtx = null, pageView = true }) {
  const canvasCtx = formatCanvasState(canvasState);
  const studyCtx = formatStudyStats(studyStats);
  const docMeta = [];
  const docBodies = [];
  for (const d of savedDocs || []) {
    docMeta.push(
      `- ${d.path} (${d.mimeType}, ${d.bytes} bytes, original name: ${d.name}` +
        (d.extractedChars ? `, ${d.extractedChars} chars extracted` : ", no text extracted") +
        (d.role === "reference" ? ", REFERENCE" : d.role === "goal" ? ", LEARNING GOAL" : "") +
        ")"
    );
    if (d.extractedText) {
      docBodies.push(
        `===== BEGIN ATTACHED DOCUMENT: ${d.name} (${d.path}) =====\n` +
          d.extractedText +
          `\n===== END ATTACHED DOCUMENT: ${d.name} =====`
      );
    }
  }

  const reference = (savedDocs || []).filter((d) => d.role === "reference");
  const goalDocs = (savedDocs || []).filter((d) => d.role === "goal");
  const roleNotes = [
    reference.length
      ? "Documents marked REFERENCE are background material the student wants you to draw on and cite. They are not tasks to solve; do not work through them unless asked."
      : null,
    goalDocs.length
      ? "Documents marked LEARNING GOAL describe what the student wants to be able to do (a syllabus, exam sheet or topic list). Turn each into concrete learning goals with the set_goals tool when it is available (existing goals are kept), then briefly say what you set. Do not solve anything in them."
      : null,
  ].filter(Boolean);

  return [
    openFile ? `Open files on canvas: ${openFile}` : null,
    pageView
      ? null
      : "[Page viewing is OFF] The user has not allowed you to look at their pages: " +
        "mcp__canvas__view_pages is not available this turn. Do not try to see their handwriting; " +
        "if you need it, ask them to type or describe it (they can allow page viewing in Settings → AI).",
    roleNotes.length ? ["", ...roleNotes].join("\n") : null,
    canvasCtx || null,
    studyCtx || null,
    learningCtx || null,
    docMeta.length
      ? ["", "Attached documents saved into the workspace:", ...docMeta].join("\n")
      : null,
    docBodies.length ? ["", ...docBodies].join("\n\n") : null,
    "User request:",
    message || "(see attached image(s)/document(s))",
  ]
    .filter(Boolean)
    .join("\n");
}

function writeNdjson(res, obj) {
  if (res.writableEnded) return;
  res.write(JSON.stringify(obj) + "\n");
}

/**
 * Pretty one-line summary of a tool call for the chat timeline.
 */
function formatToolStep(name, args, status) {
  const n = String(name || "tool").trim() || "tool";
  const detail = toolArgsDetail(n, args);
  // Same label for running/completed/error — the UI shows one muted step list, no ticks.
  return detail ? `${n} · ${detail}` : n;
}

function toolArgsDetail(name, args) {
  if (!args || typeof args !== "object") return "";
  const a = args;
  const clip = (s, max = 72) => {
    const t = String(s ?? "").replace(/\s+/g, " ").trim();
    return t.length > max ? `${t.slice(0, max - 1)}…` : t;
  };
  const lower = name.toLowerCase();
  if (lower.includes("shell") || lower === "bash") {
    return clip(a.command || a.cmd || a.script || "");
  }
  if (lower.includes("read") || lower.includes("write") || lower.includes("edit") || lower.includes("delete")) {
    return clip(a.path || a.file || a.target_file || a.file_path || "");
  }
  if (lower.includes("grep") || lower.includes("search")) {
    return clip(a.pattern || a.query || a.regex || "");
  }
  if (lower.includes("glob")) {
    return clip(a.glob_pattern || a.pattern || a.glob || "");
  }
  if (typeof a.path === "string") return clip(a.path);
  if (typeof a.command === "string") return clip(a.command);
  return "";
}

/**
 * Stream a Claude query's output via NDJSON callbacks, then close the turn.
 * Emits: status, tool (tool steps), delta (assistant text), then done upstream.
 * onEvent({ type: 'status'|'tool'|'delta'|'done', ... })
 *
 * Because includePartialMessages is on, the same prose arrives twice: once as
 * stream_event deltas and again in the completed content block. Block indices
 * cannot pair the two: the SDK sends each completed block as its own assistant
 * message (content index 0), while the stream counts thinking blocks too. So
 * text is tracked per message id as a running char count — text blocks arrive
 * in order, and a completed block only contributes what the stream has not sent.
 */
async function runChatAndStream({ iter, gen, chatId, onEvent, timeoutMs, handle }) {
  /**
   * All assistant prose of the turn. Every delta carries the whole of it: the app
   * shows one answer per turn, so sending only the text since the last tool call
   * replaced what the agent had written before it (e.g. before a learning tool).
   */
  let text = "";
  /** Assistant text since the last tool step. */
  let segment = "";
  /** A tool step ended the previous stretch of prose; the next one is a new paragraph. */
  let paragraphBreak = false;
  const addText = (t) => {
    if (!t) return;
    if (paragraphBreak && text.trim()) text = text.trimEnd() + "\n\n";
    paragraphBreak = false;
    text += t;
    segment += t;
  };
  let lastStatus = "";
  let lastDeltaAt = 0;
  /** message id → { sent: text chars emitted, seen: chars of completed text blocks }. */
  const textProgress = new Map();
  const progressFor = (id) => {
    const key = id ?? "?";
    let p = textProgress.get(key);
    if (!p) textProgress.set(key, (p = { sent: 0, seen: 0 }));
    return p;
  };
  /** tool_use id → { name, detail, step } awaiting its tool_result. */
  const runningTools = new Map();
  let curMsgId = null;
  /** Bumped by every stream event; the idle timer reads it. */
  const activity = { last: Date.now() };
  const heartbeat = setInterval(() => {
    // Long Shell/Read stretches emit nothing — keep the tablet HTTP stream and
    // activity bar alive so they do not look stuck or get cut by read timeouts.
    activity.last = Date.now();
    if (lastStatus) onEvent({ type: "status", message: lastStatus });
  }, STREAM_HEARTBEAT_MS);
  if (typeof heartbeat.unref === "function") heartbeat.unref();

  const emitStatus = (msg) => {
    if (!msg || msg === lastStatus) return;
    lastStatus = msg;
    onEvent({ type: "status", message: msg });
  };

  const emitDelta = () => {
    if (!segment) return;
    const now = Date.now();
    if (now - lastDeltaAt < 30) return;
    lastDeltaAt = now;
    onEvent({ type: "delta", text, segment });
  };

  const finishCancelled = () => {
    const out = text && text.trim() ? text.trimEnd() + "\n\n(stopped)" : "(stopped)";
    onEvent({ type: "delta", text: out, segment });
    return {
      result: {
        status: "cancelled",
        subtype: null,
        id: handle.sessionId,
        error: null,
        text: out,
      },
      text: out,
      cancelled: true,
    };
  };

  const work = (async () => {
    let result = null;
    try {
      for await (const msg of iter) {
        activity.last = Date.now();
        if (isStale(chatId, gen)) {
          try {
            handle.abortController.abort();
          } catch {
            /* already aborted */
          }
          return finishCancelled();
        }

        // Incremental text. Block indices are shared across all block types.
        if (msg.type === "stream_event") {
          const ev = msg.event;
          if (ev.type === "message_start") {
            curMsgId = ev.message?.id || null;
          } else if (ev.type === "content_block_delta") {
            if (ev.delta?.type === "text_delta" && typeof ev.delta.text === "string") {
              progressFor(curMsgId).sent += ev.delta.text.length;
              addText(ev.delta.text);
              emitDelta();
            } else if (ev.delta?.type === "thinking_delta") {
              emitStatus("thinking…");
            }
          }
          continue;
        }

        if (msg.type === "assistant") {
          if (msg.error) emitStatus(String(msg.error).replace(/_/g, " "));
          const content = msg.message?.content || [];
          for (let i = 0; i < content.length; i++) {
            const block = content[i];
            if (block.type === "text") {
              const full = String(block.text || "");
              const p = progressFor(msg.message?.id);
              const already = Math.max(0, Math.min(p.sent - p.seen, full.length));
              p.seen += full.length;
              p.sent = Math.max(p.sent, p.seen);
              const rest = full.slice(already); // only what the stream has not sent
              if (rest) {
                addText(rest);
                emitDelta();
              }
            } else if (block.type === "tool_use") {
              // Flush any pending prose before the tool row.
              if (segment) {
                onEvent({ type: "delta", text, segment });
                lastDeltaAt = Date.now();
              }
              const name = block.name || "tool";
              const detail = toolArgsDetail(name, block.input);
              const step = formatToolStep(name, block.input, "running");
              runningTools.set(block.id, { name, detail, step });
              onEvent({ type: "tool", name, detail, status: "running", message: step });
              emitStatus(`using ${name}${detail ? ` · ${detail}` : ""}…`);
              // Next assistant prose starts a new paragraph of the same answer.
              segment = "";
              paragraphBreak = true;
            }
          }
          continue;
        }

        // Tool results ride back on user messages.
        if (msg.type === "user") {
          for (const block of msg.message?.content || []) {
            if (block.type !== "tool_result") continue;
            const tool = runningTools.get(block.tool_use_id);
            if (!tool) continue;
            runningTools.delete(block.tool_use_id);
            const status = block.is_error ? "error" : "completed";
            onEvent({
              type: "tool",
              name: tool.name,
              detail: tool.detail,
              status,
              message: tool.step,
            });
            emitStatus(
              `${status === "completed" ? "done" : "failed"} ${tool.name}` +
                (tool.detail ? ` · ${tool.detail}` : "")
            );
          }
          continue;
        }

        if (msg.type === "result") {
          result = msg;
          continue;
        }

        if (msg.type === "system") {
          if (msg.subtype === "status" && msg.status) emitStatus(String(msg.status).toLowerCase());
          else if (msg.subtype === "api_retry") {
            emitStatus(`retrying API (attempt ${msg.attempt})…`);
          }
          // session_state_changed / tool_progress / compact_boundary and the rest
          // carry nothing the tablet timeline shows; the result message ends the turn.
          continue;
        }
      }
    } catch (e) {
      if (isAbortError(e)) return finishCancelled();
      // An error result is delivered as a message and then thrown. The message is
      // the better carrier, so fall through when we already captured it.
      if (!(isErrorResultThrow(e) && result)) {
        console.warn("agent stream error:", e?.message || e);
        throw e;
      }
    }

    if (isStale(chatId, gen)) return finishCancelled();

    const isError = Boolean(result?.is_error);
    if (result) {
      usageLog.record({ tester: chatTester.get(chatId) || "", chatId, model: chatModel || "", result });
    }
    const finalText = isError ? "" : String(result?.result ?? text);
    // The result is authoritative; if it extends what we streamed, take the tail.
    if (!isError && finalText.startsWith(text) && finalText.length > text.length) {
      const rest = finalText.slice(text.length);
      text += rest;
      segment += rest;
    }
    if (!text) text = finalText;
    if (text) onEvent({ type: "delta", text, segment });

    const normalized = {
      status: isError ? "error" : "success",
      subtype: result?.subtype || null,
      id: result?.session_id || handle.sessionId,
      error: isError
        ? {
            message:
              (result?.errors || [])
                .map((s) => String(s).trim())
                .filter(Boolean)
                .join("; ") || "agent error",
          }
        : null,
      text: finalText,
    };
    return { result: normalized, text, cancelled: false };
  })();

  try {
    return await withIdleTimeout(work, activity, {
      // Idle kill disabled: tools can run many minutes with no stream events.
      idleMs: 0,
      hardCapMs: RUN_HARD_CAP_MS,
      label: "agent went quiet — try again (stuck run was cleared)",
    });
  } finally {
    clearInterval(heartbeat);
    // Ends the CLI process: a no-op after a clean turn, the only stop after a
    // hard-cap timeout.
    try {
      handle.abortController.abort();
    } catch {
      /* already aborted */
    }
    const id = normalizeChatId(chatId);
    if (runsByChat.get(id) === handle) runsByChat.delete(id);
    // `work` can settle after the timeout race — keep that from warning.
    void work.catch(() => {});
  }
}

/**
 * Start one Claude query for this chat. A chat with no session yet gets the
 * system preamble snapshotted alongside a pinned session id; a known chat
 * resumes its transcript.
 *
 * @returns {{ sessionId: string, fresh: boolean, iter: AsyncIterable<object>, handle: { abortController: AbortController, sessionId: string } }}
 */
function startAgentQuery({
  prompt,
  imagePayload = [],
  chatId,
  cwd = WORKSPACE,
  systemPromptText,
  model = chatModel,
  canvasTools = false,
  learningTools = false,
  openFile = "",
  project = "",
  pageView = true,
}) {
  const id = normalizeChatId(chatId);
  const { sessionId, fresh } = ensureSession(cwd, id);
  const abortController = new AbortController();
  const handle = { abortController, sessionId };
  const options = SHARED
    ? sharedAgentOptions(cwd, abortController)
    : {
        cwd,
        includePartialMessages: true,
        // The tablet cannot answer permission prompts; Improve edits the repo unattended.
        permissionMode: "bypassPermissions",
        allowDangerouslySkipPermissions: true,
        abortController,
        env: agentEnv(),
      };
  if (model) options.model = model;
  // Chat runs may look at the tablet canvas — through tools, only when asked.
  if (canvasTools) options.mcpServers = { canvas: createCanvasMcpServer(captureBroker, { pages: pageView }) };
  // Learning Mode: the learner model as tools (record evidence, goals, hint ladder).
  if (learningTools) {
    options.mcpServers = {
      ...(options.mcpServers || {}),
      // Only the chat's own project: the agent never sees another project's model.
      learning: createLearningMcpServer(learningStore, { chatId: id, openFile, project, planFor: studyPlanFor }),
    };
  }
  if (fresh) options.sessionId = sessionId;
  else options.resume = sessionId;
  // Sent every turn on purpose: with snapshot recording on, a resumed session
  // reuses the record and ignores this; where recording is not yet enabled for
  // the account, omitting it would leave the resumed session with no preamble.
  options.systemPrompt = { type: "custom", prompt: systemPromptText, snapshot: true };
  runsByChat.set(id, handle);
  const iter = query({ prompt: userMessageIter(prompt, imagePayload), options });
  return { sessionId, fresh, iter, handle };
}

/** Folders no sandboxed process may read: home directories and the host's own files. */
function hostDenyRead() {
  return sandboxDenyRead([ROOT, APP_ROOT, STATE_DIR, CLAUDE_CONFIG_DIR, CRASH_DIR]);
}

const SHARED_MCP_PREFIXES = ["mcp__canvas__", "mcp__learning__"];

/**
 * Agent options on a shared host. The agent is a sandboxed tenant of the workspace:
 * - Bash runs in the OS sandbox (Seatbelt / bubblewrap): writes only in the workspace,
 *   no reads of home directories or the host's files, network only to SANDBOX_DOMAINS,
 *   secrets removed from its environment. No way to run a command outside it, and no run
 *   at all if the sandbox cannot start.
 * - Only the tools in SHARED_AGENT_TOOLS exist (no web tools, which run outside the
 *   sandbox, no subagents), and a hook holds the file tools to the workspace.
 * - Nothing from the computer's Claude Code setup is loaded: no settings, hooks, MCP
 *   servers, plugins or CLAUDE.md, from the user's config or from the workspace.
 */
function sharedAgentOptions(cwd, abortController) {
  const env = agentEnv();
  return {
    cwd,
    includePartialMessages: true,
    abortController,
    env,
    settingSources: [],
    strictMcpConfig: true,
    tools: SHARED_AGENT_TOOLS,
    // "mcp__canvas" allows every tool of that server.
    allowedTools: [...SHARED_AGENT_TOOLS, ...SHARED_MCP_PREFIXES.map((p) => p.replace(/__$/, ""))],
    permissionMode: "dontAsk",
    // Project chats run in a subfolder; artifacts go to the workspace's .artifacts.
    additionalDirectories: [WORKSPACE],
    hooks: {
      PreToolUse: [
        {
          hooks: [
            createToolGuard({
              workspace: WORKSPACE,
              allowedTools: SHARED_AGENT_TOOLS,
              mcpPrefixes: SHARED_MCP_PREFIXES,
            }),
          ],
        },
      ],
    },
    sandbox: agentSandbox({
      workspace: WORKSPACE,
      denyRead: hostDenyRead(),
      allowedDomains: SANDBOX_DOMAINS,
      secretEnv: secretEnvNames(env),
    }),
  };
}

/** Improve is always available (no hourly time window). */
function improveWindowState() {
  return { open: true, secondsRemaining: 24 * 60 * 60, secondsUntilOpen: 0 };
}

function improveStatusPayload() {
  return {
    chatId: IMPROVE_CHAT_ID,
    status: improveSession.status,
    request: improveSession.request,
    text: improveSession.text,
    statusMessage: improveSession.statusMessage,
    startedAt: improveSession.startedAt || null,
    finishedAt: improveSession.finishedAt || null,
    autoDeploy: improveSession.autoDeploy,
    deploy: improveSession.deploy,
    agentId: improveSession.agentId,
    runId: improveSession.runId,
    error: improveSession.error,
    window: improveWindowState(),
  };
}

/** System preamble for the in-app Improve agent — sent once, with a fresh session. */
function buildImprovePreamble() {
  return [
    "You are the Claude Code agent for the Inkside project itself.",
    "The user is talking to you from inside the Android app, describing improvements they want — treat their message exactly like an instruction in a Claude Code session for this repo.",
    "Default: implement the change. Edit files with your tools; do not stop at a plan unless they ask for advice only.",
    "Keep scope focused on what they asked. Prefer concrete code changes over long explanations.",
    "Do NOT remove or gut the improve-chat bridge endpoints (/improve/stream, /improve/window, /improve/status, /improve/follow) or APP_ROOT wiring unless the user explicitly asks.",
    "",
    "Repo layout (cwd is the project root):",
    "- app/ — APK (MainActivity, CodeCanvasView, assets/chat, drawables, build.sh)",
    "- host/ — Node host (bridge) (@anthropic-ai/claude-agent-sdk) the tablet talks to",
    "- workspace/ — user canvas files (not app source; leave alone unless asked)",
    "",
    "If the user rejoined mid-run, the bridge will rebuild and adb-install the APK after you finish — do not spend a full deploy cycle yourself unless they explicitly ask you to install now.",
    "Otherwise, after meaningful Android changes you may rebuild/deploy with app/build.sh and adb if the environment allows — mention briefly what you did.",
  ].join("\n");
}

/** The per-turn user message for Improve; the preamble lives in the session. */
function buildImprovePrompt(message) {
  return ["", "User request:", message || "(empty)"].join("\n");
}

function beginNdjson(res) {
  res.status(200);
  res.setHeader("Content-Type", "application/x-ndjson; charset=utf-8");
  res.setHeader("Cache-Control", "no-cache, no-transform");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no");
  if (typeof res.flushHeaders === "function") res.flushHeaders();
}

/** Attach an HTTP response as a detachable improve listener. Closing the client does not cancel the run. */
function attachImproveListener(req, res) {
  beginNdjson(res);
  const listener = {
    closed: false,
    write(obj) {
      if (this.closed || res.writableEnded) return;
      writeNdjson(res, obj);
    },
    end() {
      if (this.closed || res.writableEnded) return;
      this.closed = true;
      try {
        res.end();
      } catch {
        /* ignore */
      }
    },
  };
  improveSession.listeners.add(listener);
  const detach = () => {
    listener.closed = true;
    improveSession.listeners.delete(listener);
  };
  // Only the response signals a real disconnect. Since Node 16 the request emits
  // "close" as soon as its body has been read, which detached every follower the
  // instant it attached.
  res.on("close", detach);
  return listener;
}

function broadcastImprove(obj) {
  if (obj.type === "status" && obj.message) {
    improveSession.statusMessage = obj.message;
  } else if (obj.type === "delta" && typeof obj.text === "string") {
    improveSession.text = obj.text;
  }
  for (const listener of [...improveSession.listeners]) {
    try {
      listener.write(obj);
    } catch {
      listener.closed = true;
      improveSession.listeners.delete(listener);
    }
  }
}

function endImproveListeners() {
  for (const listener of [...improveSession.listeners]) {
    try {
      listener.end();
    } catch {
      /* ignore */
    }
  }
  improveSession.listeners.clear();
}

async function runAdb(args, timeoutMs = 60_000) {
  return execFileAsync("adb", args, {
    timeout: timeoutMs,
    maxBuffer: 4_000_000,
  });
}

async function deployImprovedApk() {
  const androidDir = path.join(APP_ROOT, "app");
  const apk = path.join(androidDir, "build", "Inkside.apk");
  const log = [];
  const push = (line) => {
    log.push(line);
    broadcastImprove({ type: "status", message: line });
  };

  if (!ADB_SERIAL) {
    improveSession.deploy = { status: "skipped", log: "set ADB_SERIAL in host/.env to install rebuilt APKs", exitCode: 0 };
    push("deploy skipped (no ADB_SERIAL)");
    return improveSession.deploy;
  }
  improveSession.deploy = { status: "running", log: "" };
  // Give the tablet time to flush session_state.json before adb install kills it.
  push("deploying… flush session");
  await new Promise((r) => setTimeout(r, 2500));
  push("deploying… building APK");
  try {
    // Heartbeat while build.sh runs so tablet follow streams do not idle-timeout.
    const heartbeat = setInterval(() => {
      broadcastImprove({ type: "status", message: "deploying… building APK" });
    }, 20_000);
    let build;
    try {
      build = await execFileAsync("bash", ["build.sh"], {
        cwd: androidDir,
        timeout: 5 * 60 * 1000,
        maxBuffer: 8_000_000,
      });
    } finally {
      clearInterval(heartbeat);
    }
    if (build.stdout) log.push(String(build.stdout).trimEnd());
    if (build.stderr) log.push(String(build.stderr).trimEnd());

    push(`deploying… adb connect ${ADB_SERIAL}`);
    try {
      const conn = await runAdb(["connect", ADB_SERIAL], 30_000);
      if (conn.stdout) log.push(String(conn.stdout).trimEnd());
      if (conn.stderr) log.push(String(conn.stderr).trimEnd());
    } catch (e) {
      log.push(String(e.stderr || e.message || e));
    }

    // Final chance for the tablet to fsync session_state before install -r SIGKILLs it.
    push("deploying… flush session");
    await new Promise((r) => setTimeout(r, 2000));

    push(`deploying… installing on ${ADB_SERIAL}`);
    const install = await runAdb(
      ["-s", ADB_SERIAL, "install", "-r", apk],
      120_000
    );
    if (install.stdout) log.push(String(install.stdout).trimEnd());
    if (install.stderr) log.push(String(install.stderr).trimEnd());

    const joined = log.filter(Boolean).join("\n");
    improveSession.deploy = { status: "done", log: joined, exitCode: 0 };
    push("deploy done");
    return improveSession.deploy;
  } catch (e) {
    const errOut = [
      ...log,
      e.stdout ? String(e.stdout).trimEnd() : "",
      e.stderr ? String(e.stderr).trimEnd() : "",
      e.message || String(e),
    ]
      .filter(Boolean)
      .join("\n");
    improveSession.deploy = {
      status: "error",
      log: errOut,
      exitCode: e.code ?? 1,
    };
    broadcastImprove({ type: "status", message: "deploy failed" });
    return improveSession.deploy;
  }
}

/**
 * Run the improve agent detached from any single HTTP client.
 * Listeners may come and go; the run continues until completion or cancel.
 */
async function runImproveSession(message) {
  const id = IMPROVE_CHAT_ID;
  improveSession.status = "running";
  improveSession.request = String(message || "").trim().slice(0, 400);
  improveSession.text = "";
  improveSession.statusMessage = "starting…";
  improveSession.startedAt = Date.now();
  improveSession.finishedAt = 0;
  improveSession.autoDeploy = false;
  improveSession.deploy = null;
  improveSession.agentId = null;
  improveSession.runId = null;
  improveSession.error = null;

  broadcastImprove({ type: "status", message: "starting…" });

  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) {
        // Keep the same session — only clear a stuck run.
        await cancelRunForChat(id);
        broadcastImprove({ type: "status", message: "retrying agent…" });
      }

      const gen = bumpGen(id);
      await cancelRunForChat(id);
      broadcastImprove({ type: "status", message: "asking agent…" });

      const { sessionId, iter, handle } = startAgentQuery({
        prompt: buildImprovePrompt(message),
        chatId: id,
        cwd: APP_ROOT,
        systemPromptText: buildImprovePreamble(),
        // Improvements (tonight's run and the Improve chat) always get Opus,
        // whatever model the tablet picked for ordinary chats.
        model: IMPROVE_MODEL,
      });
      improveSession.agentId = sessionId;

      if (isStale(id, gen)) {
        await cancelRunForChat(id);
        improveSession.status = "cancelled";
        improveSession.text = "(stopped)";
        improveSession.finishedAt = Date.now();
        broadcastImprove({
          type: "done",
          status: "cancelled",
          text: "(stopped)",
          agentId: sessionId,
          chatId: id,
          autoDeploy: improveSession.autoDeploy,
          deploy: improveSession.deploy,
        });
        endImproveListeners();
        return;
      }

      {
        const { result, text } = await runChatAndStream({
          iter,
          gen,
          chatId: id,
          handle,
          timeoutMs: IMPROVE_TIMEOUT_MS,
          onEvent: (ev) => broadcastImprove(ev),
        });

        const cancelled = result?.status === "cancelled" || isStale(id, gen);
        const okText = text && text.trim();
        const failed = !cancelled && result?.status === "error" && !okText;

        // A dead session id would fail the same way forever: drop it so the retry
        // starts a fresh conversation (with the preamble) instead.
        if (failed && attempt === 0 && isMissingSessionError(result?.error?.message)) {
          console.warn(`[improve] session ${sessionId} is gone — starting a fresh one`);
          forgetSessionMapping(id);
          continue;
        }
        // Same rule as chat: only a mid-execution failure gets a second attempt.
        if (failed && attempt === 0 && result?.subtype === "error_during_execution") {
          continue;
        }

        const finalText = formatFailureText(result, text, cancelled);
        improveSession.text = finalText;
        improveSession.runId = result?.id || null;
        improveSession.error = result?.error?.message || null;

        if (cancelled) {
          improveSession.status = "cancelled";
        } else if (failed) {
          improveSession.status = "error";
        } else {
          improveSession.status = "finished";
        }

        const shouldDeploy =
          improveSession.autoDeploy &&
          !cancelled &&
          !failed &&
          improveSession.status === "finished";

        if (shouldDeploy) {
          await deployImprovedApk();
          if (improveSession.deploy?.status === "done") {
            const deployNote =
              "\n\n—\nDeployed: build + adb install succeeded.";
            improveSession.text = finalText + deployNote;
          } else if (improveSession.deploy?.status === "error") {
            const deployNote =
              "\n\n—\nDeploy failed:\n" +
              (improveSession.deploy.log || "(no log)").slice(0, 2000);
            improveSession.text = finalText + deployNote;
          }
        }

        improveSession.finishedAt = Date.now();
        broadcastImprove({
          type: "done",
          status: improveSession.status === "finished"
            ? result?.status || "finished"
            : improveSession.status,
          text: improveSession.text,
          error: improveSession.error,
          agentId: sessionId,
          chatId: id,
          runId: result?.id,
          autoDeploy: improveSession.autoDeploy,
          deploy: improveSession.deploy,
        });
        endImproveListeners();
        return;
      }
    }
  } catch (e) {
    try {
      await cancelRunForChat(id);
    } catch {
      /* ignore */
    }
    const { message: msg, retryable, kind } = mapAgentError(e);
    if (kind === "session_not_found") forgetSessionMapping(id);
    improveSession.status = "error";
    improveSession.error = msg;
    improveSession.finishedAt = Date.now();
    broadcastImprove({ type: "error", error: msg, retryable, chatId: id });
    endImproveListeners();
  }
}

/**
 * Local speech-to-text. One long-lived Python worker holds the chosen voice model
 * (1-3 GB) so each clip only pays for inference; it starts on the first request
 * and restarts on the next one if it dies. Requests are answered in order.
 */
const TRANSCRIBE_WORKER = path.join(ROOT, "scripts", "transcribe_worker.py");
let transcriber = null;

function resolveUv() {
  const abs = path.join(os.homedir(), ".local", "bin", "uv");
  return fs.existsSync(abs) ? abs : "uv";
}

function startTranscriber() {
  const voice = VOICE_MODELS[voiceModel];
  const proc = spawn(
    resolveUv(),
    ["run", "--python", "3.12", "--with", voice.pkg, "python", TRANSCRIBE_WORKER],
    {
      cwd: ROOT,
      env: {
        ...process.env,
        PATH: `/opt/homebrew/bin:${process.env.PATH || ""}`,
        TRANSCRIBE_ENGINE: voice.engine,
        TRANSCRIBE_MODEL: voice.repo,
      },
      stdio: ["pipe", "pipe", "pipe"],
    }
  );
  const t = { proc, pending: new Map(), nextId: 1, buf: "", readyWaiters: [], ready: false };
  const failAll = (msg) => {
    for (const w of t.readyWaiters) w.reject(new Error(msg));
    t.readyWaiters = [];
    for (const p of t.pending.values()) p.reject(new Error(msg));
    t.pending.clear();
  };
  proc.stdout.on("data", (chunk) => {
    t.buf += chunk;
    let nl;
    while ((nl = t.buf.indexOf("\n")) >= 0) {
      const line = t.buf.slice(0, nl).trim();
      t.buf = t.buf.slice(nl + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        continue; // library chatter on stdout
      }
      if (msg.ready) {
        t.ready = true;
        for (const w of t.readyWaiters) w.resolve();
        t.readyWaiters = [];
        continue;
      }
      const p = t.pending.get(msg.id);
      if (!p) continue;
      t.pending.delete(msg.id);
      if (msg.error) p.reject(new Error(msg.error));
      else p.resolve(msg.text || "");
    }
  });
  proc.stderr.on("data", (d) => {
    const s = String(d).trim();
    if (s && !/HF_TOKEN|unauthenticated/i.test(s)) console.warn("[transcribe]", s.slice(-500));
  });
  proc.on("exit", (code) => {
    if (transcriber === t) transcriber = null;
    failAll(`transcriber exited (${code})`);
  });
  proc.on("error", (e) => {
    if (transcriber === t) transcriber = null;
    failAll(`transcriber failed to start: ${e.message}`);
  });
  return t;
}

async function transcribeFile(absPath) {
  if (!transcriber) transcriber = startTranscriber();
  const t = transcriber;
  if (!t.ready) {
    await new Promise((resolve, reject) => t.readyWaiters.push({ resolve, reject }));
  }
  const id = t.nextId++;
  return new Promise((resolve, reject) => {
    t.pending.set(id, { resolve, reject });
    t.proc.stdin.write(JSON.stringify({ id, path: absPath }) + "\n");
  });
}

/** Body is the raw recording (any ffmpeg-readable format); answers { text }. */
app.post(
  "/transcribe",
  express.raw({ type: () => true, limit: "64mb" }),
  async (req, res) => {
    // Decoding runs ffmpeg on the guest's bytes outside the sandbox: opt-in when shared.
    if (SHARED && process.env.INKSIDE_DICTATION !== "1") {
      return res.status(403).json({ error: "dictation is off on this computer" });
    }
    const audio = req.body;
    if (!Buffer.isBuffer(audio) || audio.length === 0) {
      return res.status(400).json({ error: "empty audio body" });
    }
    const ext = /wav/i.test(req.get("content-type") || "") ? ".wav" : ".m4a";
    const tmp = path.join(os.tmpdir(), `cc-voice-${crypto.randomUUID()}${ext}`);
    try {
      await fsp.writeFile(tmp, audio);
      const started = Date.now();
      const text = await transcribeFile(tmp);
      res.json({ text, ms: Date.now() - started });
    } catch (e) {
      res.status(500).json({ error: `transcription failed: ${e?.message || e}` });
    } finally {
      fsp.unlink(tmp).catch(() => {});
    }
  }
);

app.get("/health", (req, res) => {
  if (!auth.isAuthed(req)) {
    // Enough to tell "not allowed here" from "wrong address"; nothing about the
    // computer or what is on it.
    return res.json({
      ok: true,
      version: BRIDGE_VERSION,
      authRequired: true,
      authenticated: false,
      reason: auth.refusal(req),
    });
  }
  const body = {
    ok: true,
    version: BRIDGE_VERSION,
    hostId: identity.hostId,
    name: identity.name,
    uptimeSec: Math.round((Date.now() - STARTED_AT) / 1000),
    authRequired: auth.tokenRequired || !auth.open,
    authenticated: true,
    shared: SHARED,
    backend: "claude-agent-sdk",
    hasApiKey: claudeAuthAvailable(),
    model: chatModel,
    provider: providerStateForDevice(),
    // The app reattaches to replies still running.
    activeChats: [...runsByChat.keys()],
  };
  if (!SHARED) {
    // Where things live: for the owner, not for guests.
    Object.assign(body, { workspace: WORKSPACE, appRoot: APP_ROOT, agentCount: sessionMap.size });
  }
  if (IMPROVE_ENABLED) {
    body.improveWindow = improveWindowState();
    body.improve = improveStatusPayload();
  }
  res.json(body);
});

app.get("/improve/window", (_req, res) => {
  res.json(improveWindowState());
});

app.get("/improve/provider", (_req, res) => {
  res.json({ ...providerStateForDevice(), hasApiKey: claudeAuthAvailable() });
});

/**
 * Switch the Mac's Claude Code provider. Runs `claude-provider <name>`, which
 * rewrites ~/.claude/settings.json; runs already in flight keep the CLI they
 * started, so this is explicitly the *next* run's provider.
 */
app.post("/improve/provider", async (req, res) => {
  if (FIXED_MODEL) return res.status(403).json({ error: "the provider is set by whoever runs this computer" });
  const provider = String(req.body?.provider ?? "").trim().toLowerCase();
  if (provider !== "claude" && provider !== "deepseek") {
    return res.status(400).json({ error: 'provider must be "claude" or "deepseek"' });
  }
  try {
    await execFileAsync(resolveProviderScript(), [provider], {
      timeout: 60_000,
      maxBuffer: 1_000_000,
    });
  } catch (e) {
    // The script puts the useful line ("DeepSeek API key not found.") on stderr.
    const detail = String(e?.stderr || e?.message || e).trim();
    return res.status(502).json({
      error: detail.slice(-300) || "provider switch failed",
      provider: readProviderState().provider,
    });
  }
  res.json({ ok: true, ...readProviderState(), hasApiKey: claudeAuthAvailable() });
});

app.get("/agent/settings", (_req, res) => {
  res.json(agentSettingsPayload());
});

/** Pick the model for chat and Improve runs; applies from the next turn on. */
app.post("/agent/model", async (req, res) => {
  if (FIXED_MODEL) return res.status(403).json({ error: "the model is set by whoever runs this computer" });
  const model = String(req.body?.model ?? "").trim().toLowerCase();
  if (!CHAT_MODELS.includes(model)) {
    return res.status(400).json({ error: `model must be one of ${CHAT_MODELS.join(", ")}` });
  }
  try {
    await saveChatModel(model);
  } catch (e) {
    return res.status(500).json({ error: `could not save model: ${e?.message || e}` });
  }
  res.json({ ok: true, ...agentSettingsPayload() });
});

app.get("/improve/status", (_req, res) => {
  res.json(improveStatusPayload());
});

app.post("/improve/follow", (req, res) => {
  if (improveSession.status !== "running") {
    return res.status(409).json({
      error: "no active improve run",
      ...improveStatusPayload(),
    });
  }

  // Re-entering a live run requests auto-deploy when the agent finishes.
  improveSession.autoDeploy = true;
  const listener = attachImproveListener(req, res);
  listener.write({
    type: "status",
    message: "rejoined · will deploy when done",
  });
  if (improveSession.text) {
    listener.write({ type: "delta", text: improveSession.text });
  } else if (improveSession.statusMessage) {
    listener.write({
      type: "status",
      message: improveSession.statusMessage,
    });
  }
});

async function handleImproveRequest(req, res) {
  const message = req.body && typeof req.body.message === "string" ? req.body.message : "";
  if (!message.trim()) {
    return res.status(400).json({ error: "message required" });
  }

  if (improveSession.status === "running") {
    return res.status(409).json({
      error: "Improve is already running — use /improve/follow to reattach.",
      ...improveStatusPayload(),
    });
  }

  const listener = attachImproveListener(req, res);
  // Fire-and-forget: the run outlives this HTTP connection.
  runImproveSession(message.trim()).catch((e) => {
    console.error("[improve] session crashed:", e?.message || e);
    if (improveSession.status === "running") {
      improveSession.status = "error";
      improveSession.error = e?.message || String(e);
      improveSession.finishedAt = Date.now();
      broadcastImprove({
        type: "error",
        error: improveSession.error,
        chatId: IMPROVE_CHAT_ID,
      });
      endImproveListeners();
    }
  });
  // Keep the listener reference so lint/unused is happy; detach happens on close.
  void listener;
}

app.post("/improve/stream", (req, res) => handleImproveRequest(req, res));

app.get("/workspace", (_req, res) => {
  res.json({ root: SHARED ? "" : WORKSPACE });
});

app.post("/file/write", async (req, res) => {
  try {
    const rel = String((req.body && req.body.path) || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    const text = req.body && req.body.text != null ? String(req.body.text) : "";
    if (Buffer.byteLength(text, "utf8") > 2_000_000) {
      return res.status(413).json({ error: "file too large" });
    }
    await fsp.mkdir(path.dirname(abs), { recursive: true });
    await writeFileNoFollow(abs, text);
    res.json({
      ok: true,
      path: path.relative(WORKSPACE, abs),
      lines: text.split(/\r?\n/).length,
    });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

/** Binary write (base64 body) for PDF create/append. */
/**
 * Export: body is the tablet's annotation layer (a PDF, one page per document page);
 * `path` is the workspace PDF under it and `pages` the comma-separated original page
 * index for each layer page (-1: a page only the app has). Answers the merged PDF.
 */
app.post(
  "/pdf/flatten",
  express.raw({ type: () => true, limit: "200mb" }),
  async (req, res) => {
    try {
      const rel = String(req.query.path || "");
      if (!rel) return res.status(400).json({ error: "path required" });
      const abs = assertInsideWorkspace(rel);
      const overlay = req.body;
      if (!Buffer.isBuffer(overlay) || overlay.length === 0) {
        return res.status(400).json({ error: "empty annotation layer" });
      }
      const pages = String(req.query.pages || "")
        .split(",")
        .filter((p) => p.trim() !== "")
        .map((p) => Number.parseInt(p, 10));
      if (pages.some((p) => !Number.isFinite(p))) {
        return res.status(400).json({ error: "bad page map" });
      }
      const original = await fsp.readFile(abs);
      const merged = await flattenPdf(original, overlay, pages);
      res.type("application/pdf").send(Buffer.from(merged));
    } catch (e) {
      res.status(e.status || 500).json({ error: `export failed: ${e.message}` });
    }
  }
);

/**
 * Rewrites a workspace PDF to a new page order: `order[j]` is the page that becomes
 * page j (twice: duplicated; missing: dropped) or -1 for a blank page of
 * `width`×`height` points. The file as it was goes to the trash first, so the
 * change can be undone from Recently deleted. Answers the new PDF.
 */
app.post("/pdf/reorder", async (req, res) => {
  try {
    const rel = String(req.body?.path || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    const order = Array.isArray(req.body?.order) ? req.body.order.map(Number) : null;
    if (!order || order.length === 0 || order.some((n) => !Number.isInteger(n))) {
      return res.status(400).json({ error: "order must be a list of page numbers" });
    }
    const width = Number(req.body?.width) || 595;
    const height = Number(req.body?.height) || 842;
    const original = await fsp.readFile(abs);
    const next = Buffer.from(await reorderPdf(original, order, [width, height]));

    const trashAbs = await trashDir();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const backup = path.join(trashAbs, `${stamp}-${path.basename(abs)}`);
    await fsp.copyFile(abs, backup, fs.constants.COPYFILE_EXCL);
    await updateTrashIndex((idx) => {
      idx[path.basename(backup)] = { original: relWorkspace(abs), deletedAt: Date.now() };
    });
    await writeFileAtomic(abs, next, WORKSPACE_REAL);
    res.type("application/pdf").send(next);
  } catch (e) {
    res.status(e.status || 500).json({ error: `reorder failed: ${e.message}` });
  }
});

/**
 * Text search in the workspace PDFs (`path` narrows it to one). Positions are
 * fractions of the page as shown, so the tablet can highlight them.
 */
app.get("/pdf/search", async (req, res) => {
  try {
    const q = String(req.query.q || "").trim();
    if (q.length < 2) return res.json({ files: [], total: 0, truncated: false });
    const rel = String(req.query.path || "");
    const onlyAbs = rel ? assertInsideWorkspace(rel) : null;
    res.json(await pdfIndex.search(q, { onlyAbs }));
  } catch (e) {
    res.status(e.status || 500).json({ error: `search failed: ${e.message}` });
  }
});

app.post("/file/write-binary", async (req, res) => {
  try {
    const rel = String((req.body && req.body.path) || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    let abs = assertInsideWorkspace(rel);
    const b64 = req.body && req.body.base64 != null ? String(req.body.base64) : "";
    if (!b64) return res.status(400).json({ error: "base64 required" });
    const buf = Buffer.from(b64, "base64");
    if (buf.length > 25_000_000) {
      return res.status(413).json({ error: "file too large" });
    }
    await fsp.mkdir(path.dirname(abs), { recursive: true });
    // Uploads never overwrite: "notes.pdf" becomes "notes (2).pdf" when taken.
    if (req.body?.unique && (await pathExists(abs))) {
      const ext = path.extname(abs);
      const stem = path.basename(abs, ext);
      let n = 2;
      let candidate;
      do {
        candidate = path.join(path.dirname(abs), `${stem} (${n})${ext}`);
        n++;
      } while (await pathExists(candidate));
      abs = assertInsideWorkspace(candidate);
    }
    await writeFileNoFollow(abs, buf);
    // Sync keeps both sides' timestamps equal, so a copy is never mistaken for a change.
    const wantMtime = Number(req.body?.mtimeMs);
    if (Number.isFinite(wantMtime) && wantMtime > 0) {
      await fsp.lutimes(abs, new Date(), new Date(wantMtime)).catch(() => {});
    }
    // Learning Mode: an uploaded worksheet should get learning goals.
    if (req.body?.unique && learningStore.isEnabled()) {
      const projectDir = await projectDirOf(abs);
      await learningStore.notePendingDocument(
        path.relative(WORKSPACE, abs),
        projectDir ? relWorkspace(projectDir) : ""
      );
    }
    res.json({ ok: true, path: path.relative(WORKSPACE, abs), bytes: buf.length });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

/**
 * Everything a tablet should mirror: [{path, size, mtimeMs}] for the documents in the
 * workspace. Hidden folders, dependency folders and very large files are left out;
 * .artifacts (visualizations) and the visualizations/ index are in.
 */
const SYNC_SKIP_DIRS = new Set(["node_modules", "__pycache__", "venv", "env", "dist", "build"]);
const SYNC_MAX_BYTES = 25_000_000;
async function syncManifest() {
  const out = [];
  async function walk(abs) {
    let entries;
    try {
      entries = await fsp.readdir(abs, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const full = path.join(abs, e.name);
      if (e.isDirectory()) {
        if (e.name.startsWith(".") && e.name !== ".artifacts" && e.name !== ".inkside") continue;
        if (SYNC_SKIP_DIRS.has(e.name)) continue;
        await walk(full);
      } else if (e.isFile()) {
        if (e.name.startsWith(".") && e.name !== ".projects.json" && e.name !== PROJECT_MARKER) continue;
        if (e.name.endsWith(".tmp")) continue;
        const st = await fsp.stat(full);
        if (st.size > SYNC_MAX_BYTES) continue;
        out.push({ path: path.relative(WORKSPACE, full), size: st.size, mtimeMs: Math.floor(st.mtimeMs) });
      }
    }
  }
  await walk(WORKSPACE);
  return out;
}

/** Sends {@code buf}, gzipped when the client accepts it (the tablet's HTTP stack unzips it itself). */
function sendMaybeGzip(req, res, buf, type) {
  res.setHeader("Content-Type", type);
  if (/\bgzip\b/.test(String(req.headers["accept-encoding"] || "")) && buf.length > 4096) {
    res.setHeader("Content-Encoding", "gzip");
    return res.send(zlib.gzipSync(buf, { level: 4 }));
  }
  return res.send(buf);
}

app.get("/sync/manifest", async (req, res) => {
  try {
    sendMaybeGzip(req, res, Buffer.from(JSON.stringify({ files: await syncManifest() })), "application/json");
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.get("/files", async (req, res) => {
  try {
    const rel = String(req.query.path || ".");
    const abs = assertInsideWorkspace(rel);
    const entries = await fsp.readdir(abs, { withFileTypes: true });
    const items = await Promise.all(
      entries
        .filter((e) => !isHiddenFileName(e.name) && e.name !== PROJECT_MARKER)
        .map(async (e) => {
          const full = path.join(abs, e.name);
          // lstat: a symlink's target (maybe outside the workspace) is not described.
          const st = await fsp.lstat(full);
          return {
            name: e.name,
            path: path.relative(WORKSPACE, full) || ".",
            type: e.isDirectory() ? "dir" : "file",
            size: st.size,
          };
        })
    );
    items.sort((a, b) => {
      if (a.type !== b.type) return a.type === "dir" ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    res.json({
      path: path.relative(WORKSPACE, abs) || ".",
      parent:
        abs === WORKSPACE
          ? null
          : path.relative(WORKSPACE, path.dirname(abs)) || ".",
      items,
    });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.get("/library", async (req, res) => {
  try {
    const data = await listLibraryEntries(String(req.query.path || "."));
    res.json(data);
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.get("/library/shared", async (req, res) => {
  try {
    res.json(await listSharedPdfs());
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

/**
 * The project a workspace path belongs to: the nearest folder above it (or the path
 * itself) holding a .ccproject marker. Lets the app enter a document's project when
 * the document is opened from elsewhere (recents, favourites, search).
 */
async function projectDirOf(abs) {
  let dir = abs;
  try {
    if (!(await fsp.stat(dir)).isDirectory()) dir = path.dirname(dir);
  } catch {
    dir = path.dirname(dir);
  }
  while (dir.startsWith(WORKSPACE) && dir !== WORKSPACE) {
    if (await isProjectDir(dir)) return dir;
    dir = path.dirname(dir);
  }
  return null;
}

app.get("/project-of", async (req, res) => {
  try {
    const rel = String(req.query.path || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const dir = await projectDirOf(assertInsideWorkspace(rel));
    if (dir) {
      let name = path.basename(dir);
      try {
        const marker = assertInsideWorkspace(path.join(dir, PROJECT_MARKER), { internal: true });
        const meta = JSON.parse(await fsp.readFile(marker, "utf8"));
        if (meta && typeof meta.name === "string" && meta.name.trim()) name = meta.name.trim();
      } catch {
        /* marker without JSON: folder name */
      }
      return res.json({ project: relWorkspace(dir), name });
    }
    res.json({ project: null });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/library/mkdir", async (req, res) => {
  try {
    const parent = String((req.body && req.body.parent) || ".");
    const name = String((req.body && req.body.name) || "").trim();
    if (!name || /[/\\]/.test(name) || name.startsWith(".")) {
      return res.status(400).json({ error: "invalid folder name" });
    }
    const parentAbs = assertInsideWorkspace(parent);
    if (await isProjectDir(parentAbs)) {
      return res.status(400).json({ error: "cannot create folder inside a project from the library" });
    }
    const abs = path.join(parentAbs, name);
    assertInsideWorkspace(abs);
    await fsp.mkdir(abs, { recursive: false });
    res.json({ ok: true, path: relWorkspace(abs), kind: "folder" });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/library/create-project", async (req, res) => {
  try {
    const parent = String((req.body && req.body.parent) || ".");
    const name = String((req.body && req.body.name) || "").trim();
    if (!name || /[/\\]/.test(name) || name.startsWith(".")) {
      return res.status(400).json({ error: "invalid project name" });
    }
    const parentAbs = assertInsideWorkspace(parent);
    if (await isProjectDir(parentAbs)) {
      return res.status(400).json({ error: "cannot nest a project inside another project" });
    }
    const abs = path.join(parentAbs, name);
    assertInsideWorkspace(abs);
    await fsp.mkdir(abs, { recursive: false });
    await fsp.writeFile(
      path.join(abs, PROJECT_MARKER),
      JSON.stringify({ name }, null, 2) + "\n",
      "utf8"
    );
    res.json({ ok: true, path: relWorkspace(abs), kind: "project" });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/library/move", async (req, res) => {
  try {
    const from = String((req.body && req.body.from) || "");
    const toDir = String((req.body && req.body.toDir) || ".");
    if (!from) return res.status(400).json({ error: "from required" });
    const fromAbs = assertInsideWorkspace(from);
    const toDirAbs = assertInsideWorkspace(toDir);
    if (await isProjectDir(toDirAbs)) {
      return res.status(400).json({ error: "cannot move into a project from the library" });
    }
    const base = path.basename(fromAbs);
    const destAbs = path.join(toDirAbs, base);
    assertInsideWorkspace(destAbs);
    if (fromAbs === destAbs) {
      return res.json({ ok: true, path: relWorkspace(destAbs) });
    }
    await fsp.rename(fromAbs, destAbs);
    res.json({ ok: true, path: relWorkspace(destAbs) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

// ---- Explorer file operations (inside projects too, unlike /library/*) ----------

const TRASH_DIR = ".trash";

/**
 * The trash folder, made if missing, and refused unless it really is
 * <workspace>/.trash: moving a file into a trash swapped for a symlink would put it
 * wherever the link points.
 */
async function trashDir() {
  const dir = path.join(WORKSPACE, TRASH_DIR);
  await fsp.mkdir(dir, { recursive: true });
  if (realpathLoose(dir) !== path.join(WORKSPACE_REAL, TRASH_DIR)) {
    const err = new Error("the trash folder is not a plain folder in the workspace");
    err.status = 500;
    throw err;
  }
  return dir;
}

function validLeafName(name) {
  return !!name && !/[/\\]/.test(name) && name !== "." && name !== ".." && !name.startsWith(".");
}

async function pathExists(abs) {
  try {
    await fsp.lstat(abs);
    return true;
  } catch {
    return false;
  }
}

/** "a.pdf" → "a copy.pdf", "a copy 2.pdf", … until free. */
async function freeCopyName(dirAbs, base) {
  const ext = path.extname(base);
  const stem = ext ? base.slice(0, -ext.length) : base;
  let candidate = `${stem} copy${ext}`;
  for (let i = 2; await pathExists(path.join(dirAbs, candidate)); i++) {
    candidate = `${stem} copy ${i}${ext}`;
  }
  return candidate;
}

function refuseRoot(abs) {
  if (abs === WORKSPACE) {
    const err = new Error("cannot change the workspace root");
    err.status = 400;
    throw err;
  }
}

app.post("/fs/mkdir", async (req, res) => {
  try {
    const parent = String(req.body?.parent || ".");
    const name = String(req.body?.name || "").trim();
    if (!validLeafName(name)) return res.status(400).json({ error: "invalid folder name" });
    const abs = assertInsideWorkspace(path.join(assertInsideWorkspace(parent), name));
    if (await pathExists(abs)) return res.status(409).json({ error: "already exists" });
    await fsp.mkdir(abs, { recursive: false });
    res.json({ ok: true, path: relWorkspace(abs) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/fs/rename", async (req, res) => {
  try {
    const from = String(req.body?.path || "");
    const name = String(req.body?.name || "").trim();
    if (!from) return res.status(400).json({ error: "path required" });
    if (!validLeafName(name)) return res.status(400).json({ error: "invalid name" });
    const fromAbs = assertInsideWorkspace(from);
    refuseRoot(fromAbs);
    const destAbs = assertInsideWorkspace(path.join(path.dirname(fromAbs), name));
    if (destAbs === fromAbs) return res.json({ ok: true, path: relWorkspace(destAbs) });
    if (await pathExists(destAbs)) return res.status(409).json({ error: "a file with that name exists" });
    await fsp.rename(fromAbs, destAbs);
    res.json({ ok: true, path: relWorkspace(destAbs) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/fs/move", async (req, res) => {
  try {
    const from = String(req.body?.from || "");
    const toDir = String(req.body?.toDir || ".");
    if (!from) return res.status(400).json({ error: "from required" });
    const fromAbs = assertInsideWorkspace(from);
    refuseRoot(fromAbs);
    const toDirAbs = assertInsideWorkspace(toDir);
    if (toDirAbs === fromAbs || toDirAbs.startsWith(fromAbs + path.sep)) {
      return res.status(400).json({ error: "cannot move a folder into itself" });
    }
    const destAbs = assertInsideWorkspace(path.join(toDirAbs, path.basename(fromAbs)));
    if (destAbs === fromAbs) return res.json({ ok: true, path: relWorkspace(destAbs) });
    if (await pathExists(destAbs)) return res.status(409).json({ error: "a file with that name exists there" });
    await fsp.rename(fromAbs, destAbs);
    res.json({ ok: true, path: relWorkspace(destAbs) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/fs/copy", async (req, res) => {
  try {
    const from = String(req.body?.from || "");
    const toDir = String(req.body?.toDir || ".");
    if (!from) return res.status(400).json({ error: "from required" });
    const fromAbs = assertInsideWorkspace(from);
    refuseRoot(fromAbs);
    const toDirAbs = assertInsideWorkspace(toDir);
    if (toDirAbs === fromAbs || toDirAbs.startsWith(fromAbs + path.sep)) {
      return res.status(400).json({ error: "cannot copy a folder into itself" });
    }
    let base = path.basename(fromAbs);
    if (await pathExists(path.join(toDirAbs, base))) base = await freeCopyName(toDirAbs, base);
    const destAbs = assertInsideWorkspace(path.join(toDirAbs, base));
    await fsp.cp(fromAbs, destAbs, { recursive: true, errorOnExist: true, force: false });
    res.json({ ok: true, path: relWorkspace(destAbs) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

/** Delete = move into workspace/.trash/<stamp>-name, so a slip can be undone on the Mac. */
app.post("/fs/delete", async (req, res) => {
  try {
    const rel = String(req.body?.path || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    refuseRoot(abs);
    if (!(await pathExists(abs))) return res.status(404).json({ error: "not found" });
    const trashAbs = await trashDir();
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const dest = path.join(trashAbs, `${stamp}-${path.basename(abs)}`);
    await fsp.rename(abs, dest);
    await updateTrashIndex((idx) => {
      idx[path.basename(dest)] = { original: relWorkspace(abs), deletedAt: Date.now() };
    });
    res.json({ ok: true, trashed: relWorkspace(dest) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

const TRASH_INDEX = ".index.json";

async function readTrashIndex() {
  try {
    const raw = await fsp.readFile(path.join(WORKSPACE, TRASH_DIR, TRASH_INDEX), "utf8");
    const o = JSON.parse(raw);
    return o && typeof o === "object" ? o : {};
  } catch {
    return {};
  }
}

let trashIndexChain = Promise.resolve();
/** Serialised read-modify-write of the trash index. */
function updateTrashIndex(mutate) {
  trashIndexChain = trashIndexChain.then(async () => {
    const idx = await readTrashIndex();
    mutate(idx);
    const dir = path.join(WORKSPACE, TRASH_DIR);
    await fsp.mkdir(dir, { recursive: true });
    await writeFileAtomic(path.join(dir, TRASH_INDEX), JSON.stringify(idx, null, 2), WORKSPACE_REAL);
  }).catch((e) => console.warn(`[trash] index: ${e?.message || e}`));
  return trashIndexChain;
}

/** Recently deleted items (newest first), with where each one came from. */
app.get("/fs/trash", async (_req, res) => {
  try {
    const dir = path.join(WORKSPACE, TRASH_DIR);
    let names = [];
    try {
      names = await fsp.readdir(dir);
    } catch {
      names = [];
    }
    const idx = await readTrashIndex();
    const items = [];
    for (const name of names) {
      if (name === TRASH_INDEX || name.startsWith(".")) continue;
      let st;
      try {
        st = await fsp.stat(path.join(dir, name));
      } catch {
        continue;
      }
      const meta = idx[name] || {};
      items.push({
        name,
        original: meta.original || name.replace(/^\d{4}-\d{2}-\d{2}T[\d-]+Z-/, ""),
        deletedAt: meta.deletedAt || st.mtimeMs,
        dir: st.isDirectory(),
      });
    }
    items.sort((a, b) => b.deletedAt - a.deletedAt);
    res.json({ items });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

/** Put a trashed item back where it was (next to it, renamed, if that spot is taken). */
app.post("/fs/restore", async (req, res) => {
  try {
    const name = String(req.body?.name || "");
    if (!name || /[/\\]/.test(name) || name.startsWith(".")) {
      return res.status(400).json({ error: "invalid trash entry" });
    }
    const src = path.join(await trashDir(), name);
    if (!(await pathExists(src))) return res.status(404).json({ error: "not in the trash" });
    const idx = await readTrashIndex();
    const original = idx[name]?.original || name.replace(/^\d{4}-\d{2}-\d{2}T[\d-]+Z-/, "");
    let destAbs = assertInsideWorkspace(original);
    refuseRoot(destAbs);
    await fsp.mkdir(path.dirname(destAbs), { recursive: true });
    if (await pathExists(destAbs)) {
      const ext = path.extname(destAbs);
      const stem = path.basename(destAbs, ext);
      let n = 1;
      let candidate;
      do {
        candidate = path.join(path.dirname(destAbs), `${stem} (restored${n > 1 ? " " + n : ""})${ext}`);
        n++;
      } while (await pathExists(candidate));
      destAbs = candidate;
    }
    await fsp.rename(src, destAbs);
    await updateTrashIndex((i) => {
      delete i[name];
    });
    res.json({ ok: true, path: relWorkspace(destAbs) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

// ---- Crash reports from the app ------------------------------------------------

const CRASH_DIR = path.join(process.env.BRIDGE_LOG_DIR || path.join(ROOT, "logs"), "crashes");
const CRASH_KEEP = 50;
const CRASH_MAX_BYTES = 256 * 1024;

/** The app uploads a crash it recorded on the device; kept under host/logs/crashes. */
app.post("/client/crash", async (req, res) => {
  try {
    const report = String(req.body?.report || "");
    if (!report.trim()) return res.status(400).json({ error: "report required" });
    const meta = {
      at: new Date(Number(req.body?.at) || Date.now()).toISOString(),
      appVersion: String(req.body?.appVersion || "").slice(0, 64),
      device: String(req.body?.device || "").slice(0, 128),
      android: String(req.body?.android || "").slice(0, 32),
    };
    await fsp.mkdir(CRASH_DIR, { recursive: true });
    const id = `${meta.at.replace(/[:.]/g, "-")}-${crypto.randomBytes(3).toString("hex")}`;
    const body =
      `Inkside crash ${id}\n` +
      Object.entries(meta).map(([k, v]) => `${k}: ${v}`).join("\n") +
      "\n\n" + report.slice(0, CRASH_MAX_BYTES) + "\n";
    await fsp.writeFile(path.join(CRASH_DIR, `${id}.txt`), body, "utf8");
    console.warn(`[crash] app crash recorded: ${id}`);
    // Keep the newest few; old reports are noise.
    const files = (await fsp.readdir(CRASH_DIR)).filter((f) => f.endsWith(".txt")).sort();
    for (const f of files.slice(0, Math.max(0, files.length - CRASH_KEEP))) {
      await fsp.rm(path.join(CRASH_DIR, f), { force: true });
    }
    res.json({ ok: true, id });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.get("/client/crashes", async (_req, res) => {
  try {
    let files = [];
    try {
      files = (await fsp.readdir(CRASH_DIR)).filter((f) => f.endsWith(".txt")).sort().reverse();
    } catch {
      files = [];
    }
    res.json({ items: files.map((f) => f.replace(/\.txt$/, "")) });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.get("/file", async (req, res) => {
  try {
    const rel = String(req.query.path || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    const st = await fsp.stat(abs);
    if (!st.isFile()) return res.status(400).json({ error: "not a file" });
    if (st.size > 2_000_000) {
      return res.status(413).json({ error: "file too large" });
    }
    const text = await fsp.readFile(abs, "utf8");
    res.json({
      path: path.relative(WORKSPACE, abs),
      text,
      lines: text.split(/\r?\n/).length,
    });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.post("/chat/preview", async (req, res) => {
  const { message, openFile = null, learningMode = null, project = null, allowPageView = true } = req.body || {};
  if (message == null) {
    return res.status(400).json({ error: "message required" });
  }
  const learning = typeof learningMode === "boolean" ? learningMode : learningStore.isEnabled();
  // Mirrors what a fresh session receives: preamble + first user message.
  const prompt = [
    buildSystemPreamble(),
    buildPrompt({
      message: String(message || ""),
      openFile,
      savedDocs: [],
      canvasState: await canvasQueue.getState(),
      studyStats: await studyStore.getStats(),
      learningCtx: learning
        ? formatLearningContext(await learningStore.snapshot(typeof project === "string" ? project : ""), {
            openFile,
            planText: formatPlanForPrompt(await studyPlanFor(typeof project === "string" ? project : "")),
          })
        : null,
      pageView: allowPageView !== false,
    }),
  ].join("\n");
  res.json({ prompt, hasApiKey: claudeAuthAvailable() });
});

/**
 * A chat turn, owned by the bridge rather than by the HTTP request that started
 * it. The tablet dropping off — app backgrounded, app killed, tailnet down —
 * detaches a listener and nothing else: the run keeps going, its output keeps
 * accumulating here, and whoever reconnects gets the whole thing replayed.
 *
 * @type {Map<string, {
 *   chatId: string, status: "running"|"finished"|"error"|"cancelled"|"interrupted",
 *   text: string, statusMessage: string, startedAt: number, finishedAt: number,
 *   error: string|null, agentId: string|null, runId: string|null,
 *   request: string, listeners: Set<{write:(o:object)=>void,end:()=>void,closed:boolean}>,
 * }>}
 */
const chatSessions = new Map();
const SESSIONS_DIR = path.join(WORKSPACE, ".canvas", "sessions");

function sessionFile(chatId) {
  return path.join(SESSIONS_DIR, `${encodeURIComponent(chatId)}.json`);
}

function sessionSummary(s) {
  return {
    chatId: s.chatId,
    status: s.status,
    statusMessage: s.statusMessage,
    text: s.text,
    startedAt: s.startedAt || null,
    finishedAt: s.finishedAt || null,
    error: s.error,
    agentId: s.agentId,
    runId: s.runId,
    request: s.request,
  };
}

async function persistSession(s) {
  try {
    await fsp.mkdir(SESSIONS_DIR, { recursive: true });
    await writeFileAtomic(sessionFile(s.chatId), JSON.stringify(sessionSummary(s), null, 2), WORKSPACE_REAL);
  } catch (e) {
    console.warn(`[chat] could not persist session ${s.chatId}: ${e?.message || e}`);
  }
}

/**
 * Sessions written by a previous bridge process. A run cannot outlive the
 * process that held its handle, so anything still marked running is reported as
 * interrupted rather than pretended to be alive — the transcript so far is
 * kept, and the session transcript still has the conversation for a resume.
 */
async function loadSessions() {
  let names = [];
  try {
    names = await fsp.readdir(SESSIONS_DIR);
  } catch {
    return;
  }
  for (const name of names) {
    if (!name.endsWith(".json")) continue;
    try {
      const row = JSON.parse(await fsp.readFile(path.join(SESSIONS_DIR, name), "utf8"));
      if (!row || typeof row.chatId !== "string") continue;
      const s = {
        chatId: row.chatId,
        status: row.status === "running" ? "interrupted" : row.status || "finished",
        text: typeof row.text === "string" ? row.text : "",
        statusMessage: row.status === "running" ? "interrupted by a bridge restart" : "",
        startedAt: row.startedAt || 0,
        finishedAt: row.finishedAt || Date.now(),
        error: row.status === "running" ? "bridge restarted while this run was in flight" : row.error || null,
        agentId: row.agentId || null,
        runId: row.runId || null,
        request: row.request || "",
        listeners: new Set(),
      };
      chatSessions.set(s.chatId, s);
      if (s.status === "interrupted") await persistSession(s);
    } catch {
      /* ignore an unreadable session file */
    }
  }
}

function newSession(chatId, request) {
  const prev = chatSessions.get(chatId);
  if (prev) endChatListeners(prev);
  const s = {
    chatId,
    status: "running",
    text: "",
    statusMessage: "starting…",
    startedAt: Date.now(),
    finishedAt: 0,
    error: null,
    agentId: null,
    runId: null,
    request: String(request || "").slice(0, 400),
    listeners: new Set(),
  };
  chatSessions.set(chatId, s);
  return s;
}

/** Attach an HTTP response to a session. Closing the client never cancels the run. */
function attachChatListener(req, res, s, { replay = true } = {}) {
  beginNdjson(res);
  const listener = {
    closed: false,
    write(obj) {
      if (this.closed || res.writableEnded) return;
      writeNdjson(res, obj);
    },
    end() {
      if (this.closed || res.writableEnded) return;
      this.closed = true;
      try {
        res.end();
      } catch {
        /* ignore */
      }
    },
  };
  s.listeners.add(listener);
  const detach = () => {
    listener.closed = true;
    s.listeners.delete(listener);
  };
  // See attachImproveListener: req "close" fires on body completion, not on
  // disconnect, so only the response is a trustworthy signal here.
  res.on("close", detach);
  if (replay) {
    // Everything produced while nobody was listening, in one go.
    listener.write({ type: "snapshot", ...sessionSummary(s) });
  }
  return listener;
}

function broadcastChat(s, obj) {
  if (obj.type === "status" && obj.message) s.statusMessage = obj.message;
  else if (obj.type === "delta" && typeof obj.text === "string") s.text = obj.text;
  for (const listener of [...s.listeners]) {
    try {
      listener.write(obj);
    } catch {
      listener.closed = true;
      s.listeners.delete(listener);
    }
  }
}

function endChatListeners(s) {
  for (const listener of [...s.listeners]) {
    try {
      listener.end();
    } catch {
      /* ignore */
    }
  }
  s.listeners.clear();
}

/** Remember which project each visualization was made in (visualizations/.projects.json). */
const VIZ_PROJECTS_FILE = () => path.join(VIZ_DIR, ".projects.json");

async function readVizProjects() {
  try {
    return JSON.parse(await fsp.readFile(VIZ_PROJECTS_FILE(), "utf8")) || {};
  } catch {
    return {};
  }
}

async function tagVizProject(stems, project) {
  if (!project || !stems.length) return;
  const map = await readVizProjects();
  let changed = false;
  for (const stem of stems) {
    if (map[stem] !== project) {
      map[stem] = project;
      changed = true;
    }
  }
  if (!changed) return;
  await fsp.mkdir(VIZ_DIR, { recursive: true });
  await writeFileAtomic(VIZ_PROJECTS_FILE(), JSON.stringify(map, null, 2), WORKSPACE_REAL);
  broadcast("files", { dirs: ["visualizations"], at: Date.now() });
}

async function finishSession(s, payload) {
  s.status = payload.status || "finished";
  s.text = payload.text ?? s.text;
  s.error = payload.error || null;
  s.agentId = payload.agentId || s.agentId;
  s.runId = payload.runId || s.runId;
  s.finishedAt = Date.now();
  s.statusMessage = "";
  // Visualizations this chat linked belong to its project.
  if (s.project) {
    const stems = [...String(s.text || "").matchAll(/\[\[viz:([^\]]+?)(?:\.html)?\]\]/g)]
      .map((m) => path.basename(m[1]));
    await tagVizProject(stems, s.project).catch(() => {});
  }
  broadcastChat(s, { type: "done", ...payload });
  endChatListeners(s);
  await persistSession(s);
}

/**
 * Run one chat turn to completion, independent of any HTTP request.
 * Resolves with the final payload; never throws.
 */
async function runChatTurn({
  s,
  id,
  prompt,
  imagePayload,
  timeoutMs,
  openFile,
  publicDocs,
  cwd,
  learning = false,
  pageView = true,
}) {
  const emit = (ev) => broadcastChat(s, ev);
  const agentCwd = cwd || WORKSPACE;
  try {
    let lastFailure = null;
    for (let attempt = 0; attempt < 2; attempt++) {
      if (attempt > 0) {
        console.warn(
          `[chat] retry chatId=${id} after status=${lastFailure?.result?.status || "?"} ` +
            `error=${lastFailure?.result?.error?.message || "none"}`
        );
        await cancelRunForChat(id);
        emit({ type: "status", message: "retrying agent…" });
      }

      const gen = bumpGen(id);
      await cancelRunForChat(id);

      emit({ type: "status", message: "asking agent…" });
      const { sessionId, iter, handle } = startAgentQuery({
        prompt,
        imagePayload,
        chatId: id,
        cwd: agentCwd,
        systemPromptText: buildSystemPreamble(),
        canvasTools: true,
        learningTools: learning,
        openFile: openFile || "",
        project: s.project || "",
        pageView,
      });
      s.agentId = sessionId;

      if (isStale(id, gen)) {
        await cancelRunForChat(id);
        const payload = {
          status: "cancelled",
          text: "(stopped)",
          error: null,
          agentId: sessionId,
          chatId: id,
          runId: null,
          openFile,
          savedDocs: [],
        };
        await finishSession(s, payload);
        return payload;
      }

      {
        const { result, text } = await runChatAndStream({
          iter,
          gen,
          chatId: id,
          handle,
          timeoutMs,
          onEvent: emit,
        });

        await adoptStrayArtifacts(agentCwd);
        const cancelled = result?.status === "cancelled" || isStale(id, gen);
        const okText = text && text.trim();
        const failed = !cancelled && result?.status === "error" && !okText;
        if (failed && attempt === 0 && isMissingSessionError(result?.error?.message)) {
          console.warn(`[chat] session ${sessionId} is gone — starting a fresh one`);
          forgetSessionMapping(id);
          lastFailure = { result, text, sessionId };
          continue;
        }
        // Only a mid-execution failure is worth a second attempt; max-turns and
        // budget errors repeat deterministically.
        if (failed && attempt === 0 && result?.subtype === "error_during_execution") {
          lastFailure = { result, text, sessionId };
          continue;
        }

        const finalText = formatFailureText(result, text, cancelled);
        if (failed) {
          console.error(
            `[chat] failed chatId=${id} runId=${result?.id || "?"} ` +
              `error=${result?.error?.message || "none"}`
          );
        }
        const payload = {
          status: cancelled ? "cancelled" : result?.status || "finished",
          text: finalText,
          error: result?.error?.message || null,
          agentId: sessionId,
          chatId: id,
          runId: result?.id,
          openFile,
          savedDocs: publicDocs,
        };
        await finishSession(s, payload);
        return payload;
      }
    }
    const payload = {
      status: "error",
      text: "(no reply)",
      error: "agent produced no reply",
      agentId: s.agentId,
      chatId: id,
      runId: null,
      openFile,
      savedDocs: publicDocs,
    };
    await finishSession(s, payload);
    return payload;
  } catch (e) {
    try {
      await cancelRunForChat(id);
    } catch {
      /* ignore */
    }
    const { message, retryable, kind } = mapAgentError(e);
    // A resume against a session that no longer exists: forget it so the next
    // turn starts clean instead of failing the same way forever.
    if (kind === "session_not_found") forgetSessionMapping(id);
    const payload = {
      status: "error",
      text: "",
      error: message,
      agentId: s.agentId,
      chatId: id,
      runId: null,
      openFile,
      savedDocs: publicDocs,
      retryable,
    };
    await finishSession(s, payload);
    return payload;
  }
}

async function handleChatRequest(req, res, { stream }) {
  const {
    message = "",
    openFile = null,
    images = [],
    documents = [],
    chatId = "default",
    project = null,
    learningMode = null,
    allowPageView = true,
    studyWeek = null,
  } = req.body || {};
  // This week's study time in the project, from the tablet's log (for the study plan).
  noteStudyTime(typeof project === "string" ? project : "", studyWeek);
  // The app sends its switch with every message; the stored setting is the fallback.
  const learning = typeof learningMode === "boolean" ? learningMode : learningStore.isEnabled();
  // Settings → AI: whether the agent may look at the user's pages at all.
  const pageView = allowPageView !== false;
  if (typeof message !== "string") {
    return res.status(400).json({ error: "message must be a string" });
  }
  if (!message.trim() && !(images && images.length) && !(documents && documents.length)) {
    return res.status(400).json({ error: "message or attachments required" });
  }

  const id = normalizeChatId(chatId);
  chatTester.set(id, req.tester || "");
  if (SHARED && !runsByChat.has(id) && runsByChat.size >= MAX_SHARED_RUNS) {
    return res.status(429).json({ error: "this computer is busy — try again in a minute", retryable: true });
  }
  const s = newSession(id, message);
  s.project = typeof project === "string" && project && project !== "." ? project : "";
  await persistSession(s);

  // The response is only a viewer. Everything below runs whether it stays or not.
  if (stream) attachChatListener(req, res, s, { replay: false });

  let savedDocs = [];
  let publicDocs = [];
  let prompt = "";
  let timeoutMs = 0; // idle kill disabled — see runChatAndStream
  let imagePayload = [];
  let agentCwd = WORKSPACE;
  try {
    agentCwd = resolveProjectCwd(project);
    broadcastChat(s, { type: "status", message: "saving attachments…" });
    savedDocs = await saveInboxDocuments(documents);
    const extractedCount = savedDocs.filter((d) => d.extractedChars > 0).length;
    if (savedDocs.length) {
      broadcastChat(s, {
        type: "status",
        message:
          extractedCount > 0
            ? `read ${extractedCount}/${savedDocs.length} attachment(s)…`
            : `saved ${savedDocs.length} attachment(s)…`,
      });
    }

    imagePayload = (images || [])
      .filter((img) => img && img.data)
      .map((img) => ({
        data: String(img.data),
        mimeType: img.mimeType || "image/png",
      }));

    // Idle windows removed: a single long tool call (simulation, build) can
    // legitimately produce nothing for many minutes. Hard cap is the only limit.
    timeoutMs = 0;
    publicDocs = savedDocs.map(({ extractedText, ...rest }) => rest);

    assertAuth();
    prompt = buildPrompt({
      message: message.trim(),
      openFile,
      savedDocs,
      canvasState: await canvasQueue.getState(),
      studyStats: await studyStore.getStats(),
      learningCtx: learning
        ? await (async () => {
            for (const d of savedDocs) {
              if (d.role !== "reference") await learningStore.notePendingDocument(d.path, s.project);
            }
            return formatLearningContext(await learningStore.snapshot(s.project), {
              openFile,
              planText: formatPlanForPrompt(await studyPlanFor(s.project)),
            });
          })()
        : null,
      pageView,
    });
  } catch (e) {
    const { message: message2, retryable } = mapAgentError(e);
    const payload = {
      status: "error",
      text: "",
      error: message2,
      agentId: null,
      chatId: id,
      runId: null,
      openFile,
      savedDocs: publicDocs,
      retryable,
    };
    await finishSession(s, payload);
    if (stream) return;
    // 400/413/503 from our own guards keep their status; agent failures are 502.
    if (e.status) return res.status(e.status).json({ error: message2 });
    return res.status(502).json({ error: message2, retryable });
  }

  const work = runChatTurn({
    s,
    id,
    prompt,
    imagePayload,
    timeoutMs,
    openFile,
    publicDocs,
    cwd: agentCwd,
    learning,
    pageView,
  });

  if (stream) {
    // Listeners get "done" from finishSession; the request may already be gone.
    work.catch((e) => console.error(`[chat] turn crashed chatId=${id}`, e));
    return;
  }
  const payload = await work;
  if (payload.status === "error" && !payload.text) {
    return res.status(502).json({ error: payload.error, retryable: !!payload.retryable });
  }
  return res.json(payload);
}

app.get("/chat/sessions", (_req, res) => {
  res.json({
    sessions: [...chatSessions.values()].map(sessionSummary),
    running: [...chatSessions.values()].filter((s) => s.status === "running").map((s) => s.chatId),
  });
});

/** Reattach to a session: replays everything so far, then streams the rest. */
app.post("/chat/follow", (req, res) => {
  const id = normalizeChatId(req.body && req.body.chatId);
  const s = chatSessions.get(id);
  if (!s) {
    beginNdjson(res);
    writeNdjson(res, { type: "snapshot", chatId: id, status: "idle", text: "", statusMessage: "" });
    return res.end();
  }
  attachChatListener(req, res, s);
  if (s.status !== "running") {
    writeNdjson(res, { type: "done", ...sessionSummary(s) });
    return res.end();
  }
});


app.post("/chat", (req, res) => handleChatRequest(req, res, { stream: false }));
app.post("/chat/stream", (req, res) => handleChatRequest(req, res, { stream: true }));

app.post("/chat/cancel", async (req, res) => {
  const requested = req.body && req.body.chatId;
  if (requested) {
    const id = normalizeChatId(requested);
    // Stale gen + cancel; improveSession finalization happens inside runImproveSession.
    bumpGen(id);
    await cancelRunForChat(id);
    const s = chatSessions.get(id);
    if (s && s.status === "running") {
      // Tell anyone attached, and leave the record cancelled for whoever reconnects.
      await finishSession(s, {
        status: "cancelled",
        text: s.text || "(stopped)",
        error: null,
        agentId: s.agentId,
        chatId: id,
        runId: s.runId,
        openFile: null,
        savedDocs: [],
      });
    }
    return res.json({ ok: true, cancelled: [id] });
  }
  // No chatId given: preserve the old "stop everything" behaviour (not for guests).
  if (SHARED) return res.status(400).json({ error: "chatId required" });
  const ids = [...runsByChat.keys()];
  await cancelAllRuns();
  res.json({ ok: true, cancelled: ids });
});

app.post("/agent/reset", async (req, res) => {
  const chatId = req.body && req.body.chatId;
  if (chatId) {
    await forgetSessionForChat(chatId);
  } else if (SHARED) {
    return res.status(400).json({ error: "chatId required" });
  } else {
    await forgetAllSessions();
  }
  res.json({ ok: true });
});

// ---- push channel ----

app.get("/events", (req, res) => {
  attachEventStream(req, res);
  // A client that reconnects mid-flight would otherwise never see commands that
  // were pushed while it was away.
  const replayed = canvasQueue.replayUnacked();
  if (replayed) console.log(`[events] replayed ${replayed} unacked batch(es)`);
});

// ---- agent → canvas commands ----

/** Direct enqueue; the agent normally writes a file instead, but this is the test seam. */
app.post("/canvas/command", async (req, res) => {
  const body = req.body;
  const list = Array.isArray(body) ? body : [body];
  const errors = [];
  list.forEach((cmd, i) => {
    const err = validateCommand(cmd);
    if (err) errors.push(`[${i}] ${err}`);
  });
  if (errors.length) return res.status(400).json({ error: errors.join("; ") });

  try {
    const file = path.join(
      canvasQueue.pendingDir,
      `api-${Date.now().toString(36)}${crypto.randomBytes(3).toString("hex")}.json`
    );
    await fsp.mkdir(canvasQueue.pendingDir, { recursive: true });
    assertInsideWorkspace(file, { internal: true });
    await createFileExclusive(file, JSON.stringify(list, null, 2));
    await canvasQueue.drain();
    res.json({ ok: true, queued: list.length, listeners: clientCount() });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ---- Learning Mode ------------------------------------------------------------------

/** Mode + counts per level, for the app's settings line. */
/** ?project= picks the project (default: the workspace outside any project). */
const learningProject = (req) => String(req.query?.project || req.body?.project || "");

app.get("/learning/state", async (req, res) => {
  try {
    res.json(await learningStore.summary(learningProject(req)));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/** The app's Learning view: one project's progress, topics and goals. */
app.get("/learning/progress", async (req, res) => {
  try {
    res.json(learningProgress(await learningStore.snapshot(learningProject(req))));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/**
 * What to study next in one project (see planner.mjs). The tablet passes this week's
 * study time in the project: ?studiedMin=&daysLeft= (today included); ?sessionMin=
 * plans a session of that length instead of what the weekly goal asks.
 */
app.get("/learning/plan", async (req, res) => {
  try {
    const project = learningProject(req);
    if (req.query?.studiedMin != null) {
      noteStudyTime(project, { studiedMinutes: req.query.studiedMin, daysLeft: req.query.daysLeft ?? 7 });
    }
    const minutes = Number(req.query?.sessionMin);
    res.json(await studyPlanFor(project, { sessionMinutes: minutes > 0 ? minutes : null }));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/**
 * The one thing to study next across all projects. The tablet sends this week's study
 * time per document ({ docMinutes: { path: minutes }, daysLeft }); it is summed per
 * project here, where it is known which folder is a project.
 */
app.post("/learning/next", async (req, res) => {
  try {
    const docMinutes = req.body?.docMinutes && typeof req.body.docMinutes === "object" ? req.body.docMinutes : {};
    const daysLeft = Number(req.body?.daysLeft) || 7;
    const perProject = new Map();
    for (const [doc, min] of Object.entries(docMinutes)) {
      const m = Number(min);
      if (!(m > 0)) continue;
      let key = "";
      try {
        const dir = await projectDirOf(assertInsideWorkspace(doc));
        key = dir ? scopeKey(relWorkspace(dir)) : "";
      } catch {
        continue;
      }
      perProject.set(key, (perProject.get(key) || 0) + m);
    }
    const all = await learningStore.snapshotAll();
    const keys = new Set([...Object.keys(all.projects || {}), ...perProject.keys()]);
    const entries = [];
    for (const key of keys) {
      noteStudyTime(key, { studiedMinutes: perProject.get(key) || 0, daysLeft });
      entries.push({ project: key, plan: await studyPlanFor(key) });
    }
    const ranked = pickNext(entries);
    const view = (r) => ({
      project: r.project,
      projectName: r.project ? path.basename(r.project) : "",
      item: r.item,
      weeklyGoalMinutes: r.plan.weeklyGoalMinutes,
      studiedMinutes: r.plan.studiedMinutes,
      sessionMinutes: r.plan.sessionMinutes,
    });
    let soonest = null;
    for (const e of entries) {
      if (e.plan.nextReviewAt && (!soonest || e.plan.nextReviewAt < soonest.at)) {
        soonest = { at: e.plan.nextReviewAt, concept: e.plan.nextReviewConcept, project: e.project };
      }
    }
    res.json({
      next: ranked[0] ? view(ranked[0]) : null,
      alternatives: ranked.slice(1, 4).map(view),
      nextReview: soonest,
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/** Set (or with 0 clear) the weekly study-time goal of a project: { project, minutes }. */
app.post("/learning/weekly-goal", async (req, res) => {
  try {
    const r = await learningStore.setWeeklyGoal({ project: learningProject(req), minutes: req.body?.minutes });
    if (!r.accepted) return res.status(400).json({ error: r.message });
    res.json(r);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/** Everything (every project), for inspection or export; ?project= for just one. */
app.get("/learning/model", async (req, res) => {
  try {
    res.json(req.query?.project != null ? await learningStore.snapshot(learningProject(req)) : await learningStore.snapshotAll());
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post("/learning/mode", async (req, res) => {
  try {
    if (typeof req.body?.enabled !== "boolean") {
      return res.status(400).json({ error: "enabled (boolean) required" });
    }
    await learningStore.setEnabled(req.body.enabled);
    res.json(await learningStore.summary(learningProject(req)));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/** Forget what the tutor has learned in one project (the mode and other projects stay). */
app.post("/learning/reset", async (req, res) => {
  try {
    await learningStore.reset(learningProject(req));
    res.json(await learningStore.summary(learningProject(req)));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/** The tablet's answer to a "capture" event (canvas screenshots for the agent). */
app.post("/canvas/capture-result", (req, res) => {
  const ok = captureBroker.settle(req.body?.id, req.body);
  if (!ok) return res.status(404).json({ error: "no capture waiting for that id" });
  res.json({ ok: true });
});

/**
 * Same capture the agent's tools use, for checking the round trip by hand:
 * POST {"kind":"pages","pages":"2-3"} → metadata and image sizes (not the images).
 */
app.post("/canvas/capture", async (req, res) => {
  try {
    const kind = req.body?.kind === "info" ? "info" : "pages";
    const spec = parsePageSpec(req.body?.pages);
    const r = await captureBroker.request(kind, spec);
    res.json({
      ok: true,
      document: r.document || null,
      currentPage: r.currentPage ?? null,
      pageCount: r.pageCount ?? null,
      images: (r.images || []).map((i) => ({ page: i.page, bytes: Math.round((i.data || "").length * 0.75) })),
    });
  } catch (e) {
    res.status(e.status || 503).json({ error: e.message });
  }
});

app.post("/canvas/ack", (req, res) => {
  const { batchId, ok = true, message = "" } = req.body || {};
  if (!batchId) return res.status(400).json({ error: "batchId required" });
  const known = canvasQueue.ack(String(batchId), Boolean(ok), String(message));
  res.json({ ok: true, known, pending: canvasQueue.pendingCount() });
});

app.post("/canvas/state", async (req, res) => {
  try {
    const saved = await canvasQueue.putState(req.body || {});
    res.json({
      ok: true,
      at: saved.at,
      notes: Array.isArray(saved.textFields) ? saved.textFields.length : 0,
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get("/canvas/state", async (_req, res) => {
  const state = await canvasQueue.getState();
  if (!state) return res.status(404).json({ error: "no canvas state reported yet" });
  res.json(state);
});

app.get("/canvas/prompt", async (_req, res) => {
  const state = await canvasQueue.getState();
  res.type("text/plain").send(formatCanvasState(state) || "(no canvas state reported yet)");
});

// ---- study data ----

app.post("/study/decks", async (req, res) => {
  try {
    const body = req.body || {};
    const saved = await studyStore.putDecks(body.decks !== undefined ? body.decks : body);
    res.json({ ok: true, at: saved.at, decks: saved.decks.length });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get("/study/decks", async (_req, res) => {
  res.json(await studyStore.getDecks());
});

app.post("/study/reviews", async (req, res) => {
  try {
    const body = req.body || {};
    const list = Array.isArray(body) ? body : body.reviews;
    const n = await studyStore.appendReviews(list);
    const stats = await studyStore.recomputeStats();
    res.json({ ok: true, appended: n, totalReviews: stats.totalReviews });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get("/study/stats", async (_req, res) => {
  res.json(await studyStore.getStats());
});

function parseScriptParams(source) {
  const params = {};
  for (const line of String(source || "").split("\n")) {
    const m = line.match(/^\s*#\s*@param\s+(\w+)\s+(\w+)(?:\s+([^\s#]+))?/);
    if (!m) continue;
    const [, name, type, def] = m;
    params[name] = { type, default: def ?? "" };
  }
  return params;
}

function parseScriptOutput(source) {
  for (const line of String(source || "").split("\n")) {
    const m = line.match(/^\s*#\s*@output\s+(\S+)/);
    if (m) return m[1];
  }
  return null;
}

app.get("/file/binary", async (req, res) => {
  try {
    const rel = String(req.query.path || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    const st = await fsp.stat(abs);
    if (!st.isFile()) return res.status(400).json({ error: "not a file" });
    if (st.size > 25_000_000) return res.status(413).json({ error: "file too large" });
    // Size + mtime is enough to tell the tablet whether its cached copy is current.
    // Re-sending a 2.3MB PDF it already has was 751ms of a 834ms document open.
    const tag = `"${st.size}-${Math.floor(st.mtimeMs)}"`;
    res.setHeader("ETag", tag);
    res.setHeader("Last-Modified", st.mtime.toUTCString());
    res.setHeader("Cache-Control", "no-cache");
    if (req.headers["if-none-match"] === tag) return res.status(304).end();
    const data = await fsp.readFile(abs);
    // Ink and chat files are big JSON: they shrink a lot on the way to another device.
    if (abs.endsWith(".json")) return sendMaybeGzip(req, res, data, "application/octet-stream");
    res.setHeader("Content-Type", "application/octet-stream");
    res.send(data);
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

app.get("/script/meta", async (req, res) => {
  try {
    const rel = String(req.query.path || "");
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    const text = await fsp.readFile(abs, "utf8");
    const lower = rel.toLowerCase();
    const lang = lower.endsWith(".py")
      ? "python"
      : lower.endsWith(".sh")
        ? "shell"
        : lower.endsWith(".js") || lower.endsWith(".mjs")
          ? "node"
          : "unknown";
    const output = parseScriptOutput(text);
    res.json({ path: rel, lang, params: parseScriptParams(text), output: output || undefined });
  } catch (e) {
    res.status(e.status || 500).json({ error: e.message });
  }
});

/** sandbox-runtime's CLI, which runs scripts under Seatbelt (macOS) or bubblewrap (Linux). */
const SRT_CLI = path.join(ROOT, "node_modules", "@anthropic-ai", "sandbox-runtime", "dist", "cli.js");
/**
 * Runs inside the sandbox and starts the script. srt hands its command to a shell, so
 * nothing a device chose (file names, parameters) goes on that command line: this
 * constant does, and the real command travels in the environment.
 */
const RUN_LAUNCHER =
  'const{spawnSync}=require("node:child_process");' +
  "const r=spawnSync(process.env.INKSIDE_RUN_CMD,JSON.parse(process.env.INKSIDE_RUN_ARGV)," +
  '{stdio:"inherit"});process.exit(r.status===null?1:r.status)';
let scriptSandboxFile = null;

/** How /run starts a script on a shared host: in the sandbox, with a clean environment. */
async function sandboxedRun(cmd, cmdArgs, cwd, scriptArgs) {
  if (!fs.existsSync(SRT_CLI)) {
    const err = new Error("scripts need the sandbox runtime on a shared host (npm install in host/)");
    err.status = 503;
    throw err;
  }
  if (!scriptSandboxFile) {
    // Outside the workspace, where nothing sandboxed can change it.
    const file = path.join(STATE_DIR, "script-sandbox.json");
    await fsp.mkdir(STATE_DIR, { recursive: true });
    const settings = scriptSandboxSettings({
      workspace: WORKSPACE,
      denyRead: hostDenyRead(),
      allowedDomains: SANDBOX_DOMAINS,
    });
    await writeFileAtomic(file, JSON.stringify(settings, null, 2));
    scriptSandboxFile = file;
  }
  return {
    file: process.execPath,
    args: [SRT_CLI, "--settings", scriptSandboxFile, process.execPath, "-e", RUN_LAUNCHER],
    opts: {
      cwd,
      env: scriptEnv({
        HOME: WORKSPACE_REAL,
        SCRIPT_ARGS: scriptArgs,
        INKSIDE_RUN_CMD: cmd,
        INKSIDE_RUN_ARGV: JSON.stringify(cmdArgs),
      }),
    },
  };
}

app.post("/run", async (req, res) => {
  try {
    const { path: rel, args = {} } = req.body || {};
    if (!rel) return res.status(400).json({ error: "path required" });
    const abs = assertInsideWorkspace(rel);
    const st = await fsp.stat(abs);
    if (!st.isFile()) return res.status(400).json({ error: "not a file" });
    const lower = rel.toLowerCase();
    let cmd;
    let cmdArgs;
    if (lower.endsWith(".py")) {
      cmd = "/usr/bin/python3";
      cmdArgs = [abs];
    } else if (lower.endsWith(".sh")) {
      cmd = "/bin/bash";
      cmdArgs = [abs];
    } else if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
      cmd = process.execPath;
      cmdArgs = [abs];
    } else {
      return res.status(400).json({ error: "unsupported script type" });
    }
    for (const [k, v] of Object.entries(args || {})) {
      if (v != null && String(v).length) cmdArgs.push(`--${k}`, String(v));
    }
    const scriptArgs = JSON.stringify(args || {});
    const run = SHARED
      ? await sandboxedRun(cmd, cmdArgs, path.dirname(abs), scriptArgs)
      : { file: cmd, args: cmdArgs, opts: { env: { ...process.env, SCRIPT_ARGS: scriptArgs } } };
    const { stdout, stderr } = await execFileAsync(run.file, run.args, {
      timeout: 120_000,
      maxBuffer: 4_000_000,
      ...run.opts,
    });
    res.json({
      ok: true,
      stdout: String(stdout || ""),
      stderr: String(stderr || ""),
      exitCode: 0,
    });
  } catch (e) {
    res.json({
      ok: false,
      stdout: e.stdout ? String(e.stdout) : "",
      stderr: e.stderr ? String(e.stderr) : e.message || String(e),
      exitCode: e.code ?? 1,
    });
  }
});

const ARTIFACTS = path.join(WORKSPACE, ".artifacts");
const VIZ_DIR = path.join(WORKSPACE, "visualizations");
await fsp.mkdir(ARTIFACTS, { recursive: true });
await fsp.mkdir(VIZ_DIR, { recursive: true });

/**
 * Empty explorer picker for an artifact. Same stem as the HTML under .artifacts/;
 * content stays empty — the app opens the HTML, never the .viz as source.
 */
async function ensureVizPointer(htmlName) {
  const base = path.basename(String(htmlName || ""));
  if (!base.toLowerCase().endsWith(".html")) return null;
  const stem = base.slice(0, -5);
  if (!stem || stem.includes("..")) return null;
  await fsp.mkdir(VIZ_DIR, { recursive: true });
  const vizRel = path.join("visualizations", `${stem}.viz`);
  const vizAbs = path.join(WORKSPACE, vizRel);
  if (!isInside(realpathLoose(VIZ_DIR), WORKSPACE_REAL)) return null;
  // Exclusive create: an existing pointer (or anything planted there) is left alone.
  await createFileExclusive(vizAbs, "").catch((e) => {
    if (e?.code !== "EEXIST") throw e;
  });
  return vizRel;
}

/**
 * A project chat runs inside its project folder, and an agent that writes a
 * relative ".artifacts/x.html" (sessions started before the prompt named the
 * absolute path still do) strands it where the app never looks. Move such files
 * into the real .artifacts/ and drop the project-local .viz pointers it made.
 */
async function adoptStrayArtifacts(cwd) {
  if (!cwd || path.resolve(cwd) === WORKSPACE) return;
  const strayDir = path.join(cwd, ".artifacts");
  // A folder swapped for a symlink would have us move files in from wherever it points.
  if (realpathLoose(strayDir) !== path.join(realpathLoose(cwd), ".artifacts")) return;
  if (!isInside(realpathLoose(cwd), WORKSPACE_REAL)) return;
  if (realpathLoose(ARTIFACTS) !== path.join(WORKSPACE_REAL, ".artifacts")) return;
  let names = [];
  try {
    names = (await fsp.readdir(strayDir)).filter((n) => n.toLowerCase().endsWith(".html"));
  } catch {
    return; // nothing stranded
  }
  for (const name of names) {
    try {
      // Plain files only: a symlink is not adopted.
      if (!(await fsp.lstat(path.join(strayDir, name))).isFile()) continue;
      await fsp.rename(path.join(strayDir, name), path.join(ARTIFACTS, name));
      await ensureVizPointer(name);
      await tagVizProject([name.slice(0, -5)], path.relative(WORKSPACE, cwd));
      await fsp.unlink(path.join(cwd, "visualizations", `${name.slice(0, -5)}.viz`)).catch(() => {});
      console.log(`[viz] adopted stray artifact ${path.relative(WORKSPACE, cwd)}/.artifacts/${name}`);
    } catch (e) {
      console.warn(`[viz] could not adopt ${name}: ${e?.message || e}`);
    }
  }
  await fsp.rmdir(path.join(cwd, "visualizations")).catch(() => {}); // only if now empty
  await fsp.rmdir(strayDir).catch(() => {});
  if (names.length) broadcast("files", { dirs: ["visualizations"], at: Date.now() });
}

/** Ensure every .artifacts/*.html has a visualizations/*.viz companion. */
async function syncVizPointersFromArtifacts() {
  let created = 0;
  try {
    const entries = await fsp.readdir(ARTIFACTS, { withFileTypes: true });
    for (const e of entries) {
      if (!e.isFile() || !e.name.toLowerCase().endsWith(".html")) continue;
      const vizAbs = path.join(VIZ_DIR, `${e.name.slice(0, -5)}.viz`);
      try {
        await fsp.access(vizAbs);
      } catch {
        await ensureVizPointer(e.name);
        created++;
        // Made while exactly one chat was running: it belongs to that chat's project.
        const running = [...chatSessions.values()].filter((x) => x.status === "running");
        if (running.length === 1 && running[0].project) {
          await tagVizProject([e.name.slice(0, -5)], running[0].project).catch(() => {});
        }
      }
    }
  } catch (err) {
    console.warn(`[viz] sync failed: ${err?.message || err}`);
  }
  return created;
}

function startArtifactVizWatch() {
  let timer = null;
  const kick = () => {
    if (timer) return;
    timer = setTimeout(async () => {
      timer = null;
      const n = await syncVizPointersFromArtifacts();
      if (n > 0) {
        // Surface new .viz files in the explorer (visualizations/ is not ignored).
        broadcast("files", { dirs: ["visualizations"], at: Date.now() });
      }
    }, 250);
  };
  try {
    fs.watch(ARTIFACTS, { persistent: true }, (_type, filename) => {
      if (filename && String(filename).toLowerCase().endsWith(".html")) kick();
      else if (!filename) kick();
    });
    console.log(`watching artifacts → visualizations/: ${ARTIFACTS}`);
  } catch (e) {
    console.warn(`[viz] could not watch artifacts: ${e?.message || e}`);
  }
}

await syncVizPointersFromArtifacts();
startArtifactVizWatch();
/**
 * KaTeX, served to artifact pages. The chat WebView is a file:// document and the
 * artifact iframe is http://, so the page cannot reach in and typeset the frame
 * itself — the maths has to arrive already wired up from this side.
 */
app.use(
  "/katex",
  express.static(path.join(ROOT, "assets", "katex"), { maxAge: 3600_000 })
);

/** Shared artifact styling, linked into every artifact page. */
app.use(
  "/artifact-assets",
  express.static(path.join(ROOT, "assets"), { maxAge: 3600_000 })
);

/*
 * The injected links are relative to the page (`up` climbs from the artifact back to the
 * host's root), so they also work when a proxy serves the host under a path such as
 * https://example.com/inkside/.
 */
const STYLE_INJECT = (up) => `
<link rel="stylesheet" href="${up}artifact-assets/artifact.css">
`;

const KATEX_INJECT = (up) => `
<link rel="stylesheet" href="${up}katex/katex.min.css">
<script src="${up}katex/katex.min.js"></script>
<script src="${up}katex/auto-render.min.js"></script>
<script>
(function () {
  function go() {
    if (typeof renderMathInElement !== "function") return;
    try {
      renderMathInElement(document.body, {
        delimiters: [
          { left: "$$", right: "$$", display: true },
          { left: "\\\\[", right: "\\\\]", display: true },
          { left: "$", right: "$", display: false },
          { left: "\\\\(", right: "\\\\)", display: false }
        ],
        ignoredTags: ["script", "noscript", "style", "textarea", "pre", "code", "option"],
        throwOnError: false
      });
    } catch (e) {}
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", go);
  } else {
    go();
  }
})();
</script>
`;

/**
 * Splice {@code inject} in ahead of {@code tag}.
 *
 * <p>Not String.replace: a `$$` in the replacement string is its escape for a
 * literal `$`, so the KaTeX config went out as `{ left: "$", display: true }` and
 * every inline `$x$` in an artifact typeset as centred display maths.
 */
function insertBefore(html, tag, inject) {
  const at = html.indexOf(tag);
  if (at < 0) return html + inject;
  return html.slice(0, at) + inject + html.slice(at);
}

/**
 * Overnight improvement notes.
 *
 * The tablet posts a line of "make this better" whenever the user thinks of one;
 * nothing runs then. A scheduled job drains this queue in the small hours, hands
 * it to Claude Code, and deploys the result, so the user states what they want
 * and finds it done rather than spending the evening supervising it.
 */
const NOTES_DIR = path.join(WORKSPACE, ".improve");
const NOTES_FILE = path.join(NOTES_DIR, "queue.json");

async function readNotes() {
  try {
    const raw = await fsp.readFile(NOTES_FILE, "utf8");
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

async function writeNotes(list) {
  await fsp.mkdir(NOTES_DIR, { recursive: true });
  await writeFileAtomic(NOTES_FILE, JSON.stringify(list, null, 2) + "\n", WORKSPACE_REAL);
}

/** What the last night that actually did something ended up doing. */
async function readLastRun() {
  try {
    return JSON.parse(await fsp.readFile(path.join(NOTES_DIR, "last-run.json"), "utf8"));
  } catch {
    return null;
  }
}

app.get("/improve/notes", async (_req, res) => {
  res.json({ notes: await readNotes(), lastRun: await readLastRun() });
});

app.post("/improve/notes", async (req, res) => {
  const text = String(req.body?.text ?? "").trim();
  if (!text) return res.status(400).json({ error: "text required" });
  const notes = await readNotes();
  notes.push({
    id: "n" + Date.now().toString(36) + Math.floor(Math.random() * 4096).toString(16),
    text,
    at: new Date().toISOString(),
  });
  await writeNotes(notes);
  res.json({ ok: true, count: notes.length });
});

/**
 * Drop a queued note. With `send: true` the note is not dropped but *dispatched*:
 * it leaves the queue and starts in the Improve chat now, so a request made at
 * 14:00 does not have to wait for 03:00. The caller follows the run like any
 * other Improve turn.
 */
app.post("/improve/notes/delete", async (req, res) => {
  const id = String(req.body?.id ?? "");
  const send = req.body?.send === true;
  const notes = await readNotes();
  const note = notes.find((n) => n.id === id);

  // Plain delete stays forgiving (a note drained by the night run is already
  // gone); asking to send it is a promise, so a missing note is an error.
  if (!note && send) return res.status(404).json({ error: "note not found" });

  const kept = notes.filter((n) => n.id !== id);
  await writeNotes(kept);

  if (send) {
    if (improveSession.status === "running") {
      return res.status(409).json({
        error: "Improve is already running — use /improve/follow to reattach.",
        ...improveStatusPayload(),
      });
    }
    // Fire-and-forget: the run outlives this HTTP connection. Status is set to
    // "running" synchronously inside runImproveSession, so a follow that arrives
    // with this reply always finds it.
    runImproveSession(note.text).catch((e) => {
      console.error("[improve] session crashed:", e?.message || e);
      if (improveSession.status === "running") {
        improveSession.status = "error";
        improveSession.error = e?.message || String(e);
        improveSession.finishedAt = Date.now();
        broadcastImprove({
          type: "error",
          error: improveSession.error,
          chatId: IMPROVE_CHAT_ID,
        });
        endImproveListeners();
      }
    });
    return res.json({ ok: true, sent: true, count: kept.length });
  }

  res.json({ ok: true, count: kept.length });
});

app.post("/improve/notes/clear", async (_req, res) => {
  await writeNotes([]);
  res.json({ ok: true, count: 0 });
});

/**
 * An artifact file's real path, or null. The agent writes .artifacts/, so a symlink there
 * must not serve a file from anywhere else.
 */
async function artifactFile(rel) {
  // Express has already URL-decoded the route parameter.
  if (String(rel).includes("\0")) return null;
  const abs = path.resolve(ARTIFACTS, rel);
  if (!isInside(abs, ARTIFACTS) || abs === ARTIFACTS) return null;
  let real;
  try {
    real = realpathLoose(abs);
  } catch {
    return null;
  }
  if (!isInside(real, path.join(WORKSPACE_REAL, ".artifacts"))) return null;
  try {
    return (await fsp.stat(real)).isFile() ? real : null;
  } catch {
    return null;
  }
}

/** Serve artifact HTML with maths support folded in; everything else is static. */
app.get(/^\/artifacts\/(.+\.html)$/, async (req, res, next) => {
  try {
    const abs = await artifactFile(req.params[0]);
    if (!abs) return next();
    let html = await fsp.readFile(abs, "utf8");
    // /artifacts/a/b.html → "../../" back to the host's root.
    const up = "../".repeat(String(req.params[0]).split("/").length);
    // House styling goes in first so a page's own rules still win.
    if (!/artifact-assets\/artifact\.css/i.test(html)) {
      html = html.includes("</head>")
        ? insertBefore(html, "</head>", STYLE_INJECT(up))
        : STYLE_INJECT(up) + html;
    }
    // Skip only when the page already wires KaTeX itself (avoid double-inject).
    if (!/katex\.min\.(js|css)|renderMathInElement/i.test(html)) {
      if (html.includes("</body>")) {
        html = insertBefore(html, "</body>", KATEX_INJECT(up));
      } else if (html.includes("</head>")) {
        html = insertBefore(html, "</head>", KATEX_INJECT(up));
      } else {
        html = html + KATEX_INJECT(up);
      }
    }
    res.setHeader("Content-Type", "text/html; charset=utf-8");
    res.setHeader("Cache-Control", "no-cache");
    res.send(html);
  } catch {
    next();
  }
});

// Other artifact files (images, data). Not express.static: it follows symlinks.
app.get(/^\/artifacts\/(.+)$/, async (req, res, next) => {
  const abs = await artifactFile(req.params[0]);
  if (!abs) return next();
  res.setHeader("Cache-Control", "no-cache");
  res.sendFile(abs, { dotfiles: "allow" }, (err) => {
    if (err && !res.headersSent) next();
  });
});

await fsp.mkdir(WORKSPACE, { recursive: true });
// The host's own folders exist before any sandbox starts, so its write protection has
// real paths to hold (bubblewrap can only protect what exists).
for (const dir of [".canvas/sessions", ".canvas/pdf-text", ".trash", ".inbox", ".learning", ".study", ".improve"]) {
  await fsp.mkdir(path.join(WORKSPACE, dir), { recursive: true });
}
await canvasQueue.init();
await studyStore.init();
await learningStore.init();
await loadSessionMap();
await loadSessions();
startWorkspaceWatch();

/** Machinery, caches and build output churn constantly; none of it is user content. */
const WATCH_IGNORED = new Set([
  ".canvas", ".artifacts", ".study", ".learning", ".inbox", ".git", ".trash",
  "node_modules", ".venv", "venv", "__pycache__", ".DS_Store",
]);

function watchPathIgnored(rel) {
  if (!rel) return true;
  for (const part of rel.split(path.sep)) {
    if (WATCH_IGNORED.has(part)) return true;
    if (part.startsWith(".bridge-ping")) return true;
  }
  return false;
}

/**
 * Tell connected tablets when the workspace changes on disk.
 *
 * <p>The explorer was load-once: a file created or deleted by the agent, by a
 * script, or from the Mac simply never appeared. Events are coalesced because a
 * single save can emit several, and carry only the parent directory — the client
 * refreshes what it has open rather than trusting the payload.
 */
function startWorkspaceWatch() {
  let pending = new Set();
  let timer = null;
  const flush = () => {
    timer = null;
    const dirs = [...pending];
    pending = new Set();
    if (dirs.length) broadcast("files", { dirs, at: Date.now() });
  };
  try {
    fs.watch(WORKSPACE, { recursive: true }, (_type, filename) => {
      if (!filename) return;
      const rel = String(filename);
      if (watchPathIgnored(rel)) return;
      const dir = path.dirname(rel);
      pending.add(dir === "." ? "" : dir);
      if (!timer) timer = setTimeout(flush, 350);
    });
    console.log(`watching: ${WORKSPACE}`);
  } catch (e) {
    console.warn(`[watch] could not watch workspace: ${e?.message || e}`);
  }
}

// Unknown routes and thrown errors answer in JSON, like every other route —
// Express's defaults are HTML pages the app cannot show.
app.use((req, res) => {
  res.status(404).json({ error: `no route for ${req.method} ${req.path}` });
});
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, _next) => {
  if (res.headersSent) return;
  let status = err.status || err.statusCode || 500;
  let message = err.message || "internal error";
  if (err.type === "entity.parse.failed") message = "request body is not valid JSON";
  if (err.type === "entity.too.large") message = "request body too large";
  if (status >= 500) console.error(`[http] ${req.method} ${req.path} id=${req.id}:`, err);
  res.status(status).json({ error: message, requestId: req.id });
});

// A stray rejected promise (a dropped stream, a failed watcher) must not take the
// whole bridge — and every running agent — down with it.
process.on("unhandledRejection", (reason) => {
  console.error("[unhandledRejection]", reason);
});

const httpServer = app.listen(PORT, HOST, () => {
  console.log(`Inkside host on http://${HOST}:${PORT}`);
  console.log(`workspace: ${WORKSPACE}`);
  console.log(`app root: ${APP_ROOT}`);
  if (ADB_SERIAL) console.log(`adb serial: ${ADB_SERIAL}`);
  console.log(`claude auth: ${claudeAuthAvailable() ? "set" : "MISSING"}`);
  console.log(`canvas queue: ${canvasQueue.pendingDir}`);
  console.log(`study store: ${studyStore.root}`);
  console.log(`session map: ${sessionMap.size} chat(s)`);
  console.log(`chat sessions: ${chatSessions.size} restored`);
  console.log(`host v${BRIDGE_VERSION} · "${identity.name}" · ${auth.tokenRequired ? "BRIDGE_TOKEN required" : auth.open ? "OPEN to any network (INKSIDE_OPEN=1)" : "devices on this computer's network only"}`);
  if (SHARED) {
    console.log(`shared: agent and scripts sandboxed · network for them: ${SANDBOX_DOMAINS.join(", ") || "none"} · up to ${MAX_SHARED_RUNS} runs at once`);
    if (process.platform === "linux") {
      for (const bin of ["bwrap", "socat"]) {
        try {
          execFileSync("which", [bin], { stdio: "ignore" });
        } catch {
          console.warn(`shared: ${bin} is missing — chats and scripts will fail until it is installed`);
        }
      }
    }
  } else if (auth.tokenRequired) {
    console.warn("INKSIDE_SHARED=0: the token holder gets everything this computer's user has. Only do this if the token is yours alone.");
  }
  if (IMPROVE_ENABLED) console.log("improve: on (INKSIDE_IMPROVE=1) — devices can have an agent change this repository");
  if (/^(127\.|localhost$|::1$)/.test(HOST)) {
    console.log(`listening on this computer only (${HOST}:${PORT}): devices reach it through your proxy's address`);
  } else {
    const addrs = reachableAddresses();
    console.log(`connect from the tablet: ⋮ → Connect a computer, then enter ${addrs.length ? addrs.join(" or ") : "this computer's IP address"}${PORT === 8787 ? "" : `:${PORT}`}`);
  }
  // Index the PDFs for search once things have settled.
  setTimeout(() => {
    const t0 = Date.now();
    pdfIndex.warm().then(() => console.log(`[pdf-index] ready in ${Date.now() - t0} ms`));
  }, 15_000).unref();
});

/** Graceful stop (launchd / Ctrl-C): stop taking requests, let in-flight ones end. */
let shuttingDown = false;
function shutdown(signal) {
  if (shuttingDown) return;
  shuttingDown = true;
  console.log(`[bridge] ${signal} — shutting down`);
  httpServer.close(() => process.exit(0));
  // Streams (SSE, agent runs) keep sockets open; don't wait on them forever.
  setTimeout(() => process.exit(0), 5000).unref();
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
