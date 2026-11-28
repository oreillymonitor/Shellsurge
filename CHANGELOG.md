# Changelog


## 1.16.10 - 2026-11-28

### Fixed
- Handle angle-bracket multi-line prompts, open-circle thoughts, and scrollback scope
- Focus alerts on action needed and idle completions while suppressing mid-turn waits

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.9...1.16.10)

## 1.16.9 - 2026-11-28

### New
- Add Android Share Sheet drop, Tailscale subnet/preview/diagnostics, smarter notifications, and readable output polish

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.8...1.16.9)

## 1.16.8 - 2026-11-28

### New
- Retro CRT themes, Gogh catalog, theme importer, default deck, and typography polish

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.7...1.16.8)

## 1.16.7 - 2026-11-28

### New
- Collapsible settings, agent-first keybar, and inline prompt composer

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.6...1.16.7)

## 1.16.6 - 2026-11-28

### New
- Replace popover menus with expanding inline trays, direct icons, and inline script templates

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.5...1.16.6)

## 1.16.5 - 2026-11-28

### Fixed
- Restore standard Material 3 dropdowns for overflow menus

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.4...1.16.5)

## 1.16.4 - 2026-11-28

### New
- Anchor overflow menu, enrich readable output, and group links

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.3...1.16.4)

## 1.16.3 - 2026-11-28

### New
- Add composer file attach, tmux/ports drawers, app lock, and settings search

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.2...1.16.3)

## 1.16.2 - 2026-11-27

### New
- Add automatic rate-limited GitHub update banner

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.1...1.16.2)

## 1.16.1 - 2026-11-27

### New
- Add Readable Output sheet and unify session/hosts menus into drawers

### Internal
- Guard releases with tests/lint, add debug applicationIdSuffix, ignore AGENTS.md

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.16.0...1.16.1)

## 1.16.0 - 2026-11-27

### New
- Support wrapped URLs, OSC 8 links, Links sheet, and OAuth localhost tunneling

### Documentation
- Highlight full Android keyboard support in README

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.15.2...1.16.0)

## 1.15.2 - 2026-11-25

### Fixed
- Place modifier as first optional parameter in AgentSectionHeader

### Other changes
- Unify Prompt Composer and Quick Response bottom sheet design

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.15.1...1.15.2)

## 1.15.1 - 2026-11-25

### Fixed
- Unify single-shot Nano notification pipeline and prevent re-alert loops

### Documentation
- Add redacted app screenshots to README
- Replace old Settings screenshot with 1.15.0 Settings pages

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.15.0...1.15.1)

## 1.14.9 - 2026-11-25

### Fixed
- Match home-tilde paths in PROMPT_LINE_REGEX

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.8...1.14.9)

## 1.14.8 - 2026-11-25

### Fixed
- Stabilize output settling across TUI prose, tmux redraws, and repeated turns

### Documentation
- Refresh README, PRIVACY, and upstream Shellwave sponsorship links
- Highlight agentic coding harness support and notification improvements

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.7...1.14.8)

## 1.14.7 - 2026-11-24

### Fixed
- Report remote SSH target port on TailscaleSocks5Socket so known_hosts persists across restarts

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.6...1.14.7)

## 1.14.6 - 2026-11-24

### Fixed
- Prevent Int.MAX_VALUE token-limit overflow wiping Gemini Nano prompts

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.5...1.14.6)

## 1.14.5 - 2026-11-24

### Fixed
- Prevent Nano polish prompt echoes and redesign Prompt Composer layout

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.4...1.14.5)

## 1.14.4 - 2026-11-24

### Fixed
- Bypass Android DNS in sshj and resolve MagicDNS/peer hostnames

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.3...1.14.4)

## 1.14.3 - 2026-11-24

### Fixed
- Set TS_LOGS_DIR and sandbox env dirs before tsnet.Start

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.2...1.14.3)

## 1.14.2 - 2026-11-24

### New
- Add on-device crash diagnostics, panic recovery, and Seq.setContext

### Fixed
- Guard null Build.SUPPORTED_ABIS in JVM unit test

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.1...1.14.2)

## 1.14.1 - 2026-11-24

### Fixed
- Feed Android network interfaces to netmon and auto-open auth browser
- Use KTX String.toUri in SettingsScreen browser launcher

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.14.0...1.14.1)

## 1.14.0 - 2026-11-24

### New
- Add per-host startup directory and auto-launch options; credit Shellwave in Support
- Add instant Wi-Fi to 5G network handoff reconnect
- Embed userspace tsnet node, SOCKS5 dialer, Tailnet peer browser, and Tailscale SSH

