# Security

## Reporting a vulnerability

Please report vulnerabilities privately through GitHub: the repository's **Security** tab →
**Report a vulnerability**. Don't open a public issue for them. You'll get an answer within a
week; fixes go into a release, with credit if you'd like it.

## What the host protects

The host (`host/`) gives devices an AI agent that reads, writes and runs things on the
computer it runs on. How far a device gets depends on how the host is set up:

| Setup | Who can connect | What they get |
|---|---|---|
| No `BRIDGE_TOKEN` (default) | Devices on the computer's own network or tailnet | Everything the computer's user account can do: the agent and scripts run unsandboxed, as that user. Meant for your own devices on your own network. |
| `BRIDGE_TOKEN` set (shared) | Anyone with the token | The workspace only. The agent's commands and scripts run in the OS sandbox (Seatbelt / bubblewrap) with writes confined to the workspace, no access to home directories, the host's files or Claude's config, no network beyond an allowlist and no secrets in their environment. File routes refuse paths outside the workspace, including through symlinks. Developer endpoints are off. |

Reports we're most interested in:

- a way for a token holder (or the agent acting for one) to read, write or run anything outside
  the workspace on a shared host, or to reach the host's credentials;
- a way to use a host without being let in (wrong network, no or wrong token), including from a
  web page in someone's browser;
- a way for content reaching the app (an agent reply, a synced document, an artifact page) to
  run code in the app or read its files.

Known limits, by design:

- Guests on a shared host share one workspace and can see and change each other's documents and
  chats. Separate people need separate hosts.
- Artifact pages (HTML visualizations the agent writes) run their scripts with the host's origin,
  so they can do what the device viewing them can.
- The sandbox is the OS's, as used by Claude Code; a flaw in it is a flaw here. For the strongest
  boundary, run a shared host as its own OS user or in a VM or container.
