# Inkside app (Android)

Page-based PDF documents, stylus ink, text boxes, study cards and a chat with the agent
on a connected computer. Works on its own: with no computer connected, documents live on the
tablet and everything except the agent, scripts and dictation is available.

## Build and install

Needs the Android SDK (build-tools 36, platform 36) and JDK 17. No Gradle project: Gradle
is only used once, to download the libraries into `libs/`.

```bash
bash build.sh                          # → build/Inkside.apk
adb install -r build/Inkside.apk
```

Libraries (fetched by `tools/mlkit-deps`): ML Kit digital ink (handwriting search) and
PdfBox-Android (export, page reordering and PDF search on the tablet).

## Workspaces and the remote

The tablet always holds the documents: files under the app's private storage
(`files/profiles/local/workspace`), with projects, trash and file operations
(`LocalWorkspace`, PDF work in `LocalPdf`). Ink, undo history, the handwriting index and the
session live beside it (`Profiles` — one profile; layouts from earlier versions are folded
into it on first start, the replaced state is moved aside, not deleted).

A connected computer is the **remote**: `RemoteSync` keeps its workspace the same as the
tablet's. A pass compares the tablet, the computer (`GET /sync/manifest`) and what both had
after the last pass (`sync_state.json`): one side changed → copy, deleted on one side →
delete on the other (to its trash), changed on both → the newer wins and the other stays
as "name (conflict).ext". A pass that would delete most files aborts. Passes run when files
change, when the computer reports changes, every 90 s, and before a chat message or script
run is sent (`MainActivity.syncThen`). Hidden and dependency folders, files over 25 MB and
empty folders are not mirrored.

One computer is linked at a time (`PairedHosts.remote`; connecting a new one replaces the old).
Disconnecting removes it: no `bridge`, no sync. Connecting (`Computers`): by the computer's address
(`HostLink.connect`, same network, plus an access token if its owner asks for one). When it stops
answering, its other known addresses are tried. Live sync (`RemoteSync.setLive`) is a switch: on, a pass
follows each change and runs every few seconds; off, only Sync now.

## Using it

- **Stylus / eraser** draws; **finger** pans, pinch zooms.
- The editor is always one open PDF (stacked pages). Ink, images and text fields are
  annotations in app data, keyed by document path; **Export PDF** stamps them onto a copy.
  Scrolling past the last page appends a blank page.
- **Study week** (⋮ menu, or the calendar button beside the stopwatch) draws the time the
  stopwatch ran as a plain weekly schedule: a rounded block for every stretch, coloured by
  the folder of the document that was open, with the time spent per project above it and
  past weeks one tap (or swipe) away. Sessions are logged in `StudyLog`
  (`files/study_sessions.json`), per device rather than per workspace; one the app never
  got to close is recovered up to its last heartbeat.
- **Tap a selected text field** (or double-tap one) to edit it in place.
- **Code** opens in the docked editor panel: line numbers, live syntax highlighting,
  auto-indent, find/replace, undo/redo, run (run needs a computer).
- Annotations (blue box, green underline, …) are classified on the tablet and sent with
  each chat turn, so *"replace the for loop in the blue box with map"* resolves to lines.

## Code layout

`MainActivity` keeps the lifecycle, the shared views and state, the toolbar
(`buildCenter`), theming and side-panel width budgeting. Each feature area lives in its
own class that holds the activity as `act` and is a field on it: `penTools`,
`textTools`, `chatView` (chat panel views), `conversations` (sessions, sending,
streaming), `codeEditor`, `explorer`, `projects`, `documents`,
`pageOrganizer`, `pdfSearch`, `pdfExport`, `transfers`, `canvasAgent`, `canvasDrops`,
`canvasPaste`, `artifacts`, `vizImages`, `dictation`, `instantChat`, `undoScrubber`,
`favorites`, `colorPanels`, `settingsPanel`, `pageStyle`, `overflowMenu`, `zen`,
`slideshow`, `bridgeStatus`, `persistence` (session save / restore), `refreshRate` and
`computers` (connecting, switching workspaces). A controller's field initialisers run before
its constructor sets `act`, so anything there that needs the activity is assigned in the
constructor.

## Rendering notes

With a PDF open, pages are stacked in world space and drawn under annotations.
Pan/zoom uses a screen-space backdrop with a 40% overscan margin (`OVERSCAN_FRAC` in
`CodeCanvasView`) so scrolling stays smooth. Syntax highlighting runs on a background
thread past `ASYNC_HIGHLIGHT_MIN_LINES` (1200), rendering plain text until tokens land.
