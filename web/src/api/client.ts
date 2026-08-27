import { queryClient } from './queryClient';

function safeStorageGet(key: string) {
  try { return localStorage.getItem(key); } catch { return null; }
}

export const API_BASE = (() => {
  const configured = (import.meta as any).env?.VITE_API_BASE || safeStorageGet('baby-record-api-base') || '';
  return String(configured).replace(/\/$/, '');
})();

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

export async function apiFetch(path: string, init: RequestInit = {}) {
  return fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init.headers || {}),
    },
  });
}

async function fetchJson<T>(path: string): Promise<T> {
  const res = await apiFetch(path);
  if (!res.ok) throw new ApiError(res.status, `${path}:${res.status}`);
  return res.json() as Promise<T>;
}

export const queryApi = {
  dashboard<T>(babyId: number) {
    return queryClient.fetchQuery({
      queryKey: ['baby', babyId, 'dashboard'],
      queryFn: () => fetchJson<T>(`/api/v1/babies/${babyId}/dashboard`),
      staleTime: 0,
    });
  },
  stats<T>(babyId: number, days: number) {
    return queryClient.fetchQuery({
      queryKey: ['baby', babyId, 'stats', days],
      queryFn: () => fetchJson<T>(`/api/v1/babies/${babyId}/stats?days=${days}`),
      staleTime: 10_000,
    });
  },
  history<T>(babyId: number, date: string) {
    return queryClient.fetchQuery({
      queryKey: ['baby', babyId, 'history', date],
      queryFn: () => fetchJson<T>(`/api/v1/babies/${babyId}/events?date=${encodeURIComponent(date)}`),
      staleTime: 5_000,
    });
  },
  invalidateBaby(babyId: number) {
    return queryClient.invalidateQueries({ queryKey: ['baby', babyId] });
  },
  clear() {
    queryClient.clear();
  },
};
