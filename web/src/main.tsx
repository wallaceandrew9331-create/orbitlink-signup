import { StrictMode, useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { AdminPage } from './AdminPage'
import { I18nProvider, LOCALES, useI18n, type Locale } from './i18n'
import { SignupPage } from './SignupPage'
import './styles.css'

function useHashRoute(): string {
  const [hash, setHash] = useState(window.location.hash)
  useEffect(() => {
    const onChange = () => setHash(window.location.hash)
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return hash
}

function App() {
  const { t, locale, setLocale } = useI18n()
  const route = useHashRoute()
  const admin = route === '#/admin'

  useEffect(() => {
    document.documentElement.lang = locale
  }, [locale])

  return (
    <>
      <a className="skip" href="#main">Skip to content</a>
      <header className="top">
        <div className="brand">
          <img src="/favicon.svg" alt="" width={28} height={28} />
          <div>
            <h1>Orbitlink</h1>
            <p>{t('app.tagline')}</p>
          </div>
        </div>
        <nav aria-label="Main">
          <a href="#/" aria-current={!admin ? 'page' : undefined}>{t('nav.signup')}</a>
          <a href="#/admin" aria-current={admin ? 'page' : undefined}>{t('nav.admin')}</a>
        </nav>
        <label className="lang">
          <span className="visually-hidden">{t('lang.label')}</span>
          <select value={locale} onChange={(e) => setLocale(e.target.value as Locale)} aria-label={t('lang.label')}>
            {LOCALES.map((l) => <option key={l} value={l}>{l === 'en-US' ? 'English (US)' : 'Español (MX)'}</option>)}
          </select>
        </label>
      </header>
      <main id="main">{admin ? <AdminPage /> : <SignupPage />}</main>
    </>
  )
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <I18nProvider>
      <App />
    </I18nProvider>
  </StrictMode>,
)
