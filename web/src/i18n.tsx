import { createContext, useContext, useMemo, useState, type ReactNode } from 'react'

export const LOCALES = ['en-US', 'es-MX'] as const
export type Locale = (typeof LOCALES)[number]

const en = {
  'app.tagline': 'Satellite internet, one hexagon at a time',
  'nav.signup': 'Check availability',
  'nav.admin': 'Admin',
  'lang.label': 'Language',
  'search.label': 'Service address',
  'search.placeholder': 'Try "Memphis", "Berlin" or "Monterrey"',
  'search.button': 'Search',
  'search.none': 'No matching addresses. You can also click the map.',
  'map.label': 'Coverage map. Click a hexagon to check that area.',
  'map.legend.available': 'Available',
  'map.legend.low': 'Few slots left',
  'map.legend.full': 'Waitlist only',
  'map.legend.selected': 'Your cell',
  'result.heading': 'Your coverage cell',
  'result.available': 'Service available',
  'result.full': 'This cell is full',
  'result.slots': '{n} slots left in this cell',
  'result.waitlist': '{n} people on the waitlist',
  'result.waitlist.one': '1 person on the waitlist',
  'result.deposit': 'Refundable deposit',
  'form.heading.order': 'Reserve your kit',
  'form.heading.waitlist': 'Join the waitlist',
  'form.name': 'Full name',
  'form.email': 'Email',
  'form.card': 'Payment method',
  'form.card.ok': 'Test card (approves)',
  'form.card.decline': 'Test card (declines)',
  'form.waitlistNote': 'No charge now. We take the deposit when a slot opens for you.',
  'form.submit.order': 'Pay deposit and order',
  'form.submit.waitlist': 'Join waitlist',
  'form.submitting': 'Submitting…',
  'form.retrying': 'Connection problem, retrying (attempt {n})…',
  'done.confirmed': 'Order confirmed',
  'done.confirmedBody': 'Your deposit of {amount} was received. Order {id}.',
  'done.pending': 'Confirming your payment',
  'done.pendingBody': 'Our payment provider is slow to answer. Your slot is held, and this page updates by itself. You will not be charged twice.',
  'done.declined': 'Payment declined',
  'done.declinedBody': 'Your card was declined and the slot was released. You can try again with another card.',
  'done.waitlist': "You're #{position} on the waitlist",
  'done.waitlistBody': '{n} people are ahead of you. We will email you when a slot opens.',
  'done.waitlistBody.one': '1 person is ahead of you. We will email you when a slot opens.',
  'done.again': 'Check another address',
  'error.rateLimited': 'Too many attempts. Please wait {n} seconds and try again.',
  'error.conflict': 'This email already has an order or waitlist spot in this cell.',
  'error.generic': 'Something went wrong: {detail}',
}

type Key = keyof typeof en

