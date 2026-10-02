# App ↔ host protocol

The app talks to the host over HTTP (JSON bodies, NDJSON for streamed agent replies,
server-sent events for push). This page covers connecting to a host and the file routes
the app's own `LocalWorkspace` mirrors; the agent and canvas routes are listed in
[host/README.md](../host/README.md).

## Connecting

The user types the computer's address (`192.168.1.20`, or `host:port`; the port
defaults to 8787). The app asks `GET /health`:

- From a device the host lets in, it answers
  `{ok, version, hostId, name, authenticated: true, shared, …}` (a host for just its
  owner adds `workspace` and `appRoot`; a shared one does not say). The app keeps
  `hostId` (each computer's documents get their own app state), the name and the
  address.
- From anywhere else it answers only
  `{ok, version, authRequired: true, authenticated: false, reason}` —
  `reason` is `network` (not on this computer's network), `token` (the host
  requires its access token, `BRIDGE_TOKEN`: the app sends it as `Authorization: Bearer <token>`),
  `origin` (a browser request from another site) or `host` (addressed by an unknown host name).
  Every other route answers `403` or `401`.

**Same network** means the device's IPv4 address lies in the subnet of one of the
computer's network interfaces, or both are on the same Tailscale tailnet
(100.64.0.0/10); loopback always counts.

With `BRIDGE_TOKEN` set the network does not matter: a request must carry the token, from anywhere
(this computer too, unless `BRIDGE_TRUST_LOOPBACK=1`), and the host is **shared**: see
[host/README.md](../host/README.md#sharing-a-computer). `INKSIDE_FIXED_MODEL=1` (the default
when shared) makes `/agent/settings` report `fixedModel: true` and refuses changes to the model
and provider.

The host sends no CORS headers and refuses requests whose `Origin` is not its own, so web pages
cannot use it. A request let in without a token must name the host by an IP address,
`localhost`, the computer's name, a `.local` or `.ts.net` name, or one in
`INKSIDE_ALLOWED_HOSTS` (this blocks DNS rebinding).

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
