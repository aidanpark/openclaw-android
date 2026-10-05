import { useState, useCallback, useEffect, useRef } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Tool {
  id: string
  name: string
  desc: () => string
  category: () => string
}

// The app installs a tool only through the signed installer chain. These have no such chain yet
// (kept in step with BridgeGuard.terminalOnlyTools — a unit test compares them): shown, but not installable.
const NOT_INSTALLABLE_YET = ['openssh-server', 'opencode', 'chromium', 'code-server']

// Not installable from the app, but there is a known way: say it (shown under the description)
const TERMINAL_INSTALL = ['code-server']

// In the chain but not yet known to work on Android (its install may fail): marked, not hidden
const EXPERIMENTAL = ['claude-code', 'codex-cli']

// For these ends the script's last output line says what went wrong; for the others (busy, outdated
// installer, cancelled, unknown, ...) the last line is only progress or an app message and would mislead
const DETAIL_REASONS = ['INSTALL_FAILED', 'VERIFY_FAILED', 'FILE_MISMATCH', 'INDEX_NETWORK', 'INDEX_VERIFY', 'ENV', 'LOCK']

function getTools(): Tool[] {
  const terminal = () => t('tools_cat_terminal')
  const ai = () => t('tools_cat_ai')
  const network = () => t('tools_cat_network')
  const system = () => t('tools_cat_system')
  return [
    { id: 'tmux', name: 'tmux', desc: () => t('tool_tmux'), category: terminal },
    { id: 'code-server', name: 'code-server', desc: () => t('tool_code_server'), category: terminal },
    { id: 'opencode', name: 'OpenCode', desc: () => t('tool_desc_opencode'), category: ai },
    { id: 'claude-code', name: 'Claude Code', desc: () => t('tool_claude_code'), category: ai },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: () => t('tool_gemini_cli'), category: ai },
    { id: 'codex-cli', name: 'Codex CLI', desc: () => t('tool_codex_cli'), category: ai },
    { id: 'openssh-server', name: 'SSH Server', desc: () => t('tool_desc_openssh'), category: network },
    { id: 'ttyd', name: 'ttyd', desc: () => t('tool_ttyd'), category: network },
    { id: 'dufs', name: 'dufs', desc: () => t('tool_dufs'), category: network },
    { id: 'android-tools', name: 'Android Tools', desc: () => t('tool_desc_android_tools'), category: system },
    { id: 'playwright', name: 'Playwright', desc: () => t('tool_desc_playwright'), category: system },
    { id: 'chromium', name: 'Chromium', desc: () => t('tool_desc_chromium'), category: system },
  ]
}

interface ToolEvent {
  target?: string | null
  phase?: string
  progress?: number
  message?: string
  cancelRequested?: boolean
  longRunning?: boolean
  reason?: string | null
  errorKind?: string
}

interface Running {
  tool: string
  progress: number
  message: string
  cancelRequested: boolean
  longRunning: boolean
}

// reason (ToolFailure name from native) -> translated message; anything unknown gets the generic one
function reasonText(reason?: string | null): string {
  const keys: Record<string, Parameters<typeof t>[0]> = {
    SETUP_INCOMPLETE: 'tool_err_setup_incomplete',
    SCRIPT_OUTDATED: 'tool_err_script_outdated',
    NOT_RUN: 'tool_err_not_run',
    BUSY: 'tool_err_busy',
    INTERRUPTED: 'tool_err_interrupted',
    INDEX_NETWORK: 'tool_err_index_network',
    INDEX_VERIFY: 'tool_err_index_verify',
    INSTALL_FAILED: 'tool_err_install_failed',
    VERIFY_FAILED: 'tool_err_verify_failed',
    FILE_MISMATCH: 'tool_err_file_mismatch',
    ENV: 'tool_err_env',
    LOCK: 'tool_err_lock',
  }
  return t((reason && keys[reason]) || 'tool_err_unknown')
}

