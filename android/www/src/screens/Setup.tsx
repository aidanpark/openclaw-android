import { useState, useCallback, useEffect, useRef, Fragment, type CSSProperties } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { routeFor, type SetupStatus, type BootstrapState } from '../lib/setupRoute'
import {
  SETUP_KIND,
  SETUP_STAGE_TOTAL,
  SETUP_STAGE_KEYS,
  SETUP_REASON_KEYS,
  REFUSED_PHASE,
  RESUMABLE_REASONS,
  TERMINAL_FALLBACK_REASON,
  isRunBusy,
  isRunEnd,
  readSetupRun,
  readSetupResult,
  readLastSetupRun,
  setupMarkerPresent,
  setupReasonKey,
  stageNumber,
  warnLines,
  resumeInfo,
  type SetupRunEvent,
  type SetupResult,
  type LastSetupRun,
} from '../lib/setupRun'
import { ConfirmCard } from '../components/ConfirmCard'
import { t, type TranslationKey } from '../i18n'

interface Props {
  onComplete: () => void
}

// installing: ① the bootstrap (startSetup) or ② the runtime components (the managed SETUP run).
// failed: the bootstrap failed. done: the bootstrap finished and the terminal installs the rest (a
// script the app cannot run itself). finished / run-failed: how the SETUP run ended (run-failed also
// for a cancel or a refusal). resume: the bootstrap is there but the rest never finished.
type SetupPhase =
  | 'platform-select'
  | 'tool-select'
  | 'installing'
  | 'failed'
  | 'done'
  | 'resume'
  | 'finished'
  | 'run-failed'

interface SetupState {
  phase?: 'idle' | 'running' | 'done' | 'failed'
  progress?: number
  message?: string
  errorKind?: string
}

// A page created while an install runs (the Activity was recreated) missed its progress events;
// ask native where it is and continue from there instead of showing the first step at 0%.
function readSetupState(): SetupState {
  return bridge.callJson<SetupState>('getSetupState') ?? {}
}

interface Platform {
  id: string
  name: string
  icon: string
  desc: string
}

function getOptionalTools() {
  return [
    { id: 'tmux', name: 'tmux', desc: t('tool_tmux') },
    { id: 'ttyd', name: 'ttyd', desc: t('tool_ttyd') },
    { id: 'dufs', name: 'dufs', desc: t('tool_dufs') },
    { id: 'claude-code', name: 'Claude Code', desc: t('tool_claude_code') },
    { id: 'gemini-cli', name: 'Gemini CLI', desc: t('tool_gemini_cli') },
    { id: 'codex-cli', name: 'Codex CLI', desc: t('tool_codex_cli') },
  ]
}

function getTips() {
  return [
    t('tip_1'),
    t('tip_2'),
    t('tip_3'),
    t('tip_4'),
  ]
}

// The command the done screen types into the terminal (BridgeGuard.terminalCommands); no newline,
// the user reads it and presses Enter
const ONBOARD_COMMAND = 'openclawOnboard'

// How long the "preparing" state waits for the first run_progress before giving the page back
// (native refreshes the script first, bounded by a 12 s download deadline; this only guards
// against a lost event)
const PRECHECK_POLL_MS = 1000
const PRECHECK_GIVE_UP_MS = 60_000

// Script output lines shown under a failure (native already caps them; this keeps the card short)
const DETAIL_MAX_LINES = 6

// The one script line shown under a success with warnings (native already cuts it to this)
const WARNING_MAX_CHARS = 200

const WRAP: CSSProperties = { overflowWrap: 'anywhere', wordBreak: 'break-word' }
const NOTE: CSSProperties = { fontSize: 12, color: 'var(--text-secondary)', marginTop: 6, textAlign: 'center', ...WRAP }
const PART_ROW: CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  gap: 8,
  fontSize: 13,
  marginBottom: 6,
  flexWrap: 'wrap',
}
const PART_STATE: CSSProperties = { color: 'var(--text-secondary)', textAlign: 'right', minWidth: 0, ...WRAP }
const RESULT_CARD: CSSProperties = { maxWidth: 360, width: '100%' }
const RESULT_TEXT: CSSProperties = { fontSize: 13, lineHeight: 1.6, whiteSpace: 'pre-line', ...WRAP }
const SECONDARY: CSSProperties = { fontSize: 12, color: 'var(--text-secondary)', marginTop: 6, ...WRAP }
const OUTPUT_BOX: CSSProperties = {
  fontFamily: 'monospace',
  fontSize: 12,
  whiteSpace: 'pre-wrap',
  background: 'var(--bg-tertiary)',
  borderRadius: 6,
  padding: 8,
  marginTop: 6,
  ...WRAP,
}

