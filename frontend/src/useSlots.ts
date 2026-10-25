import { useCallback, useEffect, useState } from 'react'
import { errorMessage, getSlots, type Slot } from './api'

interface SlotsState {
  slots: Slot[]
  loading: boolean
  error: string | null
}

/** Loads upcoming slots and exposes loading/error state plus a reload function. */
export function useSlots(includeBooked: boolean) {
  const [state, setState] = useState<SlotsState>({ slots: [], loading: true, error: null })
  const [version, setVersion] = useState(0)

  useEffect(() => {
    let cancelled = false
    getSlots(includeBooked).then(
      (slots) => !cancelled && setState({ slots, loading: false, error: null }),
      (e) => !cancelled && setState((s) => ({ ...s, loading: false, error: errorMessage(e) })),
    )
    return () => {
      cancelled = true
    }
  }, [includeBooked, version])

  const reload = useCallback(() => {
    setState((s) => ({ ...s, loading: true, error: null }))
    setVersion((v) => v + 1)
  }, [])

  return { ...state, reload }
}