function failureText(reason?: string | null, message?: string): string {
  const base = reasonText(reason)
  const detail = (message ?? '').trim()
  return reason && DETAIL_REASONS.includes(reason) && detail ? `${base}\n${t('tool_last_output')}: ${detail}` : base
}

interface InstalledInfo {
  installed: Set<string>
  // Files are there, but the tool's last install ended "does not work" (native remembers it across
  // app restarts): such a tool must not read as "installed"
  broken: Set<string>
}

function readInstalled(): InstalledInfo {
  const result = bridge.callJson<Array<{ id: string; broken?: boolean }>>('getInstalledTools') ?? []
  return {
    installed: new Set(result.map(x => x.id)),
    broken: new Set(result.filter(x => x.broken).map(x => x.id)),
  }
}

// A page opened after the install ended (the Activity was recreated meanwhile, or the user was on
// another tab) still tells how the last install ended — otherwise a failure goes unnoticed
function lastEndNotice(): string {
  const last = bridge.callJson<ToolEvent>('getToolInstallState')
  if (!last || !last.target) return ''
  const name = getTools().find(x => x.id === last.target)?.name ?? last.target
  if (last.phase === 'failed') return `${name}: ${failureText(last.reason, last.message)}`
  if (last.phase === 'cancelled') return `${name}: ${t('tool_cancelled')}`
  return ''
}

let checkCounter = 0
// Ask native to run each installed tool's check; the answers carry this id back
function startCheck(): string {
  checkCounter += 1
  const id = `tools-check-${checkCounter}`
  bridge.call('checkInstalledToolsAsync', id)
  return id
}

function toRunning(d: ToolEvent): Running {
  return {
    tool: d.target ?? '',
    progress: d.progress ?? 0,
    message: d.message ?? '',
    cancelRequested: !!d.cancelRequested || d.phase === 'cancelling',
    longRunning: !!d.longRunning,
  }
}

