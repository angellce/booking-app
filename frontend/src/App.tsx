import { useState } from 'react'
import BookingView from './BookingView'
import HostView from './HostView'

type View = 'book' | 'host'

export default function App() {
  const [view, setView] = useState<View>('book')

  return (
    <>
      <header className="navbar">
        <div className="container navbar-inner">
          <h1 className="brand">
            <svg className="brand-icon" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="5" width="18" height="16" rx="3" />
              <path d="M3 10h18M8 3v4M16 3v4" />
            </svg>
            Book a Slot
          </h1>
          <nav aria-label="Main">
            <button
              type="button"
              className="nav-button"
              aria-current={view === 'book' ? 'page' : undefined}
              onClick={() => setView('book')}
            >
              Book
            </button>
            <button
              type="button"
              className="nav-button"
              aria-current={view === 'host' ? 'page' : undefined}
              onClick={() => setView('host')}
            >
              Host
            </button>
          </nav>
        </div>
      </header>
      <main className="container">{view === 'book' ? <BookingView /> : <HostView />}</main>
    </>
  )
}
