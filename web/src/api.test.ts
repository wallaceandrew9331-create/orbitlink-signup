import { describe, expect, it } from 'vitest'
import { ApiError, submitOrder } from './api'

const input = { lat: 35.1, lng: -90, email: 'a@example.com', fullName: 'A', locale: 'en-US', paymentToken: 'tok_visa' }
const ok = (body: unknown, replayed = false) =>
  new Response(JSON.stringify(body), { status: replayed ? 200 : 201, headers: { 'Idempotent-Replayed': String(replayed) } })

describe('submitOrder', () => {
  it('retries network errors and 5xx with the same Idempotency-Key', async () => {
    const keys: string[] = []
    const responses: Array<() => Response> = [
      () => { throw new TypeError('network down') },
      () => new Response('{}', { status: 503 }),
      () => ok({ kind: 'ORDER', order: { id: 'o1' }, waitlist: null }, true),
    ]
    const fetchImpl = async (_url: RequestInfo | URL, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key')!)
      return responses[keys.length - 1]()
    }

    const result = await submitOrder(input, 'key-123', { fetchImpl: fetchImpl as typeof fetch, backoffMs: 1 })

    expect(keys).toEqual(['key-123', 'key-123', 'key-123'])
    expect(result.replayed).toBe(true)
    expect(result.placement.order?.id).toBe('o1')
  })

  it('does not retry client errors', async () => {
    let calls = 0
    const fetchImpl = async () => {
      calls++
      return new Response(JSON.stringify({ detail: 'nope' }), { status: 409 })
    }
    await expect(submitOrder(input, 'k', { fetchImpl: fetchImpl as typeof fetch, backoffMs: 1 }))
      .rejects.toBeInstanceOf(ApiError)
    expect(calls).toBe(1)
  })
})
