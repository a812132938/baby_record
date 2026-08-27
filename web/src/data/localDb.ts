import Dexie, { type EntityTable } from 'dexie';
import type { LocalBabyEvent, LocalBabyProfile, LocalPendingAction, LocalSnapshot } from '../domain/model';
import { normalizeDeploymentKey } from './deploymentScope';

type KvRow = { key: string; value: string };

const ACTIVE_SCOPE_KEY = 'baby-record-active-scope-v1';
const LEGACY_OWNER_KEY = 'baby-record-legacy-owner-v1';
const DEPLOYMENT_MIGRATION_KEY = 'baby-record-deployment-scope-migrated-v1';
const DATABASE_PREFIX = 'baby-record-v6';
const GUEST_SCOPE = 'guest';
const LEGACY_DATABASE = 'baby-record';
const LEGACY_EVENTS = ['baby-record-events-v3', 'baby-record-events-v2'];
const LEGACY_PENDING = ['baby-record-pending-v2', 'baby-record-pending-v1'];
const LEGACY_PROFILE = 'baby-record-profile-v1';

class BabyRecordDb extends Dexie {
  events!: EntityTable<LocalBabyEvent, 'id'>;
  pending!: EntityTable<LocalPendingAction, 'id'>;
  kv!: EntityTable<KvRow, 'key'>;

  constructor(name: string) {
    super(name);
    this.version(1).stores({
      events: 'id, type, at, clientEventId, serverId, pending',
      pending: 'id, kind, localEventId, clientEventId, serverId, at',
      kv: 'key',
    });
    this.version(2).stores({
      events: 'id, type, at, clientEventId, serverId, pending, serverUpdatedAt',
      pending: 'id, kind, localEventId, clientEventId, serverId, at, expectedUpdatedAt',
      kv: 'key',
    });
  }
}

function safeLocalGet(key: string) {
  try { return localStorage.getItem(key); } catch { return null; }
}

function safeLocalSet(key: string, value: string) {
  try { localStorage.setItem(key, value); } catch {}
}

function safeLocalRemove(key: string) {
  try { localStorage.removeItem(key); } catch {}
}

function validScope(value: string | null) {
  return value && /^(?:guest|family-\d+-baby-\d+|deployment-[A-Za-z0-9%._~-]+-family-\d+-baby-\d+)$/.test(value) ? value : GUEST_SCOPE;
}

function legacyDataScope(familyId: number, babyId: number) {
  if (!Number.isSafeInteger(familyId) || familyId < 1 || !Number.isSafeInteger(babyId) || babyId < 1) {
    throw new Error('Invalid local data scope');
  }
  return `family-${familyId}-baby-${babyId}`;
}

function dataScope(familyId: number, babyId: number, deploymentKey?: string) {
  const identityScope = legacyDataScope(familyId, babyId);
  return `deployment-${encodeURIComponent(normalizeDeploymentKey(deploymentKey))}-${identityScope}`;
}

function deploymentScopePrefix(deploymentKey?: string) {
  return `deployment-${encodeURIComponent(normalizeDeploymentKey(deploymentKey))}-`;
}

function databaseName(scope: string) {
  return `${DATABASE_PREFIX}-${scope}`;
}

function scopedStorageKey(key: string, scope = activeScope) {
  return `${key}:${scope}`;
}

function readArray<T>(keys: string[]): T[] {
  for (const key of keys) {
    try {
      const raw = safeLocalGet(key);
      if (raw) return JSON.parse(raw) as T[];
    } catch { return []; }
  }
  return [];
}

function readProfile(key: string): LocalBabyProfile | null {
  try {
    const raw = safeLocalGet(key);
    return raw ? JSON.parse(raw) as LocalBabyProfile : null;
  } catch { return null; }
}

function scopedFallback(scope = activeScope): LocalSnapshot {
  return {
    events: readArray<LocalBabyEvent>([scopedStorageKey(LEGACY_EVENTS[0], scope)]).sort((a, b) => b.at - a.at),
    pending: readArray<LocalPendingAction>([scopedStorageKey(LEGACY_PENDING[0], scope)]),
    profile: readProfile(scopedStorageKey(LEGACY_PROFILE, scope)),
  };
}

function hasData(snapshot: LocalSnapshot) {
  return snapshot.events.length > 0 || snapshot.pending.length > 0 || snapshot.profile !== null;
}

