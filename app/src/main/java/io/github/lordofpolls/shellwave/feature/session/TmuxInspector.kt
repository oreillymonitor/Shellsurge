package io.github.lordofpolls.shellwave.feature.session

import io.github.lordofpolls.shellwave.core.util.posixQuote

internal data class TmuxPaneInfo(
    val sessionName: String,
    val windowIndex: Int,
    val paneIndex: Int,
    val title: String,
    val currentCommand: String,
    val currentPath: String,
    val active: Boolean,
)

internal data class TmuxWindowInfo(
    val sessionName: String,
    val index: Int,
    val name: String,
    val active: Boolean,
    val bellFlag: Boolean,
    val activityFlag: Boolean,
    val panes: List<TmuxPaneInfo>,
)

internal data class TmuxSessionInfo(
    val name: String,
    val attached: Boolean,
    val windows: List<TmuxWindowInfo>,
)

internal sealed class TmuxInspectResult {
    data class Available(val sessions: List<TmuxSessionInfo>) : TmuxInspectResult()
    data object NotInstalled : TmuxInspectResult()
    data object NoServer : TmuxInspectResult()
    data class Error(val message: String) : TmuxInspectResult()
}

private const val MARKER_NO_TMUX = "__NO_TMUX__"
private const val MARKER_NO_SERVER = "__NO_SERVER__"
private const val MARKER_SESSIONS = "__SESSIONS__"
private const val MARKER_WINDOWS = "__WINDOWS__"
private const val MARKER_PANES = "__PANES__"

internal val TMUX_INSPECT_COMMAND: String = """
if ! command -v tmux >/dev/null 2>&1; then
  echo "$MARKER_NO_TMUX"
  exit 0
fi
if ! tmux list-sessions >/dev/null 2>&1; then
  echo "$MARKER_NO_SERVER"
  exit 0
fi
echo "$MARKER_SESSIONS"
tmux list-sessions -F '#{session_name}	#{session_attached}'
echo "$MARKER_WINDOWS"
tmux list-windows -a -F '#{session_name}	#{window_index}	#{window_name}	#{window_active}	#{window_bell_flag}	#{window_activity_flag}'
echo "$MARKER_PANES"
tmux list-panes -a -F '#{session_name}	#{window_index}	#{pane_index}	#{pane_title}	#{pane_current_command}	#{pane_current_path}	#{pane_active}'
""".trimIndent()

private enum class InspectSection { NONE, SESSIONS, WINDOWS, PANES }

private data class RawWindow(
    val sessionName: String,
    val index: Int,
    val name: String,
    val active: Boolean,
    val bellFlag: Boolean,
    val activityFlag: Boolean,
)

/**
 * Parses output from [TMUX_INSPECT_COMMAND] into structured session/window/pane trees.
 */
