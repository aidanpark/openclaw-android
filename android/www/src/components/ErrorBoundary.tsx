import { Component, type ReactNode } from 'react'
import { t } from '../i18n'

interface State {
  failed: boolean
}

/** Catches a render error so a bridge/UI failure shows a reload prompt instead of a blank WebView. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { failed: false }

  static getDerivedStateFromError(): State {
    return { failed: true }
  }

  componentDidCatch(error: unknown) {
    console.error('[ui] render error', error)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="setup-container">
        <div className="setup-logo">⚠️</div>
        <div className="setup-title">{t('error_boundary_title')}</div>
        <button className="btn btn-primary" onClick={() => window.location.reload()}>
          {t('error_boundary_reload')}
        </button>
      </div>
    )
  }
}
