# OpenMausDroid

Run [OpenMausBot](https://github.com/milind-soni/OpenMausBot) inside a full
Ubuntu desktop on your Android phone. OpenMausDroid bundles an Ubuntu 24.04
rootfs, [proot](https://github.com/ysdragon/proot-static) and XFCE into an
APK, starts them in a foreground service, and puts a native chat interface on
top of the harness API.

## What you get

- **Bundled Ubuntu rootfs** - extracted on first launch, no root or Termux
  required. proot provides the fake-root container (the app uid is used
  underneath).
- **OpenMausBot harness** - `openmausbot serve` on `127.0.0.1:8799`, started
  and supervised by the app. The immutable app system prompt (see
  `app/src/main/assets/prompts/system-prompt.md`) is injected into the
  harness profile before any agent file, so bots always know they run in an
  Android app environment.
- **Native chat UI** - bot list, threaded messages, approval and question
  cards answered exactly like the desktop client (permission cards map to
  allow/deny, question cards answer with the chosen text). Messages are sent
  **only** with the Send button; the keyboard Enter key inserts a newline.
- **Model and provider switching** - pick any provider instance/model
  offered by the harness, connect an Anthropic API key or base URL from
  Settings.
- **Agents, MCP and skills** - upload agent `.md` files (imported through
  the harness' markdown package import), add/remove MCP servers and install
  skills per bot, all from Settings.
- **Terminal tab** - embedded `ttyd` web terminal in a WebView.
- **Desktop tab** - embedded noVNC viewer showing the XFCE session the bot
  works in. Start up to 3 independent sessions; each gets its own display,
  VNC port (5900+n) and noVNC URL (6080+n), so multiple bots can work side
  by side.
- **Background service** - a `specialUse` foreground service with a wake
  lock keeps the environment alive until Android decides otherwise;
  a watchdog restarts the harness, terminal and sessions if they die.

## First launch

1. Allow notifications, all-files access (so the environment can see
   `/sdcard`) and ignoring battery optimizations.
2. Press **Start setup** on the Setup screen. The rootfs is extracted and
   `bootstrap.sh` installs XFCE, TigerVNC, Node.js, OpenMausBot and noVNC.
   This takes roughly 10-20 minutes and needs a network connection.
3. When the harness answers, the screen switches to Chat.

Logs stream live on the Setup screen (`Environment` from the Settings tab).

## Building

CI builds the APK (see `.github/workflows/build-apk.yml`):

1. The workflow downloads the runtime bundle into
   `app/src/main/assets/bundle/` (git-ignored):
    - `ubuntu-rootfs.bin` (ubuntu-base-24.04.5-base-arm64 rootfs, gzipped -
      renamed because aapt2 gunzips `*.gz` assets and strips the extension)
   - `proot` (ysdragon/proot-static v5.4.0, `proot-aarch64-static`)
   - `ttyd` (ttyd 1.7.7, `ttyd.aarch64`)
2. Gradle builds `assembleRelease`.

The APK is uploaded as a workflow artifact, and attached to a GitHub release
when a `v*` tag is pushed. Release signing is optional: add the repository
secrets `MAUS_KEYSTORE_B64` (base64-encoded keystore), `MAUS_KEYSTORE_PASSWORD`,
`MAUS_KEY_ALIAS` and `MAUS_KEY_PASSWORD`; without them the build is
debug-signed.

To build locally, place the same three files in
`app/src/main/assets/bundle/` and run `./gradlew assembleRelease`.

## Architecture

```
app (Kotlin + Compose)
├── core/Proot.kt       proot exec/launch helpers, binds /dev /proc /sys /sdcard /storage
├── core/Setup.kt       asset copy, rootfs extract (core/Archive.kt), bootstrap.sh
├── core/Omb.kt         OkHttp client for the harness API + /api/events SSE
├── core/Sessions.kt    XFCE/VNC session lifecycle
├── service/MausService foreground service: setup -> harness -> watchdog
└── ui/                 Chat, Bots, Terminal, Desktop, Settings, Setup
```

Inside the rootfs (installed by `app/src/main/assets/setup/`):

| script          | role                                                        |
| --------------- | ----------------------------------------------------------- |
| `bootstrap.sh`  | apt packages, Node.js, OpenMausBot, noVNC, VNC password     |
| `maus-harness`  | `openmausbot serve --port 8799 --data-dir /root/.openmausbot` |
| `maus-ttyd`     | web terminal on 127.0.0.1:7681                              |
| `maus-session`  | Xvnc + XFCE + websockify for display `:n`                   |

The guest shares the host network namespace, so every loopback port is
reachable directly from the app.

## Notes

- Android 8.0+ (minSdk 26), arm64-v8a only.
- The VNC password for local sessions is `openmaus`; all VNC/noVNC traffic
  is bound to 127.0.0.1 and never leaves the device.
- Model providers need credentials - either an API key in Settings or a
  CLI sign-in (Claude Code / Codex) run from the Terminal tab.