### Fixed
- Replace XML tags with plain Nano prompts and guard against meta-refusals
- Avoid K2 safe-call DFA OOM in AddEditHostScreen and raise Gradle heap
- Replace anet with Cgo getifaddrs for Go 1.26 gomobile compatibility

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.13.2...1.14.0)

## 1.13.2 - 2026-11-24

### Other changes
- Upgrade on-device digest with turn-aware sanitizer, fact extraction, and structured capsule
- Pack digest terminal input bottom-up with cruft filtering and error anchoring
- Make agent notifications and quick replies reliable across TUI chrome and passive turns

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.13.1...1.13.2)

## 1.13.1 - 2026-11-24

### Other changes
- Eliminate few-shot prompt bleed in Gemini Nano prompts

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.13.0...1.13.1)

## 1.13.0 - 2026-11-24

### Other changes
- Add hands-free notifications, smarter Nano prompt tools, image attach, git diff, and link actions
- Fix NotificationCompat.Builder call in SessionAlerts
- Avoid overlapping URL path matches in TerminalLinkDetector and use AutoMirrored icon
- Upgrade Gemini Nano with ModelConfig tier/channel, request tuning, warmup, streaming, and Settings controls
- Use positional booleans for PromptPrefix constructor

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.12.0...1.13.0)

## 1.12.0 - 2026-11-24

### New
- Option to hide the key bar when a physical keyboard is attached
- Show the server's pre-login banner
- Detect physical keyboards through InputDevice
- Back steps through visited folders
- Copy path, open in terminal, breadcrumb follows current folder
- Move, copy, compress, multi-download and open with

### Fixed
- Report unverified uploads as failures and truncate on download (#14)
- Leave the terminal when the last session closes
- Send AltGr characters from physical keyboards

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.11.1...1.12.0)

## 1.11.1 - 2026-11-24

### Fixed
- Add R8 keep rules for ML Kit GenAI / AICore and surface checkStatus errors

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.11.0...1.11.1)

## 1.11.0 - 2026-11-23

### New
- Update GitHub support links and apply new app icon from assets/newicon.jpeg
- On-device Gemini Nano terminal summary, TTS read-back, and voice response loop (v1.10.24)

### Fixed
- Eliminate accidental pinch magnification and restore always-visible scrollbar and stepper
- Enable unified scrollbar and 10-line steppers for tmux mouse reporting and local scrollback
- Add KeyEvent import for KeyBar double-arrow scroll handler
- Remove unused brand_icon_mark resource
- Fix TerminalBuffer dimension references and update README for ShellSurge (v1.10.25)
- Align pinned AI & keyboard keys into a 2x2 grid of circular buttons (v1.10.26)
- Fix idle output fallback & Nano handling, add /usage favorite, and calibrate 2-line scroll ramping (v1.10.27)
- Align success phrasing in AgentDigestModel and test assertion (v1.10.28)
- Strip chained spoken intro fillers in smartClean

