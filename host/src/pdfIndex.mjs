import fs from "node:fs";
import fsp from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { extractPdfText, searchPdf } from "./pdfTools.mjs";
import { realpathLoose, writeFileAtomic } from "./confine.mjs";

/** Folders search never walks into, besides hidden ones (app data, trash, chat uploads). */
const SKIP_DIRS = new Set(["node_modules"]);

/**
 * Text of the workspace's PDFs for search. Extracting is the slow part, so each
 * file's text is kept on disk under `.canvas/pdf-text/`, keyed by its path and
 * reused while its size and mtime are unchanged.
 */
export class PdfIndex {
  constructor(workspace) {
    this.workspace = workspace;
    this.realWorkspace = realpathLoose(workspace);
    this.cacheDir = path.join(workspace, ".canvas", "pdf-text");
    this.memory = new Map(); // abs -> { stamp, pages }
    this.inflight = new Map(); // abs -> Promise<pages>
  }

  async listPdfs() {
    const out = [];
    const walk = async (dir) => {
      let entries;
      try {
        entries = await fsp.readdir(dir, { withFileTypes: true });
      } catch {
        return;
      }
      for (const e of entries) {
        const full = path.join(dir, e.name);
        if (e.isDirectory()) {
          if (e.name.startsWith(".") || SKIP_DIRS.has(e.name)) continue;
          await walk(full);
        } else if (e.isFile() && e.name.toLowerCase().endsWith(".pdf")) {
          out.push(full);
        }
      }
    };
    await walk(this.workspace);
    return out.sort();
  }

  cacheFile(abs) {
    const rel = path.relative(this.workspace, abs);
    const key = crypto.createHash("sha1").update(rel).digest("hex").slice(0, 20);
    return path.join(this.cacheDir, `${key}.json`);
  }

  /** Page runs for one PDF, from memory, the disk cache, or a fresh extraction. */
  async pages(abs) {
    const st = await fsp.stat(abs);
    const stamp = `${st.size}:${Math.round(st.mtimeMs)}`;
    const mem = this.memory.get(abs);
    if (mem && mem.stamp === stamp) return mem.pages;
    const busy = this.inflight.get(abs);
    if (busy) return busy;
    const job = (async () => {
      const file = this.cacheFile(abs);
      try {
        const cached = JSON.parse(await fsp.readFile(file, "utf8"));
        if (cached.stamp === stamp && Array.isArray(cached.pages)) {
          this.memory.set(abs, { stamp, pages: cached.pages });
          return cached.pages;
        }
      } catch {
        // No cache yet, or unreadable: extract below.
      }
      let pages;
      try {
        pages = await extractPdfText(await fsp.readFile(abs));
      } catch (e) {
        console.warn(`[pdf-index] ${path.relative(this.workspace, abs)}: ${e?.message || e}`);
        pages = [];
      }
      this.memory.set(abs, { stamp, pages });
      try {
        await fsp.mkdir(this.cacheDir, { recursive: true });
        await writeFileAtomic(file, JSON.stringify({ stamp, pages }), this.realWorkspace);
      } catch (e) {
        console.warn(`[pdf-index] cache write: ${e?.message || e}`);
      }
      return pages;
    })().finally(() => this.inflight.delete(abs));
    this.inflight.set(abs, job);
    return job;
  }

  /**
   * Matches of `query` in one PDF (`onlyAbs`) or all of them, grouped by file:
   * `{ files: [{ path, matches: [...] }], total, truncated }`.
   */
  async search(query, { onlyAbs = null, limit = 300, perFile = 60 } = {}) {
    const targets = onlyAbs ? [onlyAbs] : await this.listPdfs();
    const files = [];
    let total = 0;
    let truncated = false;
    for (const abs of targets) {
      if (total >= limit) {
        truncated = true;
        break;
      }
      if (!fs.existsSync(abs)) continue;
      const pages = await this.pages(abs);
      const cap = Math.min(perFile, limit - total);
      const matches = searchPdf(pages, query, cap + 1);
      if (matches.length > cap) {
        truncated = true;
        matches.length = cap;
      }
      if (matches.length === 0) continue;
      total += matches.length;
      files.push({ path: path.relative(this.workspace, abs), pageCount: pages.length, matches });
    }
    return { files, total, truncated };
  }

  /** Fill the cache in the background so the first search does not wait. */
  async warm() {
    for (const abs of await this.listPdfs()) {
      try {
        await this.pages(abs);
      } catch {
        // A file that vanished or cannot be read is skipped.
      }
    }
  }
}
