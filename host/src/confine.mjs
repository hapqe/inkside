/**
 * Keeping everything a device can reach inside the workspace.
 *
 * Three layers, so that one mistake is not enough:
 *
 * 1. HTTP paths (`resolveInside`): lexically inside the workspace, *and* inside it after
 *    symlinks are resolved, *and* not in the host's own machinery (dot folders other
 *    than the ones the app syncs). The agent can create symlinks; a link pointing out of
 *    the workspace must never become a way to read or write outside it.
 * 2. The host's own writes into the workspace (`writeFileAtomic`, `writeFileNoFollow`,
 *    `createFileExclusive`) never follow a symlink someone planted at the target.
 * 3. On a shared host (see server.mjs, SHARED), the agent and scripts run in the OS
 *    sandbox (`agentSandbox`, `scriptSandboxSettings`): writes only in the workspace, no
 *    reads of home directories or the host's files, no network beyond an allowlist, no
 *    credentials in the environment. `createToolGuard` holds the agent's in-process
 *    file tools (Read, Write, Edit, Glob, Grep), which the OS sandbox does not cover, to
 *    the same rule.
 */
import fs from "node:fs";
import fsp from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";

export function isInside(child, parent) {
  return child === parent || child.startsWith(parent.endsWith(path.sep) ? parent : parent + path.sep);
}

/**
 * The real path of `abs`, resolving symlinks in every component that exists. The part
 * that does not exist yet (a file about to be written) is appended unchanged.
 */
export function realpathLoose(abs) {
  let head = path.resolve(abs);
  const tail = [];
  for (;;) {
    try {
      const real = fs.realpathSync.native(head);
      return tail.length ? path.join(real, ...tail.reverse()) : real;
    } catch (e) {
      if (e?.code !== "ENOENT" && e?.code !== "ENOTDIR") throw e;
      const parent = path.dirname(head);
      if (parent === head) return path.join(head, ...tail.reverse());
      tail.push(path.basename(head));
      head = parent;
    }
  }
}

/**
 * Dot names a device may see or change over HTTP. Everything else that starts with a dot
 * is the host's (chat sessions, agent ids, the trash index, learner model) or a tool's
 * (.git, .claude, .venv) and stays out of reach.
 */
const HTTP_DOT_DIRS = new Set([".inkside", ".artifacts"]);
const HTTP_DOT_FILES = new Set([".projects.json", ".ccproject"]);

export function httpPathAllowed(rel) {
  const parts = String(rel).split(/[/\\]+/).filter((p) => p && p !== ".");
  return parts.every((p, i) => {
    if (!p.startsWith(".")) return true;
    if (p === "..") return false;
    return HTTP_DOT_DIRS.has(p) || (i === parts.length - 1 && HTTP_DOT_FILES.has(p));
  });
}

function refuse(message, status = 400) {
  const err = new Error(message);
  err.status = status;
  return err;
}

/**
 * @param {string} workspace absolute workspace root
 * @returns {(relOrAbs: string, opts?: { internal?: boolean }) => string} resolves a
 *   device-supplied path to an absolute one under `workspace`, or throws (status 400).
 *   `internal` skips the dot-name policy, for paths the host builds itself.
 */
export function createWorkspaceResolver(workspace) {
  const root = path.resolve(workspace);
  const realRoot = realpathLoose(root);
  return function resolveInside(relOrAbs, { internal = false } = {}) {
    const raw = String(relOrAbs ?? "");
    if (raw.includes("\0")) throw refuse("bad path");
    const abs = path.resolve(root, raw);
    if (!isInside(abs, root)) throw refuse("path outside workspace");
    if (!internal && !httpPathAllowed(path.relative(root, abs))) throw refuse("path not available");
    if (!isInside(realpathLoose(abs), realRoot)) throw refuse("path outside workspace");
    return abs;
  };
}

const O_NOFOLLOW = fs.constants.O_NOFOLLOW ?? 0;

/**
 * Write a file in place without following a symlink at the target (it fails with ELOOP
 * instead). Parent directories must already have been checked by the resolver.
 */
