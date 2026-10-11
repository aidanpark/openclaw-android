import { useEffect, useRef } from 'react'

// Events closer together than this are one return to the page (a terminal → WebView switch can
// bring visibilitychange, focus and webview_shown at once)
const COALESCE_MS = 300

/**
 * Calls [onVisible] when the page is shown again: the document becomes visible, the window gets the
 * focus, or native says the WebView was shown again (`webview_shown`: MainActivity.showWebView —
 * back from the terminal — and the return to the app after HOME, the notification shade or a system
 * screen). For state that changes outside the page (a gateway started in the
 * terminal, a tool installed there). No timers: nothing is read while the page stays in front.
 * [onVisible] may change on every render; the newest one is called.
 */
export function useVisibleAgain(onVisible: () => void): void {
  const latest = useRef(onVisible)
  useEffect(() => {
    latest.current = onVisible
  }, [onVisible])

  useEffect(() => {
    // No DOM (a test bundle run in node): nothing to listen to
    if (typeof document === 'undefined' || typeof window.addEventListener !== 'function') return
    let last = -Infinity
    const call = () => {
      const now = Date.now()
      if (now - last < COALESCE_MS) return
      last = now
      latest.current()
    }
    const shown = () => {
      if (document.visibilityState === 'hidden') return
      call()
    }
    // Native sends webview_shown only with the WebView in front (MainActivity: showWebView, and the
    // return to the app — onResume / window focus). The document's visibility may not have caught up
    // yet when it arrives, so it is not asked here.
    const nativeShown = () => call()
    document.addEventListener('visibilitychange', shown)
    window.addEventListener('focus', shown)
    window.addEventListener('native:webview_shown', nativeShown)
    return () => {
      document.removeEventListener('visibilitychange', shown)
      window.removeEventListener('focus', shown)
      window.removeEventListener('native:webview_shown', nativeShown)
    }
  }, [])
}
