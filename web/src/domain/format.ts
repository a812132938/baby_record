import type { BabyGender } from './model';

export function formatTime(ts: number) {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

export function formatDateTime(ts: number) {
  const d = new Date(ts);
  const today = new Date();
  const sameDay = d.toDateString() === today.toDateString();
  return sameDay ? `今天 ${formatTime(ts)}` : `${d.getMonth() + 1}月${d.getDate()}日 ${formatTime(ts)}`;
}

export function toInputDateTime(ts: number) {
  const d = new Date(ts);
  const pad = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function toLocalDateTime(ts: number) {
  const d = new Date(ts);
  const pad = (v: number, n = 2) => String(v).padStart(n, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

export function relative(ts?: number) {
  if (!ts) return '暂无';
  const diff = Math.max(0, Date.now() - ts);
  const m = Math.floor(diff / 60000);
  if (m < 1) return '刚刚';
  if (m < 60) return `${m}分钟`;
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest ? `${h}小时${rest}分` : `${h}小时`;
}

export function duration(ms: number) {
  const min = Math.max(0, Math.floor(ms / 60000));
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (!h) return `${m}分钟`;
  return `${h}小时${String(m).padStart(2, '0')}分`;
}

export function compactDuration(minutes: number) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (!h) return `${m}m`;
  if (!m) return `${h}h`;
  return `${h}h ${m}m`;
}

export function clockDuration(seconds: number) {
  const safe = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const rest = safe % 60;
  return hours ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}` : `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}

export function dateKey(ts: number) {
  const d = new Date(ts);
  const pad = (v: number) => String(v).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;
}

export function dayBounds(key: string) {
  const [y,m,d] = key.split('-').map(Number);
  const start = new Date(y, m - 1, d, 0, 0, 0, 0).getTime();
  const endDate = new Date(y, m - 1, d + 1, 0, 0, 0, 0);
  return { start, end: endDate.getTime() };
}

function calendarDayOrdinal(value: string) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  return Math.floor(date.getTime() / 86400000);
}

/** Inclusive calendar-day age: the birth day itself is day 1. */
export function daysOld(birthDate?: string | null) {
  if (!birthDate) return null;
  const born = calendarDayOrdinal(birthDate);
  if (born === null) return null;
  const today = new Date();
  const current = Math.floor(Date.UTC(today.getFullYear(), today.getMonth(), today.getDate()) / 86400000);
  if (born > current) return null;
  return current - born + 1;
}

export function birthWeightGrams(value: string) {
  const kg = Number(value);
  if (!Number.isFinite(kg) || kg < 0.1 || kg > 15) return null;
  const grams = Math.round(kg * 1000);
  return grams >= 100 && grams <= 15000 ? grams : null;
}

export function birthWeightKg(value?: number | null) {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 100 || value > 15000) return '';
  return (value / 1000).toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}

export function genderLabel(value?: BabyGender | null) {
  return value === 'BOY' ? '男孩' : value === 'GIRL' ? '女孩' : '性别未设置';
}

export function birthWeightLabel(value?: number | null) {
  const kg = birthWeightKg(value);
  return kg ? `出生 ${kg}kg` : '出生体重未设置';
}
