import React from 'react';
import AiWorkspace from './ai/AiWorkspace';
import { cancelPendingCreations, isPendingCreation, isRetryablePendingStatus, reconcilePendingSuccess } from './data/pendingQueue';
import {
  API_BASE,
  BREASTFEEDING_SESSION_KEY,
  FAMILY_CREATION_DEPLOYMENT_KEY,
  LEGACY_FAMILY_CREATION_KEY,
  clearPendingFamilyCreation,
  currentDevicePendingFamilyCreation,
  deviceName,
  getDeviceId,
  legacyFamilyCreationPresent,
  loadBreastfeedingSession,
  savePendingFamilyCreation,
  storageRemove,
  storageSet,
  uuid,
} from './data/deviceStorage';
import { normalizeBreastLastSide, type BreastSide } from './domain/feeding';
import {
  applyPendingOverlay,
  feedingEventType,
  isFeedingEvent,
  overlappingServerSleepIds,
  remoteToLocal,
} from './domain/events';
import {
  birthWeightGrams,
  birthWeightKg,
  dateKey,
  dayBounds,
  daysOld,
  duration,
  toInputDateTime,
  toLocalDateTime,
} from './domain/format';
import { breastSideSeconds, buildLocalTrend } from './domain/stats';
import type {
  BabyEvent,
  BabyGender,
  BreastfeedingSession,
  FamilyDevice,
  FeedingType,
  IdentitySnapshot,
  LocalBabyProfile,
  Me,
  OnboardingMode,
  PendingAction,
  PendingFamilyCreation,
  RemoteDashboard,
  RemoteEvent,
  SheetType,
  StatsResponse,
  SyncMode,
  TrendDay,
} from './domain/model';
import { HomeScreen, SyncBadge, type TodayTotals } from './features/HomeScreen';
import { BabyProfileSheet, type BabyProfileForm } from './features/sheets/BabyProfileSheet';
import { BottleAmountSheet } from './features/sheets/BottleAmountSheet';
import { DevicesSheet } from './features/sheets/DevicesSheet';
import { DirectBreastfeedSheet } from './features/sheets/DirectBreastfeedSheet';
import { EventEditorSheet, type EventEditDraft } from './features/sheets/EventEditorSheet';
import { FamilySheet, type CreateFamilyForm, type JoinFamilyForm } from './features/sheets/FamilySheet';
import { HistorySheet } from './features/sheets/HistorySheet';
import { PoopSheet, type PoopDraft } from './features/sheets/PoopSheet';
import { PumpingSheet } from './features/sheets/PumpingSheet';
import { QuickRecordSheet } from './features/sheets/QuickRecordSheet';
import { RecordTimeSheet } from './features/sheets/RecordTimeSheet';
import { StatsSheet } from './features/sheets/StatsSheet';

const legacyFamilyCreationDetected = legacyFamilyCreationPresent();
const restoredFamilyCreation = currentDevicePendingFamilyCreation();

function loadEvents(): BabyEvent[] {
  try {
    const snapshot = (globalThis as any).__BABY_INITIAL_SNAPSHOT__;
    const initial = snapshot?.events;
    return Array.isArray(initial) ? initial : [];
  } catch { return []; }
}

function loadPending(): PendingAction[] {
  try {
    const runtime = (globalThis as any).__BABY_RUNTIME_PENDING__;
    if (Array.isArray(runtime)) return runtime;
    const initial = (globalThis as any).__BABY_INITIAL_SNAPSHOT__?.pending;
    return Array.isArray(initial) ? initial : [];
  } catch { return []; }
}

function savePending(actions: PendingAction[]) {
  (globalThis as any).__BABY_RUNTIME_PENDING__ = actions;
}

function loadLocalProfile(): LocalBabyProfile | null {
  try {
    const snapshot = (globalThis as any).__BABY_INITIAL_SNAPSHOT__;
    return snapshot?.profile || null;
  } catch { return null; }
}

function createInitialState() {
  return {
    activePage: 'home' as 'home' | 'ai',
    aiEnabled: false,
    events: loadEvents() as BabyEvent[],
    sheet: null as SheetType,
    breastBottleAmount: '',
    formulaAmount: '',
    pumpLeftMl: '',
    pumpRightMl: '',
    pumpDurationMinutes: '',
    directSession: loadBreastfeedingSession() as BreastfeedingSession | null,
    tick: Date.now(),
    toast: '',
    syncMode: 'checking' as SyncMode,
    realtime: false,
    me: null as Me | null,
    baby: loadLocalProfile() || { nickname: '宝宝', birthday: null as string | null, gender: null as BabyGender | null, birthWeightGrams: null as number | null },
    serverQuickAmounts: [] as number[],
    joinCode: '',
    joinNickname: '',
    joining: false,
    onboardingMode: 'create' as OnboardingMode,
    createFamilyName: restoredFamilyCreation?.request.familyName || '',
    createNickname: restoredFamilyCreation?.request.nickname || '',
    createBabyNickname: restoredFamilyCreation?.request.babyNickname || '',
    createBirthDate: restoredFamilyCreation?.request.birthDate || '',
    createGender: restoredFamilyCreation?.request.gender || '' as BabyGender | '',
    createBirthWeightKg: birthWeightKg(restoredFamilyCreation?.request.birthWeightGrams),
    creatingFamily: false,
    creationRecoveryInvalid: '',
    legacyCreationRecoveryDetected: legacyFamilyCreationDetected,
    familyInvite: '',
    loadingInvite: false,
    inviteError: false,
    pendingCount: loadPending().length,
    failedPendingCount: loadPending().filter(action => action.blocked).length,
    draftAt: Date.now(),
    timeReturnSheet: 'quick' as SheetType,
    timeInput: toInputDateTime(Date.now()),
    poopColor: '黄色',
    poopTexture: '奶瓣',
    poopAmount: '中',
    editEventId: null as string | null,
    editTimeInput: '',
    editAmount: '',
    editLeftSeconds: '',
    editRightSeconds: '',
    editLeftMl: '',
    editRightMl: '',
    editDurationMinutes: '',
    editPoopColor: '黄色',
    editPoopTexture: '奶瓣',
    editPoopAmount: '中',
    deleteConfirm: false,
    babyNickname: '',
    babyBirthday: '',
    babyGender: '' as BabyGender | '',
    babyBirthWeightKg: '',
    savingBaby: false,
    devices: [] as FamilyDevice[],
    loadingDevices: false,
    trendDays: buildLocalTrend(loadEvents()) as TrendDay[],
    loadingStats: false,
    historyDate: dateKey(Date.now()),
    historyEvents: [] as BabyEvent[],
    loadingHistory: false,
    installPrompt: null as any,
    installed: window.matchMedia?.('(display-mode: standalone)').matches || false,
  };
}

type AppState = ReturnType<typeof createInitialState>;

export default class App extends React.Component<Record<string, never>, AppState> {
  timer: any;
  toastTimer: any;
  eventSource: EventSource | null = null;
  resumeSyncTimer: ReturnType<typeof setTimeout> | null = null;
  realtimeRetryTimer: ReturnType<typeof setTimeout> | null = null;
  realtimeRetryDelayMs = 1000;
  pendingRetryTimer: ReturnType<typeof setTimeout> | null = null;
  pendingRetryDelayMs = 1000;
  realtimeRefresh: { epoch: number; requested: boolean; promise: Promise<void> } | null = null;
  identityEpoch = 0;
  activeIdentity: IdentitySnapshot | null = null;
  authAttempt = 0;
  capabilitiesAttempt = 0;
  pendingFlushes = new Map<number, Promise<boolean>>();
  state = createInitialState();

  componentDidMount() {
    this.timer = setInterval(() => this.setState({ tick: Date.now() }), 1000);
    window.addEventListener('online', this.bootstrapRemote);
    document.addEventListener('visibilitychange', this.scheduleResumeSync);
    window.addEventListener('pageshow', this.scheduleResumeSync);
    window.addEventListener('focus', this.scheduleResumeSync);
    window.addEventListener('beforeinstallprompt', this.captureInstallPrompt as EventListener);
    window.addEventListener('appinstalled', this.onInstalled);
    this.bootstrapRemote();
  }

  componentWillUnmount() {
    clearInterval(this.timer);
    clearTimeout(this.toastTimer);
    if (this.resumeSyncTimer) clearTimeout(this.resumeSyncTimer);
    this.clearRealtimeRetry();
    this.clearPendingRetry();
    window.removeEventListener('online', this.bootstrapRemote);
    document.removeEventListener('visibilitychange', this.scheduleResumeSync);
    window.removeEventListener('pageshow', this.scheduleResumeSync);
    window.removeEventListener('focus', this.scheduleResumeSync);
    window.removeEventListener('beforeinstallprompt', this.captureInstallPrompt as EventListener);
    window.removeEventListener('appinstalled', this.onInstalled);
    this.eventSource?.close();
    this.eventSource = null;
    this.activeIdentity = null;
    this.identityEpoch++;
    this.authAttempt++;
    this.capabilitiesAttempt++;
  }

  api = (path: string, init: RequestInit = {}) => {
    const formal = (globalThis as any).__babyApi?.apiFetch;
    if (formal) return formal(path, init);
    return fetch(`${API_BASE}${path}`, {
      credentials: 'include',
      ...init,
      headers: {
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...(init.headers || {}),
      },
    });
  };

  captureIdentity = (): IdentitySnapshot | null => this.activeIdentity;

  isCurrentIdentity = (identity: IdentitySnapshot | null): identity is IdentitySnapshot => !!identity &&
    identity.epoch === this.activeIdentity?.epoch &&
    identity.familyId === this.activeIdentity.familyId &&
    identity.babyId === this.activeIdentity.babyId &&
    identity.familyId === this.state.me?.familyId &&
    identity.babyId === this.state.me?.babyId;

  deploymentKey = () => FAMILY_CREATION_DEPLOYMENT_KEY;

