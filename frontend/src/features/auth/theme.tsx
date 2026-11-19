import { useSyncExternalStore } from 'react'

type Theme = 'light' | 'dark'

const storageKey = 'api-sentinel-theme'
const themeChange = 'api-sentinel-theme-change'

function readTheme(): Theme {
  try {
    return localStorage.getItem(storageKey) === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

let theme: Theme = typeof window === 'undefined' ? 'dark' : readTheme()

function applyTheme(value: Theme) {
  theme = value
  document.documentElement.dataset.theme = value
}

if (typeof window !== 'undefined') applyTheme(theme)

function chooseTheme(value: Theme) {
  applyTheme(value)
  try {
    localStorage.setItem(storageKey, value)
  } catch {
    // The current tab still uses the selected theme when storage is disabled.
  }
  window.dispatchEvent(new Event(themeChange))
}

function subscribe(notify: () => void) {
  function sync(event: StorageEvent) {
    if (event.key === storageKey || event.key === null) {
      applyTheme(readTheme())
      notify()
    }
  }

  window.addEventListener('storage', sync)
  window.addEventListener(themeChange, notify)
  return () => {
    window.removeEventListener('storage', sync)
    window.removeEventListener(themeChange, notify)
  }
}

export function ThemeSwitch() {
  const current = useSyncExternalStore<Theme>(subscribe, () => theme, () => 'dark')
  return <div className="theme-switch" data-value={current} role="group" aria-label="Color theme">
    <span className="theme-thumb" aria-hidden="true" />
    <button type="button" aria-label="Light" title="Light theme" aria-pressed={current === 'light'} onClick={() => chooseTheme('light')}>
      <span className="theme-sun" aria-hidden="true" />
    </button>
    <button type="button" aria-label="Dark" title="Dark theme" aria-pressed={current === 'dark'} onClick={() => chooseTheme('dark')}>
      <span className="theme-moon" aria-hidden="true" />
    </button>
  </div>
}
