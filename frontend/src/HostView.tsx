import { useState, type FormEvent } from 'react'
import { createSlot, errorMessage, toFieldErrors, type Slot } from './api'
import { formatDay, formatTimeRange, groupByDay, timeZone, todayIsoDate } from './format'
import { useSlots } from './useSlots'

interface FormErrors {
  date?: string
  time?: string
  duration?: string
}

function validate(date: string, time: string, duration: string): FormErrors {
  const errors: FormErrors = {}
  if (!date) errors.date = 'Please choose a date.'
  if (!time) errors.time = 'Please choose a start time.'
  else if (date && new Date(`${date}T${time}`) <= new Date()) errors.time = 'Start time must be in the future.'
  const minutes = Number(duration)
  if (!Number.isInteger(minutes) || minutes < 5 || minutes > 480) {
    errors.duration = 'Duration must be a whole number between 5 and 480 minutes.'
  }
  return errors
}

/** Booked wins over a calendar clash; a clashing free slot is hidden from visitors. */
function SlotStatus({ slot }: { slot: Slot }) {
  if (slot.booked) return <span className="badge badge-booked">Booked</span>
  if (slot.calendarConflict) {
    return (
      <span className="badge badge-conflict" title="Clashes with an event in your Google Calendar; hidden from visitors">
        Calendar conflict
      </span>
    )
  }
  return <span className="badge badge-free">Available</span>
}

export default function HostView() {
  const { slots, loading, error, reload } = useSlots(true)
  const [date, setDate] = useState('')
  const [time, setTime] = useState('')
  const [duration, setDuration] = useState('30')
  const [errors, setErrors] = useState<FormErrors>({})
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const formErrors = validate(date, time, duration)
    setErrors(formErrors)
    setSubmitError(null)
    setSuccess(null)
    if (Object.keys(formErrors).length > 0) return

    setSubmitting(true)
    try {
      // The date/time inputs are in the host's local time zone; the API receives an ISO instant (UTC)
      const slot = await createSlot({
        startAt: new Date(`${date}T${time}`).toISOString(),
        durationMinutes: Number(duration),
      })
      setSuccess(`Slot created: ${formatDay(slot.startAt)}, ${formatTimeRange(slot.startAt, slot.endAt)}.`)
      setTime('')
      reload()
    } catch (e) {
      const server = toFieldErrors(e)
      setErrors({ time: server.startAt, duration: server.durationMinutes })
      setSubmitError(errorMessage(e))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <>
      <section className="intro intro-compact" aria-labelledby="host-title">
        <h2 id="host-title">Manage your availability</h2>
        <p className="intro-text">Add the times you're free. Visitors can book each slot once.</p>
      </section>
      <div className="columns">
        <section className="card" aria-labelledby="create-title">
          <h2 id="create-title">Create a slot</h2>
          <form onSubmit={handleSubmit} noValidate>
            <div className="field">
              <label htmlFor="date">Date</label>
              <input
                id="date"
                type="date"
                value={date}
                min={todayIsoDate()}
                onChange={(e) => setDate(e.target.value)}
                required
                aria-invalid={!!errors.date}
                aria-describedby={errors.date ? 'date-error' : undefined}
              />
              {errors.date && (
                <p id="date-error" className="field-error">
                  {errors.date}
                </p>
              )}
            </div>
            <div className="field">
              <label htmlFor="time">Start time</label>
              <input
                id="time"
                type="time"
                value={time}
                onChange={(e) => setTime(e.target.value)}
                required
                aria-invalid={!!errors.time}
                aria-describedby={errors.time ? 'time-hint time-error' : 'time-hint'}
              />
              <p id="time-hint" className="hint">
                Your time zone: {timeZone}
              </p>
              {errors.time && (
                <p id="time-error" className="field-error">
                  {errors.time}
                </p>
              )}
            </div>
            <div className="field">
              <label htmlFor="duration">Duration (minutes)</label>
              <input
                id="duration"
                type="number"
                inputMode="numeric"
                min={5}
                max={480}
                step={5}
                value={duration}
                onChange={(e) => setDuration(e.target.value)}
                required
                aria-invalid={!!errors.duration}
                aria-describedby={errors.duration ? 'duration-error' : undefined}
              />
              {errors.duration && (
                <p id="duration-error" className="field-error">
                  {errors.duration}
                </p>
              )}
            </div>
            {submitError && (
              <p className="alert alert-error" role="alert">
                {submitError}
              </p>
            )}
            {success && (
              <p className="alert alert-success" role="status">
                {success}
              </p>
            )}
            <div className="actions">
              <button type="submit" className="button" disabled={submitting}>
                {submitting ? 'Creating…' : 'Create slot'}
              </button>
            </div>
          </form>
        </section>

        <section className="card" aria-labelledby="upcoming-title">
          <h2 id="upcoming-title">Upcoming slots</h2>
          {loading && slots.length === 0 ? (
            <p role="status">Loading slots…</p>
          ) : error ? (
            <div className="alert alert-error" role="alert">
              <p>{error}</p>
              <button type="button" className="button button-secondary" onClick={() => reload()}>
                Try again
              </button>
            </div>
          ) : slots.length === 0 ? (
            <p className="empty">No upcoming slots yet. Create one to get started.</p>
          ) : (
            groupByDay(slots).map((group) => (
              <div key={group.day} className="day">
                <h3>{group.day}</h3>
                <ul className="slot-list">
                  {group.slots.map((slot) => (
                    <li key={slot.id}>
                      <span>
                        {formatTimeRange(slot.startAt, slot.endAt)}{' '}
                        <span className="muted">· {slot.durationMinutes} min</span>
                      </span>
                      <SlotStatus slot={slot} />
                    </li>
                  ))}
                </ul>
              </div>
            ))
          )}
        </section>
      </div>
    </>
  )
}