  activateIdentity = async (me: Me) => {
    this.clearRealtimeRetry();
    this.clearPendingRetry();
    const epoch = ++this.identityEpoch;
    this.activeIdentity = null;
    const repo = (globalThis as any).__babyLocalRepo;
    const snapshot = await repo?.activateScope?.(me.familyId, me.babyId, this.deploymentKey()) || {
      events: this.state.events,
      pending: loadPending(),
      profile: this.state.baby,
    };
    if (epoch !== this.identityEpoch) return false;
    const pending = snapshot.pending.map((action: PendingAction) => action.familyId && action.babyId
      ? action
      : { ...action, familyId: me.familyId, babyId: me.babyId });
    (globalThis as any).__BABY_INITIAL_SNAPSHOT__ = { ...snapshot, pending };
    (globalThis as any).__BABY_RUNTIME_PENDING__ = pending;
    const directSession = this.state.directSession?.familyId === me.familyId && this.state.directSession?.babyId === me.babyId ? this.state.directSession : null;
    if (!directSession) storageRemove(BREASTFEEDING_SESSION_KEY);
    await new Promise<void>(resolve => this.setState({
      me,
      events: snapshot.events,
      baby: snapshot.profile || { nickname: '宝宝', birthday: null, gender: null, birthWeightGrams: null },
      serverQuickAmounts: [],
      familyInvite: '',
      loadingInvite: false,
      inviteError: false,
      devices: [],
      savingBaby: false,
      loadingDevices: false,
      loadingStats: false,
      loadingHistory: false,
      pendingCount: pending.length,
      failedPendingCount: pending.filter((action: PendingAction) => action.blocked).length,
      directSession,
      trendDays: buildLocalTrend(snapshot.events),
      historyEvents: [],
      syncMode: 'cloud',
    }, resolve));
    if (epoch !== this.identityEpoch) return false;
    this.activeIdentity = { epoch, familyId: me.familyId, babyId: me.babyId };
    return true;
  };

  enterSetupMode = async (openOnboarding = false) => {
    this.clearRealtimeRetry();
    this.clearPendingRetry();
    const attempt = ++this.authAttempt;
    const epoch = ++this.identityEpoch;
    this.activeIdentity = null;
    this.eventSource?.close();
    await (globalThis as any).__babyApi?.queryApi?.clear?.();
    if (attempt !== this.authAttempt || epoch !== this.identityEpoch) return;
    const snapshot = await (globalThis as any).__babyLocalRepo?.deactivateScope?.() || {
      events: [], pending: [], profile: null,
    };
    if (attempt !== this.authAttempt || epoch !== this.identityEpoch) return;
    (globalThis as any).__BABY_INITIAL_SNAPSHOT__ = snapshot;
    (globalThis as any).__BABY_RUNTIME_PENDING__ = snapshot.pending;
    storageRemove(BREASTFEEDING_SESSION_KEY);
    this.setState({
      me: null,
      events: snapshot.events,
      baby: snapshot.profile || { nickname: '宝宝', birthday: null, gender: null, birthWeightGrams: null },
      serverQuickAmounts: [],
      familyInvite: '',
      loadingInvite: false,
      inviteError: false,
      devices: [],
      savingBaby: false,
      loadingDevices: false,
      loadingStats: false,
      loadingHistory: false,
      pendingCount: snapshot.pending.length,
      failedPendingCount: snapshot.pending.filter((action: PendingAction) => action.blocked).length,
      directSession: null,
      trendDays: buildLocalTrend(snapshot.events),
      historyEvents: [],
      syncMode: 'setup',
      realtime: false,
      joining: false,
      creatingFamily: false,
      sheet: openOnboarding ? 'family' : this.state.sheet,
      onboardingMode: openOnboarding ? 'create' : this.state.onboardingMode,
    });
  };

  bootstrapRemote = async () => {
    void this.loadCapabilities();
    const attempt = ++this.authAttempt;
    try {
      const meRes = await this.api('/api/v1/auth/me');
      if (attempt !== this.authAttempt) return;
      if (meRes.status === 401) {
        await this.enterSetupMode(true);
        return;
      }
      if (!meRes.ok) throw new Error(`me:${meRes.status}`);
      const me = await meRes.json() as Me;
      if (attempt !== this.authAttempt || !(await this.activateIdentity(me))) return;
      const identity = this.captureIdentity();
      const pendingCreation = currentDevicePendingFamilyCreation();
      if (pendingCreation) await this.confirmPendingFamilyCreation(pendingCreation, attempt);
      if (attempt !== this.authAttempt) return;
      await this.flushPending(true, identity);
      await this.refreshDashboard(identity);
      if (this.isCurrentIdentity(identity)) this.startRealtime();
    } catch {
      if (attempt === this.authAttempt) this.setState({ syncMode: 'local', realtime: false });
    }
  };

  loadCapabilities = async () => {
    const attempt = ++this.capabilitiesAttempt;
    try {
      const res = await this.api('/api/v1/capabilities', { cache: 'no-store' });
      if (attempt !== this.capabilitiesAttempt) return;
      if (!res.ok) {
        this.setState({ aiEnabled: false });
        return;
      }
      const capabilities = await res.json() as { aiEnabled?: boolean };
      if (attempt === this.capabilitiesAttempt) this.setState({ aiEnabled: capabilities.aiEnabled === true });
    } catch {
      if (attempt === this.capabilitiesAttempt) this.setState({ aiEnabled: false });
    }
  };

  refreshDashboard = async (identity = this.captureIdentity()) => {
    if (!this.isCurrentIdentity(identity)) return false;
    const babyId = identity.babyId;
    try {
      let dashboard: RemoteDashboard;
      const queryApi = (globalThis as any).__babyApi?.queryApi;
      if (queryApi) {
        dashboard = await queryApi.dashboard(babyId) as RemoteDashboard;
      } else {
        const res = await this.api(`/api/v1/babies/${babyId}/dashboard`);
        if (!this.isCurrentIdentity(identity)) return;
        if (res.status === 401) {
          await this.enterSetupMode();
          return;
        }
        if (!res.ok) throw new Error(`dashboard:${res.status}`);
        dashboard = await res.json() as RemoteDashboard;
      }
      if (!this.isCurrentIdentity(identity)) return;
      const pending = loadPending();
      const remoteEvents = remoteToLocal(dashboard.timeline || []);
      const oldestRemoteAt = remoteEvents.length >= 60 ? Math.min(...remoteEvents.map(event => event.at)) : Number.NEGATIVE_INFINITY;
      const olderEvents = this.state.events.filter(event => !event.pending && event.at < oldestRemoteAt);
      const merged = applyPendingOverlay([...remoteEvents, ...olderEvents], pending, this.state.events);
      const baby = {
        nickname: dashboard.baby?.nickname || '宝宝',
        birthday: dashboard.baby?.birthday || null,
        gender: dashboard.baby?.gender || null,
        birthWeightGrams: dashboard.baby?.birthWeightGrams ?? null,
      };
      void (globalThis as any).__babyLocalRepo?.saveProfile?.(baby);
      this.save(merged);
      this.setState({
        baby,
        serverQuickAmounts: dashboard.feedQuickAmounts || [],
        syncMode: 'cloud',
        pendingCount: pending.length,
        failedPendingCount: pending.filter(action => action.blocked).length,
      });
      if (pending.some(action => action.familyId === identity.familyId && action.babyId === babyId)) {
        void this.flushPending(true, identity);
      }
      return true;
    } catch (error: any) {
      if (!this.isCurrentIdentity(identity)) return false;
      if (error?.status === 401) await this.enterSetupMode();
      else this.setState({ syncMode: 'local' });
      return false;
    }
  };

  clearRealtimeRetry = () => {
    if (this.realtimeRetryTimer) clearTimeout(this.realtimeRetryTimer);
    this.realtimeRetryTimer = null;
    this.realtimeRetryDelayMs = 1000;
  };

  clearPendingRetry = () => {
    if (this.pendingRetryTimer) clearTimeout(this.pendingRetryTimer);
    this.pendingRetryTimer = null;
    this.pendingRetryDelayMs = 1000;
  };

  schedulePendingRetry = (identity: IdentitySnapshot) => {
    if (this.pendingRetryTimer || !this.isCurrentIdentity(identity)) return;
    const delay = this.pendingRetryDelayMs;
    this.pendingRetryDelayMs = Math.min(delay * 2, 30000);
    this.pendingRetryTimer = setTimeout(() => {
      this.pendingRetryTimer = null;
      if (this.isCurrentIdentity(identity)) void this.flushPending(true, identity);
    }, delay);
  };

  scheduleRealtimeRetry = (identity: IdentitySnapshot) => {
    if (this.realtimeRetryTimer || !this.isCurrentIdentity(identity)) return;
    const delay = this.realtimeRetryDelayMs;
    this.realtimeRetryDelayMs = Math.min(delay * 2, 30000);
    this.realtimeRetryTimer = setTimeout(() => {
      this.realtimeRetryTimer = null;
      if (this.isCurrentIdentity(identity)) void this.refreshRealtimeData(identity, true);
    }, delay);
  };

  refreshRealtimeData = async (identity: IdentitySnapshot | null, queueIfBusy = false) => {
    if (!this.isCurrentIdentity(identity)) return;
    const activeRefresh = this.realtimeRefresh;
    if (activeRefresh?.epoch === identity.epoch) {
      if (queueIfBusy) activeRefresh.requested = true;
      await activeRefresh.promise;
      return;
    }

    const refresh = { epoch: identity.epoch, requested: false, promise: Promise.resolve() };
    refresh.promise = (async () => {
      do {
        refresh.requested = false;
        if (!this.isCurrentIdentity(identity)) return;
        const babyId = identity.babyId;
        await (globalThis as any).__babyApi?.queryApi?.invalidateBaby?.(babyId);
        if (!this.isCurrentIdentity(identity)) return;
        const refreshed = await this.refreshDashboard(identity);
        if (!this.isCurrentIdentity(identity)) return;
        if (!refreshed) {
          this.scheduleRealtimeRetry(identity);
          return;
        }
        this.clearRealtimeRetry();
        await this.flushPending(true, identity);
        if (!this.isCurrentIdentity(identity)) return;
        if (this.state.sheet === 'stats') await this.openStats(identity);
        if (this.state.sheet === 'history') await this.openHistory(this.state.historyDate, identity);
      } while (refresh.requested && this.isCurrentIdentity(identity));
    })().finally(() => {
      if (this.realtimeRefresh === refresh) this.realtimeRefresh = null;
    });
    this.realtimeRefresh = refresh;
    await refresh.promise;
  };

