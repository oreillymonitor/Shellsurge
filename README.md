# ShellSurge

<p align="center">
  <b>An Android SSH and terminal client built on <a href="https://github.com/LordOfPolls/Shellwave">Shellwave</a>.</b>
  <br>
  Adds embedded Tailscale (<code>tsnet</code>), mobile Git diff &amp; SFTP workflows, unified <code>tmux</code> scroll navigation, background command notifications, and optional on-device Gemini Nano text utilities.
  <br><br>
  Requires Android 12 (API 31) or later. GPLv3.
</p>

<p align="center">
  <a href="https://github.com/oreillymonitor/Shellsurge/releases">
    <img src="https://img.shields.io/github/v/release/oreillymonitor/Shellsurge?label=Release" alt="Latest release">
  </a>
  <a href="https://github.com/oreillymonitor/Shellsurge/actions/workflows/ci.yml">
    <img src="https://github.com/oreillymonitor/Shellsurge/actions/workflows/ci.yml/badge.svg" alt="CI build status">
  </a>
  <img src="https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white" alt="Android 12 or later">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="Licence">
  </a>
</p>

---

## Built on Shellwave

ShellSurge is an independent fork of **[Shellwave](https://github.com/LordOfPolls/Shellwave)** by **LordOfPolls**. The core SSH transport, hardware-backed Android Keystore credential vault, strict host key verification, `tmux` session resilience, port forwarding, script automation, and terminal renderer all come from upstream Shellwave.

**If you find ShellSurge useful, please support the original Shellwave project financially:**

- **[GitHub Sponsors — LordOfPolls](https://github.com/sponsors/LordOfPolls)**
- **[Shellwave on Google Play](https://play.google.com/store/apps/details?id=io.github.lordofpolls.shellwave)** (includes an optional one-time supporter tip in Settings)
- **[Shellwave on F-Droid](https://f-droid.org/packages/io.github.lordofpolls.shellwave)**
- **[Upstream Repository](https://github.com/LordOfPolls/Shellwave)**

---

## Notable Enhancements in ShellSurge

### Embedded Tailscale (`tsnet`) & Network Mobility
- **No VPN Slot Required:** Runs a userspace Tailscale node (`tailscale-bridge/`) inside the app via a local SOCKS5 tunnel, leaving Android's system VPN slot free.
- **Flexible Sign-In:** Supports interactive browser OAuth login, optional pre-auth keys, and custom Headscale control URLs.
- **MagicDNS & Tailnet Browser:** Resolves MagicDNS FQDNs and short peer hostnames directly through `tsnet` (with peer-directory IP fallback), lists your tailnet peers in a **Tailnet Devices** sheet for quick connect or host import, supports keyless Tailscale SSH (`AuthNone`), and preserves remote target ports in `known_hosts` across restarts.
- **Wi-Fi ↔ 5G Handoff & Diagnostics:** Monitors default network transitions on Android to wake reconnecting sessions immediately, update `netmon` routes, and capture startup/crash diagnostics in **Settings → Tailscale (Embedded)**.

### Mobile Git Diff, SFTP & Terminal File Links
- **Git Diff & Status Sheet:** Inspect repository branch, changed files, and color-coded diff hunks directly over SSH, jump straight into editing modified files, or draft a review note in the composer.
- **Expanded SFTP Browser & Remote Editor:** Move, copy, compress, multi-download, open-with, copy remote paths, jump straight from a folder into a terminal, navigate breadcrumb history, and edit files remotely with syntax highlighting.
- **Image Attachment:** Pick a photo via Android's photo picker to upload it via SFTP (`/tmp/shellsurge-*`) and insert the remote path into the prompt composer.
- **Tappable Terminal Paths:** Tap compiler or stacktrace file references (`path/to/File.kt:142`) to edit remotely, download, copy, or send into the composer.

### Prompt Composer, Voice Dictation & Optional Gemini Nano Utilities
- **Full-Width Prompt Composer Sheet:** Pinned Send/Enter footer above the soft keyboard, larger multi-line editor, clipboard paste, image attach, Undo transform restore, and wrapping `Templates` / CLI slash-command tabs without horizontal scrolling.
- **Voice Input & Spoken Macros:** Android speech dictation with macros that expand spoken phrases like `"slash plan"`, `"slash diff"`, `"new line"`, and `"bullet point"`.
- **Optional On-Device Text Tools:** Uses Gemini Nano locally via Android AICore on supported devices (such as Pixel 9+) with deterministic offline rule-based fallbacks everywhere else:
  - **Nano Polish:** Cleans up grammar, awkward phrasing, filler words, and multi-step bullet lists while keeping file paths, flags, and commands intact.
  - **Shell cmd:** Converts plain-English requests into a single-line POSIX shell command.
  - **Terminal Digest:** Condenses recent terminal scrollback into a short summary with error/file extraction, `[y/N]` and numbered option chips, and optional offline Android TTS read-aloud.
- **On-Device AI Settings:** Inspect active AICore model status, token window, and capabilities; trigger **Download / Warm up / Refresh**; and toggle **Full vs Fast**, **Stable vs Preview**, **Thinking mode**, and session pre-warming.

### Per-Host Startup, CLI Presets & Background Notifications
- **Per-Host Directory & Auto-Launch:** Configure an initial working directory (`cd`) and optional startup command or CLI preset (`claude`, `claude -c`, `opencode`, `codex`, `aider`, `agy`, `agy -c`, or custom) per host without breaking `tmux` session reattach.
- **Quick Response Sheet & CLI Presets:** 1-tap `y`, `n`, `1`–`4`, `Enter`, `Ctrl+C`, `Esc`, and configurable per-CLI slash commands (`Claude Code`, `OpenCode`, `Codex CLI`, `Aider`, `Antigravity CLI`).
- **Command & Prompt Alerts:** Background notifications (via idle timer plus `OSC 133`, `OSC 9`, and `OSC 777` hooks) with inline **Yes / No / Option** actions, direct **Reply**, live tab status badges, and optional auto-summary when returning to a finished tab.

### Tmux Scroll Navigation & Terminal Ergonomics
- **Unified Scroll Engine:** Drag the scrollbar or use `[ ▲10 ]` / `[ ▼10 ]` steppers smoothly across both local scrollback and `tmux` mouse-tracking copy-mode / pagers (`less`, `vim`).
- **1-Tap Return to Prompt:** Tapping `[ N↓ ]` while in `tmux` copy-mode sends `q` and returns directly to the live prompt.
- **KeyBar & Input Polish:** Uniform 48dp touch targets, flush soft-keyboard inset alignment, optional KeyBar auto-hide when a hardware keyboard is attached (with `AltGr` support), Material 3 Settings cards, and a user-initiated GitHub Releases update check.

---

## Core Security & Terminal Foundation (from Shellwave)

- **Resilient Connections:** Automatic reconnection without re-prompting for passwords; optional `tmux`-backed sessions reattach with scrollback intact.
- **Hardware-Backed Keystore:** Saved passwords and private keys encrypted with Android Keystore, with optional per-credential biometric unlock.
- **Strict Host Key Verification:** Fingerprints shown on first connect; changed keys block the connection with no blind accept-all fallback, and background script runs never auto-trust new keys.
- **Key Enrolment:** Generate keys on-device and install them directly into the host's `authorized_keys`.
- **Port Forwarding & Chaining:** Local and remote port forwarding, loopback SOCKS5 proxy, `ProxyJump` chaining, SSH agent forwarding, and `~/.ssh/config` import.
- **Scripts & Automation:** Run saved scripts from the app, widgets, app shortcuts, Quick Settings tiles, or external automation intents.
- **Terminal Engine:** Vendored `termux-app` terminal emulator supporting 256-colour and 24-bit truecolour output, wide characters, and alternate screens.
- **Zero Developer Telemetry:** No analytics, no crash-reporting SDKs, and no developer backend servers. See [PRIVACY.md](PRIVACY.md) for full details.

---

## Installation

- **Releases:** Download the latest signed APK from [GitHub Releases](https://github.com/oreillymonitor/Shellsurge/releases).
- **Package ID:** `io.github.oreillymonitor.shellsurge` (installs side-by-side with upstream Shellwave without conflict).

---

## Building

CI builds the embedded Tailscale AAR (`tailscale-bridge/`) with Go + `gomobile` and compiles the Android app with Gradle:

```bash
# 1. Build the embedded Tailscale bridge AAR (requires Go + Android NDK + gomobile)
(cd tailscale-bridge && gomobile bind -v -target=android/arm64,android/amd64 -androidapi 31 \
  -javapkg=io.github.lordofpolls.shellwave.tailscale -ldflags="-s -w" -o ../app/libs/tailscale-bridge.aar .)

# 2. Build the Android APK
./gradlew assembleFossRelease   # Signed when SHELLWAVE_RELEASE_* env vars are set
./gradlew assembleFossDebug     # Debug APK
```

To run invariant checks, unit tests, and lint:

```bash
./tools/check-invariants.sh
./gradlew testFossDebugUnitTest
./gradlew lintFossDebug
```

---

## Licence & Attribution

GPLv3 — see [LICENSE](LICENSE).

- Forked from [Shellwave](https://github.com/LordOfPolls/Shellwave) (GPLv3) by [LordOfPolls](https://github.com/sponsors/LordOfPolls).
- Terminal core vendored from [termux-app](https://github.com/termux/termux-app) under GPLv3.
- Third-party attribution and library notices live in [NOTICE](NOTICE).
