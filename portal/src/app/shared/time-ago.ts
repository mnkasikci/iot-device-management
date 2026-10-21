/** "4 s ago", "3 min ago", "2 h ago" — relative to `now` (epoch millis). */
export function timeAgo(iso: string | null, now: number): string {
  if (!iso) return '—';
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
}
