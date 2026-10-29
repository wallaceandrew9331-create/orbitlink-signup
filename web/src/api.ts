export type Status = 'AVAILABLE' | 'WAITLIST'

export interface Availability {
  cell: string
  resolution: number
  status: Status
  slotsLeft: number
  waitlistDepth: number
  market: string
  currency: string
  depositMinor: number
}

export interface CellView {
  cell: string
  status: Status
  capacity: number
  reserved: number
  waiting: number
}

export interface Place {
  label: string
  lat: number
  lng: number
}

export interface Order {
  id: string
  h3Index: string
  status: 'RESERVED' | 'CONFIRMED' | 'CANCELLED'
  paymentStatus: 'NONE' | 'PENDING' | 'CAPTURED' | 'DECLINED'
  paymentAttempts: number
  depositMinor: number
  currency: string
  source: 'DIRECT' | 'WAITLIST'
}

export interface Placement {
  kind: 'ORDER' | 'WAITLIST'
  order: Order | null
  waitlist: { id: number; cell: string; position: number; ahead: number } | null
}

export interface OrderInput {
  lat: number
  lng: number
  email: string
  fullName: string
  locale: string
  paymentToken: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    readonly fieldErrors: Record<string, string> = {},
    readonly retryAfterSeconds?: number,
  ) {
    super(detail)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, init)
  if (!res.ok) {
    const problem = await res.json().catch(() => ({}))
    const retryAfter = res.headers.get('Retry-After')
    throw new ApiError(res.status, problem.detail ?? res.statusText, problem.errors ?? {},
      retryAfter ? Number(retryAfter) : undefined)
  }
  return res.json() as Promise<T>
}

export const api = {
  availability: (lat: number, lng: number) =>
    request<Availability>(`/api/availability?lat=${lat}&lng=${lng}`),
  cells: (lat: number, lng: number, k = 6) => request<CellView[]>(`/api/cells?lat=${lat}&lng=${lng}&k=${k}`),
  geocode: (q: string) => request<Place[]>(`/api/geocode?q=${encodeURIComponent(q)}`),
  order: (id: string) => request<Order>(`/api/orders/${id}`),
  pay: (id: string, paymentToken: string) =>
    request<Order>(`/api/orders/${id}/pay`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ paymentToken }),
    }),
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Worth retrying with the same key: the server may or may not have done the work. */
export function isRetryable(e: unknown): boolean {
  if (e instanceof ApiError) return e.status >= 500
  return e instanceof TypeError // fetch rejects with TypeError on network failure
}

/**
 * Submits an order, retrying network errors and 5xx with the SAME Idempotency-Key. The key is
 * what makes retrying safe: whichever attempt reached the server first created the order, and
 * every later attempt gets that same order back instead of a second one.
 */
export async function submitOrder(
  input: OrderInput,
  idempotencyKey: string,
  { attempts = 3, backoffMs = 400, fetchImpl = fetch, onRetry = (_attempt: number) => {} } = {},
): Promise<{ placement: Placement; replayed: boolean }> {
  let lastError: unknown
  for (let attempt = 0; attempt < attempts; attempt++) {
    try {
      const res = await fetchImpl('/api/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(input),
      })
      if (!res.ok) {
        const problem = await res.json().catch(() => ({}))
        const retryAfter = res.headers.get('Retry-After')
        throw new ApiError(res.status, problem.detail ?? res.statusText, problem.errors ?? {},
          retryAfter ? Number(retryAfter) : undefined)
      }
      return { placement: await res.json(), replayed: res.headers.get('Idempotent-Replayed') === 'true' }
    } catch (e) {
      lastError = e
      if (!isRetryable(e) || attempt === attempts - 1) break
      await sleep(backoffMs * 2 ** attempt)
      onRetry(attempt + 2)
    }
  }
  throw lastError
}

export const adminApi = {
  cells: (token: string) =>
    request<AdminCell[]>('/api/admin/cells', { headers: { Authorization: `Bearer ${token}` } }),
  addCapacity: (token: string, cell: string, slots: number) =>
    request<{ cell: string; slotsAdded: number; promoted: number }>(`/api/admin/cells/${cell}/capacity`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ slots }),
    }),
}

export interface AdminCell {
  cell: string
  lat: number
  lng: number
  currency: string
  capacity: number
  reserved: number
  waiting: number
  promoted: number
  confirmed: number
  awaitingPayment: number
  cancelled: number
  lookups: number
  conversion: number | null
}
