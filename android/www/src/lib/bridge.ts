/**
 * JsBridge wrapper — typed interface to window.OpenClaw (§2.6).
 * All Kotlin @JavascriptInterface methods return JSON strings.
 */

interface OpenClawBridge {
  showTerminal(): void
  showWebView(): void
  createSession(): string
  switchSession(id: string): void
  closeSession(id: string): void
  getTerminalSessions(): string
  writeCommandToTerminal(commandId: string): void
  getSetupStatus(): string
  getBootstrapStatus(): string
  startSetup(): void
  getSetupState(): string
  saveToolSelections(json: string): void
  getAvailablePlatforms(): string
  getActivePlatform(): string
  getInstalledTools(): string
  installTool(id: string): void
  cancelToolInstall(): void
  getToolInstallState(): string
  checkInstalledToolsAsync(callbackId: string): void
  // Managed runs (`oa --update` as a child process); progress arrives as `run_progress`
  startRun(kind: string, stopGateway: boolean): void
  cancelRun(): void
  getRunState(): string
  getLastRun(): string
  // Gateway status and stop; a stop's outcome arrives as `gateway_state`
  getGatewayStatus(): string
  stopGateway(force: boolean): void
  uninstallTool(id: string): void
  isToolInstalled(id: string): string
  runProbeAsync(callbackId: string, commandId: string): void
  getApkUpdateInfoAsync(callbackId: string): void
  getAppInfo(): string
  getBatteryOptimizationStatus(): string
  requestBatteryOptimizationExclusion(): void
  openSystemSettings(page: string): void
  copyText(textId: string): void
  getStorageInfo(): string
  clearCache(): void
  openUrl(url: string): void
}

declare global {
  interface Window {
    OpenClaw?: OpenClawBridge
    __oc?: { emit(type: string, data: unknown): void }
  }
}

export function isAvailable(): boolean {
  return typeof window.OpenClaw !== 'undefined'
}

export function call<K extends keyof OpenClawBridge>(
  method: K,
  ...args: Parameters<OpenClawBridge[K]>
): ReturnType<OpenClawBridge[K]> | null {
  if (window.OpenClaw && typeof window.OpenClaw[method] === 'function') {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return (window.OpenClaw[method] as (...a: any[]) => any)(...args)
  }
  console.warn('[bridge] OpenClaw not available:', method)
  return null
}

export function callJson<T>(
  method: keyof OpenClawBridge,
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  ...args: any[]
): T | null {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const raw = (call as any)(method, ...args)
  if (raw == null) return null
  try {
    return JSON.parse(raw as string) as T
  } catch {
    return raw as unknown as T
  }
}

export interface ProbeResult {
  exitCode: number
  stdout: string
  stderr: string
}

export interface ApkUpdateInfo {
  updateAvailable?: boolean
  currentVersion?: string
  latestVersion?: string
  error?: string
}

// Native answers async requests with one event carrying our callbackId; a request that gets no
// answer (page reloaded, bridge missing) resolves with `fallback` after `timeoutMs`.
const pending = new Map<string, (detail: unknown) => void>()
const listening = new Set<string>()
let sequence = 0
// A reloaded page restarts the sequence; a late answer to the previous page's request must not
// resolve a new one with the same number, so ids carry a per-page random prefix
const pageNonce = Math.random().toString(36).slice(2, 8)

function request<T>(
  eventType: string,
  start: (callbackId: string) => void,
  fallback: T,
  timeoutMs: number,
): Promise<T> {
  if (!listening.has(eventType)) {
    listening.add(eventType)
    window.addEventListener('native:' + eventType, (e: Event) => {
      const detail = (e as CustomEvent).detail as { callbackId?: string }
      const resolve = detail?.callbackId ? pending.get(detail.callbackId) : undefined
      if (resolve) resolve(detail)
    })
  }
  return new Promise<T>(resolve => {
    const callbackId = `${pageNonce}-${++sequence}`
    const timer = setTimeout(() => {
      pending.delete(callbackId)
      resolve(fallback)
    }, timeoutMs)
    pending.set(callbackId, detail => {
      clearTimeout(timer)
      pending.delete(callbackId)
      resolve(detail as T)
    })
    start(callbackId)
  })
}

// Same probe asked twice at once shares one run (mount + visibility + retry can overlap)
const probesInFlight = new Map<string, Promise<ProbeResult>>()

export function probe(commandId: string): Promise<ProbeResult> {
  const running = probesInFlight.get(commandId)
  if (running) return running
  const fresh = request<ProbeResult>(
    'command_result',
    callbackId => call('runProbeAsync', callbackId, commandId),
    { exitCode: -1, stdout: '', stderr: 'no answer' },
    12000,
  ).finally(() => probesInFlight.delete(commandId))
  probesInFlight.set(commandId, fresh)
  return fresh
}

export function apkUpdateInfo(): Promise<ApkUpdateInfo> {
  return request<ApkUpdateInfo>(
    'apk_update_info',
    callbackId => call('getApkUpdateInfoAsync', callbackId),
    { error: 'no answer' },
    15000,
  )
}

export const bridge = { isAvailable, call, callJson, probe, apkUpdateInfo }
