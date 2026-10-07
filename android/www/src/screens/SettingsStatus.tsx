import { useState, useCallback, useEffect, useRef, type CSSProperties } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { useRuntimeProbes, type ProbeSpec } from '../lib/useRuntimeProbes'
import { useVisibleAgain } from '../lib/useVisibleAgain'
import { ConfirmCard } from '../components/ConfirmCard'
import { t, getLocale, type TranslationKey } from '../i18n'

// The one managed-run kind this screen starts and shows (`oa --update`)
const RUN_KIND = 'UPDATE'

// The run_progress event and getRunState() have the same shape
interface RunEvent {
  kind?: string | null
  phase?: string
  stage?: number
  stageTotal?: number
  progress?: number
  message?: string
  cancelable?: boolean
  cancelRequested?: boolean
  longRunning?: boolean
  reason?: string | null
  exit?: number | null
  detail?: string | null
  warnings?: number
  // The checks before this run stopped a running gateway: the result card says to start it again.
  // Also set on a refusal that came after the stop. Not kept across app restarts (not in getLastRun)
  gatewayStopped?: boolean
}

// One entry of getLastRun(): the last finished run of a kind, kept by native across app restarts
interface LastRun {
  at?: number
  verdict?: 'success' | 'failure'
  reason?: string
  exit?: number
  detail?: string
  warnings?: number
}

interface GatewayStatus {
  running?: boolean
  ours?: boolean
  pids?: number[]
}

interface GatewayEvent {
  phase?: string
  result?: string
  running?: boolean
}

// A start native turned down before running anything (pre-check): only ever sent as an event,
// never returned by getRunState (the guard stays idle). Shown like an end.
const REFUSED_PHASE = 'refused'
const BUSY_PHASES = ['running', 'cancelling']
const END_PHASES = ['done', 'failed', 'cancelled', REFUSED_PHASE]

// reason (app reason code from native) -> translated message; anything unknown gets the generic one.
// No reason text says whether anything was changed: the same reason can come from the pre-check or
// from a later stage (after the core files were replaced), so the result card adds a line from the
// stage the run reached instead (stageLineKey).
const REASON_KEYS: Record<string, TranslationKey> = {
  BUSY: 'status_reason_busy',
  GATEWAY_RUNNING: 'status_reason_gateway_running',
  GATEWAY_STOP_FAILED: 'status_reason_gateway_stop_failed',
  NO_SPACE: 'status_reason_no_space',
  CACHE_STALE: 'status_reason_cache_stale',
  SESSION_GUARD: 'status_reason_session_guard',
  DOWNLOAD: 'status_reason_download',
  CHECKSUM: 'status_reason_checksum',
  INSTALL_FAILED: 'status_reason_install_failed',
  MIGRATION_FAILED: 'status_reason_migration_failed',
  HEALTH_FAILED: 'status_reason_health_failed',
  INTERRUPTED: 'status_reason_interrupted',
  CANCELLED: 'status_reason_cancelled',
  NOT_INSTALLED: 'status_reason_not_installed',
}

// Native records a run the user cancelled with this reason (verdict 'failure'); the last-update
// line says "cancelled", not "did not finish", and adds no reason line
const CANCELLED_REASON = 'CANCELLED'

// The update ran but OpenClaw cannot use its data: point at the repair and the restore commands
const DOCTOR_REASONS = ['MIGRATION_FAILED', 'HEALTH_FAILED']

// A MIGRATION/HEALTH failure whose script output says another process (a gateway the app cannot
// stop or see) holds the state database: the fix is to stop that process, not to restore a backup
const STATE_BUSY_PATTERN = /state ownership|StateOwnerContention|database is busy/i

// The script output for these tells the terminal user how to stop the gateway (pkill, Force stop);
// in the app the way is the Stop gateway button, so the output is not shown
const NO_DETAIL_REASONS = ['GATEWAY_RUNNING', 'GATEWAY_STOP_FAILED']

// Stages up to this one only check and download; the core files are replaced from the next one
const LAST_UNTOUCHED_STAGE = 2

// How long the "preparing" card waits for the first run_progress before giving the page back
// (the pre-check stops the gateway for at most 10 s; this only guards against a lost event)
const PRECHECK_POLL_MS = 1000
const PRECHECK_GIVE_UP_MS = 60_000

// Script output lines shown under a failure (native already caps them; this keeps the card short)
const DETAIL_MAX_LINES = 6

