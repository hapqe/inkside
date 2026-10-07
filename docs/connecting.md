# Connecting a computer

The Inkside app works on its own. A computer running the [Inkside host](../host/README.md) adds the
agent chat, running scripts, voice dictation and a workspace on the computer.

```
 Device (app)                          Computer (host)
 ┌─────────────────────┐ access token  ┌──────────────────────────────┐
 │ This device's       │ ────────────▶ │ Inkside host (Node)          │
 │ workspace (offline) │               │  ├ agent: Claude Code login  │
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
npm start          # prints the access token to enter in the app
```

Then in the app: **⋮ → Connect a computer**, and paste the access token it printed (`npm run
token` prints it again). The token carries where to find the computer; set `INKSIDE_PUBLIC_URL`
when it is reached through a proxy on the internet. The host keeps its documents in `~/Inkside`. Details, running it as a background service and every
setting: [host/README.md](../host/README.md).

## Workspaces

Documents are **always stored on the device**. A connected computer keeps a **copy** of the
workspace (so the agent and scripts can work on the same files); the app syncs the two in
the background (live: within seconds; or switch **Live sync** off and tap **Sync now**). One computer
at a time; **Settings → Remote** connects and disconnects. Without a computer the agent, scripts and
dictation are unavailable and everything else works the same. Ink and chats sync with the documents;
undo history stays on each device.

Connect a computer with its access token, and nothing else. Testers paste the token they were
given and reach the Inkside test computer over the internet.

## Share your computer with people you trust

You can let friends or classmates use your host over the internet. Put it behind a TLS proxy, set
`BRIDGE_TOKEN` and `INKSIDE_PUBLIC_URL`, and give each person their own token
(`npm run testers -- add <name> --url <public url>`); they paste it, and that is all. Their documents stay on their own device, and the agent and scripts run
in a sandbox that sees only the shared workspace.

Things to know before you do:
- **The agent runs on your account.** Their messages use your subscription or API key, so share
  only with people you trust, and consider a key with a spending limit.
- **Everyone with a token shares one workspace.** Don't share a host that holds anything private.

Setup, tokens and the proxy are in [Sharing a computer](../host/README.md#sharing-a-computer).

## Security

Every device needs an access token. The owner's token can do what your user account can; with
`BRIDGE_TOKEN` set the host is shared, and its agent and scripts are sandboxed to the
workspace — see [Sharing a computer](../host/README.md#sharing-a-computer). To report a
vulnerability, see [SECURITY.md](../SECURITY.md).

How the app and host talk: [protocol.md](protocol.md).
