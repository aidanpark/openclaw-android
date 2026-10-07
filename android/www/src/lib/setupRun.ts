/**
 * The first install's second half as a managed run (SETUP): `post-setup.sh` run by the app, its
 * progress as `run_progress` events. Shapes and readers for the setup page; the decisions here are
 * pure functions of what native returned.
 */
import { bridge } from './bridge'
import type { TranslationKey } from '../i18n'

// The managed-run kind the setup page starts and shows
export const SETUP_KIND = 'SETUP'

// `▸ [N/7]` in post-setup.sh (oa_stage 1..7); the names are the page's (SETUP_STAGE_KEYS)
export const SETUP_STAGE_TOTAL = 7

// run_progress and getRunState() of a SETUP run (needMb/haveMb/warn only for this kind; values
// native has not set are left out of the JSON)
export interface SetupRunEvent {
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
  // error=free-space of the result file (or the storage line printed before the lock)
  needMb?: number | null
  haveMb?: number | null
  // warn= of the result file: `tools:<id>` or the id of a skipped non-essential part
  warn?: string[]
}

// getSetupResult(): the last full setup's result file (`post-setup-result.conf`), written by any
// run — the app's or one typed into the terminal. managed: the app can run the script itself.
export interface SetupResult {
  present?: boolean
  stage?: string
  error?: string
  reason?: string
  needMb?: number
  haveMb?: number
  warn?: string[]
  exit?: number
  // The file has no exit and nothing that could still write it is alive: the run was killed
  interrupted?: boolean
  managed?: boolean
}

// getLastRun().SETUP: the app's last SETUP run, kept across restarts (no needMb/haveMb/warn)
export interface LastSetupRun {
  at?: number
  verdict?: 'success' | 'failure'
  reason?: string
  exit?: number
  detail?: string
  warnings?: number
}

// A start native turned down before running anything: only ever an event (getRunState stays idle)
export const REFUSED_PHASE = 'refused'
const BUSY_PHASES = ['running', 'cancelling']
const END_PHASES = ['done', 'failed', 'cancelled', REFUSED_PHASE]

// Plain boolean (not a type guard): `!isRunBusy(x)` must not narrow x to null
export function isRunBusy(s: SetupRunEvent | null | undefined): boolean {
  return !!s && BUSY_PHASES.includes(s.phase ?? '')
}

export function isRunEnd(s: SetupRunEvent | null | undefined): s is SetupRunEvent {
  return !!s && END_PHASES.includes(s.phase ?? '')
}

// The managed run native holds, only when it is a SETUP run
export function readSetupRun(): SetupRunEvent | null {
  const now = bridge.callJson<SetupRunEvent>('getRunState')
  return now && now.kind === SETUP_KIND ? now : null
}

export function readSetupResult(): SetupResult | null {
  return bridge.callJson<SetupResult>('getSetupResult')
}

export function readLastSetupRun(): LastSetupRun | null {
  return bridge.callJson<Record<string, LastSetupRun>>('getLastRun')?.[SETUP_KIND] ?? null
}

// The marker `.post-setup-done`: with it a SETUP run must not start (the script would end at once
// with exit 0 and no result, which reads as UNKNOWN)
export function setupMarkerPresent(): boolean {
  return bridge.callJson<{ platformInstalled?: boolean }>('getSetupStatus')?.platformInstalled === true
}

// Refused NOT_INSTALLED: no bash, or the script (after its refresh) cannot be run by the app.
// The page keeps the terminal flow then.
export const TERMINAL_FALLBACK_REASON = 'NOT_INSTALLED'

// The user's cancel and a stop from elsewhere: the next run continues where this one stopped
export const RESUMABLE_REASONS = ['INTERRUPTED', 'CANCELLED']

const NO_SPACE_REASON = 'NO_SPACE'

// reason (app reason code of a SETUP run) -> translated message; anything unknown gets the generic
// one. Only NO_SPACE says that nothing was installed (the script says so itself: the space check
// comes before any stage); every other failure may come after stages that did install something.
export const SETUP_REASON_KEYS: Record<string, TranslationKey> = {
  NO_SPACE: 'setup_reason_no_space',
  NETWORK: 'setup_reason_network',
  VERIFY_FAILED: 'setup_reason_verify_failed',
  INSTALL_FAILED: 'setup_reason_install_failed',
  ENV: 'setup_reason_env',
  OPENCLAW_INCOMPLETE: 'setup_reason_openclaw_incomplete',
  INTERRUPTED: 'setup_reason_interrupted',
  CANCELLED: 'setup_reason_cancelled',
  BUSY: 'setup_reason_busy',
}

