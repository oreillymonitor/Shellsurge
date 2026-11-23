# Privacy Policy for ShellSurge

**Last updated:** 23 November 2026

ShellSurge ("the app", forked from Shellwave) is an SSH client for Android.
This policy explains what data the app handles and, just as importantly, what it
does not.

## Summary

ShellSurge does not collect, track, or sell your personal data. It has no
developer-operated analytics, no crash-reporting SDKs, no advertising, and no
account system. All data you enter — hosts, credentials, scripts, settings —
stays on your device, encrypted at rest where applicable, unless you explicitly
export or share it yourself.

## Data the app stores

Everything below is stored **locally on your device only**, in a local
database, `SharedPreferences`, and the Android Keystore:

- **Host details** you add (hostname, port, username, nicknames, per-host
  settings such as terminal profile or colour scheme).
- **Credentials** (passwords and/or private keys) you choose to save for a
  host, encrypted using Android's hardware-backed Keystore. Access to saved
  credentials can be gated behind your device's biometric/lock-screen
  authentication.
- **Scripts and automation triggers** you create for running commands on
  your hosts.
- **App preferences**, such as key bar layouts, enabled AI CLI toggles,
  notification settings, and terminal bell preferences.

## Data the app transmits

- **SSH / SFTP traffic:** The app connects only to the hosts **you**
  configure. That network traffic goes directly from your device to the server
  you specified — it never passes through the developer or any intermediary
  service. ShellSurge has no backend server of its own.
- **Local network discovery/access:** The app requests local network access
  solely so it can reach SSH servers on your local network (e.g. a home server
  or Raspberry Pi).
- **Update checks (user-initiated):** When you tap **Check for updates** in
  Settings, ShellSurge makes a direct HTTPS request to the public GitHub
  Releases API (`https://api.github.com/repos/oreillymonitor/Shellsurge/releases/latest`)
  to compare the latest published tag against the installed app version. No
  device identifiers or user data are included.

## Voice input & on-device AI features

- **Voice dictation & voice replies (`RECORD_AUDIO`):** When you explicitly tap
  the microphone button (or enable microphone access for post-summary quick
  replies), ShellSurge uses Android's built-in `SpeechRecognizer`. Depending on
  your device, Android version, and configured system speech recognition
  provider, speech audio may be processed on-device or by your device's system
  speech service under your device vendor's terms. ShellSurge itself does not
  record, store, or upload audio.
- **On-device AI (`com.google.mlkit:genai-prompt` / Android AICore):** Prompt
  polishing and terminal digest summaries attempt to use Gemini Nano locally via
  Android AICore on supported devices (such as Pixel 9+); otherwise, ShellSurge
  falls back to deterministic rule-based text processing on-device. If your
  device's AICore service requires downloading the Gemini Nano model weights,
  that download is handled by Android AICore / Google Play Services on your
  device. Terminal text sent to AICore is processed locally on-device.

## Data you choose to export

If you use the app's config export/import feature, an export file (containing
host, script, and preference configuration — never passwords, private keys, or
passphrases) is written to storage you choose. Sharing that file is entirely
under your control.

## Permissions

- **Internet / Local network / Network state** — to establish SSH connections to
  hosts you configure, optionally probe reachability on unmetered networks, and
  perform user-initiated GitHub update checks.
- **Record audio (`RECORD_AUDIO`)** — optional, requested only when you use
  voice dictation or voice replies.
- **Foreground service** — to keep SSH sessions and automations running while
  the app is backgrounded.
- **Notifications** — to alert you about session status, terminal bells,
  command completion / response-needed prompts, and automation results.
- **Vibrate** — for haptic feedback (e.g. terminal bell).

## Data deletion

Because all app data lives only on your device, uninstalling the app (or
deleting individual hosts/credentials within it) permanently removes that data.

## Children's privacy

ShellSurge is not directed at children and does not knowingly collect personal
data from anyone.

## Changes to this policy

If this policy changes, the "Last updated" date above will be revised and the
new version published in the repository.

## Contact

Questions about this policy can be raised via
[GitHub Issues](https://github.com/oreillymonitor/Shellsurge/issues).
