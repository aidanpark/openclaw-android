import { StrictMode, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Router } from './lib/router'
import { App } from './App'
import { ErrorBoundary } from './components/ErrorBoundary'
import { LocaleContext, getLocale } from './i18n'
import './styles/global.css'

// Existing debt: entry file with a local component; moving it is a refactor with no behavior gain
// eslint-disable-next-line react-refresh/only-export-components
function Root() {
  const [locale] = useState(getLocale)
  return (
    <StrictMode>
      <LocaleContext.Provider value={locale}>
        <ErrorBoundary>
          <Router>
            <App />
          </Router>
        </ErrorBoundary>
      </LocaleContext.Provider>
    </StrictMode>
  )
}

createRoot(document.getElementById('root')!).render(<Root />)
