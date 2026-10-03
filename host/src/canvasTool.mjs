/**
 * Canvas awareness for the chat agent, as tools — not as context.
 *
 * The agent does not see the tablet unless it asks. Two tools, served to each chat
 * run through an in-process MCP server:
 *
 *   where       — which document and page the user has open (text only, cheap)
 *   view_pages  — screenshots of pages (PDF + handwriting + notes), e.g. to check
 *                 a worked solution; "current", "all", "3", "2-4" or "1,3,5"
 *
 * The bridge cannot draw the canvas itself, so a request goes to the tablet over the
 * existing SSE channel ("capture" event) and the app answers on
 * POST /canvas/capture-result with JPEGs. CaptureBroker pairs the two.
 */
import crypto from "node:crypto";
import { createSdkMcpServer, tool } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";

/** Most pages one call may return (each is a full-page image). */
export const MAX_CAPTURE_PAGES = 20;

/**
 * "current" | "all" | "3" | "2-4" | "1,3,5-6" → { mode, list? } with 1-based pages.
 * Throws on anything else so the agent gets a clear message.
 */
export function parsePageSpec(spec) {
  const s = String(spec == null || spec === "" ? "current" : spec).trim().toLowerCase();
  if (s === "current" || s === "this" || s === "here") return { mode: "current" };
  if (s === "all") return { mode: "all" };
  const list = [];
  for (const part of s.split(",")) {
    const p = part.trim();
    if (!p) continue;
    const range = /^(\d+)\s*-\s*(\d+)$/.exec(p);
    if (range) {
      let a = Number(range[1]);
      let b = Number(range[2]);
      if (a > b) [a, b] = [b, a];
      for (let i = a; i <= b && list.length <= MAX_CAPTURE_PAGES; i++) list.push(i);
      continue;
    }
    if (/^\d+$/.test(p)) {
      list.push(Number(p));
      continue;
    }
    throw new Error(`pages must be "current", "all", a number, a range like "2-4" or a list like "1,3" (got "${spec}")`);
  }
  const uniq = [...new Set(list.filter((n) => n >= 1))].slice(0, MAX_CAPTURE_PAGES);
  if (!uniq.length) throw new Error(`no valid page numbers in "${spec}"`);
  return { mode: "list", list: uniq };
}

export class CaptureBroker {
  /**
   * @param {{ broadcast: (event: string, data: object) => void, clientCount: () => number, timeoutMs?: number }} opts
   */
  constructor({ broadcast, clientCount, timeoutMs = 30_000 }) {
    this.broadcast = broadcast;
    this.clientCount = clientCount;
    this.timeoutMs = timeoutMs;
    /** @type {Map<string, { resolve: Function, reject: Function, timer: NodeJS.Timeout }>} */
    this.pending = new Map();
  }

  /**
   * Ask the tablet. kind "info" returns document/page only; "pages" adds images.
   * @returns {Promise<{ document?: string, currentPage?: number, pageCount?: number, images?: Array<{ page: number, data: string, mimeType?: string }> }>}
   */
  request(kind, pages = { mode: "current" }) {
    if (this.clientCount() === 0) {
      return Promise.reject(new Error("The tablet app is not connected to the bridge right now (open the app and try again)."));
    }
    const id = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("The tablet did not answer in time — is the app open with a document?"));
      }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.broadcast("capture", { id, kind, pages, maxPages: MAX_CAPTURE_PAGES });
    });
  }

  /** The app's answer. False when nobody is waiting for this id (late or unknown). */
  settle(id, payload) {
    const p = this.pending.get(String(id || ""));
    if (!p) return false;
    clearTimeout(p.timer);
    this.pending.delete(String(id));
    if (!payload || payload.ok === false) {
      p.reject(new Error(payload?.error || "the tablet could not capture the canvas"));
    } else {
      p.resolve(payload);
    }
    return true;
  }
}

function describe(info) {
  if (!info.document) return "No document is open on the tablet.";
  const name = String(info.document).split("/").pop();
  return `Open document: ${name} (${info.document}); the user is on page ${info.currentPage} of ${info.pageCount}.`;
}

/**
 * Per-run MCP server exposing the canvas tools. `pages: false` (the user turned page
 * viewing off) leaves view_pages out; the app refuses page captures then as well.
 */
export function createCanvasMcpServer(broker, { pages = true } = {}) {
  const tools = [
    tool(
      "where",
      "Which document and page the user currently has open on their tablet canvas. " +
        "Text only. Use when the user refers to 'this page', 'here', 'my notes' and you need to know which.",
      {},
      async () => {
        try {
          const info = await broker.request("info");
          return { content: [{ type: "text", text: describe(info) }] };
        } catch (e) {
          return { content: [{ type: "text", text: String(e.message || e) }], isError: true };
        }
      }
    ),
    tool(
      "view_pages",
      "Screenshots of the user's tablet canvas pages — the PDF with their handwriting, notes and images on it. " +
        "Use ONLY when the user explicitly asks you to look at their canvas/page/notes/handwriting " +
        "(e.g. 'check my solution', 'look at page 3', 'is my proof right?'). Not for general questions. " +
        `pages: "current" (default: the page they are on), "all", a number, a range like "2-4", or a list like "1,3". ` +
        `A proof or solution that runs over several pages: request the range. At most ${MAX_CAPTURE_PAGES} pages per call.`,
      {
        pages: z
          .string()
          .optional()
          .describe('"current", "all", "3", "2-4" or "1,3,5" (1-based page numbers)'),
      },
      async (args) => {
        try {
          const spec = parsePageSpec(args?.pages);
          const result = await broker.request("pages", spec);
          const images = Array.isArray(result.images) ? result.images : [];
          const content = [
            {
              type: "text",
              text:
                describe(result) +
                (images.length
                  ? ` Showing page${images.length > 1 ? "s" : ""} ${images.map((i) => i.page).join(", ")}.`
                  : " No pages could be captured.") +
                (result.truncated ? ` (Limited to ${MAX_CAPTURE_PAGES} pages.)` : ""),
            },
          ];
          for (const img of images) {
            if (!img?.data) continue;
            content.push({ type: "text", text: `Page ${img.page}:` });
            content.push({ type: "image", data: img.data, mimeType: img.mimeType || "image/jpeg" });
          }
          return { content };
        } catch (e) {
          return { content: [{ type: "text", text: String(e.message || e) }], isError: true };
        }
      }
    ),
  ];
  return createSdkMcpServer({
    name: "canvas",
    version: "1.0.0",
    tools: pages ? tools : tools.filter((t) => t.name !== "view_pages"),
  });
}
