# Changelog


## 1.16.5 - 2026-11-27

### Fixed
- Restore standard Material 3 dropdown menus for all `⋮` overflow buttons across Hosts and Terminal sessions

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.4...1.16.5)

## 1.16.4 - 2026-11-27

### Improved
- Switch terminal overflow (`⋮`) menu to a styled top-right anchored dropdown popover
- Upgrade Readable Output formatting with structured Markdown blocks, reflowed prose, inline styles, diff/table cards, and a Formatted/Raw toggle
- Separate and group `http(s)://` web links vs `file://` links in Links sheet with filter pills and direct remote file editing

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.3...1.16.4)

## 1.16.3 - 2026-11-27

### New
- Add complementary File attachment upload to Prompt Composer alongside Image
- Add remote `tmux` session, window, and pane management drawer with bell/activity indicators
- Add Listening Ports scanner drawer with 1-tap SSH local forwarding and browser preview
- Add optional app-wide biometric/PIN lock, timeout selector, and `FLAG_SECURE` Recents privacy toggle
- Add pinned Settings search bar to filter preference sections

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.2...1.16.3)

## 1.16.2 - 2026-11-27

### New
- Add automatic rate-limited GitHub update banner on Hosts with a Settings toggle and per-version dismiss

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.1...1.16.2)

## 1.16.1 - 2026-11-27

### New
- Add Readable Output sheet and unify session/hosts menus into drawers

### Internal
- Guard releases with tests/lint, add debug applicationIdSuffix, ignore AGENTS.md

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.0...1.16.1)

## 1.16.0 - 2026-11-27

### New
- **Wrapped URLs, OSC 8 links, Links sheet, and OAuth localhost tunneling**: join soft-wrapped and TUI-boxed multi-line URLs across rows, capture OSC 8 hyperlinks, add subtle link underlines and selection **Open** support (including in mouse-tracking mode), list session URLs in a **Links…** bottom sheet, and offer one-tap SSH local port forwarding (`Tunnel :<port> & Open`) for OAuth `localhost` callbacks

### Documentation
- Highlight full Android keyboard support in README

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.15.2...1.16.0)

## 1.15.2 - 2026-11-24

### Improved
- **Unified Prompt Composer and Quick Response bottom sheets**: extract shared design tokens and composables (`AgentSheetComponents.kt`) so both sheets share a pinned header with top-right CLI agent switcher and close button, matching `36dp`/`10dp` top action strips, uniform `8dp`/`30dp` slash-command chips and launch pills, and a consistent pinned footer

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.15.1...1.15.2)

## 1.15.1 - 2026-11-24

### Improved
- **Unified single-shot Gemini Nano notification & digest pipeline**: compute one timeout-bounded digest per settled turn (`CachedTurnDigest`) to populate both background notifications and the in-tab digest capsule, skipping background Nano passes while actively watching a normal foreground turn complete

### Fixed
- **Notification & Nano re-alert loops**: remove the duplicate ambient `outputTick` Nano polling loop in `SessionsScreen`, disarm `TerminalOutputWatcher` after both `COMPLETED` and `RESPONSE_NEEDED` alerts, normalize `HH:MM(:SS)` clocks and `tmux` date/clock status bars in `screenContentSignature`, and acknowledge alerts cleanly on notification swipe-dismiss or tab focus (`ACTION_NOTIFICATION_DISMISSED` / `acknowledgeCurrentOutput`)
- **Blocking prompt classification**: prioritize `[y/N]`, `password:`, and real interactive approval selectors over idle TUI input boxes and shell prompts so numbered markdown lists and conversational sign-off questions classify as `COMPLETED` without false `Yes / No` notification buttons

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.15.0...1.15.1)

## 1.15.0 - 2026-11-24

### Improved
- **UI, copy, and ergonomics polish**:
  - **Settings**: reorder cards cleanly (`Appearance`, `Terminal & Input`, `Notifications`, `Coding Agents`, `On-Device AI`, `Tailscale`, `Hosts & Security`, …), remove the duplicate Tailscale enable switch so **Connect / Disconnect** works directly, add a **Sponsor Shellwave on GitHub** row, and tighten card descriptions
  - **Terminal menu**: group actions into labeled sections with dividers and place **Close session** at the bottom in error tint
  - **Quick Response & Digest Capsule**: replace `"vibe directives"` / `"Gemini is summarizing…"` copy with clear neutral labels, replace horizontal scrolling in `AgentDigestCapsule` with wrapping `FlowRow` chips/actions, use compact sheet drag handles, and enlarge touch targets to `32dp`–`34dp`
  - **Host & Tailnet screens**: split **Session & Startup** out of **Connection & Routing** in Host Settings, add `.imePadding()` and larger icons to Git Diff, and pair **Save** + **Connect** actions in the Tailnet device dialog

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.9...1.15.0)

