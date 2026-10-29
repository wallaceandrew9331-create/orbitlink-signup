// Launch-day spike: availability lookups and orders ramp from a trickle to a burst, concentrated
// on a few launch cities so a handful of H3 cells get hammered (the hard case for row locks).
// An "abuser" hammers from one IP to check the rate limiter holds under load.
import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'

const BASE = __ENV.BASE_URL || 'http://api:8080'
const PEAK_LOOKUPS = Number(__ENV.PEAK_LOOKUPS || 1500)
const PEAK_ORDERS = Number(__ENV.PEAK_ORDERS || 300)

const spike = (peak) => [
  { duration: '20s', target: Math.round(peak / 15) }, // normal day
  { duration: '10s', target: peak },                  // the launch announcement lands
  { duration: '60s', target: peak },                  // sustained burst
  { duration: '10s', target: 0 },
]

export const options = {
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    availability: {
      executor: 'ramping-arrival-rate', exec: 'availability', startRate: 20, timeUnit: '1s',
      preAllocatedVUs: 200, maxVUs: 2000, stages: spike(PEAK_LOOKUPS),
    },
    orders: {
      executor: 'ramping-arrival-rate', exec: 'order', startRate: 5, timeUnit: '1s',
      preAllocatedVUs: 100, maxVUs: 2000, stages: spike(PEAK_ORDERS),
    },
    abuser: {
      executor: 'constant-arrival-rate', exec: 'abuser', rate: 50, timeUnit: '1s',
      duration: '100s', preAllocatedVUs: 10, maxVUs: 50,
    },
  },
  // Thresholds double as the way to get per-scenario numbers into the summary.
  thresholds: {
    'http_req_duration{scenario:availability}': ['p(99)<1000'],
    'http_req_duration{scenario:orders}': ['p(99)<3000'],
    'http_req_failed{scenario:availability}': ['rate<0.01'],
    'checks{scenario:orders}': ['rate>0.99'],
  },
}

const METROS = [
  [35.1495, -90.049], [30.2672, -97.7431], [39.7392, -104.9903], [47.6062, -122.3321], [25.6866, -100.3161],
]

const reserved = new Counter('orders_reserved')
const waitlisted = new Counter('orders_waitlisted')
const replayMismatch = new Counter('idempotency_replay_mismatch')
const rateLimited = new Counter('rate_limited')

function point() {
  if (Math.random() < 0.7) {
    const [lat, lng] = METROS[Math.floor(Math.random() * METROS.length)]
    return { lat: lat + (Math.random() - 0.5) * 0.4, lng: lng + (Math.random() - 0.5) * 0.4 }
  }
  return { lat: 30 + Math.random() * 15, lng: -120 + Math.random() * 40 }
}

// A different client address per request; the API trusts X-Forwarded-For in this setup.
const octet = () => Math.floor(Math.random() * 256)
const ip = () => `10.${octet()}.${octet()}.${1 + Math.floor(Math.random() * 254)}`

export function availability() {
  const p = point()
  const res = http.get(`${BASE}/api/availability?lat=${p.lat.toFixed(5)}&lng=${p.lng.toFixed(5)}`,
    { headers: { 'X-Forwarded-For': ip() }, tags: { name: 'availability' } })
  check(res, { 'availability 200': (r) => r.status === 200 })
}

export function order() {
  const p = point()
  const id = `${__VU}-${__ITER}-${Math.random().toString(36).slice(2, 10)}`
  const body = JSON.stringify({
    lat: p.lat, lng: p.lng, email: `load-${id}@example.com`, fullName: 'Load Test',
    locale: Math.random() < 0.5 ? 'en-US' : 'es-MX', paymentToken: 'tok_visa',
  })
  const params = {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `load-key-${id}`, 'X-Forwarded-For': ip() },
    tags: { name: 'order' },
  }
  const res = http.post(`${BASE}/api/orders`, body, params)
  const ok = check(res, { 'order 201': (r) => r.status === 201 })
  if (!ok) return
  const placed = res.json()
  if (placed.kind === 'ORDER') reserved.add(1)
  else waitlisted.add(1)

  // 5% of customers double-click: same key, same body. They must get the same resource back.
  if (Math.random() < 0.05) {
    const again = http.post(`${BASE}/api/orders`, body, { ...params, tags: { name: 'order_replay' } })
    const a = again.status === 200 ? again.json() : {}
    const same = placed.kind === 'ORDER'
      ? a.order && a.order.id === placed.order.id
      : a.waitlist && a.waitlist.id === placed.waitlist.id
    if (!same) replayMismatch.add(1)
  }
}

export function abuser() {
  const res = http.get(`${BASE}/api/availability?lat=35.14&lng=-90.05`,
    { headers: { 'X-Forwarded-For': '203.0.113.66' }, tags: { name: 'abuser' } })
  if (res.status === 429) rateLimited.add(1)
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY || '/results/summary.json'
  return { [out]: JSON.stringify(data, null, 2), stdout: textSummary(data) }
}

function textSummary(data) {
  const m = data.metrics
  const trend = (k) => m[k]
    ? ['p(50)', 'p(95)', 'p(99)', 'max'].map((s) => `${s}=${m[k].values[s].toFixed(1)}ms`).join(' ')
    : 'n/a'
  const rate = (k) => (m[k] ? (m[k].values.rate * 100).toFixed(2) + '%' : 'n/a')
  const count = (k) => (m[k] ? m[k].values.count : 0)
  return [
    `availability: ${trend('http_req_duration{scenario:availability}')} failed=${rate('http_req_failed{scenario:availability}')}`,
    `orders:       ${trend('http_req_duration{scenario:orders}')} ok=${rate('checks{scenario:orders}')}`,
    `reserved=${count('orders_reserved')} waitlisted=${count('orders_waitlisted')} replay_mismatch=${count('idempotency_replay_mismatch')}`,
    `abuser 429s=${count('rate_limited')} iterations=${count('iterations')} dropped=${count('dropped_iterations')}`,
    '',
  ].join('\n')
}
