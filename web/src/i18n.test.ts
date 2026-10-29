import { describe, expect, it } from 'vitest'
import { formatMoney, translate } from './i18n'

// Intl output uses non-breaking spaces in some locales; normalise so expectations stay readable.
const plain = (s: string) => s.replace(/ | /g, ' ')

describe('formatMoney', () => {
  it('uses the currency minor-unit exponent and the locale separators', () => {
    expect(plain(formatMoney('en-US', 9900, 'USD'))).toBe('$99.00')
    expect(plain(formatMoney('en-US', 179900, 'MXN'))).toBe('MX$1,799.00')
    expect(plain(formatMoney('es-MX', 179900, 'MXN'))).toBe('$1,799.00')
    expect(plain(formatMoney('es-MX', 9900, 'EUR'))).toBe('EUR 99.00')
  })

  it('does not assume two decimals', () => {
    expect(plain(formatMoney('en-US', 1500, 'JPY'))).toBe('¥1,500')
  })
})

describe('translate', () => {
  it('fills placeholders and formats numbers per locale', () => {
    expect(translate('en-US', 'result.waitlist', { n: 1234 })).toBe('1,234 people on the waitlist')
    expect(translate('es-MX', 'done.waitlist', { position: 7 })).toBe('Eres el n.º 7 en la lista de espera')
  })

  it('picks the singular form from Intl.PluralRules', () => {
    expect(translate('es-MX', 'done.waitlistBody', { n: 1 })).toMatch(/^Hay 1 persona antes/)
    expect(translate('es-MX', 'done.waitlistBody', { n: 3 })).toMatch(/^Hay 3 personas antes/)
    expect(translate('en-US', 'result.waitlist', { n: 0 })).toBe('0 people on the waitlist')
  })
})
