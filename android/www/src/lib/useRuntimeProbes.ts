import { useEffect, useState } from 'react'
import { bridge } from './bridge'

export interface ProbeSpec {
  label: string
  commandId: string
  format?: (output: string) => string
}

const PENDING = '…'
const MISSING = '—'
const RETRY_MS = 10_000
const MAX_RETRIES = 12

/**
 * Runtime version lines, read without blocking the page. A value that is still missing is
 * retried every 10s for 2 minutes (post-setup may still be installing in the terminal) and the
 * whole set is re-read when the page becomes visible or native says the WebView was shown again.
 * `specs` must be a module-level constant (it is an effect dependency).
 */
export function useRuntimeProbes(specs: ProbeSpec[], retry = true): Record<string, string> {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(specs.map(s => [s.label, PENDING])),
  )

  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout> | undefined
    let tries = 0
    // Each restart starts a new generation; a run from an older one finishes quietly without
    // scheduling another retry, so overlapping restarts never multiply the retry chain
    let generation = 0

    const run = async (gen: number) => {
      const results = await Promise.all(specs.map(s => bridge.probe(s.commandId)))
      if (cancelled || gen !== generation) return
      const next: Record<string, string> = {}
      let missing = false
      specs.forEach((spec, i) => {
        const out = (results[i].stdout ?? '').trim()
        if (!out) missing = true
        next[spec.label] = out ? (spec.format ? spec.format(out) : out) : MISSING
      })
      setValues(next)
      if (retry && missing && tries < MAX_RETRIES) {
        tries++
        clearTimeout(timer)
        timer = setTimeout(() => void run(gen), RETRY_MS)
      }
    }

    const restart = () => {
      if (document.visibilityState === 'hidden') return
      tries = 0
      generation++
      clearTimeout(timer)
      void run(generation)
    }

    void run(generation)
    document.addEventListener('visibilitychange', restart)
    window.addEventListener('native:webview_shown', restart)
    return () => {
      cancelled = true
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', restart)
      window.removeEventListener('native:webview_shown', restart)
    }
  }, [specs, retry])

  return values
}