## 1.14.9 - 2026-11-24

### Fixed
- **Custom home-tilde shell prompts in notifications**: match `~/...` path prefixes in `PROMPT_LINE_REGEX` for Starship/Oh-My-Zsh/Fish prompt detection

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.8...1.14.9)

## 1.14.8 - 2026-11-24

### Improved
- **Consistent command & agent notifications**: replace packet-cancelling idle timers in `TerminalOutputWatcher` with a content-stability loop (`screenContentSignature`) so full `tmux` status bar redraws never starve alerts; detect plain agent prose above idle TUI input boxes and custom Starship/Oh-My-Zsh prompts; reset notification deduplication on each new user turn so repeated builds/approvals alert every time; lower passive prompt thresholds (`12`/`16` bytes); and ensure fresh heads-up/sound re-alerting in `SessionAlerts`

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.7...1.14.8)

## 1.14.7 - 2026-11-24

### Fixed
- **Persistent `known_hosts` trust over Embedded Tailscale**: override `getPort()` and `getRemoteSocketAddress()` on `TailscaleSocks5Socket` to report the actual SSH target port (e.g., `22`) instead of the ephemeral local SOCKS5 proxy port, so trusted hosts are not re-prompted after every app restart or update

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.6...1.14.7)

## 1.14.6 - 2026-11-24

### Fixed
- **Gemini Nano `Int.MAX_VALUE` token-limit overflow**: sanitize unbounded context-window reports (such as `2147483647` on `nano-v4-fast`) and compute token/character budgets in 64-bit `Long` arithmetic so `trimTailLinesToCharBudget` never wraps negative and wipes user prompts before inference
- **Nano Polish editing scope**: clarify `REFINE_SYSTEM_INSTRUCTION` so Nano Polish refines grammar, phrasing, structure, and bullet points across any rough draft rather than only stripping spoken filler words

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.5...1.14.6)

## 1.14.5 - 2026-11-24

### Fixed
- **Gemini Nano Polish prompt echoes**: tighten `PromptRefiner` editor/command instructions and add `isPromptEcho` + `Original:`/`Rewritten:` label extraction so Gemini Nano never overwrites the draft with instruction prompt text, falling back safely to `smartClean` when output is filtered

### Improved
- **Prompt Composer bottom sheet**: replace the narrow `AlertDialog` and horizontal scrolling option bars with a full-width `ModalBottomSheet`, a larger editor, a pinned Send/Enter footer, full-width primary Voice / Nano Polish / Shell cmd controls, and wrapping `FlowRow` tabs for utility pills, prompt templates, and agent slash commands

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.4...1.14.5)

## 1.14.4 - 2026-11-24

### Fixed
- **Embedded Tailscale MagicDNS & short hostnames**: bypass Android's system DNS in `sshj` (`ShellwaveSshClient`) when dialing via `TailscaleSocketFactory` so MagicDNS FQDNs and short hostnames go unresolved straight to `tsnet`; auto-route matching known Tailnet peer names when connected and honor Auto-start for `*.ts.net` addresses; add peer-directory IP fallback in the Go bridge when a tailnet has MagicDNS disabled

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.3...1.14.4)

## 1.14.3 - 2026-11-24

