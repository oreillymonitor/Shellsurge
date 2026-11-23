# ShellSurge ⚡

<p align="center">
  <b>A modern, AI-enhanced SSH client for Android, engineered for vibecoding and remote development.</b>
  <br>
  <i>A fork of <a href="https://github.com/LordOfPolls/Shellwave">Shellwave</a> with on-device Gemini Nano intelligence, voice controls, command notifications, and unified tmux navigation.</i>
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
  <img src="https://img.shields.io/badge/AI-Gemini%20Nano%20On--Device-4285F4?logo=google" alt="Gemini Nano On-Device">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="Licence">
  </a>
</p>

---

## ⚡ What Makes ShellSurge Different?

**ShellSurge** builds upon the security and cryptographic foundations of the original Shellwave, adapting the mobile terminal for **vibecoding with AI coding agents** and modern ergonomics:

### 1. 🧠 On-Device Gemini Nano Terminal Digest & Voice Loop
* **3-to-4 Sentence Digest**: Reads recent terminal scrollback directly from the buffer without screen capture or developer cloud servers. Uses Gemini Nano on AICore-compatible devices (e.g. Pixel 9+), and automatically falls back to deterministic local rule-based analysis on all other Android devices.
* **🔊 Audio Read-Aloud**: Tap **Read aloud** to hear the summary spoken offline via native Android Text-to-Speech (TTS).
* **🔔 Chime & Guarded Voice Reply**: When speech finishes (if microphone permission is granted), an alert chime sounds and a 3-second listening window opens for quick confirmations (*"Yes"*, *"Proceed"*, *"No"*, *"Option 1–4"*). Tapping the Mic button directly also lets you dictate longer instructions into the Prompt Composer for review before sending.
* **Ambient Inline Capsule**: Automatically detects pending `[y/N]` / `(y/n)` prompts and shows an inline card above your KeyBar with 1-tap `[ YES ]` and `[ NO ]` chips.

### 2. 🎙️ AI Voice Prompt Composer
* Multi-line prompt composer with Android voice dictation and on-device Gemini Nano structuring (with rule-based smart formatting fallback).
* Spoken macros: saying *"slash goal"* formats `/goal`, *"slash plan"* formats `/plan`, and *"bullet point"* creates clean markdown lists.

### 3. 🤖 Configurable Agent Quick Response System
* 1-tap instant responses (`[ y ]`, `[ n ]`, `[ 1 ]–[ 4 ]`, `[ Enter ]`, `[ Ctrl+C ]`, `[ ESC ]`) and reusable vibe directives.
* **Per-Agent Settings Toggles**: Enable only the AI coding agent CLIs you actually use in **Settings → AI Coding Agents** (`Claude Code`, `OpenCode`, `Codex CLI`, `Aider`, `Antigravity CLI`) to populate tailored launch buttons and slash command chips in the Quick Response sheet and Prompt Composer.
* **Command & Output Notifications**: Optional alerts when a long-running command or agent turn reaches **Completed** or **Response needed** while you're away from the tab (supports idle settle delay plus `OSC 133`, `OSC 9`, and `OSC 777` hooks).

### 4. 📜 Unified Tmux & Local Scroll Engine
* **Full Tmux Mouse-Tracking Support**: Smoothly scroll through tmux copy-mode, `less`, or `vim` on the scrollbar track and floating stepper pills (`[ ▲10 ]`, `[ ▼10 ]`).
* **Instant Prompt Return**: In tmux, tapping `[ linesBack ↓ ]` sends `"q"` to immediately exit copy-mode and return to the live prompt.

### 5. ⌨️ Refined KeyBar & Edge-to-Edge Ergonomics
* **Uniform Circular Keys**: Every key (`Ctrl`, `Alt`, `Esc`, `Tab`, `Home`, `End`, arrow cluster, and AI tools) is sized to a consistent 48dp circular touch target.
* **Zero Wasted Space**: Eliminated the dead black inset gap above soft keyboards—the KeyBar sits flush against your keyboard's paste/suggestions row.

### 6. 🎨 Modern Structured Settings & In-App Update Check
* Reorganized settings menu grouped into Material 3 cards, plus an in-app **Check for updates** row pointing to GitHub Releases.

---

## 🔒 Core Security & Terminal Features (from upstream Shellwave)

* **Resilient Connections**: Automatic re-connection without re-prompting for passwords; optional `tmux`-backed sessions reattach with scrollback intact.
* **Hardware-Backed Keystore**: Credentials encrypted with Android Keystore with optional per-credential biometric unlock.
* **Strict Host Key Verification**: Fingerprints displayed on first connect; changed keys strictly block connections with no blind "accept-all".
* **Key Enrolment**: Generate keys on-device and install directly into the host's `authorized_keys`.
* **Port Forwarding & Chaining**: Local and remote port forwarding, SOCKS5 dynamic proxy, ProxyJump chaining, and `~/.ssh/config` import.
* **SFTP & Remote Edit**: Built-in file browsing and remote editing with syntax highlighting.
* **Zero Developer Telemetry**: No analytics, no crash tracking, and no backend servers. See [PRIVACY.md](PRIVACY.md) for full details on network requests, voice recognition, and AICore.

---

## 🚀 Installation

* **Releases**: Download the latest signed APK from [GitHub Releases](https://github.com/oreillymonitor/Shellsurge/releases).
* **Package Name**: `io.github.oreillymonitor.shellsurge` (can be installed side-by-side with upstream Shellwave).

---

## 🛠️ Building

```bash
./gradlew assembleFossRelease   # Release APK (signed when SHELLWAVE_RELEASE_* env vars are set)
./gradlew assembleFossDebug     # Debug APK
```

To run invariant checks, unit tests, and lint:

```bash
./tools/check-invariants.sh
./gradlew testFossDebugUnitTest
./gradlew lintFossDebug
```

---

## 📜 Licence & Attribution

GPLv3 — see [LICENSE](LICENSE).

* Forked from [Shellwave](https://github.com/LordOfPolls/Shellwave) (GPLv3).
* Terminal core vendored from [termux-app](https://github.com/termux/termux-app) under GPLv3.
* Third-party attribution and library notices live in [NOTICE](NOTICE).