  handleRealtimeChanged = (identity = this.captureIdentity()) => this.refreshRealtimeData(identity, true);

  scheduleResumeSync = () => {
    if (document.visibilityState === 'hidden') return;
    if (this.resumeSyncTimer) clearTimeout(this.resumeSyncTimer);
    this.resumeSyncTimer = setTimeout(() => {
      this.resumeSyncTimer = null;
      const identity = this.captureIdentity();
      if (!this.isCurrentIdentity(identity)) return;
      void this.refreshRealtimeData(identity);
      this.startRealtime(identity);
    }, 100);
  };

  startRealtime = (identity = this.captureIdentity()) => {
    this.eventSource?.close();
    this.eventSource = null;
    if (!this.isCurrentIdentity(identity)) return;
    const babyId = identity.babyId;
    try {
      const source = new EventSource(`${API_BASE}/api/v1/babies/${babyId}/stream`, { withCredentials: true });
      this.eventSource = source;
      source.addEventListener('connected', () => {
        if (this.eventSource !== source || !this.isCurrentIdentity(identity)) return;
        this.setState({ realtime: true });
        void this.refreshRealtimeData(identity, true);
      });
      source.addEventListener('changed', () => {
        if (this.eventSource === source) void this.handleRealtimeChanged(identity);
      });
      source.onerror = () => {
        if (this.eventSource === source && this.isCurrentIdentity(identity)) this.setState({ realtime: false });
      };
    } catch { this.setState({ realtime: false }); }
  };

  save(events: BabyEvent[]) {
    void (globalThis as any).__babyLocalRepo?.saveEvents?.(events);
    this.setState({ events, trendDays: buildLocalTrend(events) });
  }

  setPending(actions: PendingAction[]) {
    (globalThis as any).__BABY_RUNTIME_PENDING__ = actions;
    savePending(actions);
    void (globalThis as any).__babyLocalRepo?.savePending?.(actions);
    this.setState({ pendingCount: actions.length, failedPendingCount: actions.filter(action => action.blocked).length });
  }

  enqueue(action: PendingAction) {
    const identity = this.captureIdentity();
    if (identity) action = { ...action, familyId: identity.familyId, babyId: identity.babyId };
    let actions = [...loadPending()];
    if (action.kind === 'update' && action.serverId) {
      const previous = actions.find(p => p.kind === 'update' && p.serverId === action.serverId);
      actions = actions.filter(p => !(p.kind === 'update' && p.serverId === action.serverId));
      action = {
        ...action,
        id: previous?.id || action.id,
        revision: previous ? (previous.revision ?? 0) + 1 : action.revision,
        expectedUpdatedAt: previous?.expectedUpdatedAt || action.expectedUpdatedAt,
      };
    }
    if (action.kind === 'delete' && action.serverId) {
      const previous = actions.find(p => p.serverId === action.serverId && (p.kind === 'update' || (p.kind === 'delete' && p.blocked)));
      actions = actions.filter(p => !(p.serverId === action.serverId && (p.kind === 'update' || (p.kind === 'delete' && p.blocked))));
      if (previous) action = {
        ...action,
        id: previous.id,
        revision: (previous.revision ?? 0) + 1,
        expectedUpdatedAt: previous.expectedUpdatedAt || action.expectedUpdatedAt,
      };
    }
    action = {
      ...action,
      revision: action.revision ?? 0,
      ...(isPendingCreation(action) ? { attempted: action.attempted ?? false } : {}),
    };
    actions.push(action);
    this.setPending(actions);
    if (this.state.syncMode === 'cloud') this.flushPending(false, identity);
  }

  flushPending = async (force = false, identity = this.captureIdentity()): Promise<boolean> => {
    if (!this.isCurrentIdentity(identity) || (!force && this.state.syncMode !== 'cloud')) return false;
    const active = this.pendingFlushes.get(identity.epoch);
    if (active) return active;
    const promise = this.runPendingFlush(identity).finally(() => {
      if (this.pendingFlushes.get(identity.epoch) === promise) this.pendingFlushes.delete(identity.epoch);
    });
    this.pendingFlushes.set(identity.epoch, promise);
    return promise;
  };

  runPendingFlush = async (identity: IdentitySnapshot): Promise<boolean> => {
    const babyId = identity.babyId;
    try {
      while (this.isCurrentIdentity(identity)) {
        let action = loadPending().find(action => action.familyId === identity.familyId && action.babyId === babyId && !action.blocked);
        if (!action) break;
        if (isPendingCreation(action) && action.cancelled && action.attempted === false) {
          this.setPending(loadPending().filter(item => item.id !== action!.id));
          continue;
        }
        if (isPendingCreation(action) && action.attempted === false) {
          const actions = loadPending();
          const index = actions.findIndex(item => item.id === action!.id && (item.revision ?? 0) === (action!.revision ?? 0));
          if (index < 0) continue;
          action = { ...actions[index], attempted: true };
          actions[index] = action;
          this.setPending(actions);
        }
        if (!this.isCurrentIdentity(identity)) return false;
        const res = await this.sendAction(action, babyId);
        if (!this.isCurrentIdentity(identity)) return false;
        if (res.status === 401) {
          await this.enterSetupMode();
          return false;
        }
        if (res.status === 409) {
          const latest = loadPending().find(item => item.id === action.id);
          if (latest && (latest.revision ?? 0) === (action.revision ?? 0)) {
            this.setPending(loadPending().filter(item => item.id !== action.id));
          }
          await (globalThis as any).__babyApi?.queryApi?.invalidateBaby?.(babyId);
          await this.refreshDashboard(identity);
          this.flash('这条记录已被家人修改，已采用云端最新版本');
          continue;
        }
        if (action.kind === 'delete' && res.status === 404) {
          const latest = loadPending().find(item => item.id === action.id);
          if (latest && (latest.revision ?? 0) === (action.revision ?? 0)) {
            this.setPending(loadPending().filter(item => item.id !== action.id));
          }
          continue;
        }
        if (isRetryablePendingStatus(res.status)) throw new Error(`sync:${res.status}`);
        if (res.status >= 400 && res.status < 500) {
          this.isolateTerminalAction(action, res.status);
          continue;
        }
        if (!res.ok) throw new Error(`sync:${res.status}`);
        await this.resolveSuccessfulAction(action, res);
        await (globalThis as any).__babyApi?.queryApi?.invalidateBaby?.(babyId);
      }
      if (!this.isCurrentIdentity(identity)) return false;
      this.clearPendingRetry();
      if (!loadPending().some(item => item.familyId === identity.familyId && item.babyId === babyId)) await this.refreshDashboard(identity);
      return true;
    } catch {
      if (this.isCurrentIdentity(identity)) {
        this.setState({ syncMode: 'local', realtime: false });
        this.schedulePendingRetry(identity);
      }
      return false;
    }
  };

  isolateTerminalAction = (sent: PendingAction, status: number) => {
    const actions = loadPending();
    const index = actions.findIndex(action => action.id === sent.id);
    if (index < 0) return;
    const latest = actions[index];
    if ((latest.revision ?? 0) !== (sent.revision ?? 0)) return;
    const failureMessage = `同步失败（${status}），请点开记录修改后重试`;
    actions[index] = {
      ...latest,
      blocked: true,
      failureMessage,
      ...(isPendingCreation(latest) ? { attempted: false } : {}),
    };
    this.setPending(actions);
    let events = this.state.events.map(event =>
      (latest.localEventId && event.id === latest.localEventId) || (latest.serverId && event.serverId === latest.serverId)
        ? { ...event, pending: true, syncError: failureMessage }
        : event);
    if (latest.kind === 'delete' && latest.rollbackEvent && !events.some(event => event.id === latest.rollbackEvent!.id)) {
      events = [{ ...latest.rollbackEvent, pending: true, syncError: failureMessage }, ...events];
    }
    this.save(events);
    this.flash(failureMessage);
  };

  resolveSuccessfulAction = async (sent: PendingAction, response: Response) => {
    const created = isPendingCreation(sent) || sent.kind === 'update'
      ? await response.clone().json().catch(() => null) as RemoteEvent | null
      : null;
    const actions = loadPending();
    const index = actions.findIndex(item => item.id === sent.id);
    if (index < 0) return;
    if (isPendingCreation(sent) && !created?.id) throw new Error('Creation response did not include an event id');
    if (created?.id && sent.localEventId) {
      this.save(this.state.events.map(event => event.id === sent.localEventId
        ? {
            ...event,
            serverId: created.id,
            serverUpdatedAt: created.updatedAt || event.serverUpdatedAt,
            operatorName: (event.type === 'sleep_end' ? created.endOperatorName : created.operatorName) || created.operatorName || event.operatorName,
          }
        : event));
    }
    this.setPending(reconcilePendingSuccess(actions, sent, created));
  };

  sendAction = (action: PendingAction, babyId: number) => {
    if (action.babyId !== babyId) throw new Error('Pending action belongs to another baby');
    if (action.kind === 'feed') {
      if (!action.feedingType) {
        return this.api(`/api/v1/babies/${babyId}/events/feed`, {
          method: 'POST', body: JSON.stringify({ amountMl: action.amount, eventTime: toLocalDateTime(action.at!), clientEventId: action.clientEventId }),
        });
      }
      return this.api(`/api/v1/babies/${babyId}/events/feeding`, {
        method: 'POST', body: JSON.stringify({
          type: action.feedingType,
          amountMl: action.amount,
          ...(action.data || {}),
          eventTime: toLocalDateTime(action.at!),
          clientEventId: action.clientEventId,
        }),
      });
    }
    if (action.kind === 'simple') {
      return this.api(`/api/v1/babies/${babyId}/events/simple`, {
        method: 'POST', body: JSON.stringify({ type: action.simpleType, eventTime: toLocalDateTime(action.at!), clientEventId: action.clientEventId, data: action.data }),
      });
    }
    if (action.kind === 'sleep_start') {
      return this.api(`/api/v1/babies/${babyId}/sleep/start`, {
        method: 'POST', body: JSON.stringify({ eventTime: toLocalDateTime(action.at!), clientEventId: action.clientEventId }),
      });
    }
    if (action.kind === 'sleep_end') {
      if (action.clientEventId) return this.api(`/api/v1/babies/${babyId}/sleep/end`, {
        method: 'POST', body: JSON.stringify({ eventTime: toLocalDateTime(action.at!), clientEventId: action.clientEventId }),
      });
      return this.api(`/api/v1/babies/${babyId}/sleep/${action.serverId}/end`, {
        method: 'POST', body: JSON.stringify({ eventTime: toLocalDateTime(action.at!) }),
      });
    }
    if (action.kind === 'update') {
      return this.api(`/api/v1/babies/${babyId}/events/${action.serverId}`, {
        method: 'PATCH',
        body: JSON.stringify({
          eventTime: action.updateStartAt ? toLocalDateTime(action.updateStartAt) : undefined,
          endTime: action.updateEndAt ? toLocalDateTime(action.updateEndAt) : undefined,
          amountMl: action.amount,
          data: action.data,
          expectedUpdatedAt: action.expectedUpdatedAt,
        }),
      });
    }
    const version = action.expectedUpdatedAt ? `?expectedUpdatedAt=${encodeURIComponent(action.expectedUpdatedAt)}` : '';
    return this.api(`/api/v1/babies/${babyId}/events/${action.serverId}${version}`, { method: 'DELETE' });
  };

