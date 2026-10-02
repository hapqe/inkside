import { PDFDocument, degrees } from "pdf-lib";

/**
 * Stamps the tablet's annotation layer onto the original PDF.
 *
 * `overlayBytes` has one page per document page, all at the document's page size
 * (the tablet shows every PDF page stretched to the first page's size). `pages[i]`
 * is the original page that overlay page `i` lies over, or -1 for a page only the
 * app has — that one is taken as it is, paper and all. The original pages keep
 * their own content, so their text stays vector and selectable.
 */
export async function flattenPdf(originalBytes, overlayBytes, pages) {
  const original = await PDFDocument.load(originalBytes, { ignoreEncryption: true });
  const overlay = await PDFDocument.load(overlayBytes);
  const out = await PDFDocument.create();
  const overlayPages = overlay.getPages();
  if (pages.length !== overlayPages.length) {
    throw new Error(`page map has ${pages.length} entries for ${overlayPages.length} pages`);
  }
  const fileCount = original.getPageCount();

  for (let i = 0; i < pages.length; i++) {
    const fileIdx = pages[i];
    if (!(fileIdx >= 0 && fileIdx < fileCount)) {
      const [copy] = await out.copyPages(overlay, [i]);
      out.addPage(copy);
      continue;
    }
    const [page] = await out.copyPages(original, [fileIdx]);
    out.addPage(page);
    const [layer] = await out.embedPages([overlayPages[i]]);
    placeLayer(page, layer);
  }
  return out.save();
}

/**
 * Draws `layer` over the page as the tablet shows it: filling the crop box, turned
 * with the page's /Rotate so the ink lands where it was written.
 */
function placeLayer(page, layer) {
  const box = page.getCropBox();
  const rot = ((page.getRotation().angle % 360) + 360) % 360;
  const quarter = rot === 90 || rot === 270;
  // Size on screen: a quarter-turned page shows its box's height as its width.
  const width = quarter ? box.height : box.width;
  const height = quarter ? box.width : box.height;
  // The layer's bottom-left corner in page space, rotated counter-clockwise by
  // `rot` about that corner, maps the layer onto the page as it is displayed.
  let x = box.x;
  let y = box.y;
  if (rot === 90) x = box.x + box.width;
  else if (rot === 180) {
    x = box.x + box.width;
    y = box.y + box.height;
  } else if (rot === 270) y = box.y + box.height;
  page.drawPage(layer, { x, y, width, height, rotate: degrees(rot) });
}

/**
 * A new page order for a PDF. `order[j]` is the original page that becomes page j
 * (listed twice: duplicated; left out: dropped), or -1 for a new blank page of
 * `blankSize` ([width, height] in points, as the page is shown).
 */
export async function reorderPdf(originalBytes, order, blankSize) {
  const original = await PDFDocument.load(originalBytes, { ignoreEncryption: true });
  const out = await PDFDocument.create();
  const count = original.getPageCount();
  for (const idx of order) {
    if (Number.isInteger(idx) && idx >= 0 && idx < count) {
      const [page] = await out.copyPages(original, [idx]);
      out.addPage(page);
    } else {
      out.addPage(blankSize);
    }
  }
  if (out.getPageCount() === 0) throw new Error("a document needs at least one page");
  return out.save();
}

// ---- Search ------------------------------------------------------------------------

let pdfjsPromise = null;
function pdfjs() {
  if (!pdfjsPromise) {
    pdfjsPromise = import("pdfjs-dist/legacy/build/pdf.mjs");
  }
  return pdfjsPromise;
}

/**
 * Text of every page as positioned runs: `{ s, x, y, w, h }`, with x/y/w/h as
 * fractions of the page as it is shown (rotation applied, origin top-left).
 */