export async function writeFileNoFollow(abs, data) {
  const fh = await fsp.open(abs, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_TRUNC | O_NOFOLLOW, 0o644);
  try {
    await fh.writeFile(data);
  } finally {
    await fh.close();
  }
}

/** Create a new file; fails if anything (a file or a planted symlink) is already there. */
export async function createFileExclusive(abs, data) {
  await fsp.writeFile(abs, data, { flag: "wx" });
}

/** Append to a file without following a symlink at the target. */
export async function appendFileNoFollow(abs, data) {
  const fh = await fsp.open(abs, fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_APPEND | O_NOFOLLOW, 0o644);
  try {
    await fh.appendFile(data);
  } finally {
    await fh.close();
  }
}

/**
 * Replace a file atomically: write a fresh temp file next to it (exclusive create, so a
 * planted symlink cannot redirect it), then rename over the target (rename replaces a
 * symlink rather than writing through it). With `realRoot`, the folder it lands in must
 * really be inside that root (a folder swapped for a symlink is refused).
 */
export async function writeFileAtomic(abs, data, realRoot = null) {
  if (realRoot && !isInside(realpathLoose(path.dirname(abs)), realRoot)) {
    throw refuse("path outside workspace");
  }
  const tmp = `${abs}.${crypto.randomBytes(6).toString("hex")}.tmp`;
  try {
    await fsp.writeFile(tmp, data, { flag: "wx" });
    await fsp.rename(tmp, abs);
  } catch (e) {
    await fsp.rm(tmp, { force: true }).catch(() => {});
    throw e;
  }
}

/** Workspace paths only the host writes, even where the agent may otherwise write. */
export const PROTECTED_WORKSPACE_PATHS = [
  ".canvas/agent-map.json",
  ".canvas/agent-settings.json",
  ".canvas/sessions",
  ".canvas/state.json",
  ".canvas/pdf-text",
  ".learning",
  ".study",
  ".trash",
  ".improve",
  ".inbox",
  ".claude",
  ".mcp.json",
];

/** Tools the agent gets on a shared host. No web tools (they run unsandboxed), no subagents. */
export const SHARED_AGENT_TOOLS = ["Bash", "Read", "Write", "Edit", "Glob", "Grep", "NotebookEdit", "TodoWrite"];

/** Which input field holds a path, and whether the tool writes there. */
const PATH_FIELDS = {
  Read: [["file_path", false]],
  Write: [["file_path", true]],
  Edit: [["file_path", true]],
  MultiEdit: [["file_path", true]],
  NotebookEdit: [["notebook_path", true]],
  Glob: [["path", false]],
  Grep: [["path", false]],
};

function expandHome(p) {
  if (p === "~" || p.startsWith("~/")) return path.join(os.homedir(), p.slice(1));
  return p;
}

/** The fixed directory part of a glob ("/a/b/**\/*.py" → "/a/b"). */
function globBase(pattern) {
  const parts = pattern.split("/");
  const fixed = [];
  for (const part of parts) {
    if (/[*?[\]{}]/.test(part)) break;
    fixed.push(part);
  }
  return fixed.join("/") || (pattern.startsWith("/") ? "/" : ".");
}

/**
 * A PreToolUse hook: allows only `allowedTools` (plus `mcpPrefixes`), and holds every
 * path a file tool touches inside the workspace, symlinks resolved. Writes to the host's
 * own files are refused too. Runs for subagents as well.
 *
 * @param {{ workspace: string, allowedTools: string[], mcpPrefixes?: string[], protectedPaths?: string[] }} opts
 */