// The one script line shown under a success with warnings (native already cuts it to this)
const WARNING_MAX_CHARS = 200

const VERSION_PROBES: ProbeSpec[] = [
  { label: 'OpenClaw', commandId: 'openclawVersion' },
  { label: 'Node.js', commandId: 'nodeVersion' },
]

function reasonText(reason?: string | null): string {
  return t((reason && REASON_KEYS[reason]) || 'status_reason_unknown')
}

// The repair hint under a MIGRATION/HEALTH failure (null for other reasons). [detail] is the script
// output block: the result's own, or the one kept with the last run (lines joined with " / ")
function repairHintKey(reason?: string | null, detail?: string | null): TranslationKey | null {
  if (!reason || !DOCTOR_REASONS.includes(reason)) return null
  return STATE_BUSY_PATTERN.test(detail ?? '') ? 'status_hint_state_busy' : 'status_hint_doctor'
}

// The reason line of the result card. A refusal native could not name (the runner could not be
// built, an unknown kind) says the update could not be started, not "could not confirm it finished".
function resultReasonText(r: RunEvent): string {
  if (r.phase === REFUSED_PHASE && !(r.reason && REASON_KEYS[r.reason])) return t('status_refused_unknown')
  return reasonText(r.reason)
}

// Whether the installed files may have been changed, from how far the run got. A refusal or a failure
// in the check/download stages changed none; a later one may have. status_unchanged speaks of the
// files only: the checks may have stopped the gateway, which the card says on its own line.
// MIGRATION_FAILED and HEALTH_FAILED say "the update ran" themselves. An end event without a stage (the bridge's error handler sends
// one) gets no line: it cannot tell which stage was reached.
function stageLineKey(r: RunEvent): TranslationKey | null {
  if (r.phase === REFUSED_PHASE) return 'status_unchanged'
  if (r.phase !== 'failed') return null
  if (r.reason && DOCTOR_REASONS.includes(r.reason)) return null
  if (typeof r.stage !== 'number') return null
  return r.stage <= LAST_UNTOUCHED_STAGE ? 'status_unchanged' : 'status_maybe_changed'
}

function isFailure(r: RunEvent): boolean {
  return r.phase === 'failed' || r.phase === REFUSED_PHASE
}

function detailLines(detail?: string | null): string {
  return (detail ?? '').trim().split('\n').slice(0, DETAIL_MAX_LINES).join('\n')
}

// A success with warnings: native's detail is the script's last [WARN] sentence, shown as printed
// (English, never translated or matched — STATE_BUSY_PATTERN is for MIGRATION/HEALTH failures only).
// Empty for a success without warnings, or from a native that sends no detail for a success.
function warningLine(verdictOk: boolean, warnings?: number, detail?: string | null): string {
  if (!verdictOk || (warnings ?? 0) <= 0) return ''
  return (detail ?? '').trim().split('\n')[0].slice(0, WARNING_MAX_CHARS)
}

// Plain boolean (not a type guard): `!isBusy(x)` must not narrow x to null
function isBusy(s: RunEvent | null | undefined): boolean {
  return !!s && BUSY_PHASES.includes(s.phase ?? '')
}

function isEnd(s: RunEvent | null | undefined): s is RunEvent {
  return !!s && END_PHASES.includes(s.phase ?? '')
}

function readRunState(): RunEvent | null {
  return bridge.callJson<RunEvent>('getRunState')
}

function readLastRun(): LastRun | null {
  return bridge.callJson<Record<string, LastRun>>('getLastRun')?.[RUN_KIND] ?? null
}

function readGateway(): GatewayStatus | null {
  return bridge.callJson<GatewayStatus>('getGatewayStatus')
}

// A page made after a run failed or was cancelled (the Activity was recreated, or the user was on
// another screen) still shows how it ended; a success shows in the status card's last-update line
function initialResult(): RunEvent | null {
  const now = readRunState()
  if (!now || (now.kind && now.kind !== RUN_KIND)) return null
  return now.phase === 'failed' || now.phase === 'cancelled' ? now : null
}

function formatTime(at?: number): string {
  if (!at || at <= 0) return ''
  try {
    return new Date(at * 1000).toLocaleString(getLocale())
  } catch {
    return new Date(at * 1000).toLocaleString()
  }
}

function lastRunLine(last: LastRun | null): string {
  if (!last || !last.verdict) return t('status_last_none')
  const time = formatTime(last.at)
  if (last.verdict === 'success') {
    const n = last.warnings ?? 0
    return n > 0 ? t('status_last_success_warn', { n: String(n), time }) : t('status_last_success', { time })
  }
  if (last.reason === CANCELLED_REASON) return t('status_last_cancelled', { time })
  return t('status_last_failure', { time })
}

