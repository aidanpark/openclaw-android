import { useState, useEffect } from 'react'
import { useRoute } from '../lib/router'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

export function SettingsKeepAlive() {
  const { navigate } = useRoute()
  const [batteryExcluded, setBatteryExcluded] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    const status = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
    // Existing debt: one-shot read of native state on mount; restructuring is a behavior risk
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (status) setBatteryExcluded(status.isIgnoring)
  }, [])

  const ppkCommand = 'adb shell device_config set_sync_disabled_for_tests activity_manager/max_phantom_processes 2147483647'

  function handleCopyCommand() {
    bridge.call('copyText', 'ppkCommand')
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  function handleRequestExclusion() {
    bridge.call('requestBatteryOptimizationExclusion')
    // Re-check after user returns
    setTimeout(() => {
      const status = bridge.callJson<{ isIgnoring: boolean }>('getBatteryOptimizationStatus')
      if (status) setBatteryExcluded(status.isIgnoring)
    }, 3000)
  }

  return (
    <div className="page">
      <div className="page-header">
        <button className="back-btn" onClick={() => navigate('/settings')}>←</button>
        <div className="page-title">{t('ka_title')}</div>
      </div>

      <div style={{ fontSize: 14, color: 'var(--text-secondary)', marginBottom: 20, lineHeight: 1.6 }}>
        {t('ka_desc')}
      </div>

      {/* 1. Battery Optimization */}
      <div className="section-title">{t('ka_battery')}</div>
      <div className="card">
        <div className="card-row">
          <div className="card-content">
            <div className="card-label">{t('ka_status')}</div>
          </div>
          {batteryExcluded ? (
            <span style={{ color: 'var(--success)', fontSize: 14 }}>{t('ka_excluded')}</span>
          ) : (
            <button className="btn btn-small btn-primary" onClick={handleRequestExclusion}>
              {t('ka_request')}
            </button>
          )}
        </div>
      </div>

      {/* 2. Developer Options */}
      <div className="section-title">{t('ka_developer')}</div>
      <div className="card">
        <div style={{ fontSize: 14, lineHeight: 1.6, marginBottom: 12 }}>
          {t('ka_developer_desc').split('\n').map((line, i) => (
            <span key={i}>{line}{i < t('ka_developer_desc').split('\n').length - 1 && <br />}</span>
          ))}
        </div>
        <button
          className="btn btn-small btn-secondary"
          onClick={() => bridge.call('openSystemSettings', 'developer')}
        >
          {t('ka_open_dev')}
        </button>
      </div>

      {/* 3. Phantom Process Killer */}
      <div className="section-title">{t('ka_phantom')}</div>
      <div className="card">
        <div style={{ fontSize: 14, lineHeight: 1.6, marginBottom: 12 }}>
          {t('ka_phantom_desc')}
        </div>
        <div className="code-block">
          {ppkCommand}
          <button className="copy-btn" onClick={handleCopyCommand}>
            {copied ? t('ka_copied') : t('ka_copy')}
          </button>
        </div>
      </div>

      {/* 4. Charge Limit */}
      <div className="section-title">{t('ka_charge')}</div>
      <div className="card">
        <div style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          {t('ka_charge_desc')}
        </div>
      </div>
    </div>
  )
}