### Other changes
- Add keyboard autocorrect/voice toggle, AI prompt composer, and CI APK artifact
- Add gitleaksignore for upstream SSH key template false positive
- Fix Compose lint ModifierParameter order in TerminalInputCapture
- Use KTX SharedPreferences.edit in TerminalInputPreferences
- Ignore APK files and build-artifacts directory
- Change applicationId to io.github.lordofpolls.shellwave.fork for side-by-side install
- Add password visibility toggle and disable autocorrect on password fields
- Add voice dictation and on-device Gemini Nano prompt refiner
- Fix DisposableEffect import and simplify generateContent prompt call
- Fix FeatureStatus import path to com.google.mlkit.genai.common.FeatureStatus
- Move genai-prompt dependency to versions.toml catalog to satisfy lint
- Fix double sparkle icon, handle AICore download and add smart cleaner fallback
- Fix DownloadFailed logging and exhaustive when branch
- Configure static debug keystore to enable seamless in-place APK updates
- Implement agent quick response menu and vibe coding QoL features
- Fix TerminalOutput write argument type for raw control characters
- Enforce Password keyboard type, disable auto-capitalization, and add singleLine to password fields
- Stack Prompt Composer and Robot above regular keyboard icon in KeyBar
- Position Prompt Composer and Robot above regular keyboard icon (v1.10.6)
- Fix Compose lint ModifierParameter default value in KeyboardKey (v1.10.7)
- Add quick launch buttons for agy, agy -c, and opencode (v1.10.8)
- Add interactive terminal scrollbar with scrubbing and eliminate drag deadzone (v1.10.9)
- Fix CircleShape in TerminalScrollbar (v1.10.10)
- Calibrate scrollbar scrub speed and fix horizontal tooltip badge (v1.10.11)
- Rebrand to ShellSurge, switch resilient tmux to shellsurge, add scroll steppers, and calibrate progressive scrollbar (v1.10.12)
- Fix MachineText style font weight parameter (v1.10.13)
- Modernize Settings and Terminal configuration UI with M3 cards, segmented selectors, and sticky preview (v1.10.14)
- Fix literal prompt text in color scheme cards (v1.10.15)
- Fix scrollbar edge touch delegation, state tracking, and thumb visibility (v1.10.16)
- Fix ColumnScope DSL scope violation in SessionsScreen (v1.10.17)
- Unify KeyBar keys into circular buttons and eliminate dead gap above keyboard (v1.10.23)
- Redesign AddEditHostScreen with Material 3 SettingsGroupCards and QoL enhancements (v1.10.29)
- Compact AgentQuickResponseSheet with unified directive pills and accessible slash commands (v1.10.30)
- Add Gemini summary loading cue, increase summary verbosity to 3-4 sentences, and fix composer button padding (v1.10.31)
- Fix syntax in AgentQuickResponseSheet and bump version to v1.10.32
- Optimize Gemini Nano prompt to ultra-lean briefing and add zero-token preamble stripping (v1.10.33)
- Compact PromptComposerDialog, add agy/opencode slash switcher, and add KeyBar quick-response drawer & Enter shortcuts (v1.10.34)
- Fix Compose ModifierParameter and KTX lint issues (v1.10.35)
- Add Settings self-update release flow and terminal output notifications (v1.10.36)
- Limit output notifications to Completed and Response needed with deduplication (v1.10.37)
- Keep turn armed during intermediate output before quick-echo check (v1.10.38)
- Add optional GitHub token support for private repo updates (v1.10.39)

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

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.9.1...1.10.0)

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

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.8.0...1.9.1)

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

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.7.1...1.8.0)

## 1.7.1 - 2026-09-13

### New
- Add PgUp and PgDn to the default key bar
- Scroll mouse-aware apps with a drag instead of a button drag
- Move PgUp and PgDn into the arrow cluster beside the up key

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.6.1...1.7.1)

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

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.5.0...1.6.1)

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

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.4.2...1.5.0)

## 1.4.2 - 2026-08-28

### Fixed
- Clamp scrollback viewport row to the live transcript when drawing
- Notification reflects host being disconnected

### Internal
- Update AGP version

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.4.1...1.4.2)

## 1.4.1 - 2026-08-26

### New
- Implement one-time supporter prompt

### Fixed
- Enable auto reconnection for Play Billing client
- Unblock a hung capture-mode command past the 120s timeout
- Keep concurrent background script runs from killing each other

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.4.0...1.4.1)

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

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.3.1...1.4.0)

## 1.3.1 - 2026-08-25

### Internal
- Drop the foojay-provisioned daemon JVM toolchain
- Build the release on JDK 25

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.3.0...1.3.1)

## 1.3.0 - 2026-08-25

### New
- Toast when a background trigger starts a script
- Put a capture run's output in its result notification
- Scrollable, themed widget with a header that opens the app

### Fixed
- Refresh the widget when a script is pinned or unpinned

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.2.2...1.3.0)

## 1.2.2 - 2026-08-25

### Fixed
- Keep the WorkManager InputMerger constructor R8 strips

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.2.1...1.2.2)

## 1.2.1 - 2026-08-25

### Fixed
- Missing glance view on widget

### Documentation
- Privacy.md

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.2.0...1.2.1)

## 1.2.0 - 2026-08-25

### New
- Detect a host's MAC address over SSH

### Fixed
- Show why a session failed to connect on the terminal screen

### Internal
- Write release notes from the commit history

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.1.0...1.2.0)

## 1.1.0 - 2026-08-25

### New
- Wake a host over Wake-on-LAN
- Export the app's configuration to a JSON file

### Internal
- Pin the daemon JVM with a foojay-provisioned toolchain

### Documentation
- Point to release APKs

[Every commit in this release](https://github.com/oreillymonitor/Shellsurge/compare/1.0...1.1.0)

## 1.0 - 2026-08-24

### Other changes
- An Android SSH client for Android 12+