let activeScope = validScope(safeLocalGet(ACTIVE_SCOPE_KEY));
let localDb = new BabyRecordDb(databaseName(activeScope));
let scopeTransition: Promise<unknown> = Promise.resolve();

function serializeScopeTransition<T>(task: () => Promise<T>): Promise<T> {
  const result = scopeTransition.then(task, task);
  scopeTransition = result.then(() => undefined, () => undefined);
  return result;
}

async function readSnapshot(db: BabyRecordDb, scope: string): Promise<LocalSnapshot> {
  try {
    const [events, pending, profileRow] = await Promise.all([
      db.events.toArray(),
      db.pending.toArray(),
      db.kv.get('profile'),
    ]);
    return {
      events: events.sort((a, b) => b.at - a.at),
      pending,
      profile: profileRow?.value ? JSON.parse(profileRow.value) as LocalBabyProfile : scopedFallback(scope).profile,
    };
  } catch {
    return scopedFallback(scope);
  }
}

async function writeSnapshot(db: BabyRecordDb, scope: string, snapshot: LocalSnapshot) {
  safeLocalSet(scopedStorageKey(LEGACY_EVENTS[0], scope), JSON.stringify(snapshot.events));
  safeLocalSet(scopedStorageKey(LEGACY_PENDING[0], scope), JSON.stringify(snapshot.pending));
  if (snapshot.profile) safeLocalSet(scopedStorageKey(LEGACY_PROFILE, scope), JSON.stringify(snapshot.profile));
  else safeLocalRemove(scopedStorageKey(LEGACY_PROFILE, scope));

  try {
    await db.transaction('rw', db.events, db.pending, db.kv, async () => {
      await Promise.all([db.events.clear(), db.pending.clear(), db.kv.delete('profile')]);
      if (snapshot.events.length) await db.events.bulkPut(snapshot.events);
      if (snapshot.pending.length) await db.pending.bulkPut(snapshot.pending);
      if (snapshot.profile) await db.kv.put({ key: 'profile', value: JSON.stringify(snapshot.profile) });
    });
  } catch {}
}

async function clearScope(scope: string, db = new BabyRecordDb(databaseName(scope))) {
  safeLocalRemove(scopedStorageKey(LEGACY_EVENTS[0], scope));
  safeLocalRemove(scopedStorageKey(LEGACY_PENDING[0], scope));
  safeLocalRemove(scopedStorageKey(LEGACY_PROFILE, scope));
  try {
    await db.transaction('rw', db.events, db.pending, db.kv, async () => {
      await Promise.all([db.events.clear(), db.pending.clear(), db.kv.clear()]);
    });
  } catch {}
  if (db !== localDb) db.close();
}

async function readLegacySnapshot(): Promise<LocalSnapshot> {
  const fallback: LocalSnapshot = {
    events: readArray<LocalBabyEvent>(LEGACY_EVENTS).sort((a, b) => b.at - a.at),
    pending: readArray<LocalPendingAction>(LEGACY_PENDING),
    profile: readProfile(LEGACY_PROFILE),
  };
  if (typeof indexedDB === 'undefined') return fallback;
  try {
    if (!(await Dexie.exists(LEGACY_DATABASE))) return fallback;
    const legacyDb = new BabyRecordDb(LEGACY_DATABASE);
    const snapshot = await readSnapshot(legacyDb, GUEST_SCOPE);
    legacyDb.close();
    return hasData(snapshot) ? snapshot : fallback;
  } catch {
    return fallback;
  }
}

async function selectScope(scope: string) {
  if (scope === activeScope) return;
  localDb.close();
  activeScope = scope;
  localDb = new BabyRecordDb(databaseName(scope));
  if (scope === GUEST_SCOPE) safeLocalRemove(ACTIVE_SCOPE_KEY);
  else safeLocalSet(ACTIVE_SCOPE_KEY, scope);
}

export async function bootstrapLocalDb(deploymentKey?: string) {
  if (activeScope.startsWith('deployment-') && !activeScope.startsWith(deploymentScopePrefix(deploymentKey))) {
    await selectScope(GUEST_SCOPE);
  }
  if (typeof indexedDB === 'undefined') return;
  try { await localDb.open(); } catch {}
}

export async function loadLocalSnapshot(): Promise<LocalSnapshot> {
  return readSnapshot(localDb, activeScope);
}

