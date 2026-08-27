import type { BabyEvent, EventType, FeedingType, PendingAction, RemoteEvent } from './model';
import { dayBounds } from './format';

const FEEDING_EVENT_TYPES: EventType[] = ['feed', 'direct_breastfeed', 'bottle_breast_milk', 'formula_feed', 'pumping'];

export function feedingEventType(type?: FeedingType | null): EventType {
  return type === 'DIRECT_BREASTFEED' ? 'direct_breastfeed'
    : type === 'BOTTLE_BREAST_MILK' ? 'bottle_breast_milk'
    : type === 'FORMULA_FEED' ? 'formula_feed'
    : type === 'PUMPING' ? 'pumping'
    : 'feed';
}

export function isFeedingEvent(event: BabyEvent) {
  return FEEDING_EVENT_TYPES.includes(event.type);
}

export function parseMeta(raw?: string | null) {
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return {}; }
}

export function eventsOnDate(events: BabyEvent[], key: string) {
  const { start, end } = dayBounds(key);
  return events.filter(e => e.at >= start && e.at < end).sort((a,b)=>b.at-a.at);
}

export function overlappingServerSleepIds(events: BabyEvent[], start: number, end: number) {
  const intervals = new Map<number, { start?: number; end?: number }>();
  for (const event of events) {
    if (!event.serverId || (event.type !== 'sleep_start' && event.type !== 'sleep_end')) continue;
    const interval = intervals.get(event.serverId) || {};
    if (event.type === 'sleep_start') interval.start = event.at;
    else interval.end = event.at;
    intervals.set(event.serverId, interval);
  }
  const overlapping = new Set<number>();
  for (const [serverId, interval] of intervals) {
    const intervalStart = interval.start ?? interval.end;
    const intervalEnd = interval.end ?? Number.POSITIVE_INFINITY;
    if (intervalStart !== undefined && intervalStart < end && intervalEnd > start) overlapping.add(serverId);
  }
  return overlapping;
}

export function remoteToLocal(events: RemoteEvent[]) {
  const local: BabyEvent[] = [];
  events.forEach(e => {
    const baseId = e.clientEventId || `server-${e.id}`;
    const common = { clientEventId: e.clientEventId || undefined, serverId: e.id, serverUpdatedAt: e.updatedAt || undefined, operatorName: e.operatorName || undefined };
    const meta = parseMeta(e.eventData);
    if (e.eventType === 'FEED' || ['DIRECT_BREASTFEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED', 'PUMPING'].includes(e.eventType)) {
      const type = feedingEventType(e.eventType === 'FEED' ? (e.feedingType || e.type || meta.type as FeedingType | undefined) : e.eventType as FeedingType);
      local.push({ id: baseId, type, at: new Date(e.startTime).getTime(), amount: e.amountMl || undefined, meta, ...common });
    } else if (e.eventType === 'POOP') {
      local.push({ id: baseId, type: 'poop', at: new Date(e.startTime).getTime(), meta, ...common });
    } else if (e.eventType === 'PEE') {
      local.push({ id: baseId, type: 'pee', at: new Date(e.startTime).getTime(), meta, ...common });
    } else if (e.eventType === 'SLEEP') {
      local.push({ id: baseId, type: 'sleep_start', at: new Date(e.startTime).getTime(), ...common });
      if (e.endTime) local.push({ id: `${baseId}-end`, type: 'sleep_end', at: new Date(e.endTime).getTime(), serverId: e.id, serverUpdatedAt: e.updatedAt || undefined, operatorName: e.endOperatorName || e.operatorName || undefined, meta: { sleepClientEventId: e.clientEventId || undefined } });
    }
  });
  return local.sort((a, b) => b.at - a.at);
}

export function applyPendingOverlay(remoteEvents: BabyEvent[], pending: PendingAction[], currentLocal: BabyEvent[]) {
  const pendingLocalIds = new Set(pending.map(p => p.localEventId).filter(Boolean));
  const pendingClientIds = new Set(pending.map(p => p.clientEventId).filter(Boolean));
  const pendingServerIds = new Set(pending.map(p => p.serverId).filter(Boolean));
  const localOverlay = currentLocal.filter(e => e.pending && (
    pendingLocalIds.has(e.id) ||
    (!!e.clientEventId && pendingClientIds.has(e.clientEventId)) ||
    (!!e.serverId && pendingServerIds.has(e.serverId))
  ));
  let merged = [...localOverlay, ...remoteEvents];
  for (const action of pending) {
    if (action.kind === 'delete' && action.serverId && !action.blocked) {
      merged = merged.filter(e => e.serverId !== action.serverId);
    }
    if (action.kind === 'update' && action.serverId) {
      merged = merged.map(e => {
        if (e.serverId !== action.serverId) return e;
        if (e.type === 'sleep_end' && action.updateEndAt) return { ...e, at: action.updateEndAt, pending: true };
        if (e.type !== 'sleep_end' && action.updateStartAt) {
          return { ...e, at: action.updateStartAt, amount: action.amount ?? e.amount, meta: action.data ?? e.meta, pending: true };
        }
        return e;
      });
    }
  }
  const seen = new Set<string>();
  return merged.filter(e => !seen.has(e.id) && seen.add(e.id)).sort((a,b)=>b.at-a.at);
}