function detailLines(detail?: string | null): string {
  return (detail ?? '').trim().split('\n').slice(0, DETAIL_MAX_LINES).join('\n')
}

// The script's last [WARN] sentence, shown as printed (English, never translated)
function warningLine(detail?: string | null): string {
  return (detail ?? '').trim().split('\n')[0].slice(0, WARNING_MAX_CHARS)
}

// A skipped optional tool by the name the wizard shows; an id the wizard does not offer as it is
function toolName(id: string): string {
  return getOptionalTools().find(tool => tool.id === id)?.name ?? id
}

function reasonText(reason?: string | null, needMb?: number | null, haveMb?: number | null): string {
  const { key, vars } = setupReasonKey(reason, needMb, haveMb)
  return t(key, vars)
}

// "Stopped at step n/7 (name)", or '' when the stage is unknown
function stoppedAtText(stage?: number | string | null): string {
  const n = stageNumber(stage)
  if (n === 0) return ''
  return t('setup_stopped_at', { n: String(n), total: String(SETUP_STAGE_TOTAL), name: t(SETUP_STAGE_KEYS[n - 1]) })
}

// The app can run the rest of the install itself: a script that supports it, and no marker (with
// the marker the setup already finished)
function canRunManaged(): boolean {
  return readSetupResult()?.managed === true && !setupMarkerPresent()
}

// The bootstrap and the marker are both there (false without the bridge)
function installComplete(): boolean {
  const status = bridge.callJson<SetupStatus>('getSetupStatus')
  return status?.bootstrapInstalled === true && status.platformInstalled === true
}

function readRoute() {
  return routeFor(bridge.callJson<SetupStatus>('getSetupStatus'), bridge.callJson<BootstrapState>('getSetupState'))
}

// Where the first install is when the page is created (an Activity recreation or an app restart): the
// bootstrap (getSetupState), the SETUP run (getRunState) and, when neither says anything, the files.
// Never starts anything: a run is started only by the bootstrap's end on a live page or by a button.
function initialPhase(restored: SetupState, setupRun: SetupRunEvent | null): SetupPhase {
  if (restored.phase === 'running') return 'installing'
  if (isRunBusy(setupRun)) return 'installing'
  if (restored.phase === 'failed') return 'failed'
  if (isRunEnd(setupRun)) return setupRun.phase === 'done' ? 'finished' : 'run-failed'
  // The bootstrap finished in this process but the page that would have continued is gone
  if (restored.phase === 'done') return canRunManaged() ? 'resume' : 'done'
  return readRoute() === 'resume' ? 'resume' : 'platform-select'
}

