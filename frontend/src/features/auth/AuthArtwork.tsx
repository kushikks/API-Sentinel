import type { CSSProperties, PointerEvent } from 'react'

const fireflies = [
  [11, 26, 3, 0], [20, 16, 2, 3], [32, 30, 3, 1], [40, 11, 2, 5],
  [53, 23, 4, 4], [70, 16, 3, 2], [83, 26, 3, 7], [91, 42, 2, 1],
  [72, 48, 4, 5], [62, 72, 3, 4], [37, 83, 3, 1], [17, 73, 2, 4],
  [49, 65, 3, 7], [82, 85, 2, 0], [8, 49, 3, 6], [91, 69, 3, 3],
]
const reducedMotion = typeof window === 'undefined' ? null : window.matchMedia('(prefers-reduced-motion: reduce)')

export function AuthArtwork() {
  function follow(event: PointerEvent<HTMLDivElement>) {
    if (event.pointerType !== 'mouse' || reducedMotion?.matches) return
    const { left, top } = event.currentTarget.getBoundingClientRect()
    event.currentTarget.style.setProperty('--pointer-x', `${event.clientX - left}px`)
    event.currentTarget.style.setProperty('--pointer-y', `${event.clientY - top}px`)
  }

  return <div className="auth-art" aria-hidden="true" onPointerMove={follow}>
    <div className="art-grid" />
    <div className="art-orbits">
      <div className="orbit orbit-outer" />
      <div className="orbit orbit-middle" />
      <div className="orbit orbit-inner" />
      <div className="orbit orbit-signal" />
      <div className="orbit-core"><i /><i /><i /></div>
    </div>
    {fireflies.map(([x, y, size, delay]) =>
      <i key={x + '-' + y} className="firefly" style={{
        '--x': x + '%', '--y': y + '%', '--size': size + 'px', '--delay': -delay + 's',
      } as CSSProperties} />
    )}
    <div className="art-card art-before">
      <div className="art-card-label"><span className="art-status" /> OpenAPI v1</div>
      <div className="art-code"><span className="art-method">GET</span> /students/&#123;id&#125;</div>
      <div className="art-line removed">− &quot;studentId&quot;: 42</div>
    </div>
    <div className="art-card art-after">
      <div className="art-card-label"><span className="art-status" /> OpenAPI v2</div>
      <div className="art-code"><span className="art-method">GET</span> /students/&#123;id&#125;</div>
      <div className="art-line added">+ &quot;id&quot;: 42</div>
    </div>
  </div>
}
