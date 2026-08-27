import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

const root = fileURLToPath(new URL('..', import.meta.url));

let vite;
let events;
let format;
let stats;
let familySheet;
let babyProfileSheet;

before(async () => {
  vite = await createServer({
    root,
    configFile: false,
    logLevel: 'error',
    optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true },
    appType: 'custom',
  });

  [events, format, stats, familySheet, babyProfileSheet] = await Promise.all([
    vite.ssrLoadModule('/src/domain/events.ts'),
    vite.ssrLoadModule('/src/domain/format.ts'),
    vite.ssrLoadModule('/src/domain/stats.ts'),
    vite.ssrLoadModule('/src/features/sheets/FamilySheet.tsx'),
    vite.ssrLoadModule('/src/features/sheets/BabyProfileSheet.tsx'),
  ]);
});

after(async () => {
  await vite?.close();
});

test('eventsOnDate uses a half-open day range and sorts newest first', () => {
  const { start, end } = format.dayBounds('2026-08-26');
  const result = events.eventsOnDate([
    { id: 'next-day', type: 'pee', at: end },
    { id: 'start', type: 'poop', at: start },
    { id: 'late', type: 'pee', at: end - 1 },
    { id: 'previous-day', type: 'poop', at: start - 1 },
  ], '2026-08-26');

  assert.deepEqual(result.map(event => event.id), ['late', 'start']);
});

test('a sleep ending exactly at the range start does not overlap the range', () => {
  const { start, end } = format.dayBounds('2026-08-26');
  const result = events.overlappingServerSleepIds([
    { id: 'sleep-start', type: 'sleep_start', at: start - 60_000, serverId: 7 },
    { id: 'sleep-end', type: 'sleep_end', at: start, serverId: 7 },
  ], start, end);

  assert.deepEqual([...result], []);
});

test('sleepTotal clips a sleep spanning midnight to the requested day', () => {
  const { start, end } = format.dayBounds('2026-08-26');
  const total = stats.sleepTotal([
    { id: 'sleep-start', type: 'sleep_start', at: start - 60 * 60_000 },
    { id: 'sleep-end', type: 'sleep_end', at: start + 2 * 60 * 60_000 },
  ], start, end);

  assert.equal(total, 2 * 60 * 60_000);
});

test('an active pending delete hides every row for the server event', () => {
  const remote = [
    { id: 'sleep-start', type: 'sleep_start', at: 100, serverId: 42 },
    { id: 'sleep-end', type: 'sleep_end', at: 200, serverId: 42 },
  ];
  const pending = [{ id: 'delete-42', kind: 'delete', serverId: 42 }];

  assert.deepEqual(events.applyPendingOverlay(remote, pending, []), []);
});

test('a blocked pending delete leaves the authoritative rows visible', () => {
  const remote = [
    { id: 'sleep-start', type: 'sleep_start', at: 100, serverId: 42 },
    { id: 'sleep-end', type: 'sleep_end', at: 200, serverId: 42 },
  ];
  const pending = [{ id: 'delete-42', kind: 'delete', serverId: 42, blocked: true }];

  assert.deepEqual(
    events.applyPendingOverlay(remote, pending, []).map(event => event.id),
    ['sleep-end', 'sleep-start'],
  );
});

test('a sleep update applies each timestamp to its matching row', () => {
  const remote = [
    { id: 'sleep-start', type: 'sleep_start', at: 100, serverId: 42 },
    { id: 'sleep-end', type: 'sleep_end', at: 200, serverId: 42 },
  ];
  const pending = [{
    id: 'update-42',
    kind: 'update',
    serverId: 42,
    updateStartAt: 110,
    updateEndAt: 220,
  }];

  const result = events.applyPendingOverlay(remote, pending, []);
  assert.deepEqual(
    Object.fromEntries(result.map(event => [event.type, { at: event.at, pending: event.pending }])),
    {
      sleep_end: { at: 220, pending: true },
      sleep_start: { at: 110, pending: true },
    },
  );
});

test('the pending local revision replaces its matching remote row', () => {
  const remote = [{ id: 'client-1', type: 'poop', at: 100, serverId: 9 }];
  const local = [{ id: 'client-1', type: 'poop', at: 200, serverId: 9, pending: true }];
  const pending = [{ id: 'update-9', kind: 'update', localEventId: 'client-1', serverId: 9 }];

  assert.deepEqual(events.applyPendingOverlay(remote, pending, local), local);
});

test('profile forms enforce calendar dates and inclusive weight boundaries', () => {
  const today = format.dateKey(Date.now());
  const tomorrow = format.dateKey(format.dayBounds(today).end);
  const createForm = {
    familyName: '宝宝的家',
    nickname: '妈妈',
    babyNickname: '宝宝',
    birthDate: today,
    gender: 'GIRL',
    birthWeightKg: '0.10',
  };
  const profileForm = {
    nickname: '宝宝',
    birthday: today,
    gender: 'BOY',
    birthWeightKg: '15',
  };

  assert.equal(familySheet.createFormIncomplete(createForm), false);
  assert.equal(babyProfileSheet.babyProfileIncomplete(profileForm), false);
  assert.equal(familySheet.createFormIncomplete({ ...createForm, birthDate: tomorrow }), true);
  assert.equal(familySheet.createFormIncomplete({ ...createForm, birthDate: '2025-02-29' }), true);
  assert.equal(familySheet.createFormIncomplete({ ...createForm, birthWeightKg: '0.09' }), true);
  assert.equal(babyProfileSheet.babyProfileIncomplete({ ...profileForm, birthWeightKg: '15.01' }), true);
});

test('buildLocalTrend does not count a next-day event twice across DST', { concurrency: false }, () => {
  const previousTimezone = process.env.TZ;
  process.env.TZ = 'America/New_York';
  try {
    const now = new Date(2026, 2, 9, 12, 0, 0, 0).getTime();
    const afterSpringForwardDay = new Date(2026, 2, 9, 0, 30, 0, 0).getTime();
    const days = stats.buildLocalTrend([
      { id: 'march-9', type: 'poop', at: afterSpringForwardDay },
    ], now);

    assert.equal(days.find(day => day.date === '2026-03-08').poopCount, 0);
    assert.equal(days.find(day => day.date === '2026-03-09').poopCount, 1);
  } finally {
    if (previousTimezone === undefined) delete process.env.TZ;
    else process.env.TZ = previousTimezone;
  }
});