// .btn has min-width:120px; buttons in a row shrink and wrap so a 360dp screen never scrolls sideways
const BUTTON_ROW: CSSProperties = { display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }
const ROW_BUTTON: CSSProperties = { flex: '1 1 120px', minWidth: 0 }
const WRAP: CSSProperties = { overflowWrap: 'anywhere', wordBreak: 'break-word' }
const SECONDARY: CSSProperties = { fontSize: 12, color: 'var(--text-secondary)', marginTop: 6, ...WRAP }
const INFO_VALUE: CSSProperties = { textAlign: 'right', minWidth: 0, ...WRAP }
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

type GatewayStep = 'idle' | 'confirm' | 'stopping' | 'confirmForce'

export function SettingsStatus() {
  const { navigate } = useRoute()
  const versions = useRuntimeProbes(VERSION_PROBES)
  // A page created while a run goes on (the Activity was recreated) asks native where it is
  const [run, setRun] = useState<RunEvent | null>(() => {
    const now = readRunState()
    return isBusy(now) ? now : null
  })
  const busyAtRender = useRef(run !== null)
  const [result, setResult] = useState<RunEvent | null>(() => initialResult())
  // Survives app restarts (native keeps it on disk)
  const [lastRun, setLastRun] = useState<LastRun | null>(() => readLastRun())
  const [gateway, setGateway] = useState<GatewayStatus | null>(null)
  const [confirmUpdate, setConfirmUpdate] = useState(false)
  const [gwStep, setGwStep] = useState<GatewayStep>('idle')
  const [gwNotice, setGwNotice] = useState('')
  // Which stop this page asked for; a stop native does on its own (before an update) is not ours to answer
  const gwRequest = useRef<'none' | 'normal' | 'force'>('none')
  // One start per consent: a double tap must not call startRun twice
  const starting = useRef(false)
  // Shown between the consent and native's first answer: the pre-check (stopping the gateway takes
  // up to 10 s) sends nothing until the run starts or is refused
  const [prechecking, setPrechecking] = useState(false)

  const onRunEvent = useCallback((data: unknown) => {
    const d = data as RunEvent
    if (d.kind && d.kind !== RUN_KIND) return
    starting.current = false
    setPrechecking(false)
    const now = readRunState()
    if (isBusy(d)) {
      // An event can arrive after the run already ended (it was queued before the end event):
      // native state is the truth, so a busy-looking event is shown only while native is busy
      if (!isBusy(now)) {
        setRun(null)
        setLastRun(readLastRun())
        setGateway(readGateway())
        return
      }
      setResult(null)
      setConfirmUpdate(false)
      setRun(d)
      return
    }
    // A refusal (phase 'refused') is one of the END_PHASES: it is shown as a result card
    if (isEnd(d)) {
      // A refused second start ends with BUSY while the first run still goes on: keep showing that run
      if (isBusy(now)) {
        setRun(now)
        return
      }
      setRun(null)
      setResult(d)
      // Never trust the event for the outcome on record or the gateway: read them again
      setLastRun(readLastRun())
      setGateway(readGateway())
    }
  }, [])
  useNativeEvent('run_progress', onRunEvent)

  const onGatewayEvent = useCallback((data: unknown) => {
    const d = data as GatewayEvent
    if (d.phase === 'stopping') {
      if (gwRequest.current !== 'none') setGwStep('stopping')
      return
    }
    if (d.phase !== 'done') return
    setGateway(readGateway())
    const asked = gwRequest.current
    gwRequest.current = 'none'
    if (asked === 'none') return
    if (d.result === 'STILL_RUNNING') {
      // The normal stop did not end it: a force stop needs its own consent
      if (asked === 'normal') {
        setGwNotice('')
        setGwStep('confirmForce')
        return
      }
      setGwStep('idle')
      setGwNotice(t('status_gw_still_running'))
      return
    }
    setGwStep('idle')
    setGwNotice(
      d.result === 'STOPPED'
        ? t('status_gw_result_stopped')
        : d.result === 'NOT_OURS'
          ? t('status_gw_not_ours')
          : d.result === 'NOT_RUNNING'
            ? t('status_gw_result_not_running')
            : '',
    )
  }, [])
  useNativeEvent('gateway_state', onGatewayEvent)

  // An end event that arrived between the first render and the listener would be lost: read the
  // native state again once the listeners are on, and trust it (busy -> show it, else clear)
  useEffect(() => {
    const now = readRunState()
    // Existing pattern: one-shot read of native state after the listener is registered
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRun(isBusy(now) ? now : null)
    if (busyAtRender.current && !isBusy(now) && isEnd(now) && !(now.kind && now.kind !== RUN_KIND)) setResult(now)
    setLastRun(readLastRun())
    setGateway(readGateway())
  }, [])

  // While preparing, also ask native directly: a run that started is shown even if its first event
  // was missed, and the page is given back if no answer ever comes
  useEffect(() => {
    if (!prechecking) return
    const startedAt = Date.now()
    const timer = window.setInterval(() => {
      const now = readRunState()
      if (isBusy(now) && !(now?.kind && now.kind !== RUN_KIND)) {
        starting.current = false
        setPrechecking(false)
        setResult(null)
        setRun(now)
      } else if (Date.now() - startedAt >= PRECHECK_GIVE_UP_MS) {
        starting.current = false
        setPrechecking(false)
        setLastRun(readLastRun())
        setGateway(readGateway())
      }
    }, PRECHECK_POLL_MS)
    return () => window.clearInterval(timer)
  }, [prechecking])

  const gwRunning = gateway?.running === true
  const gwOurs = gateway?.ours !== false
  // A run, or a start waiting for native's answer: nothing else may be started meanwhile
  const busyUi = run !== null || prechecking

  // Back on the page (from the terminal, another app): the gateway may have been started or stopped
  // there. Only what changes outside the page is read again — a run's state keeps coming from its
  // events, and the last result is left alone while a run is shown.
  useVisibleAgain(() => {
    setGateway(readGateway())
    if (!busyUi) setLastRun(readLastRun())
  })

  function openUpdateConfirm() {
    // What the consent says about the gateway must be the state now, not the one from page load
    setGateway(readGateway())
    setResult(null)
    setGwStep(step => (step === 'confirm' ? 'idle' : step))
    starting.current = false
    setConfirmUpdate(true)
  }

  function startUpdate() {
    if (starting.current) return
    starting.current = true
    setConfirmUpdate(false)
    setPrechecking(true)
    // Stop the gateway only if the consent the user just read said so; one started since then is
    // refused by native (GATEWAY_RUNNING) and "Try again" asks again with the stop in the text
    bridge.call('startRun', RUN_KIND, gwRunning)
  }

  function stopGateway(force: boolean) {
    gwRequest.current = force ? 'force' : 'normal'
    setGwNotice('')
    setGwStep('stopping')
    bridge.call('stopGateway', force)
  }

  function runInTerminal() {
    bridge.call('showTerminal')
    bridge.call('writeCommandToTerminal', 'oaUpdate')
  }

  const stage = run?.stage ?? 0
  const stageTotal = run?.stageTotal ?? 0
  const fraction = run
    ? (run.progress ?? 0) > 0
      ? Math.min(1, run.progress ?? 0)
      : stageTotal > 0
        ? Math.min(1, stage / stageTotal)
        : 0
    : 0
  const resultDetail =
    result?.phase === 'failed' && !(result.reason && NO_DETAIL_REASONS.includes(result.reason))
      ? detailLines(result.detail)
      : ''
  const resultWarning = result ? warningLine(result.phase === 'done', result.warnings, result.detail) : ''
  const resultStageKey = result ? stageLineKey(result) : null
  const resultHintKey = result && isFailure(result) ? repairHintKey(result.reason, result.detail) : null
  // The checks stopped the gateway before this end (success, failure, cancel, or a refusal that came
  // after the stop): it stays stopped either way, so the card says to start it again
  const resultGatewayStopped = result !== null && result.gatewayStopped === true
  // The result card already explains the run: the last-update line keeps only its one-line summary
  // while the card is on screen (the reason and hint come back once it is closed)
  const resultShown = result !== null && !busyUi && !confirmUpdate
  const showLastReason =
    !busyUi && !resultShown && lastRun?.verdict === 'failure' && lastRun.reason !== CANCELLED_REASON
  const lastHintKey = showLastReason ? repairHintKey(lastRun?.reason, lastRun?.detail) : null
  // The last result was a success with warnings and no card shows it: keep the script's advice in view
  const lastWarning =
    !busyUi && !resultShown ? warningLine(lastRun?.verdict === 'success', lastRun?.warnings, lastRun?.detail) : ''

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('status_title')}</div>
      </div>

      {/* 1. Status */}
      <div className="section-title">{t('status_section_state')}</div>
      <div className="card">
        {Object.entries(versions).map(([label, value]) => (
          <div className="info-row" key={label} style={{ gap: 12 }}>
            <span className="label">{label}</span>
            <span style={INFO_VALUE}>{value}</span>
          </div>
        ))}
        <div className="info-row" style={{ gap: 12, flexWrap: 'wrap' }}>
          <span className="label">{t('status_last_update')}</span>
          <span style={INFO_VALUE}>{busyUi ? t('status_updating') : lastRunLine(lastRun)}</span>
          {/* While a run is going native already holds an INTERRUPTED start record: it is not the last result */}
          {showLastReason && (
            <div style={{ ...SECONDARY, flexBasis: '100%', whiteSpace: 'pre-line' }}>
              {reasonText(lastRun?.reason)}
              {lastHintKey && `\n${t(lastHintKey)}`}
            </div>
          )}
          {lastWarning && (
            <div style={{ flexBasis: '100%', minWidth: 0 }}>
              <div style={SECONDARY}>{t('status_output')}</div>
              <div style={OUTPUT_BOX}>{lastWarning}</div>
            </div>
          )}
        </div>
        <div className="info-row" style={{ gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="label">{t('status_gateway')}</span>
          <span style={INFO_VALUE}>
            {gwRunning && <span className="status-dot success" />}
            {gateway === null ? t('status_gw_unknown') : gwRunning ? t('status_gw_running') : t('status_gw_not_running')}
          </span>
          <div style={{ flexBasis: '100%' }} aria-live="polite">
            {gwRunning && !gwOurs && <div style={SECONDARY}>{t('status_gw_not_ours')}</div>}
            {gwStep === 'stopping' && <div style={SECONDARY}>{t('status_gw_stopping')}</div>}
            {gwNotice && <div style={SECONDARY}>{gwNotice}</div>}
          </div>
          {gwRunning && gwOurs && gwStep === 'idle' && (
            <div style={{ ...BUTTON_ROW, marginTop: 4, flexBasis: '100%' }}>
              <button
                className="btn btn-small btn-secondary"
                style={ROW_BUTTON}
                disabled={busyUi}
                onClick={() => { setGwNotice(''); setConfirmUpdate(false); setGwStep('confirm') }}
              >
                {t('status_gw_stop')}
              </button>
            </div>
          )}
        </div>
      </div>

      {gwStep === 'confirm' && (
        <ConfirmCard
          title={t('status_gw_stop_title')}
          confirmLabel={t('status_gw_stop_confirm')}
          cancelLabel={t('status_cancel')}
          onConfirm={() => stopGateway(false)}
          onCancel={() => setGwStep('idle')}
        >
          {t('status_gw_stop_body')}
        </ConfirmCard>
      )}

      {gwStep === 'confirmForce' && (
        <ConfirmCard
          title={t('status_gw_force_title')}
          confirmLabel={t('status_gw_force_confirm')}
          cancelLabel={t('status_cancel')}
          onConfirm={() => stopGateway(true)}
          onCancel={() => setGwStep('idle')}
          danger
        >
          {t('status_gw_force_body')}
        </ConfirmCard>
      )}

      {/* 2. Update */}
      <div className="section-title">{t('status_section_update')}</div>

      {run ? (
        <div className="card">
          <div style={{ fontSize: 14, marginBottom: 8, display: 'flex', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
            <span>{t('status_updating')}</span>
            <span style={{ color: 'var(--text-secondary)' }} aria-live="polite">
              {stage > 0 && stageTotal > 0 ? t('status_stage', { n: String(stage), total: String(stageTotal) }) : t('status_preparing')}
            </span>
          </div>
          <div
            className="progress-bar"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(fraction * 100)}
          >
            <div className="progress-fill" style={{ width: `${Math.round(fraction * 100)}%` }} />
          </div>
          {run.message && <div style={SECONDARY}>{run.message}</div>}
          {run.longRunning && <div style={SECONDARY}>{t('status_long_running')}</div>}
          <div style={{ marginTop: 10 }}>
            {run.cancelRequested || run.phase === 'cancelling' ? (
              <div style={{ fontSize: 13 }}>{t('status_cancel_requested')}</div>
            ) : run.cancelable ? (
              <button className="btn btn-small btn-secondary" onClick={() => bridge.call('cancelRun')}>
                {t('status_cancel')}
              </button>
            ) : (
              <div style={{ fontSize: 13, color: 'var(--warning)', ...WRAP }}>{t('status_no_cancel')}</div>
            )}
          </div>
        </div>
      ) : prechecking ? (
        <div className="card" role="status">
          <div style={{ fontSize: 14, display: 'flex', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
            <span>{t('status_updating')}</span>
            <span style={{ color: 'var(--text-secondary)' }}>{t('status_preparing')}</span>
          </div>
          <div style={SECONDARY}>{t('status_prechecking')}</div>
        </div>
      ) : confirmUpdate ? (
        <ConfirmCard
          title={t('status_confirm_title')}
          confirmLabel={t('status_confirm_start')}
          cancelLabel={t('status_cancel')}
          onConfirm={startUpdate}
          onCancel={() => setConfirmUpdate(false)}
        >
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>{t('status_confirm_changes')}</li>
            {/* A gateway this app did not start cannot be stopped here: say only that (native refuses
                the start with GATEWAY_RUNNING and the result card shows it) */}
            {gwRunning && gwOurs && <li><strong>{t('status_confirm_gateway')}</strong></li>}
            {gwRunning && !gwOurs && <li><strong>{t('status_gw_not_ours')}</strong></li>}
            <li>{t('status_confirm_needs')}</li>
            <li>{t('status_confirm_keep_open')}</li>
          </ul>
        </ConfirmCard>
      ) : result ? (
        <div className="card" role="status">
          <div style={{ fontSize: 15, fontWeight: 600, ...WRAP }}>
            {result.phase === 'done'
              ? t('status_result_success')
              : result.phase === 'cancelled'
                ? t('status_result_cancelled')
                : t('status_result_failed')}
          </div>
          {result.phase === 'done' && (result.warnings ?? 0) > 0 && (
            <div style={SECONDARY}>
              {t(resultWarning ? 'status_result_warnings_detail' : 'status_result_warnings', { n: String(result.warnings) })}
            </div>
          )}
          {isFailure(result) && (
            <div style={{ fontSize: 13, marginTop: 8, lineHeight: 1.6, whiteSpace: 'pre-line', ...WRAP }}>
              {resultReasonText(result)}
              {resultHintKey && `\n${t(resultHintKey)}`}
              {result.reason === 'GATEWAY_RUNNING' && gwRunning && !gwOurs && `\n${t('status_gw_not_ours')}`}
            </div>
          )}
          {resultStageKey && <div style={SECONDARY}>{t(resultStageKey)}</div>}
          {resultGatewayStopped && <div style={SECONDARY}>{t('status_gateway_was_stopped')}</div>}
          {resultDetail && (
            <>
              <div style={{ ...SECONDARY, marginTop: 10 }}>{t('status_output')}</div>
              <div style={OUTPUT_BOX}>{resultDetail}</div>
            </>
          )}
          {/* The script's own words, kept apart from the translated summary above */}
          {resultWarning && (
            <>
              <div style={{ ...SECONDARY, marginTop: 10 }}>{t('status_output')}</div>
              <div style={OUTPUT_BOX}>{resultWarning}</div>
            </>
          )}
          <div style={BUTTON_ROW}>
            <button className="btn btn-small btn-secondary" style={ROW_BUTTON} onClick={() => setResult(null)}>
              {t('status_dismiss')}
            </button>
            {result.phase !== 'done' && (
              <button className="btn btn-small btn-primary" style={ROW_BUTTON} onClick={openUpdateConfirm}>
                {t('status_retry')}
              </button>
            )}
          </div>
        </div>
      ) : (
        <div className="card">
          <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.6, ...WRAP }}>{t('status_update_desc')}</div>
          <div style={BUTTON_ROW}>
            <button className="btn btn-small btn-primary" style={ROW_BUTTON} onClick={openUpdateConfirm}>
              {t('status_update_btn')}
            </button>
          </div>
        </div>
      )}

      {/* 3. Terminal (secondary path: types the command, the user presses Enter) */}
      <div className="section-title">{t('status_section_terminal')}</div>
      <div className="card">
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.6, ...WRAP }}>{t('status_terminal_desc')}</div>
        <div style={BUTTON_ROW}>
          <button className="btn btn-small btn-secondary" style={ROW_BUTTON} disabled={busyUi} onClick={runInTerminal}>
            {t('status_terminal_btn')}
          </button>
        </div>
      </div>
    </div>
  )
}
