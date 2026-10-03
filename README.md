<img src="docs/logo.svg" width="88" alt="">

# Inkside

**A notebook where the AI can see what you circled.**

Write on your PDFs with a stylus, circle the part you don't understand, and ask. Inkside is
for studying and coding on Android: lecture slides, textbooks, papers and your own notes in one
place, with an AI tutor beside the page. It is made for a tablet and stylus, and your notebook
is with you on your phone too.

[![A lecture PDF with handwriting](docs/screenshots/02-document-dark.png)](docs/gallery.md)

<sub>More in the [gallery](docs/gallery.md).</sub>

## What you can do

**Write on anything**
- Mark up PDFs with pen, highlighter and typed text, page by page.
- Handwriting that feels right: palm rejection, pen buttons, snap-to-shape, effect brushes
  (rainbow, glow, sparkle, calligraphy, spray).
- Typeset maths with LaTeX next to your handwriting.
- Reorder, duplicate and add pages; export everything back to a normal PDF.
- Search the text of your PDFs, and your own handwriting.

**Ask the AI about what's on the page**
- Circle, underline or box something, then ask. The agent sees your marks, so "explain the
  blue box" or "replace the loop in the green box with a map" just works.
- A tutor that asks before it tells, with answers in LaTeX next to your document.
- Interactive visualizations the agent builds for you, saved with the project.
- Dictate instead of typing.

**Code on the same device**
- A code editor with highlighting, find and replace, and run (with a connected computer).
- Let the agent edit your files and run scripts, with the result shown next to your notes.

**Use it on all your devices**
- Do most of your work on the tablet and still have everything on your phone. Connect a
  computer once and your documents, ink and chats sync in the background; connect your phone
  (or another tablet) to the same computer and it has everything too. No accounts, no cloud service.

**Keep track of your study**
- A timer that feeds a weekly study view: when you studied, coloured by project.
- Projects, favourites and 40+ colour themes, light and dark.

Every feature has its own page, with a video to come: **[all features](docs/features/README.md)**.

## Try it out

| | |
|---|---|
| **Download the APK** | Latest build on the [Releases page](../../releases/latest). Open it on your Android device and allow installs from your browser or file manager. |
| **IzzyOnDroid** | Install and update through an F-Droid client from the IzzyOnDroid repo. *Listing requested, link coming.* |

Needs Android 11 or newer. Start writing right away; the agent is optional (step 3 below).

## Get started

1. **Install the app.** See [Try it out](#try-it-out), or [build it yourself](app/README.md).
2. **Start writing.** Open or import a PDF, or make a blank page. Everything works offline and
   your documents stay on the device.
3. **Add the agent (optional).** The AI features run through a small program on your own
   computer, using the Claude subscription or API key you already have. Start it, type its
   address into the app, done. Step by step in [Connecting a computer](docs/connecting.md).

## Good to know

- **Private by design:** your documents live on your device, and your AI credentials stay on
  your computer. There are no Inkside accounts.
- **Bring your own AI:** the agent needs a Claude subscription or API key. Without one, the
  notebook still works fully.
- **Early software:** it is built and used by one person, so expect rough edges. Bug reports
  and ideas are welcome as [issues](../../issues).

## For developers

[App](app/README.md) · [Host](host/README.md) · [Protocol](docs/protocol.md) ·
[Security](SECURITY.md) · [Releasing](RELEASING.md)

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE) for third-party software.
