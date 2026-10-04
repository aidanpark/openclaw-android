import { useRoute } from '../lib/router'
import { t, getLocale, setLocale, availableLocales } from '../i18n'

interface MenuItem {
  icon: string
  label: string
  desc: string
  route: string
  badge?: boolean
}

function getMenu(): MenuItem[] {
  return [
    { icon: '📱', label: t('settings_platforms'), desc: t('settings_platforms_desc'), route: '/settings/platforms' },
    { icon: '🔄', label: t('settings_updates'), desc: t('settings_updates_desc'), route: '/settings/updates', badge: false },
    { icon: '⚡', label: t('settings_keep_alive'), desc: t('settings_keep_alive_desc'), route: '/settings/keep-alive' },
    { icon: '💾', label: t('settings_storage'), desc: t('settings_storage_desc'), route: '/settings/storage' },
    { icon: 'ℹ️', label: t('settings_about'), desc: t('settings_about_desc'), route: '/settings/about' },
  ]
}

export function Settings() {
  const { navigate } = useRoute()

  return (
    <div className="page">
      <div className="page-title" style={{ marginBottom: 24 }}>{t('settings_title')}</div>
      {/* Language selector */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-row" style={{ flexWrap: 'wrap', rowGap: 8 }}>
          <span className="card-icon">🌐</span>
          <div className="card-content" style={{ flex: '1 1 120px', minWidth: 0 }}>
            <div className="card-label">Language</div>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end', marginLeft: 'auto', maxWidth: '100%' }}>
            {availableLocales.map(loc => (
              <button
                key={loc.code}
                className={`btn ${getLocale() === loc.code ? 'btn-primary' : ''}`}
                // .btn has min-width:120px, three of them overflow a 360dp screen — compact here, wrap if still too wide
                style={{ padding: '4px 12px', fontSize: 13, whiteSpace: 'nowrap', minWidth: 0, minHeight: 36 }}
                onClick={() => { setLocale(loc.code); window.location.reload() }}
              >
                {loc.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {getMenu().map(item => (
        <div key={item.route} className="card" onClick={() => navigate(item.route)}>
          <div className="card-row">
            <span className="card-icon">{item.icon}</span>
            <div className="card-content">
              <div className="card-label">{item.label}</div>
              <div className="card-desc">{item.desc}</div>
            </div>
            {item.badge && <span className="card-badge" />}
            <span className="card-chevron">›</span>
          </div>
        </div>
      ))}
    </div>
  )
}
