# ShellSurge ⚡

<p align="center">
  <b>A modern, AI-enhanced SSH client for Android, engineered for vibecoding and remote development.</b>
  <br>
  <i>A modernized fork of Shellwave with on-device Gemini Nano intelligence, hands-free voice controls, and unified tmux navigation.</i>
  <br><br>
  Requires Android 12 (API 31) or later. GPLv3.
</p>

<p align="center">
  <a href="https://github.com/oreillymonitor/Shellwave/releases">
    <img src="https://img.shields.io/github/v/release/oreillymonitor/Shellwave?label=Release" alt="Latest release">
  </a>
  <a href="https://github.com/oreillymonitor/Shellwave/actions/workflows/ci.yml">
    <img src="https://github.com/oreillymonitor/Shellwave/actions/workflows/ci.yml/badge.svg" alt="CI build status">
  </a>
  <img src="https://img.shields.io/badge/Android-12%2B-3ddc84?logo=android&logoColor=white" alt="Android 12 or later">
  <img src="https://img.shields.io/badge/AI-Gemini%20Nano%20On--Device-4285F4?logo=google" alt="Gemini Nano On-Device">
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="Licence">
  </a>
</p>

---

## ⚡ What Makes ShellSurge Different?

**ShellSurge** builds upon the rock-solid security and cryptographic foundations of the original Shellwave, but reimagines the mobile terminal for **vibecoding with AI coding agents** (Antigravity CLI, Claude Code, Aider, OpenCode) and modern ergonomics:

### 1. 🧠 On-Device Gemini Nano Terminal Digest & Voice Loop
* **1-to-3 Sentence Digest**: Reads recent terminal scrollback directly from the buffer without screen capture or cloud roundtrips. Gives you a concise spoken verdict of what the agent did.
* **Novelty & Self-Correction Detection**: If the agent hit a build error it self-corrected or devised an unexpected workaround, Nano specifically adds a sentence highlighting the fix.
* **🔊 Audio Read-Aloud**: Tap `[ 🔊 Listen ]` to hear the summary read aloud offline via native Android Text-to-Speech (TTS).
* **🔔 Chime & 3-Second Voice Reply**: When speech finishes, an alert chime sounds and a 3-second microphone window opens. Speak *"Yes"*, *"Proceed"*, or *"No"* to instantly execute terminal commands hands-free.
* **Ambient Inline Capsule**: Automatically detects pending agent prompts (`[y/N]`) and pops a sleek, auto-dismissing card right above your keyboard with 1-tap `[ YES ]` and `[ NO ]` chips.

### 2. 🎙️ AI Voice Prompt Composer
* Multi-line prompt composer with voice dictation and on-device Gemini Nano structuring.
* Spoken macros: saying *"slash goal"* formats `/goal`, *"slash plan"* formats `/plan`, and *"bullet point"* creates clean markdown lists.

### 3. 🤖 Agent Quick Response System
* 1-tap instant responses (`[ y ]`, `[ n ]`, `[ 1 ]`, `[ 2 ]`, `[ Enter ]`, `[ Ctrl+C ]`).
* Launch shortcuts for CLI coding agents (`antigravity-cli`, `opencode`, `claude`, `aider`).
* One-tap terminal summarizer shortcut.

### 4. 📜 Unified Tmux & Local Scroll Engine
* **Full Tmux Mouse-Tracking Support**: Smoothly scroll through tmux copy-mode, `less`, or `vim` on the scrollbar track and floating stepper pills (`[ ▲10 ]`, `[ ▼10 ]`).
* **Instant Prompt Return**: In tmux, tapping `[ linesBack ↓ ]` sends `"q"` to immediately exit copy-mode and return to the live prompt.

### 5. ⌨️ Refined KeyBar & Edge-to-Edge Ergonomics
* **Uniform Circular Keys**: Every key (`Ctrl`, `Alt`, `Esc`, `Tab`, `Home`, `End`, arrow cluster, and AI tools) is sized to a consistent 48dp circular touch target.
* **Zero Wasted Space**: Eliminated the dead black inset gap above soft keyboards—the KeyBar sits flush against your keyboard's paste/suggestions row, reclaiming ~3 vertical lines of terminal output.

### 6. 🎨 Modern Structured Settings
* Beautifully reorganized settings menu grouped into structured cards: *Connection & Shell*, *Terminal Canvas*, *Navigation & KeyBar*, *Security & Vault*, and *Support*.
* Zero loss of any existing customization or advanced SSH option.

---

## 🔒 Core Security & Terminal Features (from upstream)

* **Resilient Connections**: Automatic re-connection without re-prompting for passwords; optional `tmux`-backed sessions reattach with scrollback intact.
* **Hardware-Backed Keystore**: Credentials encrypted with Android Keystore with optional per-credential biometric unlock.
* **Strict Host Key Verification**: Fingerprints displayed on first connect; changed keys strictly block connections with no blind "accept-all".
* **Key Enrolment**: Generate keys on-device and install directly into the host's `authorized_keys`.
* **Port Forwarding & Chaining**: Local and remote port forwarding, SOCKS5 dynamic proxy, ProxyJump chaining, and `~/.ssh/config` import.
* **SFTP & Remote Edit**: Built-in file browsing and remote editing with syntax highlighting.
* **Zero Telemetry**: No analytics, no crash tracking, and no external pings. All AI runs 100% on-device via Android AICore.

---

## 🚀 Installation

* **Releases**: Download the latest signed APK from [GitHub Releases](https://github.com/oreillymonitor/Shellwave/releases).
* **Package Name**: `io.github.oreillymonitor.shellsurge`

---

## 🛠️ Building

Two product flavours:

```bash
./gradlew assembleFossRelease   # FOSS build without proprietary dependencies
./gradlew assemblePlayRelease   # Adds Play Billing for optional tips
```

To run checks and unit tests:

```bash
./tools/check-invariants.sh
./gradlew testFossDebugUnitTest
./gradlew lintFossDebug
```

---

## 📜 Licence

GPLv3 — see [LICENSE](LICENSE). Terminal core vendored from termux-app under GPLv3. Third-party attribution lives in `NOTICE`.
