# Connecting a computer

The Inkside app works on its own. A computer running the [Inkside host](../host/README.md) adds the
agent chat, running scripts, voice dictation and a workspace on the computer.

```
 Tablet (app)                          Computer (host)
 ┌─────────────────────┐ enter its IP  ┌──────────────────────────────┐
 │ This tablet's       │ ────────────▶ │ Inkside host (Node)          │
 │ workspace (offline) │  same network │  ├ agent: Claude Code login  │
 │                     │ ◀──────────── │  │   or API key              │
 │ Computer workspace  │      HTTP     │  ├ scripts, voice, search    │
 └─────────────────────┘               │  └ ~/Inkside (the workspace) │
                                       └──────────────────────────────┘
```

## Set it up

On the computer (needs Node.js 20+):

```bash
cd host
npm install
npm start          # prints the address to enter in the app
```

Then in the app: **⋮ → Connect a computer → My own computer**, and type the address it printed
(e.g. `192.168.1.20`). The tablet must be on the same network as the computer. The host keeps
its documents in `~/Inkside`. Details, running it as a background service and every
setting: [host/README.md](../host/README.md).

## Workspaces

Documents are **always stored on the tablet**. A connected computer keeps a **copy** of the
workspace (so the agent and scripts can work on the same files); the app syncs the two in
the background (live: within seconds; or switch **Live sync** off and tap **Sync now**). One computer
at a time; **Settings → Remote** connects and disconnects. Without a computer the agent, scripts and
dictation are unavailable and everything else works the same. Ink and chats sync with the documents;
undo history stays on each device.

Connect a computer by typing its address (same network, or the same Tailscale tailnet), or, for a
computer shared over the internet with an access token, its web address (e.g. `inkside.example.com`).

## Share your computer with people you trust

You can let friends or classmates use your host over the internet. Set an access token on the
host; they connect by typing its web address (e.g. `inkside.example.com`) in the first field and
the token in the second. Their documents stay on their own device, and the agent and scripts run
in a sandbox that sees only the shared workspace.

Things to know before you do:
- **The agent runs on your account.** Their messages use your subscription or API key, so share
  only with people you trust, and consider a key with a spending limit.
- **Everyone with a token shares one workspace.** Don't share a host that holds anything private.

Setup, tokens and the proxy are in [Sharing a computer](../host/README.md#sharing-a-computer).

## Security

Without an access token the host trusts its network: anyone who can reach it can do what your
user account can. With one it becomes a shared host whose agent and scripts are sandboxed to the
workspace — see [Sharing a computer](../host/README.md#sharing-a-computer). To report a
vulnerability, see [SECURITY.md](../SECURITY.md).

How the app and host talk: [protocol.md](protocol.md).