// Translation key and values of a reason line. NO_SPACE names the sizes when native sent them.
export function setupReasonKey(
  reason?: string | null,
  needMb?: number | null,
  haveMb?: number | null,
): { key: TranslationKey; vars?: Record<string, string> } {
  if (reason === NO_SPACE_REASON) {
    if (typeof needMb === 'number' && typeof haveMb === 'number') {
      return { key: 'setup_reason_no_space_mb', vars: { need: String(needMb), have: String(haveMb) } }
    }
    return { key: 'setup_reason_no_space' }
  }
  return { key: (reason && SETUP_REASON_KEYS[reason]) || 'setup_reason_unknown' }
}

export const SETUP_STAGE_KEYS: TranslationKey[] = [
  'setup_stage_1',
  'setup_stage_2',
  'setup_stage_3',
  'setup_stage_4',
  'setup_stage_5',
  'setup_stage_6',
  'setup_stage_7',
]

// 1..7, or 0 when the value is not a stage (missing, "done", a stage a later script added)
export function stageNumber(stage?: number | string | null): number {
  const n = typeof stage === 'string' ? Number(stage) : stage
  return typeof n === 'number' && Number.isInteger(n) && n >= 1 && n <= SETUP_STAGE_TOTAL ? n : 0
}

const TOOL_WARN_PREFIX = 'tools:'

// The OpenClaw package could not be patched after its install. Scripts up to v1.2.2 also end "done"
// with this after an install cut during OpenClaw's npm extraction, while `openclaw` itself does not
// run ("package lifecycle is incomplete") and `oa --update` does not repair it (QA vc27): the page
// says OpenClaw may be incomplete, above the other lines, instead of a plain success.
const INCOMPLETE_WARN = 'hardlink-patch'

// warn= of a finished install -> the lines the done screen shows: whether OpenClaw may be
// incomplete (hardlink-patch, shown first and only there), one per optional tool that was skipped
// (named by [toolName]), then one line for all the other skipped parts together
export function warnLines(
  warn: string[] | undefined,
  toolName: (id: string) => string,
): { tools: string[]; other: boolean; incomplete: boolean } {
  const tools: string[] = []
  let other = false
  let incomplete = false
  for (const token of warn ?? []) {
    if (token.startsWith(TOOL_WARN_PREFIX)) {
      const id = token.slice(TOOL_WARN_PREFIX.length)
      if (id) tools.push(toolName(id))
    } else if (token === INCOMPLETE_WARN) {
      incomplete = true
    } else if (token) {
      other = true
    }
  }
  return { tools, other, incomplete }
}

// What the resume screen says about the last setup. The result file is the latest run of the script
// (the app's or the terminal's); the app's own record adds what the file cannot say (the user's
// cancel, a run refused or killed before it wrote the file) and the script output.
export interface ResumeInfo {
  // null: nothing to report (no earlier run, or one that left no reason) — only the button
  reason: string | null
  // The last run was stopped from outside (app closed, process killed), not by the user's cancel
  interrupted: boolean
  needMb?: number
  haveMb?: number
  // The stage the last run had started (1..7), 0 when unknown
  stage: number
  // The script output kept with the app's record, when the record is about the same failure
  detail: string
}

export function resumeInfo(result: SetupResult | null, last: LastSetupRun | null): ResumeInfo {
  const lastFailure = last?.verdict === 'failure' ? last : null
  let reason: string | null = null
  let stage = 0
  if (result?.present) {
    stage = stageNumber(result.stage)
    if (result.interrupted) {
      reason = 'INTERRUPTED'
    } else if (result.reason) {
      reason = result.reason
    } else if (typeof result.exit !== 'number') {
      // No exit yet and something that may still write the file is alive: a run goes on elsewhere
      reason = 'BUSY'
    } else {
      reason = lastFailure?.reason ?? null
    }
    // The script writes error=interrupted for the app's cancel too (its TERM trap); the app's record
    // knows it was the user
    if (reason === 'INTERRUPTED' && lastFailure?.reason === 'CANCELLED') reason = 'CANCELLED'
  } else {
    reason = lastFailure?.reason ?? null
  }
  return {
    reason,
    interrupted: reason === 'INTERRUPTED',
    needMb: result?.present ? result.needMb : undefined,
    haveMb: result?.present ? result.haveMb : undefined,
    stage,
    detail: lastFailure && reason && lastFailure.reason === reason ? (lastFailure.detail ?? '') : '',
  }
}
