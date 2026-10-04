import { useState, useEffect } from 'react'
import { bridge } from '../lib/bridge'
import { t } from '../i18n'

interface BootstrapStatus {
  installed: boolean
  prefixPath?: string
}

interface PlatformInfo {
  id: string
  name: string
}

function getCommands() {
  return [
    { label: 'Gateway', commandId: 'openclawGateway', cmd: 'openclaw gateway', desc: t('cmd_gateway') },
    { label: 'Status', commandId: 'openclawStatus', cmd: 'openclaw status', desc: t('cmd_status') },
    { label: 'Onboard', commandId: 'openclawOnboard', cmd: 'openclaw onboard', desc: t('cmd_onboard') },
    { label: 'Logs', commandId: 'openclawLogs', cmd: 'openclaw logs --follow', desc: t('cmd_logs') },
  ]
}

function getManagement() {
  return [
    { label: 'Update', commandId: 'oaUpdate', cmd: 'oa --update', desc: t('cmd_update') },
    { label: 'Install Tools', commandId: 'oaInstall', cmd: 'oa --install', desc: t('cmd_install_tools') },
  ]
}

export function Dashboard() {
  const [status, setStatus] = useState<BootstrapStatus | null>(null)
  const [platform, setPlatform] = useState<PlatformInfo | null>(null)
  const [runtimeInfo, setRuntimeInfo] = useState<Record<string, string>>({})

  function refreshStatus() {
    const bs = bridge.callJson<BootstrapStatus>('getBootstrapStatus')
    if (bs) setStatus(bs)

    const ap = bridge.callJson<PlatformInfo>('getActivePlatform')
    if (ap) setPlatform(ap)

    const nodeV = bridge.callJson<{ stdout: string }>('runCommand', 'nodeVersion')
    const gitV = bridge.callJson<{ stdout: string }>('runCommand', 'gitVersion')
    const ocV = bridge.callJson<{ stdout: string }>('runCommand', 'openclawVersion')
    setRuntimeInfo({
      'Node.js': nodeV?.stdout?.trim() || '—',
      'git': gitV?.stdout?.trim()?.replace('git version ', '') || '—',
      'openclaw': ocV?.stdout?.trim() || '—',
    })
  }

  useEffect(() => {
    refreshStatus()
  }, [])

  function runInTerminal(commandId: string) {
    bridge.call('showTerminal')
    bridge.call('writeCommandToTerminal', commandId)
  }



  if (!status?.installed) {
    return (
      <div className="page">
        <div className="setup-container" style={{ minHeight: 'calc(100vh - 80px)' }}>
          <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 64, height: 64, marginBottom: 4 }} />
          <div className="setup-title">{t('dash_setup_required')}</div>
          <div className="setup-subtitle">
            {t('dash_setup_desc')}
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="page">
      {/* Platform header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 24 }}>
        <img src="./openclaw.svg" alt="OpenClaw" style={{ width: 40, height: 40 }} />
        <div>
          <div style={{ fontSize: 20, fontWeight: 700 }}>
            {platform?.name || 'OpenClaw'}
          </div>
        </div>
      </div>

      {/* Commands */}
      <div className="section-title">{t('dash_commands')}</div>
      <div className="card">
        {getCommands().map((item, i) => (
          <div
            key={item.cmd}
            className="card-row"
            style={{ cursor: 'pointer', borderTop: i > 0 ? '1px solid var(--border)' : 'none', padding: '10px 0' }}
            onClick={() => runInTerminal(item.commandId)}
          >
            <div className="card-content">
              <div className="card-label">{item.label}</div>
              <div className="card-desc" style={{ fontFamily: 'monospace', fontSize: 12 }}>{item.cmd}</div>
            </div>
            <div className="card-chevron">›</div>
          </div>
        ))}
      </div>

      {/* Runtime info */}
      <div className="section-title">{t('dash_runtime')}</div>
      <div className="card">
        {Object.entries(runtimeInfo).map(([key, val]) => (
          <div className="info-row" key={key}>
            <span className="label">{key}</span>
            <span>{val}</span>
          </div>
        ))}
      </div>

      {/* Management */}
      <div className="section-title">{t('dash_management')}</div>
      <div className="card">
        {getManagement().map((item, i) => (
          <div
            key={item.cmd}
            className="card-row"
            style={{ cursor: 'pointer', borderTop: i > 0 ? '1px solid var(--border)' : 'none', padding: '10px 0' }}
            onClick={() => runInTerminal(item.commandId)}
          >
            <div className="card-content">
              <div className="card-label">{item.label}</div>
              <div className="card-desc" style={{ fontFamily: 'monospace', fontSize: 12 }}>{item.cmd}</div>
            </div>
            <div className="card-chevron">›</div>
          </div>
        ))}
      </div>
    </div>
  )
}