  get sorted() { return [...this.state.events].sort((a,b) => b.at - a.at); }
  get lastFeed() { return this.sorted.find(e => isFeedingEvent(e) && e.type !== 'pumping'); }
  get lastPoop() { return this.sorted.find(e => e.type === 'poop'); }
  get activeSleep() {
    const latest = this.sorted.find(e => e.type === 'sleep_start' || e.type === 'sleep_end');
    return latest && latest.type === 'sleep_start' ? latest : null;
  }

  get quickAmounts() {
    const cutoff = Date.now() - 3 * 24 * 3600000;
    const feeds = this.state.events.filter(e => (e.type === 'bottle_breast_milk' || e.type === 'formula_feed' || e.type === 'feed') && e.amount && e.at >= cutoff);
    const stats: Record<number, { count: number; last: number }> = {};
    feeds.forEach(e => {
      const a = Number(e.amount);
      if (!stats[a]) stats[a] = { count: 0, last: 0 };
      stats[a].count++;
      stats[a].last = Math.max(stats[a].last, e.at);
    });
    const localRanked = Object.keys(stats).map(Number).sort((a,b) => stats[b].count - stats[a].count || stats[b].last - stats[a].last);
    const ranked = this.state.syncMode === 'cloud' && this.state.serverQuickAmounts.length ? this.state.serverQuickAmounts : localRanked;
    return ranked.slice(0, 5);
  }

  flash = (msg: string) => {
    clearTimeout(this.toastTimer);
    this.setState({ toast: msg });
    this.toastTimer = setTimeout(() => this.setState({ toast: '' }), 1600);
  };

  addLocalEvent = (event: BabyEvent) => this.save([event, ...this.state.events]);
  resetDraft = () => this.setState({ draftAt: Date.now(), timeInput: toInputDateTime(Date.now()) });

  openQuick = () => {
    const now = Date.now();
    this.setState({ sheet: 'quick', draftAt: now, timeInput: toInputDateTime(now) });
  };

  openDirectBreastfeed = () => {
    this.setState({ sheet: 'directBreastfeed' });
  };

  openBottleBreastMilk = () => {
    this.setState({ sheet: 'bottleBreastMilk', breastBottleAmount: '' });
  };

  openFormulaFeed = () => {
    this.setState({ sheet: 'formulaFeed', formulaAmount: '' });
  };

  openPumping = () => {
    this.setState({ sheet: 'pumping', pumpLeftMl: '', pumpRightMl: '', pumpDurationMinutes: '' });
  };

  openPoopNow = () => {
    const now = Date.now();
    this.setState({ sheet: 'poop', draftAt: now, timeInput: toInputDateTime(now) });
  };

  openTime = (returnSheet: SheetType) => {
    this.setState({ sheet: 'recordTime', timeReturnSheet: returnSheet, timeInput: toInputDateTime(this.state.draftAt) });
  };

  applyDraftTime = () => {
    const at = new Date(this.state.timeInput).getTime();
    if (!Number.isFinite(at)) return;
    if (at > Date.now() + 60000) { this.flash('记录时间不能晚于现在'); return; }
    this.setState({ draftAt: at, sheet: this.state.timeReturnSheet });
  };

  recordFeeding = (feedingType: FeedingType, amount?: number, data?: Record<string, unknown>, at = this.state.draftAt || Date.now()) => {
    if ((feedingType === 'BOTTLE_BREAST_MILK' || feedingType === 'FORMULA_FEED') && (!Number.isInteger(amount) || !amount || amount < 1 || amount > 1000)) {
      this.flash('请输入 1 到 1000ml 的实际喝下量');
      return;
    }
    if (feedingType === 'PUMPING' && (!Number.isInteger(amount) || !amount || amount < 1 || amount > 1000)) {
      this.flash('请输入左侧或右侧泵奶量');
      return;
    }
    const id = uuid();
    const type = feedingEventType(feedingType);
    this.addLocalEvent({ id, clientEventId: id, type, at, amount, meta: { schemaVersion: 1, ...data }, pending: true, operatorName: this.state.me?.nickname });
    this.enqueue({ id: uuid(), kind: 'feed', localEventId: id, clientEventId: id, at, amount, feedingType, data });
    this.setState({ sheet: null, breastBottleAmount: '', formulaAmount: '', pumpLeftMl: '', pumpRightMl: '', pumpDurationMinutes: '' });
    this.resetDraft();
    const label = feedingType === 'BOTTLE_BREAST_MILK' ? `母乳瓶喂 ${amount}ml`
      : feedingType === 'FORMULA_FEED' ? `配方奶 ${amount}ml`
      : feedingType === 'PUMPING' ? `泵奶 ${amount}ml`
      : '母乳亲喂';
    this.flash(`已记录 · ${label}`);
  };

  recordBottleBreastMilk = () => this.recordFeeding('BOTTLE_BREAST_MILK', Number(this.state.breastBottleAmount));

  recordFormulaFeed = () => this.recordFeeding('FORMULA_FEED', Number(this.state.formulaAmount));

  recordPumping = () => {
    const leftMl = Number(this.state.pumpLeftMl || 0);
    const rightMl = Number(this.state.pumpRightMl || 0);
    const durationMinutes = Number(this.state.pumpDurationMinutes || 0);
    if (![leftMl, rightMl].every(value => Number.isInteger(value) && value >= 0 && value <= 1000)) { this.flash('单侧泵奶量应为 0 到 1000ml'); return; }
    if (leftMl + rightMl > 1000) { this.flash('左右侧泵奶总量不能超过 1000ml'); return; }
    if (this.state.pumpDurationMinutes && (!Number.isInteger(durationMinutes) || durationMinutes < 1 || durationMinutes > 600)) { this.flash('泵奶时长应为 1 到 600 分钟'); return; }
    this.recordFeeding('PUMPING', leftMl + rightMl, {
      leftMl,
      rightMl,
      ...(durationMinutes ? { durationSeconds: durationMinutes * 60 } : {}),
    });
  };

  persistDirectSession = (session: BreastfeedingSession | null) => {
    if (session) storageSet(BREASTFEEDING_SESSION_KEY, JSON.stringify(session));
    else storageRemove(BREASTFEEDING_SESSION_KEY);
    this.setState({ directSession: session, tick: Date.now() });
  };

  startBreastSide = (side: BreastSide) => {
    const now = Date.now();
    const current = this.state.directSession;
    if (!current) {
      this.persistDirectSession({ familyId: this.state.me?.familyId, babyId: this.state.me?.babyId, startedAt: now, activeSide: side, activeSince: now, lastSide: side, leftSeconds: 0, rightSeconds: 0, segments: [] });
      return;
    }
    if (current.activeSide === side) return;
    const leftSeconds = breastSideSeconds(current, 'LEFT', now);
    const rightSeconds = breastSideSeconds(current, 'RIGHT', now);
    const segmentSeconds = current.activeSide && current.activeSince ? Math.max(0, Math.floor((now - current.activeSince) / 1000)) : 0;
    const segments = segmentSeconds && current.activeSide ? [...current.segments, { side: current.activeSide, seconds: segmentSeconds }] : current.segments;
    this.persistDirectSession({ ...current, activeSide: side, activeSince: now, lastSide: side, leftSeconds, rightSeconds, segments });
  };

  pauseBreastfeeding = () => {
    const current = this.state.directSession;
    if (!current?.activeSide) return;
    const now = Date.now();
    const segmentSeconds = current.activeSince ? Math.max(0, Math.floor((now - current.activeSince) / 1000)) : 0;
    this.persistDirectSession({
      ...current,
      leftSeconds: breastSideSeconds(current, 'LEFT', now),
      rightSeconds: breastSideSeconds(current, 'RIGHT', now),
      activeSide: null,
      activeSince: null,
      segments: segmentSeconds ? [...current.segments, { side: current.activeSide, seconds: segmentSeconds }] : current.segments,
    });
  };

  resumeBreastfeeding = () => {
    const current = this.state.directSession;
    if (!current || current.activeSide) return;
    this.startBreastSide(current.lastSide || 'LEFT');
  };

  finishBreastfeeding = () => {
    const current = this.state.directSession;
    if (!current) return;
    const now = Date.now();
    const leftSeconds = breastSideSeconds(current, 'LEFT', now);
    const rightSeconds = breastSideSeconds(current, 'RIGHT', now);
    if (leftSeconds + rightSeconds < 1) { this.flash('请先开始左侧或右侧计时'); return; }
    const segmentSeconds = current.activeSide && current.activeSince ? Math.max(0, Math.floor((now - current.activeSince) / 1000)) : 0;
    const segments = segmentSeconds && current.activeSide ? [...current.segments, { side: current.activeSide, seconds: segmentSeconds }] : current.segments;
    this.persistDirectSession(null);
    this.recordFeeding('DIRECT_BREASTFEED', undefined, { leftSeconds, rightSeconds, lastSide: current.lastSide, segments }, current.startedAt);
  };