export async function extractPdfText(bytes) {
  const lib = await pdfjs();
  const task = lib.getDocument({
    data: new Uint8Array(bytes),
    isEvalSupported: false,
    disableFontFace: true,
    useSystemFonts: false,
    verbosity: 0,
  });
  const doc = await task.promise;
  const pages = [];
  try {
    for (let n = 1; n <= doc.numPages; n++) {
      const page = await doc.getPage(n);
      const vp = page.getViewport({ scale: 1 });
      const content = await page.getTextContent();
      const runs = [];
      for (const item of content.items) {
        if (typeof item.str !== "string") continue;
        if (item.str.length > 0) {
          const t = lib.Util.transform(vp.transform, item.transform);
          const fontH = Math.hypot(t[2], t[3]) || Math.abs(item.height) || 10;
          const scaleX = Math.hypot(t[0], t[1]) / (Math.hypot(item.transform[0], item.transform[1]) || 1);
          const w = Math.abs(item.width) * (Number.isFinite(scaleX) && scaleX > 0 ? scaleX : 1);
          runs.push({
            s: item.str,
            x: t[4] / vp.width,
            y: (t[5] - fontH * 0.85) / vp.height,
            w: w / vp.width,
            h: (fontH * 1.1) / vp.height,
          });
        }
        if (item.hasEOL) runs.push({ s: "\n" });
      }
      pages.push(runs);
      page.cleanup();
    }
  } finally {
    await task.destroy();
  }
  return pages;
}

const fold = (s) => s.toLowerCase();
const isSpace = (c) => c === " " || c === "\n" || c === "\t" || c === " ";

/**
 * Case-insensitive matches of `query` on one page's runs; runs of whitespace
 * (including line ends) match any whitespace, so a phrase broken across lines is
 * found. Each match: `{ snippet, rects: [[x, y, w, h], ...] }`.
 */
function searchRuns(runs, query, limit) {
  // One flat string, whitespace collapsed, each character remembering its run.
  let text = "";
  const at = []; // per char: [runIndex, offsetInRun] or null for an inserted space
  let lastSpace = true;
  let prev = null;
  runs.forEach((r, ri) => {
    if (r.w == null) {
      prev = null;
    } else {
      // Runs often sit side by side with no space between them: a new line, or a
      // visible gap, still reads as one.
      if (prev && !lastSpace) {
        const newLine = Math.abs(r.y - prev.y) > prev.h * 0.5;
        const gap = r.x - (prev.x + prev.w) > prev.h * 0.12;
        if (newLine || gap) {
          text += " ";
          at.push(null);
          lastSpace = true;
        }
      }
      prev = r;
    }
    for (let k = 0; k < r.s.length; k++) {
      const c = r.s[k];
      if (isSpace(c)) {
        if (!lastSpace) {
          text += " ";
          at.push(r.w != null ? [ri, k] : null);
        }
        lastSpace = true;
      } else {
        text += c;
        at.push([ri, k]);
        lastSpace = false;
      }
    }
  });
  const hay = fold(text);
  const q = fold(query.trim().replace(/\s+/g, " "));
  const out = [];
  if (!q) return out;
  let from = 0;
  while (out.length < limit) {
    const i = hay.indexOf(q, from);
    if (i < 0) break;
    const end = i + q.length;
    // Per run touched: the span of its characters inside the match.
    const spans = new Map();
    for (let k = i; k < end; k++) {
      const a = at[k];
      if (!a) continue;
      const cur = spans.get(a[0]);
      if (cur) cur[1] = a[1];
      else spans.set(a[0], [a[1], a[1]]);
    }
    const rects = [];
    for (const [ri, [a, b]] of spans) {
      const r = runs[ri];
      if (r.w == null || !r.s.length) continue;
      const per = r.w / r.s.length;
      // Character positions are estimated from the run's average width; a little
      // slack either side keeps a wide or narrow letter inside the highlight.
      rects.push([r.x + per * (a - 0.35), r.y, per * (b - a + 1.7), r.h]);
    }
    const s0 = Math.max(0, i - 50);
    const s1 = Math.min(text.length, end + 70);
    const body = text.slice(s0, s1);
    const lead = body.length - body.trimStart().length;
    const prefix = s0 > 0 ? "…" : "";
    out.push({
      snippet: prefix + body.trim() + (s1 < text.length ? "…" : ""),
      // Where the match sits in the snippet, so the app can bold it.
      start: prefix.length + (i - s0 - lead),
      length: q.length,
      rects,
    });
    from = end;
  }
  return out;
}

/** All matches in one extracted document: `[{ page, snippet, start, length, rects }]`. */
export function searchPdf(pages, query, limit = 200) {
  const hits = [];
  for (let p = 0; p < pages.length && hits.length < limit; p++) {
    for (const m of searchRuns(pages[p], query, limit - hits.length)) {
      hits.push({ page: p, ...m });
    }
  }
  return hits;
}
