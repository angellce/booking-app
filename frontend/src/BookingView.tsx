import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError, bookSlot, errorMessage, toFieldErrors, type Booking, type Slot } from './api'
import { formatDay, formatTimeRange, groupByDay, timeZone } from './format'
import { useSlots } from './useSlots'

const EMAIL_PATTERN = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

function validate(name: string, email: string): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!name.trim()) errors.name = 'Please enter your name.'
  else if (name.trim().length > 100) errors.name = 'Name must be at most 100 characters.'
  if (!email.trim()) errors.email = 'Please enter your email.'
  else if (!EMAIL_PATTERN.test(email.trim())) errors.email = 'Please enter a valid email address.'
  return errors
}

/** Labelled summary of a booking: when, how long and for whom. */
function BookingSummary({ booking }: { booking: Booking }) {
  return (
    <dl className="summary">
      <div>
        <dt>Date</dt>
        <dd>{formatDay(booking.startAt)}</dd>
      </div>
      <div>
        <dt>Time</dt>
        <dd>{formatTimeRange(booking.startAt, booking.endAt)}</dd>
      </div>
      <div>
        <dt>Duration</dt>
        <dd>{booking.durationMinutes} min</dd>
      </div>
      <div>
        <dt>Name</dt>
        <dd>{booking.name}</dd>
      </div>
      <div>
        <dt>Email</dt>
        <dd>{booking.email}</dd>
      </div>
    </dl>
  )
}

export default function BookingView() {
  const { slots, loading, error, reload } = useSlots(false)
  const [selected, setSelected] = useState<Slot | null>(null)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  // The latest booking made in this tab (no accounts, so it is not kept after a reload)
  const [booking, setBooking] = useState<Booking | null>(null)
  const [showConfirmation, setShowConfirmation] = useState(false)
  const nameInput = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (selected) nameInput.current?.focus()
  }, [selected])

  function selectSlot(slot: Slot) {
    setSelected(slot)
    setFieldErrors({})
    setNotice(null)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!selected) return
    const errors = validate(name, email)
    setFieldErrors(errors)
    if (Object.keys(errors).length > 0) return

    setSubmitting(true)
    setNotice(null)
    try {
      setBooking(await bookSlot(selected.id, { name: name.trim(), email: email.trim(), timeZone }))
      setShowConfirmation(true)
      setSelected(null)
      setName('')
      setEmail('')
      reload()
    } catch (e) {
      if (e instanceof ApiError && (e.status === 409 || e.status === 404)) {
        // Someone else got there first (or the slot is gone): drop the selection and refresh the list
        setNotice(`${e.message} Please choose another slot.`)
        setSelected(null)
        reload()
      } else {
        setFieldErrors(toFieldErrors(e))
        setNotice(errorMessage(e))
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (booking && showConfirmation) {
    return (
      <section className="card confirmation" role="status" aria-labelledby="confirmation-title">
        <span className="check" aria-hidden="true">
          ✓
        </span>
        <h2 id="confirmation-title">You're booked!</h2>
        {booking.confirmationMessage && <p className="confirmation-message">{booking.confirmationMessage}</p>}
        <BookingSummary booking={booking} />
        <button type="button" className="button" onClick={() => setShowConfirmation(false)}>
          Book another slot
        </button>
      </section>
    )
  }

  return (
    <>
      <section className="intro" aria-labelledby="welcome-title">
        <h2 id="welcome-title">Book a time with me</h2>
        <p className="intro-text">Choose a time that suits you. It only takes a minute, and no account is needed.</p>
        <ol className="steps">
          <li>
            <span className="step-number" aria-hidden="true">1</span>
            Pick a free time below
          </li>
          <li>
            <span className="step-number" aria-hidden="true">2</span>
            Enter your name and email
          </li>
          <li>
            <span className="step-number" aria-hidden="true">3</span>
            Get your confirmation instantly
          </li>
        </ol>
      </section>
      {booking && (
        <section className="booked-banner" aria-labelledby="your-booking-title">
          <h2 id="your-booking-title">Your booking</h2>
          <p>
            <strong>{formatDay(booking.startAt)}</strong>, {formatTimeRange(booking.startAt, booking.endAt)} ·{' '}
            {booking.name}
          </p>
          <button type="button" className="link-button" onClick={() => setShowConfirmation(true)}>
            View details
          </button>
        </section>
      )}
      <div className="columns">
        <section className="card" aria-labelledby="available-title">
          <h2 id="available-title">Available slots</h2>
          {notice && !selected && (
            <p className="alert alert-error" role="alert">
              {notice}
            </p>
          )}
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
            <p className="empty">No available slots right now. Please check back later.</p>
          ) : (
            groupByDay(slots).map((group) => (
              <div key={group.day} className="day">
                <h3>{group.day}</h3>
                <ul className="slot-grid">
                  {group.slots.map((slot) => (
                    <li key={slot.id}>
                      <button
                        type="button"
                        className="slot-button"
                        aria-pressed={selected?.id === slot.id}
                        aria-label={`${group.day}, ${formatTimeRange(slot.startAt, slot.endAt)}, ${slot.durationMinutes} minutes`}
                        onClick={() => selectSlot(slot)}
                      >
                        {formatTimeRange(slot.startAt, slot.endAt)}
                        <span className="muted">{slot.durationMinutes} min</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            ))
          )}
        </section>

        <section className="card" aria-labelledby="details-title">
          <h2 id="details-title">Your details</h2>
          {!selected ? (
            <p className="empty">Select a slot to book it.</p>
          ) : (
            <form onSubmit={handleSubmit} noValidate>
              <p className="selected-slot">
                <strong>{formatDay(selected.startAt)}</strong>
                <br />
                {formatTimeRange(selected.startAt, selected.endAt)} ({selected.durationMinutes} min)
              </p>
              <div className="field">
                <label htmlFor="name">Name</label>
                <input
                  id="name"
                  ref={nameInput}
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  autoComplete="name"
                  maxLength={100}
                  required
                  aria-invalid={!!fieldErrors.name}
                  aria-describedby={fieldErrors.name ? 'name-error' : undefined}
                />
                {fieldErrors.name && (
                  <p id="name-error" className="field-error">
                    {fieldErrors.name}
                  </p>
                )}
              </div>
              <div className="field">
                <label htmlFor="email">Email</label>
                <input
                  id="email"
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  autoComplete="email"
                  maxLength={254}
                  required
                  aria-invalid={!!fieldErrors.email}
                  aria-describedby={fieldErrors.email ? 'email-error' : undefined}
                />
                {fieldErrors.email && (
                  <p id="email-error" className="field-error">
                    {fieldErrors.email}
                  </p>
                )}
              </div>
              {notice && (
                <p className="alert alert-error" role="alert">
                  {notice}
                </p>
              )}
              <div className="actions">
                <button type="submit" className="button" disabled={submitting}>
                  {submitting ? 'Booking…' : 'Book this slot'}
                </button>
                <button type="button" className="button button-secondary" onClick={() => setSelected(null)}>
                  Cancel
                </button>
              </div>
            </form>
          )}
        </section>
      </div>
    </>
  )
}
