import type { Slot } from './api'

const dayFormat = new Intl.DateTimeFormat(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
const timeFormat = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' })

export const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone

export function formatDay(iso: string): string {
  return dayFormat.format(new Date(iso))
}

export function formatTimeRange(startIso: string, endIso: string): string {
  return `${timeFormat.format(new Date(startIso))} – ${timeFormat.format(new Date(endIso))}`
}

/** Today's date as YYYY-MM-DD in local time, for <input type="date" min>. */
export function todayIsoDate(): string {
  const now = new Date()
  return new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString().slice(0, 10)
}

/** Groups slots (already sorted by start time) by local calendar day. */
export function groupByDay(slots: Slot[]): { day: string; slots: Slot[] }[] {
  const groups = new Map<string, Slot[]>()
  for (const slot of slots) {
    const day = formatDay(slot.startAt)
    groups.set(day, [...(groups.get(day) ?? []), slot])
  }
  return [...groups].map(([day, daySlots]) => ({ day, slots: daySlots }))
}