export function SettingsTools() {
  const { navigate } = useRoute()
  const [info, setInfo] = useState<InstalledInfo>(() => readInstalled())
  // What running each installed tool's `--version` said (file presence proves nothing: a tool can be
  // installed some other way, or break later): a tool that does not run must not read as "installed"
  const [checked, setChecked] = useState<Record<string, string>>({})
  const checkRef = useRef('')
  const { installed } = info
  const broken = new Set([...info.broken, ...Object.keys(checked).filter(id => checked[id] === 'failed')])
  // A page created while an install runs (the Activity was recreated) asks native where it is
  const [running, setRunning] = useState<Running | null>(() => {
    const now = bridge.callJson<ToolEvent>('getToolInstallState')
    return now && (now.phase === 'running' || now.phase === 'cancelling') ? toRunning(now) : null
  })
  const [notice, setNotice] = useState(() => lastEndNotice())
  const tools = getTools()

  const onToolEvent = useCallback((data: unknown) => {
    const d = data as ToolEvent
    if (d.errorKind === 'TERMINAL_ONLY') {
      setNotice(t('tool_terminal_only'))
      return
    }
    if (d.errorKind === 'UNINSTALL_UNSUPPORTED') {
      setNotice(t('tool_uninstall_unsupported'))
      return
    }
    if (d.phase === 'running' || d.phase === 'cancelling') {
      // An event can arrive after the install already ended (it was queued before the end event):
      // native state is the truth, so a busy-looking event is shown only while native is busy
      const now = bridge.callJson<ToolEvent>('getToolInstallState')
      if (now && now.phase !== 'running' && now.phase !== 'cancelling') {
        setRunning(null)
        setInfo(readInstalled())
        return
      }
      setNotice('')
      setRunning(toRunning(d))
      return
    }
    if (d.phase === 'done' || d.phase === 'failed' || d.phase === 'cancelled') {
      setRunning(null)
      // Never trust the event for "installed": read what is really on disk
      setInfo(readInstalled())
      // An earlier check of this tool is stale now; check everything again
      setChecked({})
      checkRef.current = startCheck()
      setNotice(d.phase === 'done' ? '' : d.phase === 'cancelled' ? t('tool_cancelled') : failureText(d.reason, d.message))
    }
  }, [])
  useNativeEvent('tool_progress', onToolEvent)

  // Answers of the run check; only the newest request counts (an older one may still be arriving)
  const onToolsCheck = useCallback((data: unknown) => {
    const d = data as { callbackId?: string; target?: string; status?: string }
    const { target, status } = d
    if (d.callbackId !== checkRef.current || !target || !status) return
    setChecked(prev => ({ ...prev, [target]: status }))
  }, [])
  useNativeEvent('tools_check', onToolsCheck)

  // An end event that arrived between the first render and the listener would be lost: read the
  // native state again once the listener is on, and trust it (busy -> show it, else clear)
  useEffect(() => {
    const now = bridge.callJson<ToolEvent>('getToolInstallState')
    const busy = !!now && (now.phase === 'running' || now.phase === 'cancelling')
    // Existing pattern: one-shot read of native state after the listener is registered
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRunning(busy && now ? toRunning(now) : null)
    setInfo(readInstalled())
    checkRef.current = startCheck()
  }, [])

  const nameOf = (id: string) => tools.find(x => x.id === id)?.name ?? id
  const categories = [...new Set(tools.map(x => x.category()))]

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('tools_title')}</div>
      </div>

      {running && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ fontSize: 14, marginBottom: 8 }}>{t('tool_installing', { name: nameOf(running.tool) })}</div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${Math.round(running.progress * 100)}%` }} />
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 6, overflowWrap: 'anywhere', wordBreak: 'break-word' }}>
            {running.message}
          </div>
          {running.longRunning && (
            <div style={{ fontSize: 12, color: 'var(--text-secondary)', marginTop: 6 }}>{t('tool_long_running')}</div>
          )}
          <div style={{ marginTop: 10 }}>
            {running.cancelRequested ? (
              <div style={{ fontSize: 13 }}>{t('tool_cancel_requested')}</div>
            ) : (
              <button className="btn btn-small btn-secondary" onClick={() => bridge.call('cancelToolInstall')}>
                {t('tool_cancel')}
              </button>
            )}
          </div>
        </div>
      )}

      {notice && (
        <div className="card" style={{ marginBottom: 16, fontSize: 14, whiteSpace: 'pre-line' }}>{notice}</div>
      )}

      {categories.map(cat => (
        <div key={cat}>
          <div className="section-title">{cat}</div>
          {tools.filter(x => x.category() === cat).map(tool => (
            <div key={tool.id} className="card">
              <div className="card-row">
                <div className="card-content">
                  <div className="card-label">{tool.name}</div>
                  <div className="card-desc">{tool.desc()}</div>
                  {TERMINAL_INSTALL.includes(tool.id) && !installed.has(tool.id) && (
                    <div className="card-desc">{t('tool_terminal_install_hint')}</div>
                  )}
                  {EXPERIMENTAL.includes(tool.id) && (
                    <div className="card-desc">{t('tool_experimental')}</div>
                  )}
                  {broken.has(tool.id) && installed.has(tool.id) && (
                    <div className="card-desc">
                      {t(NOT_INSTALLABLE_YET.includes(tool.id) ? 'tool_broken_terminal' : 'tool_installed_broken')}
                    </div>
                  )}
                </div>
                {installed.has(tool.id) && !broken.has(tool.id) ? (
                  <span style={{ fontSize: 13 }}>{t('tool_installed')}</span>
                ) : NOT_INSTALLABLE_YET.includes(tool.id) ? (
                  <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{t('tool_not_available')}</span>
                ) : (
                  <button
                    className="btn btn-small btn-primary"
                    onClick={() => { setNotice(''); bridge.call('installTool', tool.id) }}
                    disabled={running !== null}
                  >
                    {broken.has(tool.id) && installed.has(tool.id) ? t('tool_reinstall') : t('tool_install')}
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