async function activateLocalScopeNow(familyId: number, babyId: number, deploymentKey?: string): Promise<LocalSnapshot> {
  const nextScope = dataScope(familyId, babyId, deploymentKey);
  const legacyScope = legacyDataScope(familyId, babyId);
  if (nextScope === activeScope) return loadLocalSnapshot();

  const previousScope = activeScope;
  const guestSnapshot = previousScope === GUEST_SCOPE ? await loadLocalSnapshot() : null;
  await selectScope(nextScope);

  let snapshot = await loadLocalSnapshot();
  if (!hasData(snapshot) && guestSnapshot && hasData(guestSnapshot)) {
    snapshot = guestSnapshot;
    await writeSnapshot(localDb, activeScope, snapshot);
    await clearScope(GUEST_SCOPE);
  }

  const migrationMarker = scopedStorageKey(DEPLOYMENT_MIGRATION_KEY, nextScope);
  if (!safeLocalGet(migrationMarker)) {
    if (!hasData(snapshot)) {
      const legacyDb = new BabyRecordDb(databaseName(legacyScope));
      const legacySnapshot = await readSnapshot(legacyDb, legacyScope);
      if (hasData(legacySnapshot)) {
        snapshot = legacySnapshot;
        await writeSnapshot(localDb, activeScope, snapshot);
        await clearScope(legacyScope, legacyDb);
      } else legacyDb.close();
    }
    safeLocalSet(migrationMarker, '1');
  }

  if (!hasData(snapshot) && !safeLocalGet(LEGACY_OWNER_KEY)) {
    const legacy = await readLegacySnapshot();
    if (hasData(legacy)) {
      snapshot = legacy;
      await writeSnapshot(localDb, activeScope, snapshot);
    }
    safeLocalSet(LEGACY_OWNER_KEY, activeScope);
  }
  const pending = snapshot.pending.map(action => action.familyId && action.babyId
    ? action
    : { ...action, familyId, babyId });
  if (pending.some((action, index) => action !== snapshot.pending[index])) {
    snapshot = { ...snapshot, pending };
    await writeSnapshot(localDb, activeScope, snapshot);
  }
  return snapshot;
}

export async function activateLocalScope(familyId: number, babyId: number, deploymentKey?: string): Promise<LocalSnapshot> {
  return serializeScopeTransition(() => activateLocalScopeNow(familyId, babyId, deploymentKey));
}

export async function deactivateLocalScope(): Promise<LocalSnapshot> {
  return serializeScopeTransition(async () => {
    await selectScope(GUEST_SCOPE);
    return loadLocalSnapshot();
  });
}

export async function clearActiveLocalData(): Promise<LocalSnapshot> {
  return serializeScopeTransition(async () => {
    const scopeToClear = activeScope;
    await clearScope(scopeToClear, localDb);
    if (scopeToClear !== GUEST_SCOPE) await selectScope(GUEST_SCOPE);
    return { events: [], pending: [], profile: null };
  });
}

export async function saveEvents(events: LocalBabyEvent[]) {
  const db = localDb;
  const scope = activeScope;
  safeLocalSet(scopedStorageKey(LEGACY_EVENTS[0], scope), JSON.stringify(events));
  try {
    await db.transaction('rw', db.events, async () => {
      await db.events.clear();
      if (events.length) await db.events.bulkPut(events);
    });
  } catch {}
}

export async function savePendingActions(pending: LocalPendingAction[]) {
  const db = localDb;
  const scope = activeScope;
  safeLocalSet(scopedStorageKey(LEGACY_PENDING[0], scope), JSON.stringify(pending));
  try {
    await db.transaction('rw', db.pending, async () => {
      await db.pending.clear();
      if (pending.length) await db.pending.bulkPut(pending);
    });
  } catch {}
}

export async function saveProfile(profile: LocalBabyProfile) {
  const db = localDb;
  const scope = activeScope;
  safeLocalSet(scopedStorageKey(LEGACY_PROFILE, scope), JSON.stringify(profile));
  try { await db.kv.put({ key: 'profile', value: JSON.stringify(profile) }); } catch {}
}

export async function persistEventSnapshot(events: LocalBabyEvent[], pending: LocalPendingAction[]) {
  await writeSnapshot(localDb, activeScope, { events, pending, profile: (await loadLocalSnapshot()).profile });
}
