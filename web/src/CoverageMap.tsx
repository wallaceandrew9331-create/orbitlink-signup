import { useEffect, useRef } from 'react'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'
import { cellToBoundary } from 'h3-js'
import type { CellView } from './api'
import { useI18n } from './i18n'

interface Props {
  center: { lat: number; lng: number }
  cells: CellView[]
  selectedCell: string | null
  onPick: (lat: number, lng: number) => void
}

export function cellColor(c: CellView): string {
  if (c.status === 'WAITLIST') return 'var(--full)'
  return c.capacity - c.reserved <= Math.max(2, c.capacity * 0.2) ? 'var(--low)' : 'var(--ok)'
}

// Leaflet's SVG renderer takes colours as attributes, where CSS variables don't resolve.
function resolve(color: string): string {
  const m = color.match(/^var\((--[\w-]+)\)$/)
  return m ? getComputedStyle(document.documentElement).getPropertyValue(m[1]).trim() : color
}

/**
 * Leaflet used directly (no react-leaflet): the map is created once and React only pushes new
 * data into a layer group. The map is a convenience; the address search is the accessible path.
 */
export function CoverageMap({ center, cells, selectedCell, onPick }: Props) {
  const { t } = useI18n()
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<L.Map | null>(null)
  const layer = useRef<L.LayerGroup | null>(null)
  const pick = useRef(onPick)
  pick.current = onPick

  useEffect(() => {
    if (!container.current || map.current) return
    const m = L.map(container.current, { zoomControl: true }).setView([center.lat, center.lng], 8)
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 12,
      attribution: '&copy; OpenStreetMap contributors',
    }).addTo(m)
    m.on('click', (e: L.LeafletMouseEvent) => pick.current(e.latlng.lat, e.latlng.lng))
    layer.current = L.layerGroup().addTo(m)
    map.current = m
    return () => {
      m.remove()
      map.current = null
    }
  }, [])

  useEffect(() => {
    map.current?.setView([center.lat, center.lng], map.current.getZoom() < 7 ? 8 : map.current.getZoom())
  }, [center.lat, center.lng])

  useEffect(() => {
    const group = layer.current
    if (!group) return
    group.clearLayers()
    for (const c of cells) {
      const selected = c.cell === selectedCell
      const fill = resolve(cellColor(c))
      L.polygon(cellToBoundary(c.cell) as [number, number][], {
        color: selected ? resolve('var(--selected)') : fill,
        weight: selected ? 3 : 1,
        fillColor: fill,
        fillOpacity: selected ? 0.55 : 0.3,
      })
        .bindTooltip(`${c.capacity - c.reserved}/${c.capacity} · ${c.waiting} waiting`)
        .addTo(group)
    }
  }, [cells, selectedCell])

  return (
    <figure className="map-figure">
      <div ref={container} className="map" role="application" aria-label={t('map.label')} />
      <figcaption className="legend">
        <span><i style={{ background: 'var(--ok)' }} />{t('map.legend.available')}</span>
        <span><i style={{ background: 'var(--low)' }} />{t('map.legend.low')}</span>
        <span><i style={{ background: 'var(--full)' }} />{t('map.legend.full')}</span>
        <span><i className="selected" />{t('map.legend.selected')}</span>
      </figcaption>
    </figure>
  )
}
