# Inkside host

Runs on your computer so the Inkside tablet app can use an AI agent, run scripts, transcribe
dictation and keep documents in a workspace on the computer. The app works without it;
the host adds those features.

The agent is Claude Code (through the Claude Agent SDK), running as you on this
computer: it uses the login of the `claude` CLI you already have (`claude login` with
your subscription), or an API key. Your credentials never leave this computer and the
app never sees them.

## Run it

Needs Node.js 20+.

```bash
npm install
npm start
```

On first start the host creates `~/Inkside` (the workspace), gives itself an id and a name
(the computer's name) and prints the address to enter in the app.

## Connect a tablet

In the app: **⋮ → Connect a computer**, and type this computer's IP address (printed
when the host starts, e.g. `192.168.1.20`; add `:port` if you changed `PORT`).

The host only answers devices on a network this computer is on: the tablet's address
must be in the subnet of one of this computer's network interfaces (same Wi-Fi), or on
the same Tailscale tailnet. Anything else gets `403`. There are no accounts or codes —
anyone on your network can reach the host, so run it on networks you trust (or set an
access token, see [Sharing a computer](#sharing-a-computer)).

## Settings

All optional — in the environment or in `host/.env` (see `.env.example`).

| Variable | Default | |
|---|---|---|
| `WORKSPACE_ROOT` | `~/Inkside` | Where documents and projects live; the agent works in here. |
| `PORT` / `HOST` | `8787` / `0.0.0.0` | Where the host listens. |
| `INKSIDE_HOST_NAME` | the computer's name | Name shown in the app. |
| `INKSIDE_STATE_DIR` | `~/.config/inkside-host` | The host's id and name. |
| `INKSIDE_OPEN` | off | `1` accepts devices from any network, without a token. Only on networks you fully trust. A set `BRIDGE_TOKEN` still applies. |
| `BRIDGE_TOKEN` | — | An access token. When set, every request must carry it and the network rule no longer applies, so the host can be shared over the internet with whoever you give the token. It also makes the host **shared** (see [Sharing a computer](#sharing-a-computer)). The app has a field for it. Put TLS or Tailscale in front: the token travels in a header. |
| `INKSIDE_SHARED` | on with a token | `0`: a token, but not shared (the token holder gets everything your user account has; only if the token is yours alone). `1`: shared without a token. |
| `INKSIDE_FIXED_MODEL` | on when shared | `1`: the model and provider are yours to set; devices cannot change them (the app hides those settings). `0` lets devices pick on a shared host. |
| `INKSIDE_SANDBOX_DOMAINS` | `pypi.org,files.pythonhosted.org` | Shared host: the only hosts the agent's commands and scripts may reach. Empty: none. |
| `INKSIDE_DICTATION` | off when shared | Shared host: `1` turns dictation on. Audio is decoded by ffmpeg outside the sandbox, so it is off unless you choose it. |
| `INKSIDE_MAX_RUNS` | `3` | Shared host: agent replies running at once; more get `429`. |
| `INKSIDE_ALLOWED_HOSTS` | — | Extra host names (comma-separated) devices may use to address the host, besides IP addresses, `localhost`, the computer's name, `*.local` and `*.ts.net`. Needed only for requests let in without a token. |
| `BRIDGE_TRUST_LOOPBACK` | off | With a token: `1` lets requests from this computer in without it. Leave it off behind a reverse proxy on the same computer, where every request looks local. |
| `BRIDGE_USE_API_KEY` + `ANTHROPIC_API_KEY` | — | Use an API key instead of the `claude` login. |
| `INKSIDE_IMPROVE` | off | Developer setup: `1` enables the `/improve` endpoints (an agent that edits this repository). Never on a shared host. |
| `ADB_SERIAL` | — | Developer setup: where the Improve chat installs rebuilt APKs. |

## Run it in the background (macOS)

A LaunchAgent can keep it running: `scripts/ensure_bridge.sh` checks `/health` and
starts the host when it is down. Point a LaunchAgent at it with `StartInterval` 120,
`RunAtLoad` and `AbandonProcessGroup`. Logs go to `host/logs/bridge.log`.

## Sharing a computer

With `BRIDGE_TOKEN` set the host is **shared**: the people you give the token are guests,
who get the workspace and nothing else on the computer.

- **The agent runs in the OS sandbox** (Seatbelt on macOS, bubblewrap on Linux, through
  the Claude Agent SDK). Its commands can write only in the workspace, cannot read home
  directories, this repository, the host's state or Claude's config, reach only
  `INKSIDE_SANDBOX_DOMAINS` over the network (not this host, not your LAN), and see no
  secrets in their environment (`*KEY*`, `*TOKEN*`, `*SECRET*`, … are removed). There is no
  way to run a command outside the sandbox, and if the sandbox cannot start the reply fails
  instead of running unsandboxed.
- **Only plain tools**: Bash, Read, Write, Edit, Glob, Grep, NotebookEdit, TodoWrite and
  the host's own canvas and learning tools. No web tools (they would run outside the
  sandbox), no subagents. A hook holds the file tools to the workspace, symlinks resolved.
- **None of your Claude Code setup is loaded**: no settings, hooks, MCP servers, plugins
  or `CLAUDE.md`, neither yours nor any a guest writes into the workspace. Credentials
  must come from `claude login` or `host/.env`, not from `~/.claude/settings.json`.
- **Scripts** (`/run`) run in the same sandbox, through `@anthropic-ai/sandbox-runtime`,
  with a clean environment.
- **The file routes** refuse anything outside the workspace, including through a symlink,
  and the host's own folders (`.canvas`, `.trash`, `.learning`, …); the host never writes
  through a symlink planted in its folders.
- **Off for guests**: the `/improve` endpoints, switching model or provider, stopping or
  resetting every chat at once, anything about where the host keeps its files, and
  dictation unless `INKSIDE_DICTATION=1` (its audio decoding runs outside the sandbox).

**Over the internet**, put the host behind a TLS reverse proxy on a domain name and let it
listen only locally (`HOST=127.0.0.1`). Devices then enter the bare name (`inkside.example.com`)
and the token; the app uses HTTPS for names like that, no tailnet needed. Use a subdomain of
its own (artifact pages use root paths), and let replies stream. With nginx:

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_buffering off;            # agent replies and /events stream
    proxy_request_buffering off;
    proxy_read_timeout 3h;
    proxy_send_timeout 3h;
    client_max_body_size 200m;      # uploads, PDF export
}
```

The host may also run on another computer than the proxy, for example one reached through an
SSH reverse tunnel (`ssh -N -R 127.0.0.1:3460:127.0.0.1:8787 proxy-host`, then `proxy_pass` to
port 3460). Leave `BRIDGE_TRUST_LOOPBACK` off: through a proxy every request looks local.

On Linux, install `bubblewrap` and `socat` first (`apt install bubblewrap socat`); the host
warns at startup if they are missing.

**What sharing does not separate:** guests share one workspace. Everyone with the token
sees everyone's documents and chats, and their agents can change each other's files. For
separate people, run one host per person (a container or OS user each, each with its own
token and workspace). For the strongest boundary, run a shared host as its own OS user
or in a VM or container, so even a flaw in the sandbox reaches nothing of yours.

## Security and operations

- Only devices on this computer's network may connect (see above), or, with a token,
  devices that carry it. Unauthenticated `/health` says only that the device is not
  allowed and why.
- Web pages cannot use the host: it sends no CORS headers, refuses requests from other
  origins, and refuses requests let in without a token that name it by an unknown host
  name (DNS rebinding).
- Without a token the agent runs as you, with your permissions, and so do scripts: anyone
  who can reach the host can do what you can. Don't run it on public or shared networks;
  set a token (which makes it shared and sandboxed) if you must.
- **Crash reports** from the app are uploaded to `host/logs/crashes/` (newest 50 kept;
  `BRIDGE_LOG_DIR` moves it). Nothing is sent anywhere else.
- **Deleted files** go to `<workspace>/.trash/`; the app's *Recently deleted…* restores them.
- Errors come back as JSON with a `requestId` that matches the log; the host shuts down
  cleanly on SIGTERM/Ctrl-C.

## Learning Mode

Settings → AI → **Learning Mode** (lightning icon) turns every chat into an
adaptive tutor. The switch travels with each message (`learningMode` in
`/chat/stream`) and is also stored on the host (`POST /learning/mode`).

- **Global learner model, per-chat conversations.** What the student knows lives
  in `workspace/.learning/state.json`, shared by all chats; each chat keeps its
  own session. Concepts sit on a ladder: not_encountered → explained → recalled
  → applied → transferred → mastered. Only the student's own responses move a
  concept past "explained", and "mastered" needs "transferred" first — the
  store enforces both.
- **Learning goals vs tutor policy.** Documents get goals (what the student
  should be able to do) with a policy each: `self-solve`, `guided` or
  `reference`. PDFs uploaded or attached while the mode is on are queued for
  goal-setting.
- **Hint ladder.** Self-solve tasks go 1 ask what they know → 2 conceptual hint
  → 3 strategy → 4 partial → 5 full solution, one rung at a time, with the
  student contributing between rungs (skipping needs a reason).
- **Verification.** After an explanation or a finished task the tutor asks an
  understanding or transfer question; the answer decides the next step.

The agent works through the `learning` MCP tools (`learner_state`,
`record_evidence`, `set_goals`, `hint_step`, `complete_goal`); the policy and a
compact learner summary go into each turn's prompt while the mode is on.
`GET /learning/state` (counts), `GET /learning/model` (everything) and
`POST /learning/reset` are for the app.

## Tests

```bash
npm test                                 # offline checks (own port, scratch workspace)
node scripts/test_bridge.mjs --agent     # also drives the real agent (uses your quota)
```

## Improve (developer setup)

The app has no Improve chat any more. With `INKSIDE_IMPROVE=1` (never on a shared host) the
host keeps its `/improve` endpoints, which let an agent change this repository (`APP_ROOT`)
and, when `ADB_SERIAL` is set, rebuild the APK (`app/build.sh`) and install it. Off, they
answer `404` (except `/improve/provider`, which the app's settings use).

## Agent → canvas

Agents **cannot** place notes, images or other annotations on the canvas; ink, images
and notes are the user's. They may open the editor or a PDF by writing JSON into
`<workspace>/.canvas/pending/` (or `POST /canvas/command`):

```json
{"op":"file.open","path":"rel/path.py"}
{"op":"pdf.open","path":"notes.pdf"}
```

Ops: `file.open` (`script.add` legacy alias), `pdf.open`, `pdf.create` (`pdf.add` legacy
alias). Unknown ops or fields are rejected.

| Route | Purpose |
|---|---|
| `GET /events` | SSE push channel (canvas commands, rejections) |
| `POST /canvas/command` | Enqueue directly — the test seam for the file mechanism |
| `POST /canvas/ack` | Tablet confirms a batch was applied |
| `POST/GET /canvas/state` | Tablet reports what is on the canvas; the agent reads it |
| `GET /canvas/prompt` | Preview the canvas context injected into the prompt |
| `POST/GET /study/decks` | Card mirror from the tablet |
| `POST /study/reviews` | Append review results (SM-2 grade; `<3` counts as a lapse) |
| `GET /study/stats` | Weak-spot rollup, worst topic first |

Agent runs are tracked **per chat**, so a second conversation does not cancel the first.