export function createToolGuard({ workspace, allowedTools, mcpPrefixes = [], protectedPaths = PROTECTED_WORKSPACE_PATHS }) {
  const root = path.resolve(workspace);
  const realRoot = realpathLoose(root);
  const allowed = new Set(allowedTools);
  const protectedReal = protectedPaths.map((p) => path.join(realRoot, p));

  const deny = (reason) => ({
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: reason,
    },
  });

  function checkPath(raw, cwd, writes) {
    if (typeof raw !== "string" || !raw) return null;
    if (raw.includes("\0") || raw.includes("$")) return "that path is not allowed here";
    const abs = path.resolve(cwd, expandHome(raw));
    let real;
    try {
      real = realpathLoose(abs);
    } catch {
      return "that path cannot be resolved";
    }
    if (!isInside(real, realRoot)) return "only files inside the workspace are available";
    if (writes && protectedReal.some((p) => isInside(real, p))) return "that file belongs to the host";
    return null;
  }

  return async function guard(input) {
    if (input?.hook_event_name !== "PreToolUse") return { continue: true };
    const name = String(input.tool_name || "");
    const args = input.tool_input || {};
    if (!allowed.has(name) && !mcpPrefixes.some((p) => name.startsWith(p))) {
      return deny(`the ${name} tool is not available on this computer`);
    }
    let cwd = typeof input.cwd === "string" && input.cwd ? input.cwd : root;
    try {
      if (!isInside(realpathLoose(cwd), realRoot)) cwd = root;
    } catch {
      cwd = root;
    }
    for (const [field, writes] of PATH_FIELDS[name] || []) {
      const why = checkPath(args[field], cwd, writes);
      if (why) return deny(why);
    }
    if (name === "Glob" && typeof args.pattern === "string") {
      const pattern = expandHome(args.pattern);
      if (pattern.split(/[/\\]/).includes("..")) return deny("only files inside the workspace are available");
      const base = path.resolve(typeof args.path === "string" && args.path ? path.resolve(cwd, expandHome(args.path)) : cwd, globBase(pattern));
      const why = checkPath(base, cwd, false);
      if (why) return deny(why);
    }
    if (name === "Grep" && typeof args.glob === "string" && args.glob.split(/[/\\]/).includes("..")) {
      return deny("only files inside the workspace are available");
    }
    return { continue: true };
  };
}

/** Environment variable names that look like secrets. */
const SECRET_NAME = /(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH|COOKIE|SESSION)/i;

export function secretEnvNames(env) {
  return Object.keys(env).filter((k) => SECRET_NAME.test(k));
}

/** Directories no sandboxed process may read (the workspace is re-opened by allowRead). */
export function sandboxDenyRead(extra = []) {
  const homes = process.platform === "darwin" ? ["/Users", "/var/root"] : ["/home", "/root"];
  return [...new Set([...homes, os.homedir(), ...extra].map((p) => path.resolve(p)))];
}

/**
 * The SDK's `sandbox` option for a shared host: Bash runs under Seatbelt (macOS) or
 * bubblewrap (Linux); if the sandbox cannot start, the run fails instead of running
 * unsandboxed.
 *
 * @param {{ workspace: string, denyRead: string[], allowedDomains: string[], secretEnv: string[] }} opts
 */
export function agentSandbox({ workspace, denyRead, allowedDomains, secretEnv }) {
  const realRoot = realpathLoose(workspace);
  return {
    enabled: true,
    failIfUnavailable: true,
    autoAllowBashIfSandboxed: true,
    allowUnsandboxedCommands: false,
    filesystem: {
      allowWrite: [realRoot],
      denyWrite: PROTECTED_WORKSPACE_PATHS.map((p) => path.join(realRoot, p)),
      denyRead,
      allowRead: [realRoot],
    },
    network: {
      allowedDomains,
      strictAllowlist: true,
      allowLocalBinding: false,
      allowAllUnixSockets: false,
    },
    credentials: {
      envVars: secretEnv.map((name) => ({ name, mode: "deny" })),
    },
  };
}

/** The same rules for sandbox-runtime (`srt --settings`), which runs /run scripts. */
export function scriptSandboxSettings({ workspace, denyRead, allowedDomains }) {
  const realRoot = realpathLoose(workspace);
  return {
    filesystem: {
      allowWrite: [realRoot],
      denyWrite: PROTECTED_WORKSPACE_PATHS.map((p) => path.join(realRoot, p)),
      denyRead,
      allowRead: [realRoot],
    },
    network: {
      allowedDomains,
      deniedDomains: [],
    },
  };
}

/** A minimal environment for a sandboxed script: nothing secret, nothing of the host's. */
export function scriptEnv(extra = {}) {
  const env = {};
  for (const k of ["PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ"]) {
    if (process.env[k]) env[k] = process.env[k];
  }
  return { ...env, ...extra };
}