### Fixed
- **Embedded Tailscale startup panic on Android**: configure sandboxed `TS_LOGS_DIR`, `TMPDIR`, `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, and `HOME` directories inside app storage before `tsnet.Server.Start()`, fixing the `logpolicy.LogsDir` panic (`"no safe place found to store log state"`) when connecting on Android

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.2...1.14.3)

## 1.14.2 - 2026-11-24

### Improved
- **Embedded Tailscale crash diagnostics & JNI safety**: capture Android `ApplicationExitInfo` native crash reports, uncaught JVM exceptions, Go panic recovery traces, and granular startup stage breadcrumbs (`go.Seq.setContext` + `tsnet` startup stages), with a **Previous crash log** card (**Copy crash log** / **Dismiss**) right inside **Settings → Tailscale (Embedded)**

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.1...1.14.2)

## 1.14.1 - 2026-11-24

### Fixed
- **Embedded Tailscale browser sign-in**: feed Android `ConnectivityManager` default route and `NetworkInterface` snapshots into Go `netmon` (fixing Android 11+ `getifaddrs` missing `IFF_UP` flags), poll `StatusWithoutPeers` alongside `WatchIPNBus` for `AuthURL` & health diagnostics, add `http`/`https` `ACTION_VIEW` manifest `<queries>`, auto-open the browser when a sign-in URL arrives on connect, and show an explicit sign-in card with **Open browser** and **Copy link** buttons

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.0...1.14.1)

## 1.14.0 - 2026-11-24

### New
- **Embedded Tailscale (`tsnet`)**: run a userspace Tailscale node directly inside ShellSurge without occupying Android's VPN slot — includes interactive OAuth sign-in, optional pre-auth key and Headscale Control URL, MagicDNS resolution, a Tailnet Devices browser (1-tap Connect or Save Host), and per-host Embedded Tailscale & keyless Tailscale SSH (`AuthNone`) options
- **Per-host startup directory & auto-launch options**: configure a startup directory (`cd`) and auto-launch CLI agent preset (`claude`, `claude -c`, `opencode`, `codex`, `aider`, `agy`, `agy -c`, or custom command) per host, safely preserving existing resilient `tmux` sessions on reattach
- **Instant Wi-Fi ↔ 5G handoff**: monitor Android default network transitions to immediately wake reconnecting sessions, proactively refresh stale non-Tailscale sessions on network switches, and notify embedded Tailscale on link changes

### Improved
- **Settings Support card**: clarify that ShellSurge is a fork of Shellwave and link directly to the original Shellwave repository

### Fixed
- **Gemini Nano digests & TUI sanitizer**: replace XML prompt tags (`<facts>`, `<turn>`, `<draft>`, `<request>`) with plain labeled prompts and add a meta-refusal fallback guard so digests never emit `"the user provided no facts or turn."`; preserve `"Follow-Ups"` prose and strip TUI ghost prompts in `TerminalTextSanitizer`

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.13.2...1.14.0)

## 1.13.2 - 2026-11-24

### Improved
- On-device AI digest: add turn-aware TUI sanitization, diff collapsing, bottom-up smart block packing (cruft filtering + compiler/runtime error anchoring), deterministic fact extraction, and an expandable structured capsule with error callouts and tappable touched-file chips
- Notifications: strip TUI box borders, input boxes, shortcut footers, and tmux status bars before classifying prompts; detect conversational agent questions and numbered approval menus reliably; passively alert on background-resumed turns; prevent tmux clock ticks from starving the idle timer; attach contextual quick-action buttons (`Yes`/`No` or top numbered options + `Reply`) with raw-mode TUI Enter timing

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.13.1...1.13.2)

## 1.13.1 - 2026-11-24

### Fixed
- Gemini Nano: eliminate few-shot prompt bleed in Prompt Polish, Shell cmd, and Terminal Digest by switching to zero-shot tag-delimited prompts and removing shared prefix caching across tasks

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.13.0...1.13.1)

## 1.13.0 - 2026-11-23

### New
- Notifications: add inline **Yes** / **No** quick actions and **Reply** (`RemoteInput`) direct input to command/agent notifications
- Terminal tabs: show live `RUNNING`, `COMPLETED`, and `RESPONSE_NEEDED` status badges on `SessionChipRail`
- Auto-digest: automatically summarize tabs that finish while away when returning to them (with a toggle in Settings)
- Agent Digest: detect numbered CLI approval menus (`1. Allow once`, `2. Allow for session`, etc.) beyond `[y/N]` and stream live Gemini Nano summaries
- Prompt Composer: add **Shell cmd** mode (natural language → POSIX command), **Undo** transform restore, **Image** photo-picker SFTP upload (`/tmp/shellsurge-*`), and agent-aware voice slash macros (`/model` vs `/models`, `/diff`, `/review`, `/clear`, `/undo`)
- Mobile Git Diff & Status sheet: inspect branch, changed files, color-coded diff hunks, jump to SFTP edit, or ask your agent to review changes
- Terminal links: tap compiler/stacktrace file paths (`path/to/File.kt:142`) to **Ask Agent** to fix, **Edit** in the remote text editor, **Download**, or **Copy**
- On-Device AI (Gemini Nano): add Settings card showing active model name, context token limit, capabilities, **Download / Warm up / Refresh** actions, **Full vs Fast** preference, **Stable vs Preview** channel, **Thinking mode**, and **Pre-warm on session open**, backed by tuned sampling, few-shot prompts, prefix caching, and token-budget trimming

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.12.0...1.13.0)

## 1.12.0 - 2026-11-23

### New
- SFTP browser: move, copy, compress, multi-download, open-with, copy path, open folder in terminal, breadcrumb following, and back navigation through visited folders
- Terminal: option to hide the key bar when a physical keyboard is attached (detected via `InputDevice` and configuration)
- Terminal: show the server's pre-login SSH banner

### Fixed
- Physical keyboard: send `AltGr` characters properly
- Navigation: leave the terminal screen when the last session closes
- File transfer: report unverified uploads as failures and truncate on download

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.11.1...1.12.0)

## 1.11.1 - 2026-11-23

### Fixed
- Add R8 ProGuard keep rules for ML Kit GenAI Prompt / Android AICore so Gemini Nano works in minified release builds
- Surface `checkStatus()` exceptions instead of masking them as uninitialized

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.11.0...1.11.1)

## 1.11.0 - 2026-11-23

### New
- Rebrand fork to ShellSurge (`io.github.oreillymonitor.shellsurge`) for side-by-side install
- Add on-device Gemini Nano terminal digest with TTS read-aloud, guarded voice reply, and rule-based fallback
- Add AI Voice Prompt Composer with spoken macros and on-device Gemini Nano prompt refinement
- Add Agent Quick Response sheet and KeyBar quick-response drawer with per-agent toggles in Settings (`Claude Code`, `OpenCode`, `Codex CLI`, `Aider`, `Antigravity CLI`)
- Add command & output notifications (`Completed` / `Response needed`) with idle settle and `OSC 133` / `9` / `777` support
- Add unified tmux mouse-tracking & local scrollback scrollbar with 10-line stepper pills
- Modernize Settings, Terminal configuration, and Host editor UI with Material 3 cards and public GitHub Releases update check

### Fixed
- Guard voice auto-listening with `RECORD_AUDIO` permission checks and prevent unconfirmed free-form speech execution
- Prevent dismissed ambient `[y/N]` prompts from immediately re-opening on subsequent output ticks
- Isolate Tasker automation intent actions/extras and remove committed debug keystore and store-only scaffolding

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.10.0...1.11.0)

## 1.10.0 - 2026-10-30

### Fixed
- Require confirmation for externally triggered shortcuts (#2)
- Keep screens inside the insets, cap wide windows and align controls
- Clearer host cards, grouping and sort affordances
- Clearer host editor copy, key pickers and discard guards
- Explain what the SSH config import does and pick its credential
- Clearer key import, copy and delete errors
- Rotation-safe editors, profile previews and clearer copy
- Clearer run targets, dialogs and result reporting

### Internal
- Update AGP, Gradle and app dependencies

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.9.1...1.10.0)

## 1.9.1 - 2026-10-24

### New
- Group, colour and reorder saved hosts
- Manage SSH keys independently of hosts
- Forward the SSH agent with per-host keys and connect-time consent
- Edit remote text files in the SFTP browser

### Fixed
- Keep the keyboard and nav bar off the remote text editor

### Internal
- Check the Play listing for drift on every publish
- Bump sshj to 0.41.2

### Documentation
- Caption and reorder the screenshots, add a feature graphic
- Add feature graphic to readme

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.8.0...1.9.1)

## 1.8.0 - 2026-10-18

### New
- Add a Nothing colour theme with the Doto display face
- Wake a host from a quick settings tile or home screen widget

### Fixed
- Give the host key dialog three minutes and drop unused BouncyCastle picnic tables
- Make FakeSharedPreferences.remove actually delete on commit
- Keyboard text suggestions are disabled in terminal view (#1)

### Documentation
- Link the Play Store and F-Droid listings from the README
- Work search keywords and missing features into the store listing

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.7.1...1.8.0)

## 1.7.1 - 2026-09-13

### New
- Add PgUp and PgDn to the default key bar
- Scroll mouse-aware apps with a drag instead of a button drag
- Move PgUp and PgDn into the arrow cluster beside the up key

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.6.1...1.7.1)

## 1.6.1 - 2026-09-06

### New
- Expose the visible terminal screen to screen readers
- List and forget trusted host keys from settings
- Browse, rename, delete and upload over SFTP from a session
- Search scrollback and log sessions to a file
- Generate ECDSA keys, honour Match blocks and attach OpenSSH certificates
- Offer the supporter donation in three tiers
- Build and publish the Play bundle from the justfile

### Fixed
- Revoke superseded host key on accepted key-change
- Add handshake read timeout to SOCKS5 forwarder
- Stop config import overwriting the default terminal profile and colour scheme
- Read the activity through LocalActivity so lint passes
- Keep SFTP browser scroll position across same-directory refreshes
- Make radio button labels tappable across the app
- Give SFTP failures a useful reason instead of OpenSSH's bare "Failure"
- Stop dropping keystrokes under rapid terminal input
- Send typed text to the selected session after a tab switch
- Let soft-keyboard autocorrect replace the word in place

### Internal
- Build, test and guard the standing invariants on every push and commit
- Freeze existing lint warnings in a baseline and fail on new ones
- Resolve a host's connection spec in one place
- Move host delete and duplicate out of MainActivity
- Split the overflow menu, script picker and split layout out of SessionsScreen
- Share the keyed form-state guard between the host and script editors
- Observe prefs changes instead of mirroring them

### Documentation
- Tweak readme

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.5.0...1.6.1)

## 1.5.0 - 2026-08-29

### New
- Let a widget tap unlock a biometric-gated host
- Move terminal settings to a dedicated screen
- Report whether a credential is biometric-gated
- Reorganise hosts form

### Fixed
- Deep-copy duplicated hosts and give Sessions an idle empty state

### Performance
- Stop polling every vsync frame for idle terminal sessions

### Internal
- Drop unused connectionCount from SupportPromptDialog

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.4.2...1.5.0)

## 1.4.2 - 2026-08-28

### Fixed
- Clamp scrollback viewport row to the live transcript when drawing
- Notification reflects host being disconnected

### Internal
- Update AGP version

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.4.1...1.4.2)

## 1.4.1 - 2026-08-26

### New
- Implement one-time supporter prompt

### Fixed
- Enable auto reconnection for Play Billing client
- Unblock a hung capture-mode command past the 120s timeout
- Keep concurrent background script runs from killing each other

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.4.0...1.4.1)

## 1.4.0 - 2026-08-26

### New
- Import a configuration export
- Keep the screen awake while a session is on screen
- Hold an arrow key on the key bar to repeat it
- Haptic feedback on key bar presses

### Fixed
- Disable quick connect on an address it cannot parse

### Internal
- A real top bar on every pushed screen

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.3.1...1.4.0)

## 1.3.1 - 2026-08-25

### Internal
- Drop the foojay-provisioned daemon JVM toolchain
- Build the release on JDK 25

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.3.0...1.3.1)

## 1.3.0 - 2026-08-25

### New
- Toast when a background trigger starts a script
- Put a capture run's output in its result notification
- Scrollable, themed widget with a header that opens the app

### Fixed
- Refresh the widget when a script is pinned or unpinned

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.2.2...1.3.0)

## 1.2.2 - 2026-08-25

### Fixed
- Keep the WorkManager InputMerger constructor R8 strips

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.2.1...1.2.2)

## 1.2.1 - 2026-08-25

### Fixed
- Missing glance view on widget

### Documentation
- Privacy.md

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.2.0...1.2.1)

## 1.2.0 - 2026-08-25

### New
- Detect a host's MAC address over SSH

### Fixed
- Show why a session failed to connect on the terminal screen

### Internal
- Write release notes from the commit history

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.1.0...1.2.0)

## 1.1.0 - 2026-08-25

### New
- Wake a host over Wake-on-LAN
- Export the app's configuration to a JSON file

### Internal
- Pin the daemon JVM with a foojay-provisioned toolchain

### Documentation
- Point to release APKs

[Every commit in this release](https://github.com/LordOfPolls/Shellwave/compare/1.0...1.1.0)

## 1.0 - 2026-08-24

### Other changes
- An Android SSH client for Android 12+
