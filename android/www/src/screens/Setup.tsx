import { useState, useCallback, useEffect, Fragment } from 'react'
import { bridge } from '../lib/bridge'
import { useNativeEvent } from '../lib/useNativeEvent'
import { t } from '../i18n'

interface Props {
  onComplete: () => void
}

type SetupPhase = 'platform-select' | 'tool-select' | 'installing' | 'failed' | 'done'

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
    { id: 'code-server', name: 'code-server', desc: t('tool_code_server') },
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

export function Setup({ onComplete }: Props) {
  const [phase, setPhase] = useState<SetupPhase>('platform-select')
  const [platforms, setPlatforms] = useState<Platform[]>([])
  const [selectedPlatform, setSelectedPlatform] = useState('')
  const [selectedTools, setSelectedTools] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(0)
  const [message, setMessage] = useState('')
  const [errorKind, setErrorKind] = useState('')
  const [tipIndex, setTipIndex] = useState(0)

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

  const onProgress = useCallback((data: unknown) => {
    const d = data as { progress?: number; message?: string; error?: string; errorKind?: string }
    if (d.errorKind || d.error !== undefined) {
      // Native refused or failed the setup: show a translated reason and let the user retry
      setErrorKind(d.errorKind || 'UNKNOWN')
      setPhase('failed')
      return
    }
    if (d.progress !== undefined) setProgress(d.progress)
    if (d.message) setMessage(d.message)
    if (d.progress !== undefined && d.progress >= 1) {
      setPhase('done')
    }
    setTipIndex(i => (i + 1) % getTips().length)
  }, [])

  useNativeEvent('setup_progress', onProgress)

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
    setPhase('installing')
    setProgress(0)
    setMessage(t('setup_preparing'))
    setErrorKind('')
    bridge.call('startSetup')
  }

  // --- Stepper ---
  const currentStep = phase === 'platform-select' ? 0
    : phase === 'tool-select' ? 1
    : phase === 'installing' || phase === 'failed' ? 2 : 3

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

  // --- Installing ---
  if (phase === 'installing') {
    const pct = Math.round(progress * 100)
    return (
      <div className="setup-container">
        {renderStepper()}
        <div className="setup-title">{t('setup_setting_up')}</div>

        <div style={{ width: '100%', maxWidth: 320 }}>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${pct}%` }} />
          </div>
          <div style={{ textAlign: 'center', fontSize: 13, color: 'var(--text-secondary)', marginTop: 8 }}>
            {pct}%
          </div>
          <div style={{ textAlign: 'center', fontSize: 12, color: 'var(--text-secondary)', marginTop: 4 }}>
            {message}
          </div>
        </div>

        <div className="tip-card">💡 {getTips()[tipIndex]}</div>
      </div>
    )
  }

  // --- Failed ---
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

  // --- Done ---
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
