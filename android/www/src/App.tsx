import { useState, useEffect, useCallback } from 'react'
import { Route, useRoute } from './lib/router'
import { bridge } from './lib/bridge'
import { useNativeEvent } from './lib/useNativeEvent'
import { t } from './i18n'
import { Setup } from './screens/Setup'
import { Dashboard } from './screens/Dashboard'
import { Settings } from './screens/Settings'
import { SettingsKeepAlive } from './screens/SettingsKeepAlive'
import { SettingsStorage } from './screens/SettingsStorage'
import { SettingsAbout } from './screens/SettingsAbout'
import { SettingsTools } from './screens/SettingsTools'
import { SettingsStatus } from './screens/SettingsStatus'

type Tab = 'terminal' | 'dashboard' | 'settings'

export function App() {
  const { path, navigate } = useRoute()

  // Check setup status on mount
  const [setupDone, setSetupDone] = useState<boolean | null>(null)

  useEffect(() => {
    const status = bridge.callJson<{ bootstrapInstalled?: boolean; platformInstalled?: string }>(
      'getSetupStatus'
    )
    const setupState = bridge.callJson<{ running?: boolean }>('getSetupState')
    if (status && setupState?.running) {
      // Existing pattern: one-shot read of native state on mount
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setSetupDone(false)
    } else if (status) {
      setSetupDone(!!status.bootstrapInstalled && !!status.platformInstalled)
    } else {
      // Bridge not available (dev mode) — assume setup done
      setSetupDone(true)
    }
  }, [])

  // An install is running (the page was recreated mid-install, or it started elsewhere): show it,
  // whichever tab this page happens to open on
  const onSetupProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; error?: string; errorKind?: string }
    if (d.error === undefined && !d.errorKind && d.progress !== undefined && d.progress < 1) setSetupDone(false)
  }, [])
  useNativeEvent('setup_progress', onSetupProgress)

  // Determine active tab from path
  const activeTab: Tab = path.startsWith('/settings')
    ? 'settings'
    : path.startsWith('/setup')
      ? 'settings'
      : 'dashboard'

  function handleTabClick(tab: Tab) {
    if (tab === 'terminal') {
      bridge.call('showTerminal')
      return
    }
    bridge.call('showWebView')
    if (tab === 'dashboard') navigate('/dashboard')
    if (tab === 'settings') navigate('/settings')
  }

  // Show setup flow if not completed
  if (setupDone === null) return null // loading
  if (!setupDone && !path.startsWith('/setup')) {
    navigate('/setup')
  }

  return (
    <>
      {/* Tab bar */}
      <nav className="tab-bar">
        <button
          className="tab-bar-item"
          onClick={() => handleTabClick('terminal')}
        >
          {t('tab_terminal')}
        </button>
        <button
          className={`tab-bar-item ${activeTab === 'dashboard' ? 'active' : ''}`}
          onClick={() => handleTabClick('dashboard')}
        >
          {t('tab_dashboard')}
        </button>
        <button
          className={`tab-bar-item ${activeTab === 'settings' ? 'active' : ''}`}
          onClick={() => handleTabClick('settings')}
        >
          {t('tab_settings')}
        </button>
      </nav>

      {/* Routes */}
      <Route path="/setup">
        <Setup onComplete={() => { setSetupDone(true); navigate('/dashboard') }} />
      </Route>
      <Route path="/dashboard">
        <Dashboard />
      </Route>
      <Route path="/settings">
        <SettingsRouter />
      </Route>
    </>
  )
}

function SettingsRouter() {
  const { path } = useRoute()
  if (path === '/settings') return <Settings />
  if (path === '/settings/keep-alive') return <SettingsKeepAlive />
  if (path === '/settings/tools') return <SettingsTools />
  if (path === '/settings/storage') return <SettingsStorage />
  if (path === '/settings/about') return <SettingsAbout />
  if (path === '/settings/status') return <SettingsStatus />
  return <Settings />
}
