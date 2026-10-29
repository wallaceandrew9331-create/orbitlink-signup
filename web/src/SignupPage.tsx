import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, submitOrder, type Availability, type CellView, type Order, type Place, type Placement } from './api'
import { CoverageMap } from './CoverageMap'
import { useI18n } from './i18n'

const DEFAULT_CENTER = { lat: 35.1495, lng: -90.049 } // Memphis

/** crypto.randomUUID only exists in secure contexts; fall back for plain-http LAN testing. */
function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return Array.from(crypto.getRandomValues(new Uint8Array(16)), (b) => b.toString(16).padStart(2, '0')).join('')
}

type Submission =
  | { state: 'idle' }
  | { state: 'submitting'; attempt: number }
  | { state: 'done'; placement: Placement }
  | { state: 'error'; message: string }

export function SignupPage() {
  const { t, money, locale } = useI18n()
  const ids = useId()

  const [query, setQuery] = useState('')
  const [matches, setMatches] = useState<Place[] | null>(null)
  const [point, setPoint] = useState<{ lat: number; lng: number; label?: string } | null>(null)
  const [center, setCenter] = useState(DEFAULT_CENTER)
  const [availability, setAvailability] = useState<Availability | null>(null)
  const [cells, setCells] = useState<CellView[]>([])

  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [card, setCard] = useState('tok_visa')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [submission, setSubmission] = useState<Submission>({ state: 'idle' })
  const resultHeading = useRef<HTMLHeadingElement>(null)

  // One key per distinct submission. Retries and double-clicks reuse it; editing the form makes a
  // new one, because the server rejects a reused key with a different body.
  const keyFor = useRef<{ body: string; key: string } | null>(null)

  const loadCells = useCallback((lat: number, lng: number) => {
    api.cells(lat, lng).then(setCells).catch(() => setCells([]))
  }, [])

  useEffect(() => loadCells(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng), [loadCells])

  const choose = useCallback(async (lat: number, lng: number, label?: string) => {
    setPoint({ lat, lng, label })
    setCenter({ lat, lng })
    setSubmission({ state: 'idle' })
    setMatches(null)
    loadCells(lat, lng)
    try {
      setAvailability(await api.availability(lat, lng))
      requestAnimationFrame(() => resultHeading.current?.focus())
    } catch (e) {
      setSubmission({ state: 'error', message: describe(e) })
    }
  }, [loadCells, locale])

  function describe(e: unknown): string {
    if (e instanceof ApiError) {
      if (e.status === 429) return t('error.rateLimited', { n: e.retryAfterSeconds ?? 1 })
      if (e.status === 409) return t('error.conflict')
      return t('error.generic', { detail: e.detail })
    }
    return t('error.generic', { detail: String(e) })
  }

  async function search(e: FormEvent) {
    e.preventDefault()
    if (query.trim().length < 2) return
    try {
      const found = await api.geocode(query)
      setMatches(found)
      if (found.length === 1) await choose(found[0].lat, found[0].lng, found[0].label)
    } catch (err) {
      setSubmission({ state: 'error', message: describe(err) })
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!point || submission.state === 'submitting') return
    const input = { lat: point.lat, lng: point.lng, email, fullName, locale, paymentToken: card }
    const body = JSON.stringify(input)
    if (keyFor.current?.body !== body) keyFor.current = { body, key: newIdempotencyKey() }

    setFieldErrors({})
    setSubmission({ state: 'submitting', attempt: 1 })
    try {
      const { placement } = await submitOrder(input, keyFor.current.key, {
        onRetry: (attempt) => setSubmission({ state: 'submitting', attempt }),
      })
      setSubmission({ state: 'done', placement })
      loadCells(point.lat, point.lng)
      requestAnimationFrame(() => resultHeading.current?.focus())
    } catch (err) {
      if (err instanceof ApiError) setFieldErrors(err.fieldErrors)
      setSubmission({ state: 'error', message: describe(err) })
    }
  }

  // While a payment outcome is unknown the server's reconciler is working on it; poll until it settles.
  const pendingOrder = submission.state === 'done' && submission.placement.order?.paymentStatus === 'PENDING'
    ? submission.placement.order : null
  useEffect(() => {
    if (!pendingOrder) return
    const timer = setInterval(async () => {
      try {
        const order = await api.order(pendingOrder.id)
        if (order.paymentStatus !== 'PENDING') {
          setSubmission({ state: 'done', placement: { kind: 'ORDER', order, waitlist: null } })
        }
      } catch {
        /* keep polling */
      }
    }, 3000)
    return () => clearInterval(timer)
  }, [pendingOrder])

  function reset() {
    setPoint(null)
    setAvailability(null)
    setSubmission({ state: 'idle' })
    keyFor.current = null
  }

  const full = availability?.status === 'WAITLIST'
  const busy = submission.state === 'submitting'

  return (
    <div className="signup">
      <section className="panel" aria-labelledby={`${ids}-search`}>
        <form onSubmit={search} className="search" role="search">
          <label id={`${ids}-search`} htmlFor={`${ids}-q`}>{t('search.label')}</label>
          <div className="row">
            <input id={`${ids}-q`} value={query} onChange={(e) => setQuery(e.target.value)}
              placeholder={t('search.placeholder')} autoComplete="street-address" minLength={2} required />
            <button type="submit">{t('search.button')}</button>
          </div>
        </form>
        {matches && matches.length !== 1 && (
          matches.length === 0
            ? <p className="hint" role="status">{t('search.none')}</p>
            : <ul className="matches">
                {matches.map((m) => (
                  <li key={m.label}><button type="button" className="link" onClick={() => choose(m.lat, m.lng, m.label)}>{m.label}</button></li>
                ))}
              </ul>
        )}

        {availability && point && submission.state !== 'done' && (
          <div className="result" aria-live="polite">
            <h2 ref={resultHeading} tabIndex={-1}>{t('result.heading')}</h2>
            {point.label && <p className="address">{point.label}</p>}
            <p className={`badge ${full ? 'full' : 'ok'}`}>{full ? t('result.full') : t('result.available')}</p>
            <dl className="facts">
              <div><dt>H3</dt><dd><code>{availability.cell}</code> (res {availability.resolution})</dd></div>
              <div><dt>{full ? t('result.waitlist', { n: availability.waitlistDepth }) : t('result.slots', { n: availability.slotsLeft })}</dt></div>
              <div><dt>{t('result.deposit')}</dt><dd><strong>{money(availability.depositMinor, availability.currency)}</strong></dd></div>
            </dl>

            <form onSubmit={submit} className="order-form" noValidate>
              <h3>{full ? t('form.heading.waitlist') : t('form.heading.order')}</h3>
              <Field id={`${ids}-name`} label={t('form.name')} error={fieldErrors.fullName}>
                <input id={`${ids}-name`} value={fullName} onChange={(e) => setFullName(e.target.value)}
                  autoComplete="name" maxLength={100} required aria-invalid={!!fieldErrors.fullName} />
              </Field>
              <Field id={`${ids}-email`} label={t('form.email')} error={fieldErrors.email}>
                <input id={`${ids}-email`} type="email" value={email} onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email" inputMode="email" maxLength={254} required aria-invalid={!!fieldErrors.email} />
              </Field>
              {full
                ? <p className="hint">{t('form.waitlistNote')}</p>
                : <Field id={`${ids}-card`} label={t('form.card')}>
                    <select id={`${ids}-card`} value={card} onChange={(e) => setCard(e.target.value)}>
                      <option value="tok_visa">{t('form.card.ok')}</option>
                      <option value="tok_decline">{t('form.card.decline')}</option>
                    </select>
                  </Field>}
              <button type="submit" className="primary" disabled={busy} aria-busy={busy}>
                {busy ? (submission.attempt > 1 ? t('form.retrying', { n: submission.attempt }) : t('form.submitting'))
                  : full ? t('form.submit.waitlist') : t('form.submit.order')}
              </button>
            </form>
          </div>
        )}

        {submission.state === 'done' && (
          <div className="result" aria-live="polite">
            <Outcome placement={submission.placement} headingRef={resultHeading} />
            <button type="button" className="link" onClick={reset}>{t('done.again')}</button>
          </div>
        )}
        {submission.state === 'error' && <p className="error" role="alert">{submission.message}</p>}
      </section>

      <section className="panel map-panel">
        <CoverageMap center={center} cells={cells} selectedCell={availability?.cell ?? null}
          onPick={(lat, lng) => choose(lat, lng)} />
      </section>
    </div>
  )
}

