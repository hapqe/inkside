<img src="docs/logo.svg" width="88" alt="">

# Inkside

A tablet notebook for learning and coding: page-based PDF documents, stylus ink and an
AI agent that can see what you circled.

Inkside has two parts:

| Part | Runs on | What it does |
|------|---------|--------------|
| [`app/`](app/README.md) | the Android tablet | Documents, PDFs, ink, text, study cards, search, export — everything, on its own. |
| [`host/`](host/README.md) | your computer (macOS, Linux, Windows) | Optional. Adds the agent chat, running scripts, voice dictation and a workspace on the computer, using the AI subscription or API key you already have there. |

**The app works without the host.** Install it and start writing: documents live on the
tablet. When you want the agent, run the host on a computer on the same network and type
its address into the app — no accounts, codes or config files.

```
 Tablet (app)                          Computer (host)
 ┌─────────────────────┐ enter its IP  ┌──────────────────────────────┐
 │ This tablet's       │ ────────────▶ │ Inkside host (Node)            │
 │ workspace (offline) │  same network │  ├ agent: Claude Code login  │
 │                     │ ◀──────────── │  │   or API key              │
 │ Computer workspace  │      HTTP     │  ├ scripts, voice, search    │
 └─────────────────────┘               │  └ ~/Inkside (the workspace)   │
                                       └──────────────────────────────┘
```

## Quick start

**Tablet:** install the APK (build it with [app/README.md](app/README.md); releases will carry it).
It starts with a *Notes* project in the tablet's own workspace.

**Computer (optional):**

```bash
cd host
npm install
npm start          # prints the address to enter in the app
```

Then in the app: **⋮ → Connect a computer → My own computer**, and type the address it printed
(e.g. `192.168.1.20`). The tablet must be on the same network as the computer. The host keeps
its documents in `~/Inkside`. Details, running it as a background service and every
setting: [host/README.md](host/README.md).

## Screenshots

[![A lecture PDF with handwriting](docs/screenshots/02-document-dark.png)](docs/gallery.md)

More in the [gallery](docs/gallery.md).

## Workspaces

Documents are **always stored on the tablet**. A connected computer keeps a **copy** of the
workspace (so the agent and scripts can work on the same files); the app syncs the two in
the background (live: within seconds; or switch **Live sync** off and tap **Sync now**). One computer
at a time; **Settings → Remote** connects and disconnects. Without a computer the agent, scripts and
dictation are unavailable and everything else works the same. Ink and chats sync with the documents;
undo history stays on each device.

Connect a computer by typing its address (same network, or the same Tailscale tailnet).

### Want to try the agent without setting up a computer?

You can ask for a **test access token**: open an issue on this repository titled
"Test access request". You'll get an address and an access token for a small test computer
run for this purpose. Enter both under **⋮ → Connect a computer** (the token goes in the
second field). Your documents stay on your device; the test computer keeps a synced copy
with limited space, a fixed model, and may be reset or removed. Its agent runs in a sandbox that
sees only that workspace. **Everyone with a test token shares the same workspace**, so other
testers can see what you sync there, and it passes through that computer and its model provider:
don't put anything private on it.

How the two talk: [docs/protocol.md](docs/protocol.md).

## Security

Without an access token the host trusts its network: anyone who can reach it can do what your
user account can. With one it becomes a shared host whose agent and scripts are sandboxed to the
workspace — see [Sharing a computer](host/README.md#sharing-a-computer). To report a
vulnerability, see [SECURITY.md](SECURITY.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party software.