const es: Record<Key, string> = {
  'app.tagline': 'Internet satelital, un hexágono a la vez',
  'nav.signup': 'Consultar disponibilidad',
  'nav.admin': 'Administración',
  'lang.label': 'Idioma',
  'search.label': 'Dirección del servicio',
  'search.placeholder': 'Prueba "Memphis", "Berlin" o "Monterrey"',
  'search.button': 'Buscar',
  'search.none': 'No hay direcciones que coincidan. También puedes hacer clic en el mapa.',
  'map.label': 'Mapa de cobertura. Haz clic en un hexágono para consultar esa zona.',
  'map.legend.available': 'Disponible',
  'map.legend.low': 'Quedan pocos lugares',
  'map.legend.full': 'Solo lista de espera',
  'map.legend.selected': 'Tu celda',
  'result.heading': 'Tu celda de cobertura',
  'result.available': 'Servicio disponible',
  'result.full': 'Esta celda está llena',
  'result.slots': 'Quedan {n} lugares en esta celda',
  'result.waitlist': '{n} personas en la lista de espera',
  'result.waitlist.one': '1 persona en la lista de espera',
  'result.deposit': 'Depósito reembolsable',
  'form.heading.order': 'Reserva tu kit',
  'form.heading.waitlist': 'Únete a la lista de espera',
  'form.name': 'Nombre completo',
  'form.email': 'Correo electrónico',
  'form.card': 'Método de pago',
  'form.card.ok': 'Tarjeta de prueba (aprueba)',
  'form.card.decline': 'Tarjeta de prueba (rechaza)',
  'form.waitlistNote': 'Sin cargo ahora. Cobramos el depósito cuando se libere un lugar para ti.',
  'form.submit.order': 'Pagar depósito y pedir',
  'form.submit.waitlist': 'Unirme a la lista',
  'form.submitting': 'Enviando…',
  'form.retrying': 'Problema de conexión, reintentando (intento {n})…',
  'done.confirmed': 'Pedido confirmado',
  'done.confirmedBody': 'Recibimos tu depósito de {amount}. Pedido {id}.',
  'done.pending': 'Confirmando tu pago',
  'done.pendingBody': 'Nuestro proveedor de pagos tarda en responder. Tu lugar está reservado y esta página se actualiza sola. No se te cobrará dos veces.',
  'done.declined': 'Pago rechazado',
  'done.declinedBody': 'Tu tarjeta fue rechazada y el lugar se liberó. Puedes intentarlo con otra tarjeta.',
  'done.waitlist': 'Eres el n.º {position} en la lista de espera',
  'done.waitlistBody': 'Hay {n} personas antes que tú. Te escribiremos cuando se libere un lugar.',
  'done.waitlistBody.one': 'Hay 1 persona antes que tú. Te escribiremos cuando se libere un lugar.',
  'done.again': 'Consultar otra dirección',
  'error.rateLimited': 'Demasiados intentos. Espera {n} segundos e inténtalo de nuevo.',
  'error.conflict': 'Este correo ya tiene un pedido o lugar en la lista de espera en esta celda.',
  'error.generic': 'Algo salió mal: {detail}',
}

const MESSAGES: Record<Locale, Record<Key, string>> = { 'en-US': en, 'es-MX': es }

/**
 * A key may have a ".one" variant; Intl.PluralRules decides when it applies from the {n} param
 * (English and Spanish only need one/other, other languages have more categories).
 */
export function translate(locale: Locale, key: Key, params: Record<string, string | number> = {}): string {
  const messages = MESSAGES[locale]
  const singular = `${key}.one` as Key
  const useSingular = typeof params.n === 'number' && singular in messages
    && new Intl.PluralRules(locale).select(params.n) === 'one'
  return messages[useSingular ? singular : key].replace(/\{(\w+)\}/g, (_, name) =>
    name in params ? formatParam(locale, params[name]) : `{${name}}`)
}

function formatParam(locale: Locale, value: string | number): string {
  return typeof value === 'number' ? new Intl.NumberFormat(locale).format(value) : value
}

/**
 * Money arrives from the API in minor units. How many decimals a currency has (USD 2, JPY 0)
 * comes from Intl, not a hard-coded 100, and the locale decides symbol placement and separators.
 */
export function formatMoney(locale: Locale, minor: number, currency: string): string {
  const fmt = new Intl.NumberFormat(locale, { style: 'currency', currency })
  const digits = fmt.resolvedOptions().maximumFractionDigits ?? 2
  return fmt.format(minor / 10 ** digits)
}

export function detectLocale(): Locale {
  const saved = safeStorage(() => localStorage.getItem('locale'))
  if (saved && (LOCALES as readonly string[]).includes(saved)) return saved as Locale
  return navigator.language?.toLowerCase().startsWith('es') ? 'es-MX' : 'en-US'
}

function safeStorage<T>(fn: () => T): T | null {
  try {
    return fn()
  } catch {
    return null
  }
}

interface I18n {
  locale: Locale
  setLocale: (l: Locale) => void
  t: (key: Key, params?: Record<string, string | number>) => string
  money: (minor: number, currency: string) => string
}

const I18nContext = createContext<I18n | null>(null)

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(detectLocale)
  const value = useMemo<I18n>(() => ({
    locale,
    setLocale: (l) => {
      setLocaleState(l)
      safeStorage(() => localStorage.setItem('locale', l))
      document.documentElement.lang = l
    },
    t: (key, params) => translate(locale, key, params),
    money: (minor, currency) => formatMoney(locale, minor, currency),
  }), [locale])
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18n {
  const ctx = useContext(I18nContext)
  if (!ctx) throw new Error('useI18n outside I18nProvider')
  return ctx
}