function Field({ id, label, error, children }: { id: string; label: string; error?: string; children: React.ReactNode }) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <span className="field-error" id={`${id}-error`}>{error}</span>}
    </div>
  )
}

function Outcome({ placement, headingRef }: { placement: Placement; headingRef: React.RefObject<HTMLHeadingElement | null> }) {
  const { t, money } = useI18n()
  if (placement.kind === 'WAITLIST' && placement.waitlist) {
    return <>
      <h2 ref={headingRef} tabIndex={-1}>{t('done.waitlist', { position: placement.waitlist.position })}</h2>
      <p>{t('done.waitlistBody', { n: placement.waitlist.ahead })}</p>
    </>
  }
  const order = placement.order as Order
  if (order.paymentStatus === 'PENDING') {
    return <>
      <h2 ref={headingRef} tabIndex={-1}><span className="spinner" aria-hidden="true" /> {t('done.pending')}</h2>
      <p>{t('done.pendingBody')}</p>
    </>
  }
  if (order.status === 'CANCELLED') {
    return <>
      <h2 ref={headingRef} tabIndex={-1}>{t('done.declined')}</h2>
      <p>{t('done.declinedBody')}</p>
    </>
  }
  return <>
    <h2 ref={headingRef} tabIndex={-1}>{t('done.confirmed')}</h2>
    <p>{t('done.confirmedBody', { amount: money(order.depositMinor, order.currency), id: order.id.slice(0, 8) })}</p>
  </>
}
