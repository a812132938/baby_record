import type { LocalPendingAction } from '../domain/model';

export type CreatedEventIdentity = { id: number; updatedAt?: string | null };

export function isPendingCreation(action: LocalPendingAction) {
  return ['feed', 'simple', 'sleep_start', 'sleep_end'].includes(action.kind);
}

export function isRetryablePendingStatus(status: number) {
  return status === 408 || status === 425 || status === 429;
}

export function cancelPendingCreations(
  actions: LocalPendingAction[],
  matches: (action: LocalPendingAction) => boolean,
) {
  return actions.flatMap(action => {
    if (!isPendingCreation(action) || !matches(action)) return [action];
    if (action.attempted === false) return [];
    return [{ ...action, cancelled: true, revision: (action.revision ?? 0) + 1 }];
  });
}

export function reconcilePendingSuccess(
  actions: LocalPendingAction[],
  sent: LocalPendingAction,
  created?: CreatedEventIdentity | null,
) {
  const index = actions.findIndex(action => action.id === sent.id);
  if (index < 0) return actions;
  const latest = actions[index];
  const changedWhileSending = (latest.revision ?? 0) !== (sent.revision ?? 0) || latest.cancelled;
  if (!changedWhileSending) {
    return (latest.revision ?? 0) === (sent.revision ?? 0)
      ? actions.filter(action => action.id !== sent.id)
      : actions;
  }
  if (!isPendingCreation(sent)) {
    if (sent.kind !== 'update') return actions;
    if (!created?.id) throw new Error('Update response did not include an event id');
    return actions.map((action, actionIndex) => actionIndex === index ? {
      ...latest,
      expectedUpdatedAt: created.updatedAt || latest.expectedUpdatedAt,
      revision: (latest.revision ?? 0) + 1,
    } : action);
  }
  if (!created?.id) throw new Error('Creation response did not include an event id');

  const common = {
    ...latest,
    serverId: created.id,
    expectedUpdatedAt: created.updatedAt || undefined,
    revision: (latest.revision ?? 0) + 1,
  };
  const replacement: LocalPendingAction = latest.cancelled
    ? {
        ...common,
        kind: 'delete',
        at: undefined,
        amount: undefined,
        data: undefined,
        feedingType: undefined,
        attempted: undefined,
        cancelled: undefined,
      }
    : {
        ...common,
        kind: 'update',
        updateStartAt: latest.kind === 'sleep_end' ? undefined : latest.at,
        updateEndAt: latest.kind === 'sleep_end' ? latest.at : undefined,
        amount: latest.kind === 'feed' ? latest.amount : undefined,
        data: latest.kind === 'feed'
          ? { schemaVersion: 1, ...(latest.data || {}) }
          : latest.kind === 'simple' ? latest.data : undefined,
        feedingType: undefined,
        attempted: undefined,
        cancelled: undefined,
      };
  return actions.map((action, actionIndex) => actionIndex === index ? replacement : action);
}
