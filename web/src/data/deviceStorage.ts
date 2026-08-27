import { normalizeDeploymentKey } from './deploymentScope';
import type { BreastfeedingSession, PendingFamilyCreation } from '../domain/model';

const DEVICE_ID_KEY = 'baby-record-device-id';
export const LEGACY_FAMILY_CREATION_KEY = 'baby-record-pending-family-creation';
export const BREASTFEEDING_SESSION_KEY = 'baby-record-active-breastfeeding-v1';

export function storageGet(key: string) { try { return localStorage.getItem(key); } catch { return null; } }
export function storageSet(key: string, value: string) { try { localStorage.setItem(key, value); return true; } catch { return false; } }
export function storageRemove(key: string) { try { localStorage.removeItem(key); } catch {} }

export const API_BASE = (() => {
  const configured = (import.meta as any).env?.VITE_API_BASE || storageGet('baby-record-api-base') || '';
  return String(configured).replace(/\/$/, '');
})();

export const FAMILY_CREATION_DEPLOYMENT_KEY = normalizeDeploymentKey(API_BASE, globalThis.location?.origin);
const FAMILY_CREATION_KEY = `${LEGACY_FAMILY_CREATION_KEY}:${encodeURIComponent(FAMILY_CREATION_DEPLOYMENT_KEY)}`;

export function uuid() {
  const crypto = globalThis.crypto;
  if (crypto?.randomUUID) return crypto.randomUUID();
  if (!crypto?.getRandomValues) throw new Error('Secure random values are unavailable');
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function getDeviceId() {
  let id = storageGet(DEVICE_ID_KEY);
  if (!id) {
    id = uuid();
    storageSet(DEVICE_ID_KEY, id);
  }
  return id;
}

export function deviceName() {
  return `${navigator.platform || 'H5'} · ${navigator.userAgent.includes('Mobile') ? '手机' : '浏览器'}`;
}

export function loadPendingFamilyCreation(): PendingFamilyCreation | null {
  try {
    const pending = JSON.parse(storageGet(FAMILY_CREATION_KEY) || 'null') as PendingFamilyCreation | null;
    const request = pending?.request;
    if (pending?.deploymentKey !== FAMILY_CREATION_DEPLOYMENT_KEY) return null;
    if (!request || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(request.creationKey)) return null;
    for (const value of [request.familyName, request.babyNickname, request.birthDate, request.nickname, request.deviceId, request.deviceName]) {
      if (typeof value !== 'string' || !value) return null;
    }
    if (!['BOY', 'GIRL'].includes(request.gender) || !Number.isInteger(request.birthWeightGrams) || request.birthWeightGrams < 100 || request.birthWeightGrams > 15000) return null;
    return pending;
  } catch {
    return null;
  }
}

export function savePendingFamilyCreation(pending: PendingFamilyCreation) {
  if (pending.deploymentKey !== FAMILY_CREATION_DEPLOYMENT_KEY) return false;
  return storageSet(FAMILY_CREATION_KEY, JSON.stringify(pending));
}

export function clearPendingFamilyCreation(expected?: PendingFamilyCreation) {
  if (expected && loadPendingFamilyCreation()?.request.creationKey !== expected.request.creationKey) return;
  storageRemove(FAMILY_CREATION_KEY);
}

export function currentDevicePendingFamilyCreation() {
  const pending = loadPendingFamilyCreation();
  return pending?.request.deviceId === getDeviceId() ? pending : null;
}

/** The retired global key has no deployment identity and must never be parsed or replayed automatically. */
export function legacyFamilyCreationPresent() {
  return storageGet(LEGACY_FAMILY_CREATION_KEY) !== null;
}

export function loadBreastfeedingSession(): BreastfeedingSession | null {
  try {
    const value = JSON.parse(storageGet(BREASTFEEDING_SESSION_KEY) || 'null') as BreastfeedingSession | null;
    if (!value || !Number.isFinite(value.startedAt) || value.startedAt > Date.now() + 60000) return null;
    if (value.activeSide !== null && value.activeSide !== 'LEFT' && value.activeSide !== 'RIGHT') return null;
    if (value.lastSide !== null && value.lastSide !== 'LEFT' && value.lastSide !== 'RIGHT') return null;
    return {
      familyId: Number.isSafeInteger(value.familyId) ? value.familyId : undefined,
      babyId: Number.isSafeInteger(value.babyId) ? value.babyId : undefined,
      startedAt: value.startedAt,
      activeSide: value.activeSide,
      activeSince: value.activeSide && Number.isFinite(value.activeSince) ? value.activeSince : null,
      lastSide: value.lastSide,
      leftSeconds: Math.max(0, Math.floor(value.leftSeconds || 0)),
      rightSeconds: Math.max(0, Math.floor(value.rightSeconds || 0)),
      segments: Array.isArray(value.segments) ? value.segments.filter(segment => (segment?.side === 'LEFT' || segment?.side === 'RIGHT') && Number.isInteger(segment?.seconds) && segment.seconds > 0).map(segment => ({ side: segment.side, seconds: segment.seconds })) : [],
    };
  } catch { return null; }
}