  discardBreastfeeding = () => {
    this.persistDirectSession(null);
    this.setState({ sheet: null });
  };

  toggleSleep = () => this.toggleSleepAt(Date.now());

  toggleSleepAt = (at: number) => {
    const active = this.activeSleep;
    if (active && at < active.at) { this.flash('醒来时间不能早于入睡时间'); return; }
    if (active) {
      const localId = uuid();
      this.addLocalEvent({ id: localId, type: 'sleep_end', at, pending: true, serverId: active.serverId, meta: { sleepClientEventId: active.clientEventId }, operatorName: this.state.me?.nickname });
      this.enqueue({ id: uuid(), kind: 'sleep_end', localEventId: localId, clientEventId: active.clientEventId, serverId: active.serverId, at });
      this.flash(`睡眠 ${duration(at - active.at)}`);
    } else {
      const id = uuid();
      this.addLocalEvent({ id, clientEventId: id, type: 'sleep_start', at, pending: true, operatorName: this.state.me?.nickname });
      this.enqueue({ id: uuid(), kind: 'sleep_start', localEventId: id, clientEventId: id, at });
      this.flash('已开始记录睡眠');
    }
    this.setState({ sheet: null });
    this.resetDraft();
  };

  recordSimple = (type: 'poop'|'pee', data?: Record<string, unknown>) => {
    const id = uuid();
    const at = this.state.draftAt || Date.now();
    this.addLocalEvent({ id, clientEventId: id, type, at, meta: data, pending: true, operatorName: this.state.me?.nickname });
    this.enqueue({ id: uuid(), kind: 'simple', localEventId: id, clientEventId: id, at, simpleType: type === 'poop' ? 'POOP' : 'PEE', data });
    this.setState({ sheet: null });
    this.resetDraft();
    this.flash(type === 'poop' ? '已记录便便' : '已记录尿尿');
  };

  recordPoop = () => this.recordSimple('poop', {
    color: this.state.poopColor,
    texture: this.state.poopTexture,
    amount: this.state.poopAmount,
  });

  openEditEvent = (event: BabyEvent) => {
    this.setState({
      sheet: 'editEvent',
      editEventId: event.id,
      editTimeInput: toInputDateTime(event.at),
      editAmount: event.amount ? String(event.amount) : '',
      editLeftSeconds: String(event.meta?.leftSeconds ?? ''),
      editRightSeconds: String(event.meta?.rightSeconds ?? ''),
      editLeftMl: String(event.meta?.leftMl ?? ''),
      editRightMl: String(event.meta?.rightMl ?? ''),
      editDurationMinutes: event.meta?.durationSeconds ? String(Math.round(Number(event.meta.durationSeconds) / 60)) : '',
      editPoopColor: String(event.meta?.color || '黄色'),
      editPoopTexture: String(event.meta?.texture || '奶瓣'),
      editPoopAmount: String(event.meta?.amount || '中'),
      deleteConfirm: false,
    });
  };

  saveEventEdit = () => {
    const event = this.state.events.find(e => e.id === this.state.editEventId);
    if (!event) return;
    const newAt = new Date(this.state.editTimeInput).getTime();
    if (!Number.isFinite(newAt)) return;
    if (newAt > Date.now() + 60000) { this.flash('记录时间不能晚于现在'); return; }
    const isBottle = event.type === 'feed' || event.type === 'bottle_breast_milk' || event.type === 'formula_feed';
    let amount = isBottle ? Number(this.state.editAmount) : event.amount;
    if (isBottle && (!Number.isInteger(amount) || !amount || amount < 1 || amount > 1000)) { this.flash('请输入正确奶量'); return; }
    let data = event.type === 'poop' ? {
      color: this.state.editPoopColor,
      texture: this.state.editPoopTexture,
      amount: this.state.editPoopAmount,
    } : event.meta;
    if (event.type === 'direct_breastfeed') {
      const leftSeconds = Number(this.state.editLeftSeconds || 0);
      const rightSeconds = Number(this.state.editRightSeconds || 0);
      if (![leftSeconds, rightSeconds].every(value => Number.isInteger(value) && value >= 0 && value <= 86400) || leftSeconds + rightSeconds < 1) { this.flash('请输入正确的亲喂时长'); return; }
      const unchangedDurations = leftSeconds === Number(event.meta?.leftSeconds || 0) && rightSeconds === Number(event.meta?.rightSeconds || 0);
      data = { schemaVersion: 1, leftSeconds, rightSeconds, lastSide: normalizeBreastLastSide(leftSeconds, rightSeconds, event.meta?.lastSide), ...(unchangedDurations && Array.isArray(event.meta?.segments) ? { segments: event.meta.segments } : {}) };
    }
    if (event.type === 'pumping') {
      const leftMl = Number(this.state.editLeftMl || 0);
      const rightMl = Number(this.state.editRightMl || 0);
      const durationMinutes = Number(this.state.editDurationMinutes || 0);
      if (![leftMl, rightMl].every(value => Number.isInteger(value) && value >= 0 && value <= 1000) || leftMl + rightMl < 1) { this.flash('请输入正确的泵奶量'); return; }
      if (leftMl + rightMl > 1000) { this.flash('左右侧泵奶总量不能超过 1000ml'); return; }
      if (this.state.editDurationMinutes && (!Number.isInteger(durationMinutes) || durationMinutes < 1 || durationMinutes > 600)) { this.flash('请输入正确的泵奶时长'); return; }
      amount = leftMl + rightMl;
      data = { schemaVersion: 1, leftMl, rightMl, durationSeconds: durationMinutes ? durationMinutes * 60 : null };
    }

    if (event.type === 'sleep_end') {
      const start = this.state.events.find(e => e.type === 'sleep_start' && ((event.serverId && e.serverId === event.serverId) || (event.meta?.sleepClientEventId && e.clientEventId === event.meta.sleepClientEventId)));
      if (start && newAt < start.at) { this.flash('醒来时间不能早于入睡时间'); return; }
    }
    if (event.type === 'sleep_start') {
      const end = this.state.events.find(e => e.type === 'sleep_end' && ((event.serverId && e.serverId === event.serverId) || (event.clientEventId && e.meta?.sleepClientEventId === event.clientEventId)));
      if (end && newAt > end.at) { this.flash('入睡时间不能晚于醒来时间'); return; }
    }

    const updated = this.state.events.map(e => e.id === event.id ? { ...e, at: newAt, amount, meta: data, pending: true, syncError: undefined } : e);
    this.save(updated);

    const pending = loadPending();
    const creationIndex = pending.findIndex(p => p.localEventId === event.id && ['feed','simple','sleep_start','sleep_end'].includes(p.kind));
    if (creationIndex >= 0) {
      const requestData = event.type === 'direct_breastfeed' ? { leftSeconds: data?.leftSeconds, rightSeconds: data?.rightSeconds, lastSide: data?.lastSide, ...(Array.isArray(data?.segments) ? { segments: data.segments } : {}) }
        : event.type === 'pumping' ? { leftMl: data?.leftMl, rightMl: data?.rightMl, durationSeconds: data?.durationSeconds ?? undefined }
        : pending[creationIndex].data;
      const action = { ...pending[creationIndex], at: newAt, amount, data: requestData, revision: (pending[creationIndex].revision ?? 0) + 1, blocked: undefined, failureMessage: undefined, attempted: pending[creationIndex].blocked ? false : pending[creationIndex].attempted };
      pending[creationIndex] = action;
      this.setPending(pending);
    } else if (event.serverId) {
      const withoutFailedDelete = pending.filter(action => !(action.kind === 'delete' && action.blocked && action.serverId === event.serverId));
      if (withoutFailedDelete.length !== pending.length) this.setPending(withoutFailedDelete);
      this.enqueue({
        id: uuid(), kind: 'update', serverId: event.serverId, localEventId: event.id,
        updateStartAt: event.type === 'sleep_end' ? undefined : newAt,
        updateEndAt: event.type === 'sleep_end' ? newAt : undefined,
        amount: isFeedingEvent(event) ? amount : undefined,
        data: event.type === 'poop' || isFeedingEvent(event) ? data : undefined,
        expectedUpdatedAt: event.serverUpdatedAt,
      });
    }
    this.setState({ sheet: null, editEventId: null });
    this.flash('记录已修改');
  };

  deleteSelectedEvent = () => {
    const event = this.state.events.find(e => e.id === this.state.editEventId);
    if (!event) return;
    const sameSleep = (e: BabyEvent) => event.type.startsWith('sleep') && (
      (event.serverId && e.serverId === event.serverId) ||
      (event.clientEventId && e.meta?.sleepClientEventId === event.clientEventId) ||
      (event.meta?.sleepClientEventId && e.clientEventId === event.meta.sleepClientEventId)
    );
    const events = this.state.events.filter(e => e.id !== event.id && !sameSleep(e));
    this.save(events);

    let pending = loadPending();
    const clientId = event.clientEventId || String(event.meta?.sleepClientEventId || '');
    const pendingCreation = pending.find(action => isPendingCreation(action) && (action.localEventId === event.id || (!!clientId && action.clientEventId === clientId)));
    if (pendingCreation) {
      if (event.serverId && pendingCreation.attempted === false) {
        pending = pending.filter(action => action.id !== pendingCreation.id);
        this.setPending(pending);
        this.enqueue({ id: uuid(), kind: 'delete', serverId: event.serverId, localEventId: event.id, rollbackEvent: event, expectedUpdatedAt: event.serverUpdatedAt });
      } else {
        pending = cancelPendingCreations(pending, action => action.localEventId === event.id || (!!clientId && action.clientEventId === clientId));
        this.setPending(pending);
      }
    } else if (!event.serverId) {
      pending = cancelPendingCreations(pending, action => action.localEventId === event.id || (!!clientId && action.clientEventId === clientId));
      this.setPending(pending);
    } else {
      pending = pending.filter(p => !(p.kind === 'update' && p.serverId === event.serverId));
      this.setPending(pending);
      this.enqueue({ id: uuid(), kind: 'delete', serverId: event.serverId, localEventId: event.id, rollbackEvent: event, expectedUpdatedAt: event.serverUpdatedAt });
    }
    this.setState({ sheet: null, editEventId: null, deleteConfirm: false });
    this.flash('记录已删除');
  };

