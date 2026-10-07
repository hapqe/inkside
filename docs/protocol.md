# App ↔ host protocol

The app talks to the host over HTTP (JSON bodies, NDJSON for streamed agent replies,
server-sent events for push). This page covers connecting to a host and the file routes
the app's own `LocalWorkspace` mirrors; the agent and canvas routes are listed in
[host/README.md](../host/README.md).

## Connecting

The user pastes an access token; nothing else. A plain token belongs to the Inkside test
computer (`https://inkside.hapke.me`). A connection token, printed by a host (`npm run token`),
is `ink1.` + base64url of `{"u": [urls], "t": token}`: the app tries the URLs in order. It sends
the token as `Authorization: Bearer <token>` and asks `GET /health`:

- From a device the host lets in, it answers
  `{ok, version, hostId, name, authenticated: true, shared, …}` (a host for just its
  owner adds `workspace` and `appRoot`; a shared one does not say). The app keeps
  `hostId` (each computer's documents get their own app state), the name and the
  address.
- From anywhere else it answers only
  `{ok, version, authRequired: true, authenticated: false, reason}` —
  `reason` is `token` (no valid access token) or `origin` (a browser request from another
  site). Every other route answers `401` or `403`.

Only access tokens let a device in, from anywhere, this computer included: the owner's
(`BRIDGE_TOKEN`, or one the host made) or a tester's (`<state dir>/tokens.json`). With
`BRIDGE_TOKEN` set the host is **shared**: see
[host/README.md](../host/README.md#sharing-a-computer). `INKSIDE_FIXED_MODEL=1` (the default
when shared) makes `/agent/settings` report `fixedModel: true` and refuses changes to the model
and provider.

The host sends no CORS headers and refuses requests whose `Origin` is not its own, so web pages
cannot use it.

## Sync

`GET /sync/manifest` → `{files:[{path, size, mtimeMs}]}`: every document the tablet should
mirror (hidden folders other than `.artifacts`, dependency folders and files over 25 MB are left
out). `POST /file/write-binary` accepts `mtimeMs` to keep the sender's timestamp, so a copy is not
mistaken for an edit. Deletes go through `POST /fs/delete` (to the trash).

## Files

Paths are relative to the workspace root; `.` is the root. Anything resolving outside
it is refused, including through a symlink. Hidden names are refused too, except the folders
`.inkside` and `.artifacts` and the files `.projects.json` and `.ccproject`: the rest belong to
the host (chat sessions, agent ids, trash, learner model) or to tools (`.git`, `.claude`). A **project** is a folder holding a `.ccproject` marker (JSON `{"name"}`).
Hidden names (leading `.`) and `visualizations`, `node_modules`, `__pycache__`, `venv`
stay out of listings.

| Route | |
|---|---|
| `GET /files?path=` | Folder listing: `{path, parent, items:[{name, path, type: dir\|file, size}]}` |
| `GET /library?path=` | All Projects grid: folders, projects, PDFs |
| `GET /library/shared` | PDFs not inside any project |
| `GET /project-of?path=` | `{project, name}` of the project holding `path` |
| `POST /library/mkdir`, `/library/create-project`, `/library/move` | Library changes (not inside projects) |
| `POST /fs/mkdir`, `/fs/rename`, `/fs/move`, `/fs/copy` | Explorer changes; a copy gets "name copy.ext" when taken |
| `POST /fs/delete` | Moves to `.trash/<stamp>-name`, recorded in `.trash/.index.json` |
| `GET /fs/trash`, `POST /fs/restore` | Recently deleted; restore never overwrites |
| `GET /file?path=`, `POST /file/write` | Text files up to 2 MB |
| `GET /file/binary?path=` | Bytes, with `ETag: "<size>-<mtime>"` (`304` for `If-None-Match`) |
| `POST /file/write-binary` | `{path, base64, unique?}`; `unique` never overwrites ("name (2).pdf") |
| `POST /pdf/flatten?path=&pages=` | Body: the annotation layer PDF; answers the merged PDF |
| `POST /pdf/reorder` | `{path, order, width, height}`; old file to the trash; answers the new PDF |
| `GET /pdf/search?q=&path=` | `{files:[{path, pageCount, matches:[{page, snippet, start, length, rects}]}], total, truncated}` |

The app implements the same operations on the tablet in `LocalWorkspace` / `LocalPdf`,
so every screen behaves the same whichever workspace is open.