export function Setup({ onComplete }: Props) {
  const [restored] = useState(readSetupState)
  const [restoredRun] = useState(readSetupRun)
  const [phase, setPhase] = useState<SetupPhase>(() => initialPhase(restored, restoredRun))
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [selectedPlatform, setSelectedPlatform] = useState('')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(restored.phase === 'running' ? (restored.progress ?? 0) : 0)
  const [message, setMessage] = useState(restored.phase === 'running' ? (restored.message ?? '') : '')
  const [errorKind, setErrorKind] = useState(restored.phase === 'failed' ? (restored.errorKind ?? 'UNKNOWN') : '')
  const [tipIndex, setTipIndex] = useState(0)
  // ② The SETUP run while it goes on, and how it ended (the finished / run-failed screens)
  const [run, setRun] = useState<SetupRunEvent | null>(() => (isRunBusy(restoredRun) ? restoredRun : null))
  const [outcome, setOutcome] = useState<SetupRunEvent | null>(() => (isRunEnd(restoredRun) ? restoredRun : null))
  // Between startRun and native's first answer: the script is refreshed first (up to 12 s)
  const [preparing, setPreparing] = useState(false)
  const [confirmCancel, setConfirmCancel] = useState(false)
  const [confirmReinstall, setConfirmReinstall] = useState(false)
  // The last setup's result file and the app's record of it, for the resume screen
  const [setupResult, setSetupResult] = useState<SetupResult | null>(readSetupResult)
  const [lastRun, setLastRun] = useState<LastSetupRun | null>(readLastSetupRun)
  // One start per request: a double tap, a repeated bootstrap end or a rerender must not call
  // startRun twice (a second one would only be refused BUSY, but it would show that refusal)
  const starting = useRef(false)
  // The bootstrap's end continues into the SETUP run once per bootstrap
  const bootstrapEnded = useRef(false)
  // For the native listeners: the screen shown now, and the parent's onComplete (a new function on
  // every parent render — a dependency would re-register the listener and re-run the mount read)
  const phaseRef = useRef(phase)
  const onCompleteRef = useRef(onComplete)
  useEffect(() => {
    phaseRef.current = phase
  }, [phase])
  useEffect(() => {
    onCompleteRef.current = onComplete
  }, [onComplete])

  // Load available platforms
  useEffect(() => {
    const data = bridge.callJson<Platform[]>('getAvailablePlatforms')
    if (data) {
      // Existing debt: one-shot read of native state on mount; restructuring is a behavior risk
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setPlatforms(data)
    } else {
      setPlatforms([
        { id: 'openclaw', name: 'OpenClaw', icon: '/openclaw.svg', desc: 'AI agent platform' },
      ])
    }
  }, [])

  // The marker appeared while no managed run of this page was going: the setup was finished by a
  // terminal run of post-setup.sh, which ran `openclaw onboard` itself and may have warned there.
  // The page leaves for the dashboard, as the terminal flow always did — not the finished screen
  // (that one is only for a managed run that ended `done` here). Nothing is started.
  const leaveFinishedElsewhere = useCallback(() => {
    setConfirmReinstall(false)
    setConfirmCancel(false)
    setOutcome(null)
    setRun(null)
    onCompleteRef.current()
  }, [])

  const startManagedSetup = useCallback(() => {
    if (starting.current) return
    // The setup finished meanwhile (in the terminal): a run would only end as UNKNOWN
    if (setupMarkerPresent()) {
      leaveFinishedElsewhere()
      return
    }
    starting.current = true
    setOutcome(null)
    setRun(null)
    setConfirmCancel(false)
    setConfirmReinstall(false)
    setPreparing(true)
    setPhase('installing')
    bridge.call('startRun', SETUP_KIND, false)
  }, [leaveFinishedElsewhere])

  // The bootstrap finished: the app runs the rest itself when it can (the user already chose to
  // install), else the terminal does it as before
  const afterBootstrap = useCallback(() => {
    if (bootstrapEnded.current) return
    bootstrapEnded.current = true
    if (canRunManaged()) startManagedSetup()
    else setPhase('done')
  }, [startManagedSetup])

  const showResume = useCallback(() => {
    setSetupResult(readSetupResult())
    setLastRun(readLastSetupRun())
    setPhase('resume')
  }, [])

  // The page is shown again after the terminal (MainActivity.showWebView): the script may have been
  // refreshed meanwhile, so what the resume screen offers is read again. An install the terminal
  // finished meanwhile (the marker is there) leaves the resume screen the way the terminal flow
  // always left the setup page: to the dashboard (the script ran the onboarding itself). A failure
  // screen whose install was finished meanwhile leaves the same way (and so no longer offers
  // 「Reinstall the base system」).
  const onWebViewShown = useCallback(() => {
    setSetupResult(readSetupResult())
    setLastRun(readLastSetupRun())
    const shown = phaseRef.current
    if ((shown === 'resume' || shown === 'run-failed') && setupMarkerPresent()) leaveFinishedElsewhere()
  }, [leaveFinishedElsewhere])

  useNativeEvent('webview_shown', onWebViewShown)

  const showRunEnd = useCallback((d: SetupRunEvent) => {
    // No bash, or the refreshed script cannot run as a managed run: the terminal installs it, as before
    if (d.phase === REFUSED_PHASE && d.reason === TERMINAL_FALLBACK_REASON) {
      setOutcome(null)
      setPhase('done')
      return
    }
    setOutcome(d)
    setPhase(d.phase === 'done' ? 'finished' : 'run-failed')
  }, [])

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string; error?: string; errorKind?: string }
    if (d.errorKind || d.error !== undefined) {
      // Native refused or failed the setup: show a translated reason and let the user retry
      setErrorKind(d.errorKind || 'UNKNOWN')
      setPhase('failed')
      return
    }
    if (d.progress !== undefined) {
      setProgress(d.progress)
      // Progress while the wizard is still showing (the page was recreated mid-install and
      // restored late): move to the install screen instead of ignoring it
      if (d.progress < 1) setPhase(p => (p === 'platform-select' || p === 'tool-select' ? 'installing' : p))
    }
    if (d.message) setMessage(d.message)
    if (d.progress !== undefined && d.progress >= 1) {
      afterBootstrap()
    }
    setTipIndex(i => (i + 1) % getTips().length)
  }, [afterBootstrap])

  useNativeEvent('setup_progress', onProgress)

  const onRunEvent = useCallback((data: unknown) => {
    const d = data as SetupRunEvent
    // An update's event is not this page's; one without a kind (the runner could not be built)
    // only answers this page's own start
    if (d.kind ? d.kind !== SETUP_KIND : !starting.current) return
    starting.current = false
    setPreparing(false)
    const now = readSetupRun()
    if (isRunBusy(d)) {
      // An event can arrive after the run already ended (it was queued before the end event):
      // native state is the truth, so a busy-looking event is shown only while native is busy
      if (!isRunBusy(now)) return
      setOutcome(null)
      setRun(d)
      setPhase('installing')
      return
    }
    if (isRunEnd(d)) {
      // A refused second start ends with BUSY while the first run still goes on: keep showing that run
      if (isRunBusy(now)) {
        setRun(now)
        setPhase('installing')
        return
      }
      setRun(null)
      setConfirmCancel(false)
      showRunEnd(d)
    }
  }, [showRunEnd])

  useNativeEvent('run_progress', onRunEvent)

  // The state was first read while rendering (useState above); an event could land before the
  // listeners above existed. Registered now — read once more so nothing in that gap is missed.
  useEffect(() => {
    const now = readSetupState()
    const setupRun = readSetupRun()
    if (now.phase === 'running') {
      // Existing pattern: one-shot read of native state on mount
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setPhase('installing')
      setProgress(now.progress ?? 0)
      if (now.message) setMessage(now.message)
    } else if (isRunBusy(setupRun)) {
      setOutcome(null)
      setRun(setupRun)
      setPhase('installing')
    } else if (now.phase === 'failed') {
      setErrorKind(now.errorKind ?? 'UNKNOWN')
      setPhase('failed')
    } else if (now.phase === 'done' && restored.phase === 'running') {
      // The bootstrap this page was created for ended in that gap: its last event was missed
      afterBootstrap()
    } else if (isRunBusy(restoredRun) && isRunEnd(setupRun)) {
      // The SETUP run this page was created for ended in that gap
      setRun(null)
      showRunEnd(setupRun)
    } else if (now.phase !== 'done' && setupRun === null && installComplete()) {
      // Nothing is going on and the install is complete (it finished in the terminal while this
      // page was away, e.g. the user came back through the Dashboard tab): the setup page is not
      // the place — leave it the way the terminal flow does, instead of offering a first install
      onCompleteRef.current()
    }
  }, [restored.phase, restoredRun, afterBootstrap, showRunEnd])

  // While preparing, also ask native directly: a run that started is shown even if its first event
  // was missed, and the page is given back (the resume screen) if no answer ever comes
  useEffect(() => {
    if (!preparing) return
    const startedAt = Date.now()
    const timer = window.setInterval(() => {
      const now = readSetupRun()
      if (isRunBusy(now)) {
        starting.current = false
        setPreparing(false)
        setOutcome(null)
        setRun(now)
        setPhase('installing')
      } else if (Date.now() - startedAt >= PRECHECK_GIVE_UP_MS) {
        starting.current = false
        setPreparing(false)
        showResume()
      }
    }, PRECHECK_POLL_MS)
    return () => window.clearInterval(timer)
  }, [preparing, showResume])

  function handleSelectPlatform(id: string) {
    setSelectedPlatform(id)
    setPhase('tool-select')
  }

  function toggleTool(id: string) {
    setSelectedTools(prev => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function handleStartSetup() {
    // Save tool selections
    const selections: Record<string, boolean> = {}
    getOptionalTools().forEach(tool => {
      selections[tool.id] = selectedTools.has(tool.id)
    })
    bridge.call('saveToolSelections', JSON.stringify(selections))

    // Start bootstrap setup
    startInstall()
  }

  function startInstall() {
    // Bootstrap and marker both there: the setup finished while this page waited (e.g. in the wizard
    // after 「Reinstall the base system」, or on the bootstrap failure screen). A bootstrap now would
    // replace usr and no setup would follow it (the marker): the install is complete as it is, done
    // in the terminal — leave for the dashboard. Without usr (the first-install row "no bootstrap,
    // marker present") the bootstrap runs as before.
    if (installComplete()) {
      leaveFinishedElsewhere()
      return
    }
    bootstrapEnded.current = false
    // Whether ② will follow in the app: the bundled script decides while there is no home copy
    setSetupResult(readSetupResult())
    setPhase('installing')
    setProgress(0)
    setMessage(t('setup_preparing'))
    setErrorKind('')
    bridge.call('startSetup')
  }

  function cancelSetup() {
    setConfirmCancel(false)
    bridge.call('cancelRun')
  }

  // The resume screen's terminal button. The script may have become one the app can run since the
  // page read it (MainActivity refreshes it in the background and then types nothing): asked again
  // now, so the user never lands in a terminal where nothing runs. The marker case is
  // startManagedSetup's (it shows the finished screen).
  function resumeInTerminal() {
    const now = readSetupResult()
    setSetupResult(now)
    if (now?.managed === true || setupMarkerPresent()) {
      startManagedSetup()
      return
    }
    // The terminal continues the install (JsBridge.showTerminal types `bash post-setup.sh` into a new
    // session; MainActivity already did into the one it made). The page stays on this screen with the
    // marker still missing: coming back, it re-reads (onWebViewShown) and leaves once the install is
    // done — or offers the managed run if the script became capable meanwhile.
    bridge.call('showTerminal')
  }

  // 「Reinstall the base system」, confirmed: back to the first-install wizard, whose bootstrap
  // replaces the existing usr (BootstrapManager.startSetup: the old prefix is deleted only after the
  // new one is downloaded, verified and extracted; the home folder is kept) and then continues into
  // the SETUP run as on a first install. Only this live page is moved: a page made again (Activity
  // recreation) mid-wizard opens on the resume screen again.
  function startReinstall() {
    setConfirmReinstall(false)
    setOutcome(null)
    setRun(null)
    // The setup finished meanwhile (the terminal, for example): replacing usr now would remove the
    // runtime, and with the marker there no setup would run after the bootstrap to put it back. The
    // terminal finished the install: leave for the dashboard
    if (setupMarkerPresent()) {
      leaveFinishedElsewhere()
      return
    }
    setPhase('platform-select')
  }

  // The secondary action under a SETUP failure and on the resume screen. Not while a run goes on
  // elsewhere (BUSY): replacing usr under a running script would break it. Not once the setup has
  // finished (the marker, read at each render): see startReinstall.
  function renderReinstall(reason?: string | null) {
    if (reason === 'BUSY' || setupMarkerPresent()) return null
    if (confirmReinstall) {
      return (
        <div style={RESULT_CARD}>
          <ConfirmCard
            title={t('setup_reinstall_title')}
            confirmLabel={t('setup_reinstall_confirm')}
            cancelLabel={t('setup_reinstall_keep')}
            onConfirm={startReinstall}
            onCancel={() => setConfirmReinstall(false)}
          >
            {t('setup_reinstall_body')}
          </ConfirmCard>
        </div>
      )
    }
    return (
      <button className="btn btn-secondary" onClick={() => setConfirmReinstall(true)}>
        {t('setup_reinstall_btn')}
      </button>
    )
  }

  // Onboarding is interactive (it asks the user to accept terms): it stays in the terminal. The
  // command is only typed; the user presses Enter. No wait is needed: a session showTerminal has just
  // created gets the command once its shell has started (native TerminalSessionManager.writeWhenReady).
  function startOnboarding() {
    bridge.call('showTerminal')
    bridge.call('writeCommandToTerminal', ONBOARD_COMMAND)
    onComplete()
  }

  // --- Stepper ---
  const currentStep = phase === 'platform-select' ? 0
    : phase === 'tool-select' ? 1
    : phase === 'done' || phase === 'finished' ? 3 : 2

  const STEPS = [t('step_platform'), t('step_tools'), t('step_setup')]

  function renderStepper() {
    return (
      <div className="stepper">
        {STEPS.map((label, i) => (
          <Fragment key={label}>
            {i > 0 && <div className={`step-line${i <= currentStep ? ' done' : ''}`} />}
            <div className={`step${i < currentStep ? ' done' : i === currentStep ? ' active' : ''}`}>
              <span className="step-icon">{i < currentStep ? '✓' : i === currentStep ? '●' : '○'}</span>
              <span>{label}</span>
            </div>
          </Fragment>
        ))}
      </div>
    )
  }

  // The reason line, the stage it stopped at and the script output of a SETUP run that did not finish
  function renderFailure(
    reason: string | null | undefined,
    opts: { needMb?: number | null; haveMb?: number | null; stage?: number | string | null; detail?: string; refused?: boolean },
  ) {
    // A refusal native could not name (the runner could not be built) says the install could not start
    const text = opts.refused && !(reason && SETUP_REASON_KEYS[reason])
      ? t('setup_refused_unknown')
      : reasonText(reason, opts.needMb, opts.haveMb)
    const stoppedAt = opts.refused ? '' : stoppedAtText(opts.stage)
    const detail = detailLines(opts.detail)
    return (
      <>
        <div style={RESULT_TEXT}>{text}</div>
        {stoppedAt && <div style={SECONDARY}>{stoppedAt}</div>}
        {detail && (
          <>
            <div style={{ ...SECONDARY, marginTop: 10 }}>{t('status_output')}</div>
            <div style={OUTPUT_BOX}>{detail}</div>
          </>
        )}
      </>
    )
  }

  // --- Platform Select ---
  if (phase === 'platform-select') {
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_choose_platform')}</div>

        {platforms.map(p => (
          <div
            key={p.id}
            className="card"
            style={{ maxWidth: 340, width: '100%', cursor: 'pointer' }}
            onClick={() => handleSelectPlatform(p.id)}
          >
            <div style={{ fontSize: 32, marginBottom: 8 }}>
              {p.icon.startsWith('/') ? (
                <img src={p.icon.replace(/^\//, './')} alt={p.name} style={{ width: 40, height: 40 }} />
              ) : p.icon}
            </div>
            <div style={{ fontSize: 18, fontWeight: 600 }}>{p.name}</div>
            <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4 }}>
              {p.desc}
            </div>
          </div>
        ))}

        <div className="setup-subtitle">{t('setup_more_platforms')}</div>
      </div>
    )
  }

  // --- Tool Select ---
  if (phase === 'tool-select') {
    return (
      <div className="setup-container" style={{ justifyContent: 'flex-start', paddingTop: 48 }}>
        {renderStepper()}

        <div className="setup-title" style={{ fontSize: 22 }}>{t('setup_optional_tools')}</div>
        <div className="setup-subtitle">
          {t('setup_tools_desc', { platform: selectedPlatform })}
        </div>

        <div style={{ width: '100%', maxWidth: 360 }}>
          {getOptionalTools().map(tool => {
            const isSelected = selectedTools.has(tool.id)
            return (
              <div
                key={tool.id}
                className="card"
                style={{ cursor: 'pointer', marginBottom: 8 }}
                onClick={() => toggleTool(tool.id)}
              >
                <div className="card-row">
                  <div className="card-content">
                    <div className="card-label">{tool.name}</div>
                    <div className="card-desc">{tool.desc}</div>
                  </div>
                  <div
                    style={{
                      width: 44, height: 24, borderRadius: 12,
                      backgroundColor: isSelected ? 'var(--accent)' : 'var(--bg-tertiary)',
                      position: 'relative', flexShrink: 0,
                      transition: 'background-color 0.2s',
                    }}
                  >
                    <div style={{
                      width: 20, height: 20, borderRadius: 10,
                      backgroundColor: '#fff', position: 'absolute', top: 2,
                      left: isSelected ? 22 : 2,
                      transition: 'left 0.2s',
                      boxShadow: '0 1px 3px rgba(0,0,0,0.3)',
                    }} />
                  </div>
                </div>
              </div>
            )
          })}
        </div>

        <button className="btn btn-primary" onClick={handleStartSetup} style={{ marginTop: 8 }}>
          {t('setup_start')}
        </button>
      </div>
    )
  }

  // --- Installing: ① bootstrap, then ② the runtime components (managed SETUP run) ---
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    // ② has begun: native runs (or is about to run) the SETUP script
    const runtime = run !== null || preparing
    // Both parts are shown when ② will run in the app; a script the app cannot run keeps the old screen
    const twoParts = runtime || setupResult?.managed === true
    const stage = stageNumber(run?.stage)
    const stageTotal = run?.stageTotal ?? SETUP_STAGE_TOTAL
    const fraction = !runtime
      ? progress
      : (run?.progress ?? 0) > 0
        ? Math.min(1, run?.progress ?? 0)
        : stageTotal > 0 ? Math.min(1, stage / stageTotal) : 0
    const barPct = Math.round(fraction * 100)
    const cancelRequested = run !== null && (run.cancelRequested === true || run.phase === 'cancelling')
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_setting_up')}</div>

        <div style={{ width: '100%', maxWidth: 320 }}>
          {twoParts && (
            <>
              <div style={PART_ROW}>
                <span>{t('setup_part_bootstrap')}</span>
                <span style={PART_STATE}>{runtime ? '✓' : `${pct}%`}</span>
              </div>
              <div style={PART_ROW}>
                <span>{t('setup_part_runtime')}</span>
                <span style={PART_STATE} aria-live="polite">
                  {!runtime
                    ? t('setup_part_waiting')
                    : stage > 0
                      ? t('status_stage', { n: String(stage), total: String(stageTotal) })
                      : t('status_preparing')}
                </span>
              </div>
              {runtime && stage > 0 && (
                <div style={{ fontSize: 13, textAlign: 'center', marginBottom: 6, ...WRAP }}>
                  {t(SETUP_STAGE_KEYS[stage - 1])}
                </div>
              )}
            </>
          )}
          <div
            className="progress-bar"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={barPct}
          >
            <div className="progress-fill" style={{ width: `${barPct}%` }} />
          </div>
          {!twoParts && (
            <div style={{ textAlign: 'center', fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
              {pct}%
            </div>
          )}
          <div style={{ textAlign: 'center', fontSize: 12, color: 'var(--text-secondary)', marginTop: 4, ...WRAP }}>
            {runtime ? (run?.message ?? '') : message}
          </div>
          {preparing && run === null && <div style={NOTE}>{t('setup_run_prechecking')}</div>}
          {run?.longRunning && <div style={NOTE}>{t('status_long_running')}</div>}
          {twoParts && (
            <div style={{ ...NOTE, color: 'var(--warning)', fontSize: 13, marginTop: 10 }}>{t('setup_keep_open')}</div>
          )}
          {run && (
            <div style={{ marginTop: 12, textAlign: 'center' }}>
              {cancelRequested ? (
                <div style={{ fontSize: 13, ...WRAP }}>{t('status_cancel_requested')}</div>
              ) : run.cancelable && !confirmCancel ? (
                <button className="btn btn-small btn-secondary" onClick={() => setConfirmCancel(true)}>
                  {t('status_cancel')}
                </button>
              ) : null}
            </div>
          )}
        </div>

        {run && confirmCancel && !cancelRequested ? (
          <div style={RESULT_CARD}>
            <ConfirmCard
              title={t('setup_cancel_title')}
              confirmLabel={t('setup_cancel_confirm')}
              cancelLabel={t('setup_cancel_keep')}
              onConfirm={cancelSetup}
              onCancel={() => setConfirmCancel(false)}
            >
              {t('setup_cancel_body')}
            </ConfirmCard>
          </div>
        ) : (
          <div className="tip-card">💡 {getTips()[tipIndex]}</div>
        )}
      </div>
    )
  }

  // --- Failed (bootstrap) ---
  if (phase === 'failed') {
    const messages: Record<string, string> = {
      NETWORK: t('setup_err_network'),
      UPSTREAM_MISSING: t('setup_err_missing'),
      HASH_MISMATCH: t('setup_err_hash'),
      LOCAL_IO: t('setup_err_local'),
    }
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-logo">⚠️</div>
        <div className="setup-title">{t('setup_failed_title')}</div>
        <div className="setup-subtitle">{messages[errorKind] || t('setup_err_unknown')}</div>
        <button className="btn btn-primary" onClick={startInstall}>
          {t('setup_retry')}
        </button>
      </div>
    )
  }

  // --- The SETUP run did not finish (failed, cancelled or refused) ---
  if (phase === 'run-failed') {
    const o: SetupRunEvent = outcome ?? {}
    const cancelled = o.phase === 'cancelled'
    const resumable = !!o.reason && RESUMABLE_REASONS.includes(o.reason)
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-logo">⚠️</div>
        <div className="setup-title">{t(cancelled ? 'setup_run_cancelled_title' : 'setup_failed_title')}</div>
        <div className="card" style={RESULT_CARD} role="status">
          {renderFailure(o.reason, {
            needMb: o.needMb,
            haveMb: o.haveMb,
            stage: o.stage,
            // The FAIL block of a failure only (a cancel's or a refusal's output says nothing more)
            detail: o.phase === 'failed' ? (o.detail ?? '') : '',
            refused: o.phase === REFUSED_PHASE,
          })}
        </div>
        <button className="btn btn-primary" onClick={startManagedSetup}>
          {t(resumable ? 'setup_resume_btn' : 'setup_retry')}
        </button>
        {renderReinstall(o.reason)}
      </div>
    )
  }

  // --- Resume: the bootstrap is there, the rest of the install never finished ---
  if (phase === 'resume') {
    if (setupResult?.managed !== true) {
      // A script the app cannot run: the terminal continues the install, as before
      return (
        <div className="setup-container">
          {renderStepper()}
          <div className="setup-title">{t('setup_resume_title')}</div>
          <div className="setup-subtitle">{t('setup_done_desc')}</div>
          <button className="btn btn-primary" onClick={resumeInTerminal}>
            {t('setup_open_terminal')}
          </button>
        </div>
      )
    }
    const info = resumeInfo(setupResult, lastRun)
    // BUSY: a run goes on elsewhere — no headline, the reason line says it
    const headlineKey: TranslationKey | null = info.interrupted
      ? 'setup_resume_interrupted'
      : info.reason === 'CANCELLED'
        ? 'setup_run_cancelled_title'
        : info.reason && info.reason !== 'BUSY'
          ? 'setup_resume_last_failed'
          : null
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_resume_title')}</div>
        <div className="setup-subtitle">{t('setup_resume_desc')}</div>
        {info.reason && (
          <div className="card" style={RESULT_CARD} role="status">
            {headlineKey && <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 6, ...WRAP }}>{t(headlineKey)}</div>}
            {renderFailure(info.reason, {
              needMb: info.needMb,
              haveMb: info.haveMb,
              stage: info.reason === 'BUSY' ? 0 : info.stage,
              detail: info.detail,
            })}
          </div>
        )}
        <button className="btn btn-primary" onClick={startManagedSetup}>
          {t('setup_resume_btn')}
        </button>
        {renderReinstall(info.reason)}
      </div>
    )
  }

  // --- Finished: the SETUP run installed everything; onboarding is next (in the terminal) ---
  if (phase === 'finished') {
    const warn = warnLines(outcome?.warn, toolName)
    const sentence = (outcome?.warnings ?? 0) > 0 ? warningLine(outcome?.detail) : ''
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-logo">{warn.incomplete ? '⚠️' : '✅'}</div>
        <div className="setup-title">{t(warn.incomplete ? 'setup_finished_title_warn' : 'setup_finished_title')}</div>
        {/* "Everything is installed" is not said when OpenClaw may be incomplete */}
        {!warn.incomplete && <div className="setup-subtitle">{t('setup_finished_desc')}</div>}
        {/* OpenClaw may not run although the script ended "done": first, and stronger than the lines below */}
        {warn.incomplete && (
          <div className="card" style={{ ...RESULT_CARD, borderColor: 'var(--warning)' }} role="alert">
            <div style={{ ...RESULT_TEXT, color: 'var(--warning)', fontWeight: 600 }}>{t('setup_warn_incomplete')}</div>
          </div>
        )}
        {(warn.tools.length > 0 || warn.other || sentence) && (
          <div className="card" style={RESULT_CARD} role="status">
            {warn.tools.map(name => (
              <div key={name} style={{ ...RESULT_TEXT, marginBottom: 4 }}>{t('setup_warn_tool', { name })}</div>
            ))}
            {warn.other && <div style={RESULT_TEXT}>{t('setup_warn_other')}</div>}
            {/* The script's own words, kept apart from the translated summary above */}
            {sentence && (
              <>
                <div style={{ ...SECONDARY, marginTop: 10 }}>{t('status_output')}</div>
                <div style={OUTPUT_BOX}>{sentence}</div>
              </>
            )}
          </div>
        )}
        <div className="setup-subtitle">{t('setup_onboard_desc')}</div>
        <button className="btn btn-primary" onClick={startOnboarding}>
          {t('setup_onboard_btn')}
        </button>
        <button className="btn btn-secondary" onClick={onComplete}>
          {t('setup_onboard_later')}
        </button>
      </div>
    )
  }

  // --- Done (bootstrap; the terminal installs the rest) ---
  return (
    <div className="setup-container">
      {renderStepper()}
      <div className="setup-logo">✅</div>
      <div className="setup-title">{t('setup_done_title')}</div>
      <div className="setup-subtitle">
        {t('setup_done_desc')}
      </div>

      <button className="btn btn-primary" onClick={() => {
        bridge.call('showTerminal')
        onComplete()
      }}>
        {t('setup_open_terminal')}
      </button>
    </div>
  )
}