  finishAuthentication = async (me: Me, attempt: number, message: string) => {
    if (attempt !== this.authAttempt || !(await this.activateIdentity(me))) return false;
    const identity = this.captureIdentity();
    this.setState({ sheet: null, joining: false, creatingFamily: false });
    this.flash(message);
    await this.flushPending(true, identity);
    await this.refreshDashboard(identity);
    if (this.isCurrentIdentity(identity)) this.startRealtime();
    return this.isCurrentIdentity(identity);
  };

  confirmPendingFamilyCreation = async (pending: PendingFamilyCreation, attempt: number) => {
    try {
      const res = await this.api('/api/v1/auth/family/create/confirm', {
        method: 'POST',
        body: JSON.stringify({ creationKey: pending.request.creationKey }),
      });
      if (res.status === 204) {
        clearPendingFamilyCreation(pending);
        if (attempt === this.authAttempt) this.setState({ creationRecoveryInvalid: '' });
        return true;
      }
      if (attempt !== this.authAttempt) return false;
      if (res.status === 403) {
        const message = '上次创建恢复已过期；当前家庭仍可使用，如需重新创建请先退出本设备';
        this.setState({ creationRecoveryInvalid: message });
        this.flash(message);
      } else if (res.status === 409) {
        const message = '上次创建信息与当前家庭不一致；当前家庭仍可使用，如需重建请先退出本设备';
        this.setState({ creationRecoveryInvalid: message });
        this.flash(message);
      } else {
        this.flash('家庭已登录，创建恢复确认稍后会自动重试');
      }
      return false;
    } catch {
      if (attempt === this.authAttempt) this.flash('家庭已登录，创建恢复确认稍后会自动重试');
      return false;
    }
  };

  claimDevice = async () => {
    const { joinCode, joinNickname } = this.state;
    if (!joinCode.trim() || !joinNickname.trim()) return;
    const attempt = ++this.authAttempt;
    this.setState({ joining: true });
    try {
      const res = await this.api('/api/v1/auth/device/claim', {
        method: 'POST',
        body: JSON.stringify({
          inviteCode: joinCode.trim(), nickname: joinNickname.trim(), deviceId: getDeviceId(),
          deviceName: deviceName(),
        }),
      });
      if (attempt !== this.authAttempt) return;
      if (!res.ok) {
        this.setState({ joining: false });
        this.flash(res.status === 404 ? '家庭邀请码无效，请检查后重试' : '暂时无法加入家庭，请稍后重试');
        return;
      }
      const me = await res.json() as Me;
      await this.finishAuthentication(me, attempt, '这台设备已加入家庭，以后无需重复登录');
    } catch {
      if (attempt === this.authAttempt) {
        this.setState({ joining: false });
        this.flash('暂时无法连接服务器，记录仍保存在本机');
      }
    }
  };

  verifyCreatedSession = async (created: Me, attempt: number) => {
    try {
      const res = await this.api('/api/v1/auth/me', { cache: 'no-store' });
      if (attempt !== this.authAttempt || !res.ok) return null;
      const verified = await res.json() as Me;
      if (attempt !== this.authAttempt) return null;
      return verified.familyId === created.familyId &&
        verified.babyId === created.babyId &&
        verified.userId === created.userId
        ? verified
        : null;
    } catch {
      return null;
    }
  };

  discardInvalidFamilyCreation = () => {
    const pending = currentDevicePendingFamilyCreation();
    if (pending) clearPendingFamilyCreation(pending);
    this.setState({ creationRecoveryInvalid: '', creatingFamily: false });
    this.flash('已放弃失效的恢复记录，可以重新创建');
  };

  discardLegacyFamilyCreation = () => {
    storageRemove(LEGACY_FAMILY_CREATION_KEY);
    this.setState({ legacyCreationRecoveryDetected: false });
    this.flash('旧版恢复记录已清除，可以安全重新创建');
  };

  createFamily = async () => {
    const { createFamilyName, createNickname, createBabyNickname, createBirthDate, createGender, createBirthWeightKg } = this.state;
    const weightGrams = birthWeightGrams(createBirthWeightKg);
    if (!createFamilyName.trim() || !createNickname.trim() || !createBabyNickname.trim() || !createBirthDate || !createGender || weightGrams === null) return;
    if (daysOld(createBirthDate) === null) {
      this.flash('请选择有效的出生年月日');
      return;
    }
    const requestedFields = {
      familyName: createFamilyName.trim(),
      babyNickname: createBabyNickname.trim(),
      birthDate: createBirthDate,
      nickname: createNickname.trim(),
      gender: createGender,
      birthWeightGrams: weightGrams,
    };
    let pendingCreation = currentDevicePendingFamilyCreation();
    if (pendingCreation) {
      const original = pendingCreation.request;
      const changed = Object.entries(requestedFields).some(([key, value]) => original[key as keyof typeof requestedFields] !== value);
      if (changed) {
        this.setState({
          createFamilyName: original.familyName,
          createNickname: original.nickname,
          createBabyNickname: original.babyNickname,
          createBirthDate: original.birthDate,
          createGender: original.gender,
          createBirthWeightKg: birthWeightKg(original.birthWeightGrams),
        });
        this.flash('已恢复上次提交的信息，请先重试确认创建结果');
        return;
      }
    } else {
      pendingCreation = {
        deploymentKey: FAMILY_CREATION_DEPLOYMENT_KEY,
        request: {
          ...requestedFields,
          creationKey: uuid(),
          deviceId: getDeviceId(),
          deviceName: deviceName(),
        },
      };
      if (!savePendingFamilyCreation(pendingCreation)) {
        this.flash('浏览器无法保存创建进度，请检查隐私存储设置');
        return;
      }
      this.setState({ creationRecoveryInvalid: '' });
    }
    const attempt = ++this.authAttempt;
    this.setState({ creatingFamily: true });
    let creationAccepted = false;
    try {
      const res = await this.api('/api/v1/auth/family/create', {
        method: 'POST',
        body: JSON.stringify(pendingCreation.request),
      });
      if (attempt !== this.authAttempt) return;
      if (!res.ok) {
        if (res.status === 400) clearPendingFamilyCreation(pendingCreation);
        const recoveryInvalid = res.status === 403
          ? '上次创建恢复已过期，请放弃失效恢复后重新创建'
          : res.status === 409
            ? '当前设备或恢复信息存在冲突，请刷新确认登录状态；仍未登录时可放弃失效恢复'
            : '';
        this.setState({ creatingFamily: false, creationRecoveryInvalid: recoveryInvalid });
        this.flash(res.status === 400 ? '请检查家庭名称和宝宝出生信息' : recoveryInvalid || '宝宝家庭创建失败，请稍后重试');
        return;
      }
      const created = await res.json() as Me;
      creationAccepted = true;
      const verified = await this.verifyCreatedSession(created, attempt);
      if (!verified) {
        if (attempt === this.authAttempt) {
          this.setState({ creatingFamily: false, sheet: 'family', onboardingMode: 'create' });
          this.flash('创建结果待确认，请检查网络后使用原信息重试');
        }
        return;
      }
      if (!(await this.finishAuthentication(verified, attempt, '宝宝家庭创建成功，可以邀请家人了'))) return;
      await this.confirmPendingFamilyCreation(pendingCreation, attempt);
    } catch {
      if (attempt === this.authAttempt) {
        this.setState({ creatingFamily: false, sheet: 'family', onboardingMode: 'create' });
        this.flash(creationAccepted
          ? '创建结果待确认，请检查网络后使用原信息重试'
          : '宝宝家庭创建失败，请稍后重试');
      }
    }
  };

  fetchFamilyInvite = async () => {
    const identity = this.captureIdentity();
    if (this.state.syncMode !== 'cloud' || !identity) return;
    this.setState({ loadingInvite: true, inviteError: false });
    try {
      const res = await this.api('/api/v1/family/invite');
      if (!this.isCurrentIdentity(identity)) return;
      if (!res.ok) throw new Error(String(res.status));
      const data = await res.json();
      if (this.isCurrentIdentity(identity)) this.setState({
        familyInvite: data.inviteCode || '',
        loadingInvite: false,
        inviteError: !data.inviteCode,
      });
    } catch {
      if (this.isCurrentIdentity(identity)) this.setState({ loadingInvite: false, inviteError: true });
    }
  };

  openFamily = async () => {
    if (this.state.me) this.setState({ sheet: 'family' });
    else this.setState({ sheet: 'family', onboardingMode: 'create' });
    const identity = this.captureIdentity();
    await this.fetchFamilyInvite();
    if (identity && !this.isCurrentIdentity(identity)) return;
  };

  copyInvite = async () => {
    if (!this.state.familyInvite) return;
    try {
      await navigator.clipboard.writeText(this.state.familyInvite);
      this.flash('家庭邀请码已复制');
    } catch {
      this.flash('复制失败，请长按邀请码手动复制');
    }
  };

  openBabyProfile = () => {
    this.setState({
      sheet: 'babyProfile',
      babyNickname: this.state.baby.nickname,
      babyBirthday: this.state.baby.birthday || '',
      babyGender: this.state.baby.gender || '',
      babyBirthWeightKg: birthWeightKg(this.state.baby.birthWeightGrams),
    });
  };

  saveBabyProfile = async () => {
    if (!this.state.babyNickname.trim()) return;
    if (!this.state.babyBirthday || daysOld(this.state.babyBirthday) === null) {
      this.flash('请选择有效的出生年月日');
      return;
    }
    const weightGrams = birthWeightGrams(this.state.babyBirthWeightKg);
    if (!this.state.babyGender || weightGrams === null) {
      this.flash('请选择性别并填写有效的出生体重');
      return;
    }
    if (this.state.syncMode !== 'cloud') { this.flash('连接家庭后可同步宝宝资料'); return; }
    const identity = this.captureIdentity();
    if (!identity) return;
    const babyId = identity.babyId;
    this.setState({ savingBaby: true });
    try {
      const res = await this.api(`/api/v1/babies/${babyId}`, {
        method: 'PATCH',
        body: JSON.stringify({
          nickname: this.state.babyNickname.trim(),
          birthday: this.state.babyBirthday,
          gender: this.state.babyGender,
          birthWeightGrams: weightGrams,
        }),
      });
      if (!res.ok) throw new Error(String(res.status));
      const baby = await res.json();
      if (!this.isCurrentIdentity(identity)) return;
      void (globalThis as any).__babyLocalRepo?.saveProfile?.(baby);
      this.setState({ baby, savingBaby: false, sheet: 'family' });
      this.flash('宝宝资料已更新');
    } catch {
      if (this.isCurrentIdentity(identity)) {
        this.setState({ savingBaby: false });
        this.flash('宝宝资料保存失败');
      }
    }
  };