internal fun parseTmuxInspectOutput(
    stdout: String,
    stderr: String = "",
    exitCode: Int = 0,
): TmuxInspectResult {
    val lines = stdout.lineSequence().map { it.trimEnd('\r') }.toList()
    if (lines.any { it.trim() == MARKER_NO_TMUX }) return TmuxInspectResult.NotInstalled
    if (lines.any { it.trim() == MARKER_NO_SERVER }) return TmuxInspectResult.NoServer

    val hasSessionsMarker = lines.any { it.trim() == MARKER_SESSIONS }
    if (exitCode != 0 && !hasSessionsMarker) {
        val message = stderr.ifBlank { stdout }.trim().ifBlank { "tmux exited with code $exitCode" }
        return TmuxInspectResult.Error(message)
    }
    if (!hasSessionsMarker) {
        return TmuxInspectResult.Error(stderr.trim().ifBlank { "Unexpected tmux output" })
    }

    var section = InspectSection.NONE
    val sessionsOrder = mutableListOf<Pair<String, Boolean>>()
    val windows = mutableListOf<RawWindow>()
    val panes = mutableListOf<TmuxPaneInfo>()

    for (line in lines) {
        when (line.trim()) {
            MARKER_SESSIONS -> {
                section = InspectSection.SESSIONS
                continue
            }
            MARKER_WINDOWS -> {
                section = InspectSection.WINDOWS
                continue
            }
            MARKER_PANES -> {
                section = InspectSection.PANES
                continue
            }
        }
        if (line.isBlank()) continue

        val cols = line.split('\t')
        when (section) {
            InspectSection.SESSIONS -> if (cols.size >= 2) {
                val name = cols[0]
                val attached = (cols[1].toIntOrNull() ?: 0) > 0
                sessionsOrder += name to attached
            }
            InspectSection.WINDOWS -> if (cols.size >= 6) {
                val winIdx = cols[1].toIntOrNull() ?: continue
                windows += RawWindow(
                    sessionName = cols[0],
                    index = winIdx,
                    name = cols[2],
                    active = cols[3] == "1",
                    bellFlag = cols[4] == "1",
                    activityFlag = cols[5] == "1",
                )
            }
            InspectSection.PANES -> if (cols.size >= 7) {
                val winIdx = cols[1].toIntOrNull() ?: continue
                val paneIdx = cols[2].toIntOrNull() ?: continue
                panes += TmuxPaneInfo(
                    sessionName = cols[0],
                    windowIndex = winIdx,
                    paneIndex = paneIdx,
                    title = cols[3],
                    currentCommand = cols[4],
                    currentPath = cols[5],
                    active = cols[6] == "1",
                )
            }
            InspectSection.NONE -> {}
        }
    }

    val panesByWindow = panes.groupBy { it.sessionName to it.windowIndex }
    val windowsBySession = windows.groupBy { it.sessionName }

    val sessionInfos = sessionsOrder.map { (name, attached) ->
        val sessionWindows = windowsBySession[name].orEmpty()
            .sortedBy { it.index }
            .map { win ->
                TmuxWindowInfo(
                    sessionName = win.sessionName,
                    index = win.index,
                    name = win.name,
                    active = win.active,
                    bellFlag = win.bellFlag,
                    activityFlag = win.activityFlag,
                    panes = panesByWindow[win.sessionName to win.index].orEmpty()
                        .sortedBy { it.paneIndex },
                )
            }
        TmuxSessionInfo(
            name = name,
            attached = attached,
            windows = sessionWindows,
        )
    }.sortedByDescending { it.attached }

    return TmuxInspectResult.Available(sessionInfos)
}

internal fun tmuxSelectWindowCommand(sessionName: String, windowIndex: Int): String =
    "tmux select-window -t ${posixQuote("$sessionName:$windowIndex")}"

internal fun tmuxSelectPaneCommand(sessionName: String, windowIndex: Int, paneIndex: Int): String =
    "tmux select-pane -t ${posixQuote("$sessionName:$windowIndex.$paneIndex")}"

internal fun tmuxNewWindowCommand(sessionName: String, windowName: String? = null): String {
    val target = posixQuote("$sessionName:")
    val cleanName = windowName?.trim()?.takeIf { it.isNotEmpty() }
    return if (cleanName != null) {
        "tmux new-window -t $target -n ${posixQuote(cleanName)}"
    } else {
        "tmux new-window -t $target"
    }
}

internal fun tmuxRenameWindowCommand(sessionName: String, windowIndex: Int, newName: String): String =
    "tmux rename-window -t ${posixQuote("$sessionName:$windowIndex")} ${posixQuote(newName.trim())}"

internal fun tmuxKillWindowCommand(sessionName: String, windowIndex: Int): String =
    "tmux kill-window -t ${posixQuote("$sessionName:$windowIndex")}"

internal fun tmuxNewSessionCommand(sessionName: String): String =
    "tmux new-session -d -s ${posixQuote(sessionName.trim())}"

internal fun tmuxKillSessionCommand(sessionName: String): String =
    "tmux kill-session -t ${posixQuote(sessionName)}"

internal fun tmuxSwitchClientCommand(sessionName: String): String =
    "CLIENT=\$(tmux list-clients -F '#{client_tty}' 2>/dev/null | head -n 1); " +
        "if [ -n \"\$CLIENT\" ]; then tmux switch-client -c \"\$CLIENT\" -t ${posixQuote(sessionName)}; " +
        "else exit 42; fi"

internal fun tmuxAttachShellLine(sessionName: String): String =
    "tmux attach -t ${posixQuote(sessionName)}"
