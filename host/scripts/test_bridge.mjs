#!/usr/bin/env node
/**
 * Bridge smoke tests.
 *
 * Starts a bridge on a scratch port against a throwaway workspace, exercises the
 * push channel, the canvas command queue and the study store, then tears down.
 *
 *   node scripts/test_bridge.mjs          # offline checks only, no API calls
 *   node scripts/test_bridge.mjs --agent  # also drives a real Claude query (costs credit)
 */

import { spawn } from "node:child_process";
import http from "node:http";
import fsp from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isSameNetwork } from "../src/network.mjs";
import { createAuth } from "../src/auth.mjs";
import { LearningStore } from "../src/learning.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(__dirname, "..", "src", "server.mjs");
const PORT = Number(process.env.TEST_PORT || 8799);
const BASE = `http://127.0.0.1:${PORT}`;
const WITH_AGENT = process.argv.includes("--agent");

let passed = 0;
let failed = 0;

function check(name, ok, detail = "") {
  if (ok) {
    passed++;
    console.log(`  ok   ${name}`);
  } else {
    failed++;
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`);
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function json(method, route, body) {
  const res = await fetch(BASE + route, {
    method,
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  let parsed = null;
  try {
    parsed = await res.json();
  } catch {
    /* some routes return text */
  }
  return { status: res.status, body: parsed };
}

/** A request with headers fetch() will not send (Host). */
function rawRequest(port, { method = "GET", path: p = "/", headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: "127.0.0.1", port, method, path: p, headers }, (res) => {
      let body = "";
      res.on("data", (d) => (body += d));
      res.on("end", () => resolve({ status: res.statusCode, body }));
    });
    req.on("error", reject);
    req.end();
  });
}

/** Collects SSE frames in the background until aborted. */
function openEvents() {
  const ctrl = new AbortController();
  const frames = [];
  const done = (async () => {
    const res = await fetch(BASE + "/events", { signal: ctrl.signal });
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let i;
        while ((i = buf.indexOf("\n\n")) >= 0) {
          const raw = buf.slice(0, i);
          buf = buf.slice(i + 2);
          const ev = /^event: (.+)$/m.exec(raw);
          const data = /^data: (.+)$/m.exec(raw);
          if (ev && data) {
            try {
              frames.push({ event: ev[1], data: JSON.parse(data[1]) });
            } catch {
              /* ignore */
            }
          }
        }
      }
    } catch {
      /* aborted */
    }
  })();
  return { frames, close: () => ctrl.abort(), done };
}

async function main() {
  const ws = await fsp.mkdtemp(path.join(os.tmpdir(), "cc-bridge-test-"));
  console.log(`workspace: ${ws}\n`);

  const server = spawn(process.execPath, [SERVER], {
    env: {
      ...process.env,
      PORT: String(PORT),
      WORKSPACE_ROOT: ws,
      BRIDGE_LOG_DIR: path.join(ws, ".logs"),
      BRIDGE_TOKEN: "",
      INKSIDE_IMPROVE: "1",
      INKSIDE_STATE_DIR: path.join(ws, ".host-state"),
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const logs = [];
  server.stdout.on("data", (d) => logs.push(String(d)));
  server.stderr.on("data", (d) => logs.push(String(d)));

  try {
    // Wait for boot.
    let up = false;
    for (let i = 0; i < 40; i++) {
      try {
        const r = await fetch(BASE + "/health");
        if (r.ok) {
          up = true;
          break;
        }
      } catch {
        /* not yet */
      }
      await sleep(250);
    }
    if (!up) throw new Error(`bridge did not start:\n${logs.join("")}`);

    console.log("health");
    const health = await json("GET", "/health");
    check("responds ok", health.body?.ok === true);
    check("reports per-chat runs", Array.isArray(health.body?.activeChats));
    check("reports claude backend", health.body?.backend === "claude-agent-sdk");
    check("reports improve session", health.body?.improve?.status === "idle");
    check("improve chat id fixed", health.body?.improve?.chatId === "app-improve");
    check(
      "reports the active provider",
      ["claude", "deepseek"].includes(health.body?.provider?.provider)
    );
    // The provider block travels to the tablet, so it may never carry key material.
    check(
      "provider block leaks no key material",
      !JSON.stringify(health.body?.provider || {}).includes("sk-")
    );

    console.log("\nimprove endpoints");
    const win = await json("GET", "/improve/window");
    check("improve window always open", win.body?.open === true);
    check("improve window shape", typeof win.body?.open === "boolean");
    const improveStatus = await json("GET", "/improve/status");
    check("improve status idle", improveStatus.body?.status === "idle");
    check("improve status has window", improveStatus.body?.window?.open === true);
    const followIdle = await json("POST", "/improve/follow", {});
    check("follow rejects when idle", followIdle.status === 409);
    const improveEmpty = await json("POST", "/improve/stream", { message: "" });
    check("improve stream requires message", improveEmpty.status === 400);
    // The tablet renders the user's bubble from this after a night run it never
    // sent, so it must be present even while idle.
    check("improve status carries the request", typeof improveStatus.body?.request === "string");

    console.log("\nimprove notes");
    const addNote = await json("POST", "/improve/notes", { text: "sharpen the eraser" });
    check("note queued", addNote.body?.ok === true && addNote.body?.count === 1);
    check("empty note rejected", (await json("POST", "/improve/notes", { text: "  " })).status === 400);
    const listed = await json("GET", "/improve/notes");
    const note = listed.body?.notes?.[0];
    check("note listed", note?.text === "sharpen the eraser");
    // The panel prints this stamp next to each note, so it has to stay parseable.
    check("note carries an ISO timestamp", typeof note?.at === "string" && !Number.isNaN(Date.parse(note.at)));
    const dropped = await json("POST", "/improve/notes/delete", { id: note?.id });
    check("note deleted by id", dropped.body?.count === 0);
    // Plain delete stays forgiving; a note already drained by the night run is gone.
    const droppedAgain = await json("POST", "/improve/notes/delete", { id: note?.id });
    check("plain delete of a missing note is a no-op", droppedAgain.body?.ok === true);
    // Sending is a promise: a note that is not there is an error, not a silent skip.
    // (Only ever tested with a missing id — a real one would start an agent run.)
    const sendMissing = await json("POST", "/improve/notes/delete", {
      id: "n-does-not-exist",
      send: true,
    });
    check("send of a missing note is refused", sendMissing.status === 404);

    console.log("\nprovider");
    const prov = await json("GET", "/improve/provider");
    check("provider reported", ["claude", "deepseek"].includes(prov.body?.provider));
    check("provider key status is masked", ["set", "missing"].includes(prov.body?.keyStatus));
    check("provider reports auth", typeof prov.body?.hasApiKey === "boolean");
    check(
      "provider response leaks no key material",
      !JSON.stringify(prov.body || {}).includes("sk-")
    );
    // Rejected before the script runs — never switch the real provider from a test.
    const badProvider = await json("POST", "/improve/provider", { provider: "gpt" });
    check("unknown provider rejected", badProvider.status === 400);

    console.log("\ncommand validation");
    const badOp = await json("POST", "/canvas/command", { op: "note.teleport" });
    check("unknown op rejected", badOp.status === 400);
    const disabledCreate = await json("POST", "/canvas/command", { op: "note.create", text: "nope" });
    check("note.create disabled", disabledCreate.status === 400);
    check(
      "note.create error names disable",
      String(disabledCreate.body?.error || "").includes("disabled")
    );
    const disabledImage = await json("POST", "/canvas/command", { op: "image.add", path: "a.png" });
    check("image.add disabled", disabledImage.status === 400);
    const missing = await json("POST", "/canvas/command", { op: "file.open" });
    check("missing required field rejected", missing.status === 400);
    const badField = await json("POST", "/canvas/command", { op: "file.open", path: "a.py", colour: "blue" });
    check("unknown field rejected", badField.status === 400);

    console.log("\npush channel");
    const es = openEvents();
    await sleep(400);
    check("client gets hello", es.frames.some((f) => f.event === "hello"));

    // Placement ops written into pending/ must be rejected, not pushed.
    const pending = path.join(ws, ".canvas", "pending");
    await fsp.mkdir(pending, { recursive: true });
    await fsp.writeFile(
      path.join(pending, "t1.json"),
      JSON.stringify({ op: "note.create", text: "from a file" })
    );
    await sleep(700);
    const canvasFrame = es.frames.find((f) => f.event === "canvas");
    check("note.create file does not reach client", !canvasFrame);
    const rejectedNote = await fsp.readdir(path.join(ws, ".canvas", "rejected")).catch(() => []);
    check(
      "note.create file is quarantined",
      rejectedNote.some((n) => n.includes("t1.json"))
    );

    await fsp.writeFile(
      path.join(pending, "open1.json"),
      JSON.stringify({ op: "file.open", path: "demo.py" })
    );
    await sleep(700);
    const openFrame = es.frames.find((f) => f.event === "canvas");
    check("file.open reaches client", Boolean(openFrame));
    check(
      "command carries an assigned id",
      Boolean(openFrame?.data?.commands?.[0]?.id)
    );
    const batchId = openFrame?.data?.batchId;

    const applied = await fsp.readdir(path.join(ws, ".canvas", "applied")).catch(() => []);
    check("ingested file leaves pending", applied.some((n) => n.endsWith("open1.json")));

    await fsp.writeFile(path.join(pending, "bad.json"), "{not json");
    await sleep(700);
    const rejected = await fsp.readdir(path.join(ws, ".canvas", "rejected")).catch(() => []);
    check("malformed file is quarantined", rejected.length > 0);

    console.log("\nack and replay");
    es.close();
    await sleep(200);
    const es2 = openEvents();
    await sleep(600);
    check(
      "unacked batch replays to a reconnecting client",
      es2.frames.some((f) => f.event === "canvas" && f.data?.replay === true)
    );
    const ack = await json("POST", "/canvas/ack", { batchId, ok: true });
    check("ack accepted", ack.body?.known === true);
    es2.close();
    await sleep(200);
    const es3 = openEvents();
    await sleep(600);
    const stillReplayed = es3.frames.filter(
      (f) => f.event === "canvas" && f.data?.batchId === batchId
    );
    check("acked batch no longer replays", stillReplayed.length === 0);
    es3.close();

    console.log("\ncanvas state");
    await json("POST", "/canvas/state", {
      textFields: [{ id: "n1", text: "a note", cx: 10, cy: 20 }],
      editorPath: "open.py",
      images: [{ cx: 1, cy: 2, png: "BASE64SHOULDNOTPERSIST" }],
      strokes: [{ colorName: "blue", samples: [[1, 2, 3]] }],
    });
    const stateRaw = await fsp.readFile(path.join(ws, ".canvas", "state.json"), "utf8");
    check("base64 image payload stripped", !stateRaw.includes("BASE64SHOULDNOTPERSIST"));
    check("stroke samples stripped", !stateRaw.includes("[1,2,3]") && !stateRaw.includes("[1, 2, 3]"));

    const promptRes = await fetch(BASE + "/canvas/prompt");
    const promptText = await promptRes.text();
    check("prompt lists the note by id", promptText.includes("note id=n1"));
    check("prompt lists the editor file", promptText.includes("open.py"));
    check("prompt does not invent canvas sheets", !promptText.includes("at (0,0)"));

    console.log("\nartifact serving");
    await fsp.mkdir(path.join(ws, ".artifacts"), { recursive: true });
    await fsp.writeFile(
      path.join(ws, ".artifacts", "t.html"),
      "<html><head></head><body><p>inline $x$ and display $$y$$</p></body></html>"
    );
    const artRes = await fetch(BASE + "/artifacts/t.html");
    const artHtml = await artRes.text();
    check("house stylesheet linked", artHtml.includes("/artifact-assets/artifact.css"));
    check("katex wired in", artHtml.includes("renderMathInElement"));
    // String.replace eats `$$` in the replacement, which turned the display
    // delimiter into `$` and typeset every inline `$x$` as centred display maths.
    check(
      "display delimiter survives injection",
      artHtml.includes('{ left: "$$", right: "$$", display: true }')
    );
    check(
      "inline delimiter still inline",
      artHtml.includes('{ left: "$", right: "$", display: false }')
    );
    check("page body kept", artHtml.includes("inline $x$ and display $$y$$"));
    // Relative links, so the host also works under a proxy path (https://example.com/inkside/).
    check("injected links are relative to the page",
      artHtml.includes('href="../artifact-assets/artifact.css"') && artHtml.includes('src="../katex/katex.min.js"'));
    await fsp.mkdir(path.join(ws, ".artifacts", "deep"), { recursive: true });
    await fsp.writeFile(path.join(ws, ".artifacts", "deep", "n.html"), "<html><head></head><body>n</body></html>");
    const nested = await (await fetch(BASE + "/artifacts/deep/n.html")).text();
    check("…and climb back from nested artifacts", nested.includes('href="../../artifact-assets/artifact.css"'));

    console.log("\nstudy store");
    await json("POST", "/study/reviews", {
      reviews: [
        { topic: "weak", grade: 1 },
        { topic: "weak", grade: 2 },
        { topic: "weak", grade: 5 },
        { topic: "strong", grade: 5 },
        { topic: "strong", grade: 4 },
        { topic: "strong", grade: 5 },
      ],
    });
    const stats = await json("GET", "/study/stats");
    check("reviews counted", stats.body?.totalReviews === 6);
    check("weakest topic ranked first", stats.body?.topics?.[0]?.topic === "weak");
    check("lapse rate computed", Math.abs((stats.body?.topics?.[0]?.lapseRate ?? 0) - 0.667) < 0.01);

    const preview = await json("POST", "/chat/preview", {
      message: "hi",
    });
    const prompt = preview.body?.prompt || "";
    check("prompt carries study history", prompt.includes("[Study history"));
    check("prompt carries canvas contents", prompt.includes("[Document]"));
    check("prompt forbids canvas notes", prompt.includes("do not place or edit them"));
    check("prompt does not advertise note.create", !prompt.includes("note.create"));
    check("prompt does not inject prior chat turns", !prompt.includes("[Prior turns in this chat"));
    check("weak topic surfaced, strong topic not", prompt.includes("weak") && !/- strong:/.test(prompt));

    console.log("\nexplorer file operations");
    await fsp.mkdir(path.join(ws, "proj"), { recursive: true });
    await fsp.writeFile(path.join(ws, "proj", "a.txt"), "hello", "utf8");
    const mk = await json("POST", "/fs/mkdir", { parent: "proj", name: "sub" });
    check("fs mkdir", mk.status === 200 && mk.body?.path === "proj/sub");
    const mkDup = await json("POST", "/fs/mkdir", { parent: "proj", name: "sub" });
    check("fs mkdir refuses existing", mkDup.status === 409);
    const badName = await json("POST", "/fs/mkdir", { parent: "proj", name: "../x" });
    check("fs mkdir rejects path names", badName.status === 400);
    const cp1 = await json("POST", "/fs/copy", { from: "proj/a.txt", toDir: "proj" });
    check("fs copy next to itself gets a copy name", cp1.body?.path === "proj/a copy.txt");
    const mv = await json("POST", "/fs/move", { from: "proj/a copy.txt", toDir: "proj/sub" });
    check("fs move into folder", mv.status === 200 && mv.body?.path === "proj/sub/a copy.txt");
    const intoSelf = await json("POST", "/fs/move", { from: "proj/sub", toDir: "proj/sub" });
    check("fs move refuses folder into itself", intoSelf.status === 400);
    const rn = await json("POST", "/fs/rename", { path: "proj/sub/a copy.txt", name: "b.txt" });
    check("fs rename", rn.status === 200 && rn.body?.path === "proj/sub/b.txt");
    const rnClash = await json("POST", "/fs/rename", { path: "proj/sub/b.txt", name: "b.txt" });
    check("fs rename to same name is a no-op", rnClash.status === 200);
    const del = await json("POST", "/fs/delete", { path: "proj/sub" });
    let gone = false;
    try { await fsp.stat(path.join(ws, "proj", "sub")); } catch { gone = true; }
    check("fs delete moves to trash", del.status === 200 && gone
      && String(del.body?.trashed || "").startsWith(".trash/"));
    const rootDel = await json("POST", "/fs/delete", { path: "." });
    check("fs delete refuses the workspace root", rootDel.status === 400);
    const outside = await json("POST", "/fs/delete", { path: "../etc" });
    check("fs delete refuses paths outside the workspace", outside.status === 400);

    console.log("\ncanvas capture tool");
    const { parsePageSpec } = await import("../src/canvasTool.mjs");
    check("page spec: current by default", parsePageSpec().mode === "current");
    check("page spec: range", JSON.stringify(parsePageSpec("2-4").list) === "[2,3,4]");
    check("page spec: list dedupes", JSON.stringify(parsePageSpec("3,1,3").list) === "[3,1]");
    let specError = false;
    try { parsePageSpec("page five"); } catch { specError = true; }
    check("page spec: rejects nonsense", specError);
    const esCap = openEvents();
    await sleep(300);
    const capPromise = json("POST", "/canvas/capture", { kind: "pages", pages: "2-3" });
    let capFrame = null;
    for (let i = 0; i < 40 && !capFrame; i++) {
      capFrame = esCap.frames.find((f) => f.event === "capture");
      if (!capFrame) await sleep(100);
    }
    check("capture request reaches the tablet channel",
      capFrame?.data?.kind === "pages" && JSON.stringify(capFrame?.data?.pages?.list) === "[2,3]");
    const tinyJpeg = Buffer.from("fake-jpeg-bytes").toString("base64");
    const answer = await json("POST", "/canvas/capture-result", {
      id: capFrame?.data?.id,
      ok: true,
      document: "course/sheet.pdf",
      currentPage: 2,
      pageCount: 5,
      images: [{ page: 2, data: tinyJpeg }, { page: 3, data: tinyJpeg }],
    });
    check("tablet answer accepted", answer.status === 200);
    const capRes = await capPromise;
    check("capture resolves with the tablet's pages",
      capRes.status === 200 && capRes.body?.images?.length === 2 && capRes.body?.currentPage === 2);
    const late = await json("POST", "/canvas/capture-result", { id: capFrame?.data?.id, ok: true });
    check("a second answer for the same id is refused", late.status === 404);
    esCap.close();

    console.log("\nproject of a document");
    await fsp.mkdir(path.join(ws, "courses", "algebra", "week1"), { recursive: true });
    await fsp.writeFile(path.join(ws, "courses", "algebra", ".ccproject"), JSON.stringify({ name: "Algebra" }));
    await fsp.writeFile(path.join(ws, "courses", "algebra", "week1", "sheet.pdf"), "%PDF-1.4");
    const po = await json("GET", "/project-of?path=" + encodeURIComponent("courses/algebra/week1/sheet.pdf"));
    check("finds the enclosing project", po.body?.project === "courses/algebra" && po.body?.name === "Algebra");
    await fsp.writeFile(path.join(ws, "loose.pdf"), "%PDF-1.4");
    const none = await json("GET", "/project-of?path=loose.pdf");
    check("loose documents have no project", none.status === 200 && none.body?.project === null);

    console.log("\nupload without overwriting");
    const up1 = await json("POST", "/file/write-binary", {
      path: "proj/up.txt", base64: Buffer.from("first").toString("base64"), unique: true,
    });
    const up2 = await json("POST", "/file/write-binary", {
      path: "proj/up.txt", base64: Buffer.from("second").toString("base64"), unique: true,
    });
    const firstKept = await fsp.readFile(path.join(ws, "proj", "up.txt"), "utf8").catch(() => "");
    check("first upload keeps its name", up1.body?.path === "proj/up.txt");
    check("second upload gets a free name", up2.body?.path === "proj/up (2).txt" && firstKept === "first");

    console.log("\nsync");
    await fsp.mkdir(path.join(ws, "sync-t", "node_modules"), { recursive: true });
    await fsp.mkdir(path.join(ws, "sync-t", ".secret"), { recursive: true });
    await fsp.writeFile(path.join(ws, "sync-t", "a.txt"), "a");
    await fsp.writeFile(path.join(ws, "sync-t", "node_modules", "x.js"), "x");
    await fsp.writeFile(path.join(ws, "sync-t", ".secret", "k"), "k");
    await fsp.writeFile(path.join(ws, "sync-t", ".hidden"), "h");
    await fsp.mkdir(path.join(ws, ".inkside", "doc_states"), { recursive: true });
    await fsp.writeFile(path.join(ws, ".inkside", "doc_states", "abc.json"), "{}");
    const wantT = 1_700_000_000_000;
    await json("POST", "/file/write-binary", {
      path: "sync-t/stamped.bin", base64: Buffer.from("zz").toString("base64"), mtimeMs: wantT,
    });
    const man = await json("GET", "/sync/manifest");
    const paths = (man.body?.files || []).map((f) => f.path);
    check("the manifest lists documents", paths.includes("sync-t/a.txt"));
    check("it leaves out hidden and dependency folders",
      !paths.some((p) => p.includes("node_modules") || p.includes(".secret") || p.endsWith(".hidden")));
    check("handwriting state is mirrored too", paths.includes(".inkside/doc_states/abc.json"));
    const stamped = (man.body?.files || []).find((f) => f.path === "sync-t/stamped.bin");
    check("an upload can keep the sender's timestamp", stamped && stamped.mtimeMs === wantT);

    console.log("\nlearning mode");
    const L = await import("../src/learning.mjs");
    const lws = await fsp.mkdtemp(path.join(os.tmpdir(), "cc-learning-"));
    const lStore = new L.LearningStore(lws);
    await lStore.init();
    const tutorExplains = await lStore.recordEvidence({ concept: "Chain rule", level: "explained", source: "tutor" });
    check("tutor may record an explanation", tutorExplains.accepted && tutorExplains.level === "explained");
    const tutorClaims = await lStore.recordEvidence({ concept: "chain rule", level: "applied", source: "tutor", evidence: "I showed it" });
    check("exposure is not understanding: tutor cannot claim 'applied'", !tutorClaims.accepted);
    const noEvidence = await lStore.recordEvidence({ concept: "chain rule", level: "recalled", source: "student" });
    check("student levels need evidence", !noEvidence.accepted);
    const lEarly = await lStore.recordEvidence({ concept: "chain rule", level: "mastered", source: "student", evidence: "x" });
    check("mastered only after transferred", !lEarly.accepted);
    const lApplied = await lStore.recordEvidence({ concept: "Chain  Rule", level: "applied", source: "student", evidence: "derived sin(x^2) alone" });
    check("student evidence raises the level (same concept key)", lApplied.accepted && lApplied.level === "applied");
    const lAgain = await lStore.recordEvidence({ concept: "chain rule", level: "explained", source: "tutor" });
    check("re-explaining never lowers a shown level", lAgain.level === "applied");
    await lStore.recordEvidence({ concept: "chain rule", level: "transferred", source: "student", evidence: "used it on e^(3x)" });
    const lMastered = await lStore.recordEvidence({ concept: "chain rule", level: "mastered", source: "student", evidence: "several correct" });
    check("mastered after transferred", lMastered.accepted && lMastered.level === "mastered");
    const lFailed = await lStore.recordEvidence({ concept: "product rule", level: "not_encountered", source: "student", evidence: "could not recall" });
    check("failed recall is recorded", lFailed.accepted);

    const lGoals = await lStore.setGoals({ document: "c/sheet.pdf", goals: [
      { task: "Exercise 1", concepts: ["chain rule"] },
      { task: "Notes", policy: "reference" },
    ] });
    check("goals defined with ids and self-solve default",
      lGoals.accepted && lGoals.goals[0].id === "g1" && lGoals.goals[0].policy === "self-solve");
    const h1 = await lStore.hintStep({ document: "c/sheet.pdf", goalId: "g1", level: 1 });
    check("first hint rung accepted", h1.accepted && h1.hintLevel === 1);
    const h2noContrib = await lStore.hintStep({ document: "c/sheet.pdf", goalId: "g1", level: 2 });
    check("next rung waits for the student's own contribution", !h2noContrib.accepted);
    const lSkip = await lStore.hintStep({ document: "c/sheet.pdf", goalId: "g1", level: 5, studentContribution: "wrote f'(x)" });
    check("self-solve rungs cannot be skipped without a reason", !lSkip.accepted);
    const h2 = await lStore.hintStep({ document: "c/sheet.pdf", goalId: "g1", level: 2, studentContribution: "named the outer function" });
    check("rung 2 after a contribution", h2.accepted && h2.hintLevel === 2);
    const lDone = await lStore.completeGoal({ document: "c/sheet.pdf", goalId: "g1", by: "student", evidence: "finished it" });
    check("goal solved by the student", lDone.accepted && lDone.status === "solved_by_student");
    const regoal = await lStore.setGoals({ document: "c/sheet.pdf", goals: [{ task: "Exercise 1" }, { task: "Exercise 2" }] });
    check("re-defining goals keeps progress on unchanged tasks",
      regoal.goals.find((g) => g.task === "Exercise 1")?.hintLevel === 2);
    await lStore.notePendingDocument("c/new.pdf");
    const lCtx = L.formatLearningContext(await lStore.snapshot(), { openFile: "c/sheet.pdf" });
    check("prompt block carries policy, model and goals",
      lCtx.includes("Learning Mode is ON") && lCtx.includes("chain rule") && lCtx.includes("Exercise 2")
      && lCtx.includes("c/new.pdf"));

    // One learner model per project: a chat sees only its own project's.
    await lStore.recordEvidence({ project: "Physics", concept: "Newton's laws", level: "explained", source: "tutor" });
    await lStore.setGoals({ project: "Physics", document: "Physics/ws.pdf", goals: [{ task: "Problem 1" }] });
    const physCtx = L.formatLearningContext(await lStore.snapshot("Physics"), { openFile: "Physics/ws.pdf" });
    check("a project's prompt has its own concepts and goals",
      physCtx.includes("newton's laws") || physCtx.includes("Newton's laws"));
    check("a project's prompt has nothing from other projects",
      !physCtx.includes("- chain rule:") && !physCtx.includes("c/new.pdf") && physCtx.includes("Problem 1"));
    const rootCtx = L.formatLearningContext(await lStore.snapshot(""), { openFile: "c/sheet.pdf" });
    check("the workspace's prompt has nothing from a project", !rootCtx.includes("Newton"));
    check("the tutor's tools stay in the project too",
      !(await lStore.snapshot("Physics/")).concepts["chain rule"]
      && !!(await lStore.snapshot("/Physics")).concepts["newton's laws"]);
    const prog = L.learningProgress(await lStore.snapshot(""));
    check("progress counts topics, mastered ones and goals",
      prog.topics.total >= 2 && prog.topics.mastered === 1 && prog.goals.total === 3
      && prog.goals.solved_by_student === 1 && prog.progress > 0 && prog.progress < 1);
    const physProg = L.learningProgress(await lStore.snapshot("Physics"));
    check("progress is per project",
      physProg.topics.items.every((t) => t.name !== "chain rule" && t.name !== "Chain rule"));
    await lStore.reset("Physics");
    check("reset forgets one project only",
      Object.keys((await lStore.snapshot("Physics")).concepts).length === 0
      && !!(await lStore.snapshot("")).concepts["chain rule"]);
    await fsp.rm(lws, { recursive: true, force: true });

    // A model from before projects were kept apart is split up by project.
    const mws = await fsp.mkdtemp(path.join(os.tmpdir(), "cc-learning-"));
    await fsp.mkdir(path.join(mws, ".learning"), { recursive: true });
    await fsp.writeFile(path.join(mws, ".learning", "state.json"), JSON.stringify({
      version: 1, enabled: true,
      concepts: {
        "integrals": { name: "Integrals", level: "applied", evidence: [] },
        "loose idea": { name: "Loose idea", level: "explained", evidence: [] },
      },
      goals: { "Math/sheet.pdf": { items: [{ id: "g1", task: "Ex 1", concepts: ["Integrals"], status: "open" }] } },
      pendingDocs: ["Math/new.pdf", "other.pdf"],
    }));
    const mStore = new L.LearningStore(mws, { projectOf: async (doc) => (doc.startsWith("Math/") ? "Math" : "") });
    await mStore.init();
    const mMath = await mStore.snapshot("Math");
    const mRoot = await mStore.snapshot("");
    check("old goals move to their document's project", !!mMath.goals["Math/sheet.pdf"] && !mRoot.goals["Math/sheet.pdf"]);
    check("old concepts follow the goals that name them", !!mMath.concepts["integrals"] && !mRoot.concepts["integrals"]);
    check("other old concepts stay outside projects", !!mRoot.concepts["loose idea"] && !mMath.concepts["loose idea"]);
    check("old pending documents move too", mMath.pendingDocs.includes("Math/new.pdf") && mRoot.pendingDocs.includes("other.pdf"));
    check("the mode survives the move", mStore.isEnabled());
    await fsp.rm(mws, { recursive: true, force: true });

    // Page viewing switched off: no page tool, and the agent is told why.
    const { createCanvasMcpServer: mkCanvas } = await import("../src/canvasTool.mjs");
    const toolNames = (srv) => [...(srv.instance?._registeredTools ? Object.keys(srv.instance._registeredTools) : [])];
    const withPages = toolNames(mkCanvas({}));
    const noPages = toolNames(mkCanvas({}, { pages: false }));
    if (withPages.length) {
      check("page viewing off leaves view_pages out",
        withPages.includes("view_pages") && !noPages.includes("view_pages") && noPages.includes("where"));
    }
    const pvOff = await json("POST", "/chat/preview", { message: "check my page", allowPageView: false });
    check("page viewing off is in the prompt", (pvOff.body?.prompt || "").includes("Page viewing is OFF"));
    const pvOn = await json("POST", "/chat/preview", { message: "check my page" });
    check("page viewing is on by default", !(pvOn.body?.prompt || "").includes("Page viewing is OFF"));

    const lsOff = await json("GET", "/learning/state");
    check("learning mode off by default", lsOff.body?.enabled === false);
    const lsOn = await json("POST", "/learning/mode", { enabled: true });
    check("learning mode can be switched on", lsOn.body?.enabled === true);
    const lpv = await json("POST", "/chat/preview", { message: "help with 1a", openFile: "c/sheet.pdf" });
    check("learning block in the prompt when on", (lpv.body?.prompt || "").includes("Learning Mode is ON"));
    const lpvOff = await json("POST", "/chat/preview", { message: "hi", learningMode: false });
    check("per-request switch overrides the stored mode", !(lpvOff.body?.prompt || "").includes("Learning Mode is ON"));
    const badMode = await json("POST", "/learning/mode", { enabled: "yes" });
    check("mode needs a boolean", badMode.status === 400);
    const lReset = await json("POST", "/learning/reset", {});
    check("reset keeps the mode", lReset.body?.enabled === true && lReset.body?.concepts === 0);
    await json("POST", "/learning/mode", { enabled: false });

    console.log("\ntrash and restore");
    await fsp.writeFile(path.join(ws, "proj", "keep.txt"), "keep me", "utf8");
    await json("POST", "/fs/delete", { path: "proj/keep.txt" });
    const trash = await json("GET", "/fs/trash");
    const entry = (trash.body?.items || []).find((i) => i.original === "proj/keep.txt");
    check("trash lists the deleted file with its origin", !!entry);
    const restored = await json("POST", "/fs/restore", { name: entry?.name });
    const back = await fsp.readFile(path.join(ws, "proj", "keep.txt"), "utf8").catch(() => "");
    check("restore puts it back", restored.status === 200 && back === "keep me");
    await fsp.writeFile(path.join(ws, "proj", "twice.txt"), "one", "utf8");
    await json("POST", "/fs/delete", { path: "proj/twice.txt" });
    await fsp.writeFile(path.join(ws, "proj", "twice.txt"), "two", "utf8");
    const t2 = await json("GET", "/fs/trash");
    const e2 = (t2.body?.items || []).find((i) => i.original === "proj/twice.txt");
    const r2 = await json("POST", "/fs/restore", { name: e2?.name });
    check("restore never overwrites", r2.body?.path === "proj/twice (restored).txt");
    const badRestore = await json("POST", "/fs/restore", { name: "../../etc" });
    check("restore rejects odd names", badRestore.status === 400);

    console.log("\ncrash reports and errors");
    const crash = await json("POST", "/client/crash", {
      report: "java.lang.RuntimeException: boom\n\tat X.y(X.java:1)",
      appVersion: "test",
      device: "Pad",
    });
    check("crash report accepted", crash.status === 200 && !!crash.body?.id);
    const crashes = await json("GET", "/client/crashes");
    check("crash report listed", (crashes.body?.items || []).includes(crash.body?.id));
    const emptyCrash = await json("POST", "/client/crash", { report: "  " });
    check("empty crash report rejected", emptyCrash.status === 400);
    const badJson = await fetch(BASE + "/fs/mkdir", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{not json",
    });
    const badJsonBody = await badJson.json().catch(() => null);
    check("malformed JSON answers 400 in JSON", badJson.status === 400 && !!badJsonBody?.error);
    const unknown = await json("GET", "/definitely/not/a/route");
    check("unknown route answers 404 in JSON", unknown.status === 404 && !!unknown.body?.error);
    check("health reports a version", typeof health.body?.version === "string");
    check("auth is on, and this computer itself is trusted",
      health.body?.authRequired === true && health.body?.authenticated === true);
    check("health names the host", typeof health.body?.hostId === "string" && !!health.body?.name);

    console.log("\nlearning goals: no limit, nothing dropped");
    const lroot = await fsp.mkdtemp(path.join(os.tmpdir(), "cc-learning-"));
    const learning = new LearningStore(lroot);
    await learning.init();
    const many = Array.from({ length: 150 }, (_, i) => ({ task: `Exercise ${i + 1}`, policy: "self-solve" }));
    const first = await learning.setGoals({ document: "sheet.pdf", goals: many });
    check("150 goals are all kept (no cap of 60)", first.accepted && first.total === 150);
    await learning.completeGoal({ document: "sheet.pdf", goalId: "g3", by: "student", evidence: "solved it alone" });
    await learning.hintStep({ document: "sheet.pdf", goalId: "g4", level: 1, studentContribution: "tried" });
    const second = await learning.setGoals({
      document: "sheet.pdf",
      goals: [{ task: "Exercise 4", objective: "Updated wording" }, { task: "Exercise 151" }, { task: "Exercise 152" }],
    });
    check("a later call keeps every existing goal and adds the new ones",
      second.total === 152 && second.added === 2 && second.updated === 1);
    const snap = await learning.snapshot();
    const items = snap.goals["sheet.pdf"].items;
    check("existing goals keep their status and hint progress",
      items.find((g) => g.task === "Exercise 3").status === "solved_by_student"
      && items.find((g) => g.task === "Exercise 4").hintLevel >= 1
      && items.find((g) => g.task === "Exercise 4").objective === "Updated wording");
    check("goal ids stay unique", new Set(items.map((g) => g.id)).size === items.length);
    await fsp.rm(lroot, { recursive: true, force: true });

    console.log("\nstaying inside the workspace");
    // Outside the workspace: a folder and a file the agent might link to.
    const outsideDir = await fsp.mkdtemp(path.join(os.tmpdir(), "cc-outside-"));
    await fsp.writeFile(path.join(outsideDir, "secret.txt"), "TOP-SECRET");
    await fsp.symlink(outsideDir, path.join(ws, "linkdir"));
    await fsp.symlink(path.join(outsideDir, "secret.txt"), path.join(ws, "linkfile.txt"));
    const readLinked = await json("GET", "/file?path=" + encodeURIComponent("linkdir/secret.txt"));
    check("a file behind a symlinked folder cannot be read", readLinked.status === 400
      && !JSON.stringify(readLinked.body).includes("TOP-SECRET"));
    const readLinkFile = await json("GET", "/file?path=linkfile.txt");
    check("a symlink to an outside file cannot be read", readLinkFile.status === 400);
    const binLinked = await fetch(BASE + "/file/binary?path=linkfile.txt");
    check("…nor fetched as binary", binLinked.status === 400 && !(await binLinked.text()).includes("TOP-SECRET"));
    const writeThrough = await json("POST", "/file/write", { path: "linkfile.txt", text: "pwned" });
    check("a write through a symlink is refused", writeThrough.status === 400
      && (await fsp.readFile(path.join(outsideDir, "secret.txt"), "utf8")) === "TOP-SECRET");
    const writeInto = await json("POST", "/file/write", { path: "linkdir/new.txt", text: "x" });
    let landed = true;
    try { await fsp.stat(path.join(outsideDir, "new.txt")); } catch { landed = false; }
    check("a write into a symlinked folder is refused", writeInto.status === 400 && !landed);
    const binThrough = await json("POST", "/file/write-binary", {
      path: "linkdir/b.bin", base64: Buffer.from("x").toString("base64"),
    });
    check("a binary write into a symlinked folder is refused", binThrough.status === 400);
    const copyOut = await json("POST", "/fs/copy", { from: "linkfile.txt", toDir: "." });
    check("a symlink to outside cannot be copied in", copyOut.status === 400);
    const listLinked = await json("GET", "/files?path=linkdir");
    check("a symlinked folder cannot be listed", listLinked.status === 400);
    const pdfLinked = await json("GET", "/pdf/search?q=secret&path=linkdir");
    check("search cannot be pointed through a symlink", pdfLinked.status === 400);

    await fsp.symlink(path.join(outsideDir, "secret.txt"), path.join(ws, ".artifacts", "leak.html"));
    await fsp.symlink(path.join(outsideDir, "secret.txt"), path.join(ws, ".artifacts", "leak.txt"));
    await fsp.symlink(outsideDir, path.join(ws, ".artifacts", "leakdir"));
    const artLeak = await fetch(BASE + "/artifacts/leak.html");
    check("an artifact symlink to outside is not served (html)",
      artLeak.status === 404 && !(await artLeak.text()).includes("TOP-SECRET"));
    const artLeak2 = await fetch(BASE + "/artifacts/leak.txt");
    check("…nor as a static file", artLeak2.status === 404 && !(await artLeak2.text()).includes("TOP-SECRET"));
    const artLeak3 = await fetch(BASE + "/artifacts/leakdir/secret.txt");
    check("…nor through a symlinked folder", artLeak3.status === 404);
    const artDots = await fetch(BASE + "/artifacts/..%2f.canvas%2fstate.json");
    check("artifact paths cannot climb out", artDots.status === 404);
    await fsp.writeFile(path.join(ws, ".artifacts", "data.json"), '{"ok":1}');
    const artData = await fetch(BASE + "/artifacts/data.json");
    check("plain artifact files are still served", artData.status === 200 && (await artData.text()) === '{"ok":1}');

    const hostFile = await json("GET", "/file?path=" + encodeURIComponent(".canvas/agent-map.json"));
    check("the host's own files are not readable", hostFile.status === 400);
    const claudeSettings = await json("POST", "/file/write", { path: ".claude/settings.json", text: "{}" });
    check("no writing agent settings into the workspace", claudeSettings.status === 400);
    const mcpJson = await json("POST", "/file/write", { path: ".mcp.json", text: "{}" });
    check("no writing MCP config into the workspace", mcpJson.status === 400);
    const sessionsWrite = await json("POST", "/file/write-binary", {
      path: ".canvas/sessions/x.json", base64: Buffer.from("{}").toString("base64"),
    });
    check("no writing chat session records", sessionsWrite.status === 400);
    const inksideOk = await json("POST", "/file/write", { path: ".inkside/chats/t.json", text: "{}" });
    check("the app's own synced folder still works", inksideOk.status === 200);

    // A trash folder swapped for a symlink must not receive files.
    await fsp.writeFile(path.join(ws, "victim.txt"), "v");
    await fsp.rename(path.join(ws, ".trash"), path.join(ws, ".trash-real"));
    await fsp.symlink(outsideDir, path.join(ws, ".trash"));
    const delSwapped = await json("POST", "/fs/delete", { path: "victim.txt" });
    const outsideNames = await fsp.readdir(outsideDir);
    check("delete into a swapped trash is refused", delSwapped.status === 500
      && !outsideNames.some((n) => n.includes("victim")));
    await fsp.unlink(path.join(ws, ".trash"));
    await fsp.rename(path.join(ws, ".trash-real"), path.join(ws, ".trash"));

    // Host writes into its own folders never follow a planted symlink.
    const target = path.join(outsideDir, "clobber.txt");
    await fsp.writeFile(target, "ORIGINAL");
    await fsp.mkdir(path.join(ws, ".study"), { recursive: true });
    await fsp.rm(path.join(ws, ".study", "stats.json"), { force: true });
    await fsp.symlink(target, path.join(ws, ".study", "stats.json"));
    await json("POST", "/study/reviews", { reviews: [{ topic: "t", grade: 4 }] });
    const statsLst = await fsp.lstat(path.join(ws, ".study", "stats.json"));
    check("a planted symlink is replaced, not written through",
      (await fsp.readFile(target, "utf8")) === "ORIGINAL" && statsLst.isFile());
    await fsp.rename(path.join(ws, ".study", "reviews.jsonl"), path.join(ws, ".study", "reviews.bak"));
    await fsp.symlink(target, path.join(ws, ".study", "reviews.jsonl"));
    const appendThrough = await json("POST", "/study/reviews", { reviews: [{ topic: "t", grade: 4 }] });
    check("appends never follow a symlink", appendThrough.status === 500
      && (await fsp.readFile(target, "utf8")) === "ORIGINAL");
    await fsp.unlink(path.join(ws, ".study", "reviews.jsonl"));
    await fsp.rename(path.join(ws, ".study", "reviews.bak"), path.join(ws, ".study", "reviews.jsonl"));

    // A canvas command "file" that is a symlink is dropped unread.
    await fsp.writeFile(path.join(outsideDir, "cmd.json"), JSON.stringify({ op: "file.open", path: "x.py" }));
    await fsp.symlink(path.join(outsideDir, "cmd.json"), path.join(ws, ".canvas", "pending", "evil.json"));
    await sleep(900);
    const appliedNow = await fsp.readdir(path.join(ws, ".canvas", "applied"));
    check("a symlinked command file is not ingested",
      !appliedNow.some((n) => n.includes("evil")) && (await fsp.readdir(outsideDir)).includes("cmd.json"));

    const fromPage = await rawRequest(PORT, { path: "/files", headers: { Origin: "https://evil.example" } });
    check("requests from web pages are refused", fromPage.status === 403);
    const rebind = await rawRequest(PORT, { path: "/files", headers: { Host: `attacker.example:${PORT}` } });
    check("a DNS-rebinding host name is refused", rebind.status === 403);
    await fsp.rm(outsideDir, { recursive: true, force: true });

    console.log("\nsame network only");
    const ifaces = {
      en0: [{ family: "IPv4", address: "192.168.1.20", cidr: "192.168.1.20/24" }],
      utun4: [{ family: "IPv4", address: "100.64.0.10", cidr: "100.64.0.10/32" }],
    };
    check("a device on the same Wi-Fi is local", isSameNetwork("192.168.1.55", ifaces));
    check("another subnet is not", !isSameNetwork("192.168.2.5", ifaces));
    check("the internet is not", !isSameNetwork("8.8.8.8", ifaces));
    check("the same tailnet is local", isSameNetwork("100.64.0.20", ifaces));
    check("a tailnet this computer is not on is not",
      !isSameNetwork("100.64.0.20", { en0: ifaces.en0 }));
    check("loopback is local", isSameNetwork("127.0.0.1", {}));
    const guard = createAuth({ sameNetwork: (a) => a === "192.168.1.55" });
    const probe = (addr, p = "/files") => {
      let status = 200;
      const res = { setHeader() {}, status(c) { status = c; return this; }, json() { return this; } };
      let passed = false;
      guard.middleware({ method: "GET", path: p, headers: {}, socket: { remoteAddress: addr } }, res,
        () => { passed = true; });
      return passed ? 200 : status;
    };
    check("same-network request passes", probe("::ffff:192.168.1.55") === 200);
    check("other network is refused with 403", probe("203.0.113.9") === 403);
    check("health stays reachable from elsewhere", probe("203.0.113.9", "/health") === 200);
    const tokGuard = createAuth({ token: "t0k3n", sameNetwork: () => false });
    const tokProbe = (addr, headers) => {
      let passed = false, status = 200;
      const res = { setHeader() {}, status(c) { status = c; return this; }, json() { return this; } };
      tokGuard.middleware({ method: "GET", path: "/files", headers, socket: { remoteAddress: addr } }, res, () => { passed = true; });
      return passed ? 200 : status;
    };
    check("an access token works from any network", tokProbe("203.0.113.9", { authorization: "Bearer t0k3n" }) === 200);
    check("without it, even this computer's network is refused", tokProbe("192.168.1.5", {}) === 401);
    check("this computer itself needs the token too (a local proxy looks local)", tokProbe("127.0.0.1", {}) === 401);
    const loopGuard = createAuth({ token: "t0k3n", trustLoopback: true, sameNetwork: () => false });
    let loopPassed = false;
    loopGuard.middleware({ method: "GET", path: "/files", headers: {}, socket: { remoteAddress: "127.0.0.1" } },
      { setHeader() {}, status() { return this; }, json() { return this; } }, () => { loopPassed = true; });
    check("BRIDGE_TRUST_LOOPBACK=1 lets this computer in without it", loopPassed);
    const httpGuard = createAuth({ sameNetwork: () => true });
    const why = (headers) => httpGuard.refusal({ headers, socket: { remoteAddress: "192.168.1.5" } });
    check("a web page on another origin is refused", why({ host: "192.168.1.20:8787", origin: "https://evil.example" }) === "origin");
    check("an opaque origin is refused", why({ host: "192.168.1.20:8787", origin: "null" }) === "origin");
    check("the host's own origin is fine", why({ host: "192.168.1.20:8787", origin: "http://192.168.1.20:8787" }) === null);
    check("a rebinding host name is refused", why({ host: "attacker.example:8787" }) === "host");
    check("an address, localhost or a tailnet name is fine",
      why({ host: "192.168.1.20:8787" }) === null && why({ host: "localhost:8787" }) === null
      && why({ host: "box.tail1234.ts.net" }) === null);

    console.log("\nauth (token set, loopback not trusted)");
    const AUTH_PORT = PORT + 1;
    const AUTH_BASE = `http://127.0.0.1:${AUTH_PORT}`;
    const authServer = spawn(process.execPath, [SERVER], {
      env: {
        ...process.env,
        PORT: String(AUTH_PORT),
        WORKSPACE_ROOT: ws,
        BRIDGE_LOG_DIR: path.join(ws, ".logs"),
        BRIDGE_TOKEN: "s3cret-token",
        SOME_API_KEY: "must-not-reach-scripts",
        INKSIDE_STATE_DIR: path.join(ws, ".host-state-auth"),
      },
      stdio: ["ignore", "pipe", "pipe"],
    });
    try {
      let authUp = false;
      for (let i = 0; i < 40 && !authUp; i++) {
        try {
          authUp = (await fetch(AUTH_BASE + "/health")).ok;
        } catch {
          await sleep(250);
        }
      }
      check("token bridge starts", authUp);
      const h0 = await (await fetch(AUTH_BASE + "/health")).json();
      check("health without token is minimal", h0.authRequired === true && h0.authenticated === false
        && h0.workspace === undefined);
      const noTok = await fetch(AUTH_BASE + "/files?path=.");
      check("request without token is refused", noTok.status === 401);
      const wrongTok = await fetch(AUTH_BASE + "/files?path=.", {
        headers: { Authorization: "Bearer nope" },
      });
      check("wrong token is refused", wrongTok.status === 401);
      const okTok = await fetch(AUTH_BASE + "/files?path=.", {
        headers: { Authorization: "Bearer s3cret-token" },
      });
      check("bearer token is accepted", okTok.status === 200);
      const h1 = await (await fetch(AUTH_BASE + "/health", {
        headers: { Authorization: "Bearer s3cret-token" },
      })).json();
      check("health with token says it is shared, and not where things live",
        h1.authenticated === true && h1.shared === true && h1.workspace === undefined
        && h1.appRoot === undefined && Array.isArray(h1.activeChats) && h1.provider?.baseUrl === null);
      const q = await fetch(AUTH_BASE + "/files?path=.&token=s3cret-token");
      const cookie = q.headers.get("set-cookie") || "";
      check("query token is accepted and sets a cookie", q.status === 200 && cookie.includes("cc_token="));
      const viaCookie = await fetch(AUTH_BASE + "/files?path=.", {
        headers: { Cookie: cookie.split(";")[0] },
      });
      check("cookie authenticates follow-up requests", viaCookie.status === 200);
      check("the cookie covers the whole host when served at the root", /Path=\/;/.test(cookie));
      const prefixed = await fetch(AUTH_BASE + "/files?path=.&token=s3cret-token", {
        headers: { "X-Forwarded-Prefix": "/inkside" },
      });
      check("behind a proxy path the cookie stays on that path",
        /Path=\/inkside;/.test(prefixed.headers.get("set-cookie") || ""));

      console.log("\nshared host (a token means guests)");
      const tok = { Authorization: "Bearer s3cret-token", "Content-Type": "application/json" };
      const call = async (method, route, body) => {
        const r = await fetch(AUTH_BASE + route, { method, headers: tok, body: body ? JSON.stringify(body) : undefined });
        let b = null;
        try { b = await r.json(); } catch { /* not JSON */ }
        return { status: r.status, body: b };
      };
      check("Improve is gone", (await call("POST", "/improve/stream", { message: "x" })).status === 404
        && (await call("GET", "/improve/notes")).status === 404
        && (await call("POST", "/improve/notes/delete", { id: "x", send: true })).status === 404);
      check("the provider cannot be switched", (await call("POST", "/improve/provider", { provider: "claude" })).status === 403);
      check("the model cannot be switched", (await call("POST", "/agent/model", { model: "opus" })).status === 403);
      check("one guest cannot stop everyone's chats", (await call("POST", "/chat/cancel", {})).status === 400);
      check("one guest cannot wipe everyone's sessions", (await call("POST", "/agent/reset", {})).status === 400);
      check("the workspace root is not named", (await call("GET", "/workspace")).body?.root === "");
      const dictation = await fetch(AUTH_BASE + "/transcribe", { method: "POST", headers: { ...tok, "Content-Type": "audio/wav" }, body: "RIFF" });
      check("dictation is off unless chosen", dictation.status === 403);

      // Scripts run in the OS sandbox: no home directory, no writes outside, no network,
      // no secrets in the environment.
      const probe = [
        "import os, sys, json, urllib.request",
        "home, port = sys.argv[2], sys.argv[4]",
        "out = {}",
        "try:",
        "    os.listdir(home); out['readHome'] = True",
        "except Exception: out['readHome'] = False",
        "try:",
        "    open(os.path.join(home, '.inkside-sandbox-probe'), 'w').write('x'); out['writeHome'] = True",
        "except Exception: out['writeHome'] = False",
        "try:",
        "    urllib.request.urlopen('http://127.0.0.1:' + port + '/health', timeout=3); out['net'] = True",
        "except Exception: out['net'] = False",
        "out['secrets'] = [k for k in os.environ if 'TOKEN' in k or 'KEY' in k]",
        "open('probe-ok.txt', 'w').write('in workspace'); out['writeWs'] = True",
        "print(json.dumps(out))",
      ].join("\n");
      await fsp.writeFile(path.join(ws, "probe.py"), probe);
      const ran = await call("POST", "/run", { path: "probe.py", args: { home: os.homedir(), port: String(AUTH_PORT) } });
      let verdict = null;
      try { verdict = JSON.parse(String(ran.body?.stdout || "").trim().split("\n").pop()); } catch { /* below */ }
      if (!verdict) console.log(`    (run said: ${JSON.stringify(ran.body).slice(0, 400)})`);
      check("a script runs, and may write in the workspace", verdict?.writeWs === true);
      check("a script cannot read the home directory", verdict?.readHome === false);
      check("a script cannot write outside the workspace", verdict?.writeHome === false);
      check("a script cannot reach the network (not even this host)", verdict?.net === false);
      check("a script sees no secrets", Array.isArray(verdict?.secrets) && verdict.secrets.length === 0,
        JSON.stringify(verdict?.secrets));
      await fsp.rm(path.join(os.homedir(), ".inkside-sandbox-probe"), { force: true });

    } finally {
      authServer.kill("SIGTERM");
      await sleep(200);
      authServer.kill("SIGKILL");
    }

    if (WITH_AGENT) {
      console.log("\nagent round trip (live API)");
      const es4 = openEvents();
      await sleep(300);
      const r = await fetch(BASE + "/chat/stream", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          chatId: "agent-test",
          message:
            "Try to put a note on my canvas saying 'agent wrote this' via .canvas/pending/, then reply DONE.",
        }),
      });
      await r.text();
      await sleep(1500);
      check(
        "agent cannot place canvas notes",
        !es4.frames.some(
          (f) => f.event === "canvas" && JSON.stringify(f.data).includes("agent wrote this")
        )
      );
      es4.close();
    } else {
      console.log("\n(skipping live agent test; pass --agent to include it)");
    }
  } finally {
    server.kill("SIGTERM");
    await sleep(300);
    server.kill("SIGKILL");
    await fsp.rm(ws, { recursive: true, force: true });
  }

  console.log(`\n${passed} passed, ${failed} failed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