  openDevices = async () => {
    this.setState({ sheet: 'devices', loadingDevices: true });
    const identity = this.captureIdentity();
    if (this.state.syncMode !== 'cloud' || !identity) { this.setState({ loadingDevices: false }); return; }
    try {
      const res = await this.api('/api/v1/family/devices');
      if (!res.ok) throw new Error(String(res.status));
      const devices = await res.json();
      if (this.isCurrentIdentity(identity)) this.setState({ devices, loadingDevices: false });
    } catch {
      if (this.isCurrentIdentity(identity)) {
        this.setState({ loadingDevices: false });
        this.flash('设备列表加载失败');
      }
    }
  };

  revokeDevice = async (device: FamilyDevice) => {
    const identity = this.captureIdentity();
    if (!identity) return;
    try {
      const res = await this.api(`/api/v1/family/devices/${device.id}`, { method: 'DELETE' });
      if (!res.ok) throw new Error(String(res.status));
      if (!this.isCurrentIdentity(identity)) return;
      this.setState({ devices: this.state.devices.map(d => d.id === device.id ? { ...d, revoked: true } : d) });
      this.flash('设备授权已移除');
    } catch { if (this.isCurrentIdentity(identity)) this.flash('无法移除该设备'); }
  };

  logoutCurrent = async () => {
    const attempt = ++this.authAttempt;
    try {
      const res = await this.api('/api/v1/auth/logout', { method: 'POST' });
      if (attempt !== this.authAttempt) return;
      if (!res.ok) throw new Error(String(res.status));
      this.clearPendingRetry();
      storageRemove(BREASTFEEDING_SESSION_KEY);
      const epoch = ++this.identityEpoch;
      this.activeIdentity = null;
      this.eventSource?.close();
      const snapshot = await (globalThis as any).__babyLocalRepo?.clearScope?.() || {
        events: [], pending: [], profile: null,
      };
      if (attempt !== this.authAttempt || epoch !== this.identityEpoch) return;
      (globalThis as any).__BABY_INITIAL_SNAPSHOT__ = snapshot;
      (globalThis as any).__BABY_RUNTIME_PENDING__ = snapshot.pending;
      await (globalThis as any).__babyApi?.queryApi?.clear?.();
      if (attempt !== this.authAttempt || epoch !== this.identityEpoch) return;
      this.setState({
        me: null,
        events: snapshot.events,
        baby: snapshot.profile || { nickname: '宝宝', birthday: null, gender: null, birthWeightGrams: null },
        serverQuickAmounts: [],
        familyInvite: '',
        loadingInvite: false,
        inviteError: false,
        devices: [],
        savingBaby: false,
        loadingDevices: false,
        loadingStats: false,
        loadingHistory: false,
        pendingCount: snapshot.pending.length,
        failedPendingCount: snapshot.pending.filter((action: PendingAction) => action.blocked).length,
        directSession: null,
        trendDays: buildLocalTrend(snapshot.events),
        historyEvents: [],
        syncMode: 'setup',
        realtime: false,
        sheet: null,
      });
      this.flash('本设备已退出家庭');
    } catch {
      if (attempt === this.authAttempt) this.flash('退出失败，请检查网络后重试');
    }
  };


  captureInstallPrompt = (event: Event) => {
    event.preventDefault();
    this.setState({ installPrompt: event });
  };

  onInstalled = () => this.setState({ installed: true, installPrompt: null });

  installPwa = async () => {
    const prompt = this.state.installPrompt as any;
    if (!prompt) {
      this.flash(this.state.installed ? '已安装到桌面' : '请使用浏览器菜单中的“添加到主屏幕”');
      return;
    }
    try {
      await prompt.prompt();
      await prompt.userChoice;
      this.setState({ installPrompt: null });
    } catch { this.flash('可稍后从浏览器菜单添加到主屏幕'); }
  };

  openStats = async (identity = this.captureIdentity()) => {
    this.setState({ sheet: 'stats', trendDays: buildLocalTrend(this.state.events), loadingStats: this.state.syncMode === 'cloud' });
    if (this.state.syncMode !== 'cloud') return;
    if (!this.isCurrentIdentity(identity)) return;
    const babyId = identity.babyId;
    try {
      const queryApi = (globalThis as any).__babyApi?.queryApi;
      let data: StatsResponse;
      if (queryApi) data = await queryApi.stats(babyId, 7) as StatsResponse;
      else {
        const res = await this.api(`/api/v1/babies/${babyId}/stats?days=7`);
        if (!res.ok) throw new Error(String(res.status));
        data = await res.json() as StatsResponse;
      }
      if (this.isCurrentIdentity(identity)) this.setState({ trendDays: data.days || [], loadingStats: false });
    } catch {
      if (this.isCurrentIdentity(identity)) this.setState({ loadingStats: false });
    }
  };

  openHistory = async (date = dateKey(Date.now()), identity = this.captureIdentity()) => {
    this.setState({ sheet: 'history', historyDate: date, historyEvents: this.state.events, loadingHistory: this.state.syncMode === 'cloud' });
    if (this.state.syncMode !== 'cloud') return;
    if (!this.isCurrentIdentity(identity)) return;
    const babyId = identity.babyId;
    try {
      const queryApi = (globalThis as any).__babyApi?.queryApi;
      let remote: RemoteEvent[];
      if (queryApi) remote = await queryApi.history(babyId, date) as RemoteEvent[];
      else {
        const res = await this.api(`/api/v1/babies/${babyId}/events?date=${encodeURIComponent(date)}`);
        if (!res.ok) throw new Error(String(res.status));
        remote = await res.json() as RemoteEvent[];
      }
      if (!this.isCurrentIdentity(identity)) return;
      const remoteEvents = remoteToLocal(remote);
      const pending = loadPending();
      const { start, end } = dayBounds(date);
      const replacedServerIds = overlappingServerSleepIds(this.state.events, start, end);
      remoteEvents.forEach(event => { if (event.serverId) replacedServerIds.add(event.serverId); });
      const retained = this.state.events.filter(event => event.pending || (
        !(event.at >= start && event.at < end) &&
        (!event.serverId || !replacedServerIds.has(event.serverId))
      ));
      const cached = applyPendingOverlay([...remoteEvents, ...retained], pending, this.state.events);
      const historyEvents = applyPendingOverlay(remoteEvents, pending, this.state.events);
      this.save(cached);
      this.setState({ historyEvents, loadingHistory: false });
    } catch {
      if (this.isCurrentIdentity(identity)) this.setState({ loadingHistory: false });
    }
  };

  shiftHistoryDay = (delta: number) => {
    const { start } = dayBounds(this.state.historyDate);
    const next = new Date(start);
    next.setDate(next.getDate() + delta);
    const key = dateKey(next.getTime());
    if (key > dateKey(Date.now())) return;
    void this.openHistory(key);
  };

  // --- render-time adapters between flat component state and grouped form props ---

  closeSheet = () => this.setState({ sheet: null });

  poopDraft = (): PoopDraft => ({
    color: this.state.poopColor,
    texture: this.state.poopTexture,
    amount: this.state.poopAmount,
  });

  patchPoopDraft = (patch: Partial<PoopDraft>) => this.setState(state => ({
    poopColor: patch.color ?? state.poopColor,
    poopTexture: patch.texture ?? state.poopTexture,
    poopAmount: patch.amount ?? state.poopAmount,
  }));

  editDraft = (): EventEditDraft => ({
    timeInput: this.state.editTimeInput,
    amount: this.state.editAmount,
    leftSeconds: this.state.editLeftSeconds,
    rightSeconds: this.state.editRightSeconds,
    leftMl: this.state.editLeftMl,
    rightMl: this.state.editRightMl,
    durationMinutes: this.state.editDurationMinutes,
    poop: { color: this.state.editPoopColor, texture: this.state.editPoopTexture, amount: this.state.editPoopAmount },
  });

  patchEditDraft = (patch: Partial<EventEditDraft>) => this.setState(state => ({
    editTimeInput: patch.timeInput ?? state.editTimeInput,
    editAmount: patch.amount ?? state.editAmount,
    editLeftSeconds: patch.leftSeconds ?? state.editLeftSeconds,
    editRightSeconds: patch.rightSeconds ?? state.editRightSeconds,
    editLeftMl: patch.leftMl ?? state.editLeftMl,
    editRightMl: patch.rightMl ?? state.editRightMl,
    editDurationMinutes: patch.durationMinutes ?? state.editDurationMinutes,
    editPoopColor: patch.poop?.color ?? state.editPoopColor,
    editPoopTexture: patch.poop?.texture ?? state.editPoopTexture,
    editPoopAmount: patch.poop?.amount ?? state.editPoopAmount,
  }));

  createForm = (): CreateFamilyForm => ({
    familyName: this.state.createFamilyName,
    nickname: this.state.createNickname,
    babyNickname: this.state.createBabyNickname,
    birthDate: this.state.createBirthDate,
    gender: this.state.createGender,
    birthWeightKg: this.state.createBirthWeightKg,
  });

  patchCreateForm = (patch: Partial<CreateFamilyForm>) => this.setState(state => ({
    createFamilyName: patch.familyName ?? state.createFamilyName,
    createNickname: patch.nickname ?? state.createNickname,
    createBabyNickname: patch.babyNickname ?? state.createBabyNickname,
    createBirthDate: patch.birthDate ?? state.createBirthDate,
    createGender: patch.gender ?? state.createGender,
    createBirthWeightKg: patch.birthWeightKg ?? state.createBirthWeightKg,
  }));

  patchJoinForm = (patch: Partial<JoinFamilyForm>) => this.setState(state => ({
    joinCode: patch.code ?? state.joinCode,
    joinNickname: patch.nickname ?? state.joinNickname,
  }));

