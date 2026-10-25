const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080').replace(/\/$/, '')

export interface Slot {
  id: number
  startAt: string
  endAt: string
  durationMinutes: number
  booked: boolean
  /** Clashes with an event in the host's Google Calendar (only ever true in the host list) */
  calendarConflict: boolean
}

export interface Booking {
  id: number
  slotId: number
  startAt: string
  endAt: string
  durationMinutes: number
  name: string
  email: string
  createdAt: string
  /** Friendly message written by the LLM (or a template fallback) */
  confirmationMessage: string | null
}

export interface FieldError {
  field: string
  message: string
}

/** Error carrying the backend's problem-detail response (status 0 = server unreachable). */
export class ApiError extends Error {
  status: number
  fieldErrors: FieldError[]

  constructor(status: number, message: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(API_BASE_URL + path, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
    })
  } catch {
    throw new ApiError(0, 'Could not reach the server. Please check your connection and try again.')
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new ApiError(
      response.status,
      problem?.detail ?? `Request failed (${response.status}).`,
      problem?.errors ?? [],
    )
  }
  return response.json() as Promise<T>
}

export function getSlots(includeBooked = false): Promise<Slot[]> {
  return request(`/api/slots${includeBooked ? '?includeBooked=true' : ''}`)
}

export function createSlot(body: { startAt: string; durationMinutes: number }): Promise<Slot> {
  return request('/api/slots', { method: 'POST', body: JSON.stringify(body) })
}

export function bookSlot(
  slotId: number,
  body: { name: string; email: string; timeZone: string },
): Promise<Booking> {
  return request(`/api/slots/${slotId}/booking`, { method: 'POST', body: JSON.stringify(body) })
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Something went wrong. Please try again.'
}

/** Maps server validation errors to { field: message }. */
export function toFieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {}
  return Object.fromEntries(error.fieldErrors.map((e) => [e.field, e.message]))
}
