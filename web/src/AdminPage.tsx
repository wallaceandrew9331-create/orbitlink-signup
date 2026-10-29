import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError, type AdminCell } from './api'
import { useI18n } from './i18n'

function readToken(): string {
  try {
    return sessionStorage.getItem('adminToken') ?? ''
  } catch {
    return ''
  }
}

/** Back-office view. Kept in English: it's an internal tool, the customer flow is what's localised. */
export function AdminPage() {
  const { locale } = useI18n()
  const [token, setToken] = useState(readToken)
  const [cells, setCells] = useState<AdminCell[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [slots, setSlots] = useState<Record<string, number>>({})

  const load = useCallback(async (tok: string) => {
    try {
      setCells(await adminApi.cells(tok))
      setError(null)
    } catch (e) {
      setCells(null)
      setError(e instanceof ApiError && e.status === 401 ? 'Wrong admin token.' : String(e))
    }
  }, [])

  useEffect(() => {
    if (token) load(token)
  }, [token, load])

  function saveToken(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    const value = String(new FormData(e.currentTarget).get('token') ?? '')
    try {
      sessionStorage.setItem('adminToken', value)
    } catch { /* private mode: token lives only in memory */ }
    setToken(value)
  }

  async function addCapacity(cell: string) {
    const n = slots[cell] ?? 5
    try {
      const r = await adminApi.addCapacity(token, cell, n)
      setNotice(`Added ${r.slotsAdded} slots to ${cell}; promoted ${r.promoted} waitlisted customer(s). Notifications queued.`)
      load(token)
    } catch (e) {
      setError(String(e))
    }
  }

  const num = new Intl.NumberFormat(locale)
  const pct = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 1 })
  const total = (f: (c: AdminCell) => number) => num.format((cells ?? []).reduce((s, c) => s + f(c), 0))

  return (
    <section className="panel admin">
      <h2>Capacity by cell</h2>
      <form onSubmit={saveToken} className="row token-form">
        <label htmlFor="admin-token">Admin token</label>
        <input id="admin-token" name="token" type="password" defaultValue={token} autoComplete="off" />
        <button type="submit">Load</button>
      </form>
      {error && <p className="error" role="alert">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}
      {cells && (
        <div className="table-wrap">
          <table>
            <caption className="visually-hidden">Capacity, waitlist and conversion per H3 cell</caption>
            <thead>
              <tr>
                <th scope="col">Cell</th><th scope="col">Cur.</th><th scope="col">Capacity</th><th scope="col">Reserved</th>
                <th scope="col">Confirmed</th><th scope="col">Awaiting payment</th><th scope="col">Waiting</th>
                <th scope="col">Promoted</th><th scope="col">Lookups</th><th scope="col">Conversion</th><th scope="col">Add capacity</th>
              </tr>
            </thead>
            <tbody>
              {cells.map((c) => (
                <tr key={c.cell} className={c.waiting > 0 ? 'has-waitlist' : undefined}>
                  <th scope="row"><code>{c.cell}</code></th>
                  <td>{c.currency}</td>
                  <td>{num.format(c.capacity)}</td>
                  <td>{num.format(c.reserved)}</td>
                  <td>{num.format(c.confirmed)}</td>
                  <td>{num.format(c.awaitingPayment)}</td>
                  <td>{num.format(c.waiting)}</td>
                  <td>{num.format(c.promoted)}</td>
                  <td>{num.format(c.lookups)}</td>
                  <td>{c.conversion === null ? '–' : pct.format(c.conversion)}</td>
                  <td className="add">
                    <label className="visually-hidden" htmlFor={`slots-${c.cell}`}>Slots to add to {c.cell}</label>
                    <input id={`slots-${c.cell}`} type="number" min={1} max={10000} value={slots[c.cell] ?? 5}
                      onChange={(e) => setSlots({ ...slots, [c.cell]: Number(e.target.value) })} />
                    <button type="button" onClick={() => addCapacity(c.cell)}>Add</button>
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Total</th><td />
                <td>{total((c) => c.capacity)}</td><td>{total((c) => c.reserved)}</td><td>{total((c) => c.confirmed)}</td>
                <td>{total((c) => c.awaitingPayment)}</td><td>{total((c) => c.waiting)}</td><td>{total((c) => c.promoted)}</td>
                <td>{total((c) => c.lookups)}</td><td /><td />
              </tr>
            </tfoot>
          </table>
        </div>
      )}
    </section>
  )
}