  babyProfileForm = (): BabyProfileForm => ({
    nickname: this.state.babyNickname,
    birthday: this.state.babyBirthday,
    gender: this.state.babyGender,
    birthWeightKg: this.state.babyBirthWeightKg,
  });

  patchBabyProfileForm = (patch: Partial<BabyProfileForm>) => this.setState(state => ({
    babyNickname: patch.nickname ?? state.babyNickname,
    babyBirthday: patch.birthday ?? state.babyBirthday,
    babyGender: patch.gender ?? state.babyGender,
    babyBirthWeightKg: patch.birthWeightKg ?? state.babyBirthWeightKg,
  }));

  backfillDay = (dayStart: number) => {
    const noon = new Date(dayStart);
    noon.setHours(12, 0, 0, 0);
    const at = Math.min(noon.getTime(), Date.now());
    this.setState({ sheet: 'quick', draftAt: at, timeInput: toInputDateTime(at) });
  };

  todayTotals(todayEvents: BabyEvent[]): TodayTotals {
    const of = (type: BabyEvent['type']) => todayEvents.filter(e => e.type === type);
    const sumMl = (items: BabyEvent[]) => items.reduce((sum, e) => sum + (e.amount || 0), 0);
    const directFeeds = of('direct_breastfeed');
    return {
      directCount: directFeeds.length,
      directMinutes: Math.round(directFeeds.reduce((sum, e) => sum + Number(e.meta?.leftSeconds || 0) + Number(e.meta?.rightSeconds || 0), 0) / 60),
      breastBottleMl: sumMl(of('bottle_breast_milk')),
      formulaMl: sumMl(of('formula_feed')),
      pumpingMl: sumMl(of('pumping')),
    };
  }

  render() {
    const s = this.state;
    const { sheet, tick, toast, events, baby, me } = s;
    const sorted = this.sorted;
    const today = new Date();
    const todayStart = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime();
    const todayEnd = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 1).getTime();
    const todayEvents = sorted.filter(e => e.at >= todayStart && e.at < todayEnd);
    const todayTotals = this.todayTotals(todayEvents);
    const activeSleep = this.activeSleep;
    const editing = events.find(e => e.id === s.editEventId);
    const draftIsNow = Math.abs(Date.now() - s.draftAt) < 90000;
    const directLeftSeconds = s.directSession ? breastSideSeconds(s.directSession, 'LEFT', tick) : 0;
    const directRightSeconds = s.directSession ? breastSideSeconds(s.directSession, 'RIGHT', tick) : 0;

    if (s.activePage === 'ai' && s.aiEnabled) {
      return <AiWorkspace key={`${me?.familyId || 'none'}:${me?.babyId || 'none'}`} babyId={me?.babyId || null} request={this.api} onBack={() => this.setState({ activePage: 'home' })}/>;
    }

    return <main className="app-shell">
      <HomeScreen
        baby={baby}
        syncBadge={<SyncBadge syncMode={s.syncMode} realtime={s.realtime} pendingCount={s.pendingCount}/>}
        tick={tick}
        lastFeed={this.lastFeed}
        lastPoop={this.lastPoop}
        activeSleep={activeSleep}
        todayEvents={todayEvents}
        today={todayTotals}
        aiEnabled={s.aiEnabled}
        onOpenFamily={this.openFamily}
        onToggleSleep={this.toggleSleep}
        onOpenQuick={this.openQuick}
        onOpenPoop={this.openPoopNow}
        onOpenAi={() => s.aiEnabled && this.setState({ activePage: 'ai', sheet: null })}
        onOpenHistory={() => void this.openHistory()}
        onOpenStats={() => void this.openStats()}
        onSelectEvent={this.openEditEvent}
      />

      <QuickRecordSheet
        open={sheet === 'quick'}
        onClose={this.closeSheet}
        draftAt={s.draftAt}
        draftIsNow={draftIsNow}
        timerRunning={!!s.directSession}
        sleeping={!!activeSleep}
        onEditTime={() => this.openTime('quick')}
        onDirectBreastfeed={this.openDirectBreastfeed}
        onBottleBreastMilk={this.openBottleBreastMilk}
        onFormulaFeed={this.openFormulaFeed}
        onPumping={this.openPumping}
        onToggleSleep={() => this.toggleSleepAt(s.draftAt)}
        onPoop={() => this.setState({ sheet: 'poop' })}
        onPee={() => this.recordSimple('pee')}
      />

      <DirectBreastfeedSheet
        open={sheet === 'directBreastfeed'}
        onClose={this.closeSheet}
        running={!!s.directSession}
        activeSide={s.directSession?.activeSide ?? null}
        leftSeconds={directLeftSeconds}
        rightSeconds={directRightSeconds}
        onStartSide={this.startBreastSide}
        onPause={this.pauseBreastfeeding}
        onResume={this.resumeBreastfeeding}
        onFinish={this.finishBreastfeeding}
        onDiscard={this.discardBreastfeeding}
      />

      <BottleAmountSheet
        open={sheet === 'bottleBreastMilk'}
        onClose={this.closeSheet}
        eyebrow="母乳瓶喂"
        ariaLabel="母乳瓶喂实际喝下量"
        submitLabel="记录母乳瓶喂"
        draftAt={s.draftAt}
        value={s.breastBottleAmount}
        onChange={value => this.setState({ breastBottleAmount: value })}
        onEditTime={() => this.openTime('bottleBreastMilk')}
        onSubmit={this.recordBottleBreastMilk}
      />

      <BottleAmountSheet
        open={sheet === 'formulaFeed'}
        onClose={this.closeSheet}
        eyebrow="配方奶"
        ariaLabel="配方奶实际喝下量"
        submitLabel="记录配方奶"
        draftAt={s.draftAt}
        value={s.formulaAmount}
        onChange={value => this.setState({ formulaAmount: value })}
        onEditTime={() => this.openTime('formulaFeed')}
        onSubmit={this.recordFormulaFeed}
      />

      <PumpingSheet
        open={sheet === 'pumping'}
        onClose={this.closeSheet}
        draftAt={s.draftAt}
        leftMl={s.pumpLeftMl}
        rightMl={s.pumpRightMl}
        durationMinutes={s.pumpDurationMinutes}
        onLeftMl={pumpLeftMl => this.setState({ pumpLeftMl })}
        onRightMl={pumpRightMl => this.setState({ pumpRightMl })}
        onDurationMinutes={pumpDurationMinutes => this.setState({ pumpDurationMinutes })}
        onEditTime={() => this.openTime('pumping')}
        onSubmit={this.recordPumping}
      />

      <PoopSheet
        open={sheet === 'poop'}
        onClose={this.closeSheet}
        draftAt={s.draftAt}
        draft={this.poopDraft()}
        onChange={this.patchPoopDraft}
        onEditTime={() => this.openTime('poop')}
        onSubmit={this.recordPoop}
      />

      <RecordTimeSheet
        open={sheet === 'recordTime'}
        onClose={() => this.setState({ sheet: s.timeReturnSheet })}
        value={s.timeInput}
        onChange={timeInput => this.setState({ timeInput })}
        onApply={this.applyDraftTime}
      />

      <EventEditorSheet
        open={sheet === 'editEvent'}
        onClose={this.closeSheet}
        event={editing}
        draft={this.editDraft()}
        onChange={this.patchEditDraft}
        deleteConfirm={s.deleteConfirm}
        onDeleteConfirm={deleteConfirm => this.setState({ deleteConfirm })}
        onSave={this.saveEventEdit}
        onDelete={this.deleteSelectedEvent}
      />

      <StatsSheet
        open={sheet === 'stats'}
        onClose={this.closeSheet}
        events={events}
        todayStart={todayStart}
        now={tick}
        trendDays={s.trendDays}
        loadingStats={s.loadingStats}
        today={todayTotals}
        onOpenDay={date => void this.openHistory(date)}
      />

      <HistorySheet
        open={sheet === 'history'}
        onClose={this.closeSheet}
        date={s.historyDate}
        events={s.historyEvents}
        loading={s.loadingHistory}
        onPickDate={date => void this.openHistory(date)}
        onShiftDay={this.shiftHistoryDay}
        onSelectEvent={this.openEditEvent}
        onBackfill={this.backfillDay}
      />

      <FamilySheet
        open={sheet === 'family'}
        onClose={this.closeSheet}
        me={me}
        baby={baby}
        realtime={s.realtime}
        installed={s.installed}
        recovery={{
          legacyDetected: s.legacyCreationRecoveryDetected,
          invalidMessage: s.creationRecoveryInvalid,
          onDiscardLegacy: this.discardLegacyFamilyCreation,
          onDiscardInvalid: this.discardInvalidFamilyCreation,
        }}
        invite={{
          code: s.familyInvite,
          loading: s.loadingInvite,
          error: s.inviteError,
          onRetry: this.fetchFamilyInvite,
          onCopy: this.copyInvite,
        }}
        onOpenBabyProfile={this.openBabyProfile}
        onOpenDevices={this.openDevices}
        onInstallPwa={this.installPwa}
        onLogout={this.logoutCurrent}
        onboardingMode={s.onboardingMode}
        onOnboardingMode={onboardingMode => this.setState({ onboardingMode })}
        createForm={this.createForm()}
        onCreateForm={this.patchCreateForm}
        creating={s.creatingFamily}
        onCreate={this.createFamily}
        joinForm={{ code: s.joinCode, nickname: s.joinNickname }}
        onJoinForm={this.patchJoinForm}
        joining={s.joining}
        onJoin={this.claimDevice}
      />

      <BabyProfileSheet
        open={sheet === 'babyProfile'}
        onClose={() => this.setState({ sheet: 'family' })}
        form={this.babyProfileForm()}
        onChange={this.patchBabyProfileForm}
        saving={s.savingBaby}
        onSave={this.saveBabyProfile}
      />

      <DevicesSheet
        open={sheet === 'devices'}
        onClose={() => this.setState({ sheet: 'family' })}
        devices={s.devices}
        loading={s.loadingDevices}
        me={me}
        onRevoke={this.revokeDevice}
      />

      {toast && <div className="toast">{toast}</div>}
    </main>;
  }
}
