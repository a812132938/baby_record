import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { access, readFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import test from 'node:test';
import ts from 'typescript';

const execFileAsync = promisify(execFile);

const appSource = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8');
const aiWorkspaceSource = await readFile(new URL('../src/ai/AiWorkspace.tsx', import.meta.url), 'utf8');
const aiTypesSource = await readFile(new URL('../src/ai/types.ts', import.meta.url), 'utf8');
const sseSource = await readFile(new URL('../src/ai/sse.ts', import.meta.url), 'utf8');
const stylesSource = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8');
const localDbSource = await readFile(new URL('../src/data/localDb.ts', import.meta.url), 'utf8');
const deploymentScopeSource = await readFile(new URL('../src/data/deploymentScope.ts', import.meta.url), 'utf8');
const pendingQueueSource = await readFile(new URL('../src/data/pendingQueue.ts', import.meta.url), 'utf8');
const feedingSource = await readFile(new URL('../src/domain/feeding.ts', import.meta.url), 'utf8');
const modelSource = await readFile(new URL('../src/domain/model.ts', import.meta.url), 'utf8');
const mainSource = await readFile(new URL('../src/main.tsx', import.meta.url), 'utf8');
const apiClientSource = await readFile(new URL('../src/api/client.ts', import.meta.url), 'utf8');
const nginxSource = await readFile(new URL('../nginx.conf', import.meta.url), 'utf8');
const productionNginxSource = await readFile(new URL('../../deploy/examples/nginx.conf', import.meta.url), 'utf8');
const initSqlSource = await readFile(new URL('../../sql/init.sql', import.meta.url), 'utf8');
const applicationConfigSource = await readFile(new URL('../../server/src/main/resources/application.yml', import.meta.url), 'utf8');
const e2eScriptSource = await readFile(new URL('../../e2e-local.ps1', import.meta.url), 'utf8');
const realtimeHubSource = await readFile(new URL('../../server/src/main/java/com/babyrecord/realtime/RealtimeHub.java', import.meta.url), 'utf8');
const realtimeControllerSource = await readFile(new URL('../../server/src/main/java/com/babyrecord/controller/RealtimeController.java', import.meta.url), 'utf8');
const homeScreenSource = await readFile(new URL('../src/features/HomeScreen.tsx', import.meta.url), 'utf8');
const timelineRowsSource = await readFile(new URL('../src/components/TimelineRows.tsx', import.meta.url), 'utf8');
const trendChartSource = await readFile(new URL('../src/components/TrendChart.tsx', import.meta.url), 'utf8');
const bottomSheetSource = await readFile(new URL('../src/components/BottomSheet.tsx', import.meta.url), 'utf8');
const deviceStorageSource = await readFile(new URL('../src/data/deviceStorage.ts', import.meta.url), 'utf8');
const formatSource = await readFile(new URL('../src/domain/format.ts', import.meta.url), 'utf8');
const eventsSource = await readFile(new URL('../src/domain/events.ts', import.meta.url), 'utf8');
const statsSource = await readFile(new URL('../src/domain/stats.ts', import.meta.url), 'utf8');
const sheetsDir = new URL('../src/features/sheets/', import.meta.url);
const sheetSources = Object.fromEntries(await Promise.all(
  (await readdir(sheetsDir)).map(async name => [name.replace(/\.tsx$/, ''), await readFile(new URL(name, sheetsDir), 'utf8')]),
));
// Every file that renders UI. Assertions about what the app shows are checked across
// the whole component tree, so a component may move file without silently losing coverage.
const uiSource = [appSource, homeScreenSource, timelineRowsSource, trendChartSource, bottomSheetSource, ...Object.values(sheetSources)].join('\n');
const babyRecordApplicationSource = await readFile(new URL('../../server/src/main/java/com/babyrecord/BabyRecordApplication.java', import.meta.url), 'utf8');

function methodSource(name, nextName) {
  const start = appSource.indexOf(`  ${name} =`);
  const end = appSource.indexOf(`  ${nextName} =`, start + 1);
  assert.notEqual(start, -1, `${name} must exist`);
  assert.notEqual(end, -1, `${nextName} must follow ${name}`);
  return appSource.slice(start, end);
}

const pendingQueueOutput = ts.transpileModule(pendingQueueSource, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const pendingQueueModule = { exports: {} };
new Function('module', 'exports', pendingQueueOutput)(pendingQueueModule, pendingQueueModule.exports);
const { cancelPendingCreations, isRetryablePendingStatus, reconcilePendingSuccess } = pendingQueueModule.exports;
const feedingOutput = ts.transpileModule(feedingSource, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const feedingModule = { exports: {} };
new Function('module', 'exports', feedingOutput)(feedingModule, feedingModule.exports);
const { normalizeBreastLastSide } = feedingModule.exports;
const sseOutput = ts.transpileModule(sseSource, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const sseModule = { exports: {} };
new Function('module', 'exports', sseOutput)(sseModule, sseModule.exports);
const { findPendingAssistant, parseEventStream, revealStepSize } = sseModule.exports;

function byteStream(chunks) {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      controller.close();
    },
  });
}

async function collectEvents(stream) {
  const events = [];
  for await (const event of parseEventStream(stream)) events.push(event);
  return events;
}

test('runtime identity is not pinned to seed data', () => {
  assert.doesNotMatch(appSource, /const\s+BABY_ID\s*=/);
  assert.doesNotMatch(appSource, /BABY-2026/);
  assert.doesNotMatch(appSource, /function\s+seedEvents\s*\(/);
});

test('AI workspace uses baby-scoped conversation endpoints and idempotency keys', () => {
  assert.match(aiWorkspaceSource, /`\/api\/v1\/babies\/\$\{babyId\}\/ai\/conversations`/);
  assert.match(aiWorkspaceSource, /clientRequestId:\s*crypto\.randomUUID\(\)/);
  assert.match(aiWorkspaceSource, /clientMessageId\s*=\s*crypto\.randomUUID\(\)/);
  assert.match(aiWorkspaceSource, /\/messages`/);
  assert.match(aiWorkspaceSource, /\/retry`/);
  assert.match(aiWorkspaceSource, /\/snapshots\/\$\{encodeURIComponent/);
  assert.match(aiWorkspaceSource, /method:\s*'DELETE'/);
  assert.doesNotMatch(aiWorkspaceSource, /sk-[a-zA-Z0-9]/);
});

test('AI entry follows the server runtime capability instead of a bundled secret or build flag', () => {
  assert.match(appSource, /\/api\/v1\/capabilities/);
  assert.match(appSource, /capabilities\.aiEnabled === true/);
  assert.match(homeScreenSource, /p\.aiEnabled && <button className="ai-home-entry"/);
  assert.doesNotMatch(appSource, /VITE_(?:DEEPSEEK|AI)_/);
});

test('AI analysis requires explicit data processing consent and hides composer before the first reply', () => {
  assert.match(aiWorkspaceSource, /dataProcessingAccepted:\s*true/);
  assert.match(aiWorkspaceSource, /disabled=\{!babyId \|\| !consent \|\| busy\}/);
  assert.match(aiWorkspaceSource, /同意将去标识化的喂养记录明细（含日期和时间）以及睡眠、便便摘要发送给 DeepSeek/);
  assert.match(aiWorkspaceSource, /hasAssistantReply/);
  assert.match(aiWorkspaceSource, /\(canAsk \|\| \(isResponding && hasAssistantReply\)\)/);
  assert.match(aiWorkspaceSource, /maxLength=\{500\}/);
  assert.match(aiWorkspaceSource, /messages\.filter\(message => message\.content\?\.trim\(\)\)/);
  assert.match(aiWorkspaceSource, /会话将从列表中移除/);
});

test('AI polling is recoverable, bounded to the active view, and renders model text safely', () => {
  assert.match(aiWorkspaceSource, /POLL_INTERVAL_MS\s*=\s*1800/);
  assert.match(aiWorkspaceSource, /controllers\.current\.forEach\(controller => controller\.abort\(\)\)/);
  assert.match(aiWorkspaceSource, /window\.clearTimeout\(pollTimer\.current\)/);
  assert.match(aiWorkspaceSource, /selectedIdRef\.current === id/);
  assert.match(stylesSource, /\.ai-message-content\{[^}]*white-space:pre-wrap/);
  assert.doesNotMatch(aiWorkspaceSource, /dangerouslySetInnerHTML/);
});

test('AI async work is selection-generation guarded and polling stops for terminal access errors', () => {
  assert.match(aiWorkspaceSource, /selectionGenerationRef\s*=\s*useRef\(0\)/);
  assert.match(aiWorkspaceSource, /isCurrentSelection\(conversationId, generation\)/);
  assert.match(aiWorkspaceSource, /\[401, 403, 404\]\.includes/);
  assert.match(aiWorkspaceSource, /status === undefined \|\| status >= 500/);
  assert.match(aiWorkspaceSource, /Math\.min\(retryDelay \* 2, 15_000\)/);
});

test('AI assistant responses stream with guarded lifecycle and polling fallback', () => {
  assert.match(aiWorkspaceSource, /Accept:\s*'text\/event-stream'/);
  assert.match(aiWorkspaceSource, /parseEventStream\(response\.body\)/);
  assert.match(aiWorkspaceSource, /streamTokenRef\.current === token/);
  assert.match(aiWorkspaceSource, /isCurrentSelection\(conversationId, generation\)/);
  assert.match(aiWorkspaceSource, /streamControllerRef\.current\?\.abort\(\)/);
  assert.match(aiWorkspaceSource, /if \(streamActive \|\| !selectedId/);
  assert.match(aiWorkspaceSource, /findPendingAssistant\(conversation\.messages\)/);
  assert.match(aiWorkspaceSource, /if \(!selectedId \|\| !detail \|\| streamActive \|\| streamControllerRef\.current \|\| transientAssistant\) return/);
  assert.match(aiWorkspaceSource, /startAssistantStream\(detail, selectedId, generation\)/);
  assert.match(aiWorkspaceSource, /frame\.event === 'sync'/);
  assert.match(aiWorkspaceSource, /frame\.event === 'delta'/);
  assert.match(aiWorkspaceSource, /if \(payload\.seq <= lastSeq\) continue/);
  assert.match(aiWorkspaceSource, /frame\.event === 'completed'/);
  assert.match(aiWorkspaceSource, /await reconcileCompletedDetail\(\)/);
  assert.match(aiWorkspaceSource, /authoritative && authoritative\.status\.toString\(\)\.toUpperCase\(\) !== 'PENDING'/);
  assert.match(aiWorkspaceSource, /aria-live=\{streaming \? 'polite'/);
  assert.match(stylesSource, /\.ai-stream-cursor\{/);
});

test('AI completed streams retain transient text until authoritative detail is available', () => {
  assert.match(aiWorkspaceSource, /const reconcileCompletedDetail = async \(\) =>/);
  assert.match(aiWorkspaceSource, /while \(isCurrentStream\(\)\)/);
  assert.match(aiWorkspaceSource, /const result = await loadDetail\(conversationId, true, generation\)/);
  assert.match(aiWorkspaceSource, /if \(!result\.detail && !result\.retryable\) return false/);
  assert.match(aiWorkspaceSource, /Math\.min\(retryDelay \* 2, DETAIL_RECONCILE_MAX_DELAY_MS\)/);
  assert.doesNotMatch(aiWorkspaceSource, /conversationFinished \|\|/);
});

test('AI search status is shown only for authoritative completed messages', () => {
  assert.match(aiWorkspaceSource, /assistantCompleted = assistant && message\.status\.toString\(\)\.toUpperCase\(\) === 'COMPLETED'/);
  assert.match(aiWorkspaceSource, /assistantCompleted && message\.searchUsed !== undefined/);
});

test('AI stream reveal queue smooths bursts, accelerates backlog, and bounds completion', () => {
  assert.equal(revealStepSize(12), 1);
  assert.equal(revealStepSize(24), 2);
  assert.equal(revealStepSize(80), 4);
  assert.equal(revealStepSize(200), 16);
  assert.equal(revealStepSize(100, 5), 20);
  assert.equal(revealStepSize(3, 1), 3);
  assert.match(aiWorkspaceSource, /REVEAL_TICK_MS\s*=\s*24/);
  assert.match(aiWorkspaceSource, /REVEAL_COMPLETE_MAX_MS\s*=\s*720/);
  assert.match(aiWorkspaceSource, /targetContent \+= payload\.text/);
  assert.match(aiWorkspaceSource, /window\.setTimeout\(revealNext, REVEAL_TICK_MS\)/);
  assert.match(aiWorkspaceSource, /syncReveal\(payload\.content, payload\.seq\)/);
  assert.match(aiWorkspaceSource, /await revealFinalContent\(payload\.content, payload\.seq\)/);
  assert.match(aiWorkspaceSource, /revealHandleRef\.current\?\.cancel\(\)/);
  assert.match(aiWorkspaceSource, /if \(!isCurrentStream\(\)\) return;[\s\S]*const result = await loadDetail/);
  const completedIndex = aiWorkspaceSource.indexOf("frame.event === 'completed'");
  const statusIndex = aiWorkspaceSource.indexOf('setDetail(current => current ? { ...current, status:', completedIndex);
  const revealIndex = aiWorkspaceSource.indexOf('await revealFinalContent', completedIndex);
  const detailIndex = aiWorkspaceSource.indexOf('await loadDetail', revealIndex);
  assert.ok(completedIndex >= 0 && statusIndex > completedIndex && revealIndex > statusIndex && detailIndex > revealIndex);
});

test('SSE parser preserves split UTF-8 and handles CRLF, comments, multi-data, and EOF', async () => {
  const encoder = new TextEncoder();
  const first = encoder.encode(': keepalive\r\nid: m1:4\r\nevent: delta\r\ndata: {"text":"宝宝好"}\r\n\r\n');
  const chineseStart = first.indexOf(0xe5);
  const tail = encoder.encode('event: done\ndata: first\ndata: second\n\nevent: error\ndata: failed');
  const events = await collectEvents(byteStream([
    first.slice(0, chineseStart + 1),
    first.slice(chineseStart + 1),
    tail,
  ]));
  assert.deepEqual(events, [
    { event: 'delta', data: '{"text":"宝宝好"}', id: 'm1:4' },
    { event: 'done', data: 'first\nsecond', id: 'm1:4' },
    { event: 'error', data: 'failed', id: 'm1:4' },
  ]);
});

test('AI stream candidate selection resumes the latest pending assistant and ignores terminal replies', () => {
  const pending = findPendingAssistant([
    { id: 'u1', role: 'USER', status: 'COMPLETED' },
    { id: 'a1', role: 'ASSISTANT', status: 'COMPLETED' },
    { id: 'a2', role: 'assistant', status: 'pending' },
  ]);
  assert.equal(pending?.id, 'a2');
  assert.equal(findPendingAssistant([{ id: 'a3', role: 'ASSISTANT', status: 'COMPLETED' }]), undefined);
});

test('AI conversation drawer keeps current detail and manages modal focus', () => {
  assert.match(aiWorkspaceSource, /if \(selectedIdRef\.current === id\) return false/);
  assert.match(aiWorkspaceSource, /if \(id !== selectedId\) selectConversation\(id\); closeConversationList\(\)/);
  assert.match(aiWorkspaceSource, /querySelectorAll<HTMLElement>\(FOCUSABLE_SELECTOR\)/);
  assert.match(aiWorkspaceSource, /event\.key !== 'Tab'/);
  assert.match(aiWorkspaceSource, /returnTarget\?\.isConnected/);
  assert.match(aiWorkspaceSource, /listDefaultTriggerRef\.current\?\.focus\(\)/);
});

test('AI snapshot dashboard traps and restores focus through unified close handling', () => {
  assert.match(aiWorkspaceSource, /snapshotReturnFocusRef\s*=\s*useRef<HTMLElement \| null>\(null\)/);
  assert.match(aiWorkspaceSource, /const closeSnapshot = useCallback/);
  assert.match(aiWorkspaceSource, /openSnapshot\(data, returnTarget\)/);
  assert.match(aiWorkspaceSource, /snapshotDialogRef\.current/);
  assert.match(aiWorkspaceSource, /className="ai-snapshot-overlay" onMouseDown=\{closeSnapshot\}/);
  assert.match(aiWorkspaceSource, /aria-label="关闭数据看板" onClick=\{closeSnapshot\}/);
  assert.match(aiWorkspaceSource, /snapshotOpenRef\.current\) return/);
  assert.match(aiWorkspaceSource, /!dialog\.contains\(document\.activeElement\)/);
});

test('AI snapshot dashboard separates feeding modes and declares excluded data', () => {
  for (const field of [
    'directBreastfeedCount', 'bottleBreastMilkCount', 'formulaFeedCount', 'pumpingCount',
    'directBreastfeeds', 'bottleBreastMilkFeeds', 'formulaFeeds', 'unclassifiedBottleFeeds', 'pumpingRecords',
    'recordCoverage', 'rhythm', 'completedSessions', 'byColor', 'byTexture', 'byAmount', 'qualityNotes', 'excludedEventTypes',
  ]) assert.match(aiTypesSource, new RegExp(`${field}\\?`));
  assert.match(aiWorkspaceSource, /逐条喂养时间/);
  assert.match(aiWorkspaceSource, /距上次已记录喂养/);
  assert.match(aiWorkspaceSource, /相邻已记录喂养间隔/);
  assert.match(aiWorkspaceSource, /喂养记录明细（含日期和时间）/);
  assert.match(aiWorkspaceSource, /泵奶是母乳产量记录，不计入宝宝实际摄入量/);
  assert.match(aiWorkspaceSource, /尿尿记录未纳入本次分析/);
});

test('AI entry remains separate from the record FAB and mobile workspace uses dynamic viewport height', () => {
  const summaryIndex = homeScreenSource.indexOf('className="today-strip feeding-summary"');
  const aiEntryIndex = homeScreenSource.indexOf('className="ai-home-entry"');
  const timelineIndex = homeScreenSource.indexOf('className="timeline-section"');
  assert.ok(summaryIndex < aiEntryIndex && aiEntryIndex < timelineIndex);
  assert.match(homeScreenSource, /className="fab" onClick=\{p\.onOpenQuick\}/);
  assert.match(stylesSource, /\.ai-workspace\{[^}]*height:100dvh/);
  assert.match(stylesSource, /\.ai-composer textarea\{[^}]*font-size:16px/);
});

test('local data repository exposes identity-scoped lifecycle', () => {
  assert.match(localDbSource, /export\s+async\s+function\s+activateLocalScope\s*\(/);
  assert.match(localDbSource, /export\s+async\s+function\s+clearActiveLocalData\s*\(/);
});

test('family async responses are guarded by the captured identity', () => {
  for (const [method, next] of [
    ['refreshDashboard', 'handleRealtimeChanged'],
    ['openFamily', 'copyInvite'],
    ['saveBabyProfile', 'openDevices'],
    ['openDevices', 'revokeDevice'],
    ['openStats', 'openHistory'],
    ['openHistory', 'shiftHistoryDay'],
  ]) {
    assert.match(methodSource(method, next), /isCurrentIdentity\(identity\)/, `${method} must reject stale responses`);
  }
});

test('SSE connected event reconciles data missed before reconnect', () => {
  const start = appSource.indexOf('  startRealtime =');
  const end = appSource.indexOf('\n  save(', start);
  assert.ok(start >= 0 && end > start, 'startRealtime must exist before save');
  const startRealtime = appSource.slice(start, end);
  assert.match(startRealtime, /addEventListener\(['"]connected['"][\s\S]*(?:refreshRealtimeData|handleRealtimeChanged)\(identity/);
});

test('failed realtime reconciliation retries with capped backoff', () => {
  const refresh = methodSource('refreshRealtimeData', 'handleRealtimeChanged');
  const scheduleRetry = methodSource('scheduleRealtimeRetry', 'refreshRealtimeData');
  assert.match(refresh, /const refreshed = await this\.refreshDashboard\(identity\)/);
  assert.match(refresh, /if \(!refreshed\)[\s\S]*this\.scheduleRealtimeRetry\(identity\)/);
  assert.match(scheduleRetry, /Math\.min\(delay \* 2, 30000\)/);
  assert.match(scheduleRetry, /this\.refreshRealtimeData\(identity, true\)/);
  assert.match(appSource, /this\.clearRealtimeRetry\(\)[\s\S]*const epoch = \+\+this\.identityEpoch/);
});

test('mobile foreground lifecycle events trigger recovery', () => {
  const mounted = appSource.slice(
    appSource.indexOf('  componentDidMount()'),
    appSource.indexOf('  componentWillUnmount()'),
  );
  assert.match(mounted, /document\.addEventListener\(['"]visibilitychange['"]/);
  assert.match(mounted, /window\.addEventListener\(['"]pageshow['"]/);
  assert.match(mounted, /window\.addEventListener\(['"]focus['"]/);
});

test('mobile foreground lifecycle listeners are removed on unmount', () => {
  const unmounted = appSource.slice(
    appSource.indexOf('  componentWillUnmount()'),
    appSource.indexOf('  api ='),
  );
  assert.match(unmounted, /document\.removeEventListener\(['"]visibilitychange['"]/);
  assert.match(unmounted, /window\.removeEventListener\(['"]pageshow['"]/);
  assert.match(unmounted, /window\.removeEventListener\(['"]focus['"]/);
});

test('mobile foreground recovery rebuilds SSE and reconciles remote state', () => {
  const lifecycleHandler = appSource.match(/(?:document|window)\.addEventListener\(['"](?:visibilitychange|pageshow|focus)['"],\s*this\.(\w+)/)?.[1];
  assert.ok(lifecycleHandler, 'foreground lifecycle events must use a stable class handler');
  const handlerStart = appSource.indexOf(`  ${lifecycleHandler} =`);
  const handlerEnd = appSource.indexOf('\n  };', handlerStart) + 5;
  const handlerSource = appSource.slice(handlerStart, handlerEnd);
  assert.match(handlerSource, /startRealtime\((?:identity)?\)/);
  assert.match(handlerSource, /(?:refreshRealtimeData|handleRealtimeChanged)\(identity/);
});

test('SSE hub emits heartbeat frames on a configurable 20 second default interval', () => {
  const scheduled = realtimeHubSource.match(/@Scheduled\(([\s\S]*?)\)\s*void\s+sendHeartbeats\s*\(/)?.[1];
  assert.ok(scheduled, 'sendHeartbeats must be scheduled');
  assert.match(scheduled, /fixedDelayString\s*=\s*['"]\$\{app\.realtime\.heartbeat-interval-ms:20000\}['"]/);
  assert.match(realtimeHubSource, /SseEmitter\.event\(\)\.comment\(['"]heartbeat['"]\)/);
});

test('Spring scheduling is enabled for SSE heartbeats', () => {
  assert.match(babyRecordApplicationSource, /@EnableScheduling\b/);
});

test('SSE response disables proxy buffering and caching', () => {
  assert.match(realtimeControllerSource, /\.header\((?:HttpHeaders\.CACHE_CONTROL|['"]Cache-Control['"]),\s*['"][^'"]*no-cache/i);
  assert.match(realtimeControllerSource, /\.header\(['"]X-Accel-Buffering['"],\s*['"]no['"]\)/i);
});

test('pending actions keep their family and baby ownership during flush', () => {
  assert.match(modelSource, /familyId\?: number;/);
  assert.match(modelSource, /babyId\?: number;/);
  const flush = methodSource('flushPending', 'sendAction');
  assert.match(flush, /action\.familyId === identity\.familyId && action\.babyId === babyId/);
  const send = methodSource('sendAction', 'flash');
  assert.match(send, /\(action: PendingAction, babyId: number\)/);
  assert.doesNotMatch(send, /this\.state\.me/);
});

test('identity changes clear family-only cached views', () => {
  for (const [method, next] of [
    ['activateIdentity', 'enterSetupMode'],
    ['enterSetupMode', 'bootstrapRemote'],
    ['logoutCurrent', 'captureInstallPrompt'],
  ]) {
    const source = methodSource(method, next);
    assert.match(source, /familyInvite: ''/);
    assert.match(source, /devices: \[\]/);
  }
});

test('local cache scope includes deployment and migrates the old family-only scope', () => {
  assert.match(localDbSource, /deployment-\$\{encodeURIComponent\(normalizeDeploymentKey\(deploymentKey\)\)\}/);
  assert.match(localDbSource, /legacyScope = legacyDataScope\(familyId, babyId\)/);
  assert.match(localDbSource, /DEPLOYMENT_MIGRATION_KEY/);
  assert.match(localDbSource, /serializeScopeTransition\(\(\) => activateLocalScopeNow/);
  assert.match(localDbSource, /\{ \.\.\.action, familyId, babyId \}/);
  assert.match(mainSource, /bootstrapLocalDb\(API_BASE \|\| globalThis\.location\?\.origin/);
});

test('deployment scope distinguishes API paths on the same origin', () => {
  const output = ts.transpileModule(deploymentScopeSource, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  new Function('module', 'exports', output)(module, module.exports);
  const { normalizeDeploymentKey } = module.exports;

  assert.equal(normalizeDeploymentKey('https://EXAMPLE.com/a/?token=ignored#hash'), 'https://example.com/a');
  assert.equal(normalizeDeploymentKey('/a/', 'https://example.com'), 'https://example.com/a');
  assert.equal(normalizeDeploymentKey('https://example.com/'), normalizeDeploymentKey('https://example.com'));
  assert.notEqual(normalizeDeploymentKey('https://example.com/a'), normalizeDeploymentKey('https://example.com/b'));
});

test('birth date is converted to an inclusive calendar-day age', () => {
  assert.match(formatSource, /if \(!birthDate\) return null;/);
  assert.match(formatSource, /Date\.UTC\(today\.getFullYear\(\), today\.getMonth\(\), today\.getDate\(\)\)/);
  assert.match(formatSource, /if \(born > current\) return null;/);
  assert.match(formatSource, /return current - born \+ 1;/);
  assert.match(homeScreenSource, /babyDays !== null/);
  assert.doesNotMatch(uiSource, /<span className="day-chip">第 \{daysOld\(/);
});

test('first-run onboarding creates a family with a required birth date', () => {
  const createFamily = methodSource('createFamily', 'openFamily');
  assert.match(createFamily, /\/api\/v1\/auth\/family\/create/);
  for (const field of ['familyName', 'babyNickname', 'birthDate', 'nickname', 'creationKey', 'gender', 'birthWeightGrams', 'deviceId', 'deviceName']) {
    assert.match(createFamily, new RegExp(`\\b${field}\\b`), `family creation must send ${field}`);
  }
  assert.match(sheetSources.FamilySheet, /type="date" required max=\{dateKey\(Date\.now\(\)\)\}/);
  assert.match(sheetSources.FamilySheet, /aria-checked=\{value === 'BOY'\}/);
  assert.match(appSource, /createGender: patch\.gender/);
  assert.match(sheetSources.FamilySheet, /min="0\.10" max="15\.00" step="0\.01"/);
  assert.match(formatSource, /Math\.round\(kg \* 1000\)/);
  assert.match(sheetSources.FamilySheet, /出生年月日/);
});

test('family onboarding keeps both primary actions visible above the mobile safe area', () => {
  assert.match(sheetSources.FamilySheet, /tall className="family-sheet"/);
  assert.equal((sheetSources.FamilySheet.match(/className="family-onboarding-panel"/g) || []).length, 2);
  assert.equal((sheetSources.FamilySheet.match(/className="family-onboarding-scroll"/g) || []).length, 2);
  assert.equal((sheetSources.FamilySheet.match(/className="family-onboarding-action"/g) || []).length, 2);
  assert.match(sheetSources.FamilySheet, /family-onboarding-action"><button className="primary"[\s\S]*创建宝宝家庭/);
  assert.match(sheetSources.FamilySheet, /family-onboarding-action"><button className="primary"[\s\S]*绑定这台设备/);
  assert.match(stylesSource, /\.family-sheet\{[^}]*display:flex[^}]*overflow:hidden[^}]*padding-bottom:0/);
  assert.match(stylesSource, /\.family-onboarding-scroll\{[^}]*overflow-y:auto[^}]*overscroll-behavior:contain/);
  assert.match(stylesSource, /\.family-onboarding-action\{[^}]*flex:0 0 auto[^}]*env\(safe-area-inset-bottom\)/);
  assert.match(stylesSource, /\.sheet-tall\{[^}]*86dvh/);
});

test('family creation retries preserve one UUID v4 and its original request', () => {
  const createFamily = methodSource('createFamily', 'fetchFamilyInvite');
  assert.match(deviceStorageSource, /baby-record-pending-family-creation/);
  assert.match(deviceStorageSource, /crypto\?\.getRandomValues/);
  assert.doesNotMatch(deviceStorageSource, /Math\.random\(\)/);
  assert.doesNotMatch(appSource, /Math\.random\(\)/);
  assert.match(appSource, /restoredFamilyCreation\?\.request\.familyName/);
  assert.match(deviceStorageSource, /FAMILY_CREATION_DEPLOYMENT_KEY = normalizeDeploymentKey\(API_BASE, globalThis\.location\?\.origin\)/);
  assert.match(deviceStorageSource, /encodeURIComponent\(FAMILY_CREATION_DEPLOYMENT_KEY\)/);
  assert.match(deviceStorageSource, /pending\?\.deploymentKey !== FAMILY_CREATION_DEPLOYMENT_KEY/);
  assert.match(deviceStorageSource, /pending\?\.request\.deviceId === getDeviceId\(\)/);
  assert.match(deviceStorageSource, /storageGet\(LEGACY_FAMILY_CREATION_KEY\) !== null/);
  assert.match(uiSource, /系统不会自动发送/);
  assert.match(appSource, /discardLegacyFamilyCreation[\s\S]*storageRemove\(LEGACY_FAMILY_CREATION_KEY\)/);
  assert.match(createFamily, /currentDevicePendingFamilyCreation\(\)/);
  assert.match(createFamily, /deploymentKey: FAMILY_CREATION_DEPLOYMENT_KEY/);
  assert.match(createFamily, /body: JSON\.stringify\(pendingCreation\.request\)/);
  assert.match(createFamily, /if \(res\.status === 400\) clearPendingFamilyCreation\(pendingCreation\)/);
  assert.match(deviceStorageSource, /\^\[0-9a-f\]\{8\}[\s\S]*-4\[0-9a-f\]\{3\}-\[89ab\]/i);
});

test('family creation verifies its cookie identity before activation and confirms recovery afterwards', () => {
  const activate = methodSource('activateIdentity', 'enterSetupMode');
  const bootstrap = methodSource('bootstrapRemote', 'refreshDashboard');
  const confirm = methodSource('confirmPendingFamilyCreation', 'claimDevice');
  const verify = methodSource('verifyCreatedSession', 'discardInvalidFamilyCreation');
  const create = methodSource('createFamily', 'fetchFamilyInvite');

  assert.doesNotMatch(activate, /clearPendingFamilyCreation/);
  assert.match(verify, /\/api\/v1\/auth\/me/);
  assert.match(verify, /cache: 'no-store'/);
  for (const field of ['familyId', 'babyId', 'userId']) {
    assert.match(verify, new RegExp(`verified\\.${field} === created\\.${field}`));
  }
  const verifyIndex = create.indexOf('await this.verifyCreatedSession');
  const activateIndex = create.indexOf('await this.finishAuthentication');
  const confirmIndex = create.indexOf('await this.confirmPendingFamilyCreation');
  assert.ok(verifyIndex >= 0 && verifyIndex < activateIndex && activateIndex < confirmIndex);
  assert.match(create, /sheet: 'family'[\s\S]*创建结果待确认/);
  assert.match(confirm, /\/api\/v1\/auth\/family\/create\/confirm/);
  assert.match(confirm, /res\.status === 204[\s\S]*clearPendingFamilyCreation\(pending\)/);
  assert.match(confirm, /res\.status === 403/);
  assert.match(confirm, /res\.status === 409/);
  assert.match(bootstrap, /currentDevicePendingFamilyCreation\(\)[\s\S]*confirmPendingFamilyCreation/);
});

test('baby birth date and family invite actions fail closed in the UI', () => {
  const saveBaby = methodSource('saveBabyProfile', 'openDevices');
  const copyInvite = methodSource('copyInvite', 'openBabyProfile');
  assert.match(saveBaby, /!this\.state\.babyBirthday \|\| daysOld\(this\.state\.babyBirthday\) === null/);
  assert.match(saveBaby, /!this\.state\.babyGender \|\| weightGrams === null/);
  assert.match(saveBaby, /method: 'PATCH'/);
  assert.match(saveBaby, /birthWeightGrams: weightGrams/);
  assert.match(sheetSources.BabyProfileSheet, /type="date" required value=\{f\.birthday\}/);
  assert.match(modelSource, /gender: 'BOY' \| 'GIRL' \| null;/);
  assert.match(modelSource, /birthWeightGrams: number \| null;/);
  assert.match(copyInvite, /await navigator\.clipboard\.writeText/);
  assert.match(copyInvite, /catch[\s\S]*手动复制/);
  assert.match(appSource, /inviteError/);
  assert.match(appSource, /onRetry: this\.fetchFamilyInvite/);
  assert.match(sheetSources.FamilySheet, /onClick=\{invite\.onRetry\}>重试/);
});

test('feeding workflows use four distinct event types and sheets', () => {
  for (const type of ['DIRECT_BREASTFEED', 'BOTTLE_BREAST_MILK', 'FORMULA_FEED', 'PUMPING']) {
    assert.match(appSource, new RegExp(`'${type}'`));
    assert.match(modelSource, new RegExp(`'${type}'`));
  }
  for (const sheet of ['directBreastfeed', 'bottleBreastMilk', 'formulaFeed', 'pumping']) {
    assert.match(appSource, new RegExp(`sheet === '${sheet}'`));
  }
  assert.doesNotMatch(appSource, /sheet === 'feed'/);
  assert.match(uiSource, /母乳亲喂/);
  assert.match(uiSource, /母乳瓶喂/);
  assert.match(uiSource, /配方奶/);
  assert.match(uiSource, /泵奶/);
});

test('feeding sync uses the typed flat create contract and only falls back for legacy queued feeds', () => {
  const send = methodSource('sendAction', 'flash');
  assert.match(send, /\/events\/feeding/);
  assert.match(send, /type: action\.feedingType/);
  assert.match(send, /\.\.\.\(action\.data \|\| \{\}\)/);
  assert.doesNotMatch(send, /operator(?:Name|Nickname|Id)/);
  const legacyStart = send.indexOf('if (!action.feedingType)');
  const typedStart = send.indexOf('return this.api(`/api/v1/babies/${babyId}/events/feeding`', legacyStart);
  assert.ok(legacyStart >= 0 && typedStart > legacyStart, 'legacy feed fallback must be isolated before the typed endpoint');
  const legacyBranch = send.slice(legacyStart, typedStart);
  assert.match(legacyBranch, /\/events\/feed/);
  assert.match(legacyBranch, /amountMl: action\.amount/);
  assert.match(legacyBranch, /clientEventId: action\.clientEventId/);
  assert.doesNotMatch(legacyBranch, /\btype\s*:/);
});

test('direct breastfeeding timing survives rerenders and background time', () => {
  assert.match(deviceStorageSource, /baby-record-active-breastfeeding-v1/);
  assert.match(appSource, /activeSince/);
  assert.match(statsSource, /now - session\.activeSince/);
  assert.match(methodSource('startBreastSide', 'pauseBreastfeeding'), /breastSideSeconds/);
  assert.match(methodSource('pauseBreastfeeding', 'resumeBreastfeeding'), /activeSide: null/);
  assert.match(methodSource('finishBreastfeeding', 'discardBreastfeeding'), /DIRECT_BREASTFEED/);
  assert.match(methodSource('finishBreastfeeding', 'discardBreastfeeding'), /segments/);
  assert.match(appSource, /familyId: this\.state\.me\?\.familyId/);
  assert.match(appSource, /storageRemove\(BREASTFEEDING_SESSION_KEY\)/);
});

test('feeding edits keep the generic PATCH endpoint and stable eventData', () => {
  const send = methodSource('sendAction', 'flash');
  assert.match(send, /\/events\/\$\{action\.serverId\}/);
  assert.match(send, /data: action\.data/);
  const edit = methodSource('saveEventEdit', 'deleteSelectedEvent');
  for (const field of ['leftSeconds', 'rightSeconds', 'leftMl', 'rightMl', 'durationSeconds']) {
    assert.match(edit, new RegExp(`\\b${field}\\b`));
  }
});

test('timeline labels legacy feeds safely and shows the server operator', () => {
  const timeline = timelineRowsSource;
  assert.match(timeline, /瓶喂 · 类型未设置/);
  assert.doesNotMatch(timeline, /奶粉/);
  assert.match(timeline, /e\.operatorName/);
  assert.match(timeline, /\$\{e\.operatorName\}记录/);
  assert.match(eventsSource, /operatorName: e\.operatorName/);
  assert.match(methodSource('toggleSleepAt', 'recordSimple'), /operatorName: this\.state\.me\?\.nickname/g);
  assert.match(methodSource('recordSimple', 'recordPoop'), /operatorName: this\.state\.me\?\.nickname/);
});

test('feeding summaries keep intake, direct feeding, and pumping separate', () => {
  assert.match(homeScreenSource, /className="today-strip feeding-summary"/);
  assert.match(sheetSources.StatsSheet, /母乳亲喂趋势/);
  assert.match(sheetSources.StatsSheet, /母乳瓶喂趋势/);
  assert.match(sheetSources.StatsSheet, /配方奶趋势/);
  assert.match(sheetSources.StatsSheet, /泵奶产量趋势/);
  assert.doesNotMatch(uiSource, /今日总奶量/);
});

test('pumping validates the shared 1000ml total boundary', () => {
  const create = methodSource('recordPumping', 'persistDirectSession');
  assert.match(create, /leftMl \+ rightMl > 1000/);
  assert.match(create, /左右侧泵奶总量不能超过 1000ml/);
  const edit = methodSource('saveEventEdit', 'deleteSelectedEvent');
  assert.match(edit, /leftMl \+ rightMl > 1000/);
});

test('direct breastfeeding edits keep lastSide consistent with non-zero durations', () => {
  assert.equal(normalizeBreastLastSide(0, 60, 'LEFT'), 'RIGHT');
  assert.equal(normalizeBreastLastSide(60, 0, 'RIGHT'), 'LEFT');
  assert.equal(normalizeBreastLastSide(60, 60, 'RIGHT'), 'RIGHT');
  assert.equal(normalizeBreastLastSide(60, 60, 'UNKNOWN'), 'LEFT');
  assert.match(methodSource('saveEventEdit', 'deleteSelectedEvent'), /lastSide: normalizeBreastLastSide\(leftSeconds, rightSeconds, event\.meta\?\.lastSide\)/);
});

test('an edit made while create is in flight becomes a dependent PATCH', () => {
  const sent = { id: 'q1', kind: 'feed', localEventId: 'local-1', clientEventId: 'client-1', familyId: 1, babyId: 2, amount: 90, feedingType: 'FORMULA_FEED', at: 1000, revision: 0, attempted: true };
  const edited = { ...sent, amount: 120, at: 2000, revision: 1 };
  const reconciled = reconcilePendingSuccess([edited], sent, { id: 77, updatedAt: '2026-08-21T13:00:00' });
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].kind, 'update');
  assert.equal(reconciled[0].serverId, 77);
  assert.equal(reconciled[0].updateStartAt, 2000);
  assert.equal(reconciled[0].amount, 120);
  assert.equal(reconciled[0].expectedUpdatedAt, '2026-08-21T13:00:00');
});

test('a delete made while create is in flight becomes a dependent DELETE', () => {
  const sent = { id: 'q2', kind: 'feed', localEventId: 'local-2', clientEventId: 'client-2', familyId: 1, babyId: 2, amount: 80, feedingType: 'BOTTLE_BREAST_MILK', at: 1000, revision: 0, attempted: true };
  const cancelled = cancelPendingCreations([sent], action => action.localEventId === 'local-2');
  assert.equal(cancelled[0].cancelled, true);
  assert.equal(cancelled[0].revision, 1);
  const reconciled = reconcilePendingSuccess(cancelled, sent, { id: 88, updatedAt: '2026-08-21T13:05:00' });
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].kind, 'delete');
  assert.equal(reconciled[0].serverId, 88);
  assert.equal(reconciled[0].expectedUpdatedAt, '2026-08-21T13:05:00');
});

test('an unsent create can be cancelled without creating a server record', () => {
  const unsent = { id: 'q3', kind: 'simple', localEventId: 'local-3', familyId: 1, babyId: 2, revision: 0, attempted: false };
  assert.deepEqual(cancelPendingCreations([unsent], () => true), []);
});

test('a second edit made while PATCH is in flight keeps the newer update', () => {
  const sent = { id: 'patch-1', kind: 'update', serverId: 91, familyId: 1, babyId: 2, amount: 100, updateStartAt: 1000, expectedUpdatedAt: 'v1', revision: 0 };
  const newer = { ...sent, amount: 130, updateStartAt: 2000, revision: 1 };
  const reconciled = reconcilePendingSuccess([newer], sent, { id: 91, updatedAt: 'v2' });
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].kind, 'update');
  assert.equal(reconciled[0].amount, 130);
  assert.equal(reconciled[0].expectedUpdatedAt, 'v2');
});

test('a delete made while PATCH is in flight keeps DELETE with the new version', () => {
  const sent = { id: 'patch-2', kind: 'update', serverId: 92, familyId: 1, babyId: 2, amount: 100, expectedUpdatedAt: 'v1', revision: 0 };
  const deletion = { id: 'patch-2', kind: 'delete', serverId: 92, familyId: 1, babyId: 2, expectedUpdatedAt: 'v1', revision: 1 };
  const reconciled = reconcilePendingSuccess([deletion], sent, { id: 92, updatedAt: 'v2' });
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].kind, 'delete');
  assert.equal(reconciled[0].expectedUpdatedAt, 'v2');
});

test('sleep end edits queued before sending stay on the same creation action', () => {
  const edit = methodSource('saveEventEdit', 'deleteSelectedEvent');
  assert.match(edit, /const creationIndex = pending\.findIndex\(p => p\.localEventId === event\.id && \['feed','simple','sleep_start','sleep_end'\]\.includes\(p\.kind\)\)/);
  assert.doesNotMatch(edit, /creationIndex[^\n]*!event\.serverId/);
  assert.match(edit, /pending\[creationIndex\] = action/);
  assert.match(edit, /attempted: pending\[creationIndex\]\.blocked \? false : pending\[creationIndex\]\.attempted/);
});

test('a sleep end edit made while its request is in flight becomes an end-time PATCH', () => {
  const sent = { id: 'sleep-end-edit', kind: 'sleep_end', localEventId: 'sleep-end-local', clientEventId: 'sleep-client', serverId: 50, familyId: 1, babyId: 2, at: 1000, revision: 0, attempted: true };
  const edited = { ...sent, at: 2000, revision: 1 };
  const reconciled = reconcilePendingSuccess([edited], sent, { id: 50, updatedAt: 'v2' });
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].kind, 'update');
  assert.equal(reconciled[0].serverId, 50);
  assert.equal(reconciled[0].updateStartAt, undefined);
  assert.equal(reconciled[0].updateEndAt, 2000);
  assert.equal(reconciled[0].expectedUpdatedAt, 'v2');
});

test('queued and in-flight sleep end deletions both converge to DELETE', () => {
  const remove = methodSource('deleteSelectedEvent', 'finishAuthentication');
  assert.match(remove, /event\.serverId && pendingCreation\.attempted === false[\s\S]*pending\.filter\(action => action\.id !== pendingCreation\.id\)[\s\S]*kind: 'delete'/);

  const sent = { id: 'sleep-end-delete', kind: 'sleep_end', localEventId: 'sleep-end-local', clientEventId: 'sleep-client', serverId: 51, familyId: 1, babyId: 2, at: 1000, revision: 0, attempted: true };
  const cancelled = cancelPendingCreations([sent], () => true);
  const reconciled = reconcilePendingSuccess(cancelled, sent, { id: 51, updatedAt: 'v3' });
  assert.equal(reconciled.length, 1);
  assert.equal(reconciled[0].kind, 'delete');
  assert.equal(reconciled[0].serverId, 51);
  assert.equal(reconciled[0].expectedUpdatedAt, 'v3');
});

test('terminal client errors are isolated while retryable failures keep the queue active', () => {
  const flush = methodSource('runPendingFlush', 'isolateTerminalAction');
  const isolate = methodSource('isolateTerminalAction', 'resolveSuccessfulAction');
  const edit = methodSource('saveEventEdit', 'deleteSelectedEvent');
  const enqueueStart = appSource.indexOf('  enqueue(action: PendingAction)');
  const enqueueEnd = appSource.indexOf('\n  flushPending =', enqueueStart);
  assert.ok(enqueueStart >= 0 && enqueueEnd > enqueueStart, 'enqueue must exist before flushPending');
  const enqueue = appSource.slice(enqueueStart, enqueueEnd);
  const overlayStart = eventsSource.indexOf('export function applyPendingOverlay(');
  const overlayEnd = eventsSource.length;
  const overlay = eventsSource.slice(overlayStart, overlayEnd);

  assert.match(flush, /find\(action =>[\s\S]*&& !action\.blocked\)/);
  assert.match(flush, /res\.status >= 400 && res\.status < 500[\s\S]*isolateTerminalAction/);
  assert.match(flush, /if \(!res\.ok\) throw new Error/);
  assert.match(isolate, /blocked: true/);
  assert.match(isolate, /rollbackEvent[\s\S]*syncError: failureMessage/);
  assert.match(edit, /blocked: undefined, failureMessage: undefined/);
  assert.match(enqueue, /p\.kind === 'delete' && p\.blocked/);
  assert.match(overlay, /action\.kind === 'delete' && action\.serverId && !action\.blocked/);
  for (const status of [408, 425, 429]) assert.equal(isRetryablePendingStatus(status), true);
  for (const status of [400, 403, 404, 409, 422, 500]) assert.equal(isRetryablePendingStatus(status), false);
  assert.match(flush, /if \(isRetryablePendingStatus\(res\.status\)\) throw new Error/);
});

test('successful reconciliation reloads the queue after asynchronous response parsing', () => {
  const resolve = methodSource('resolveSuccessfulAction', 'sendAction');
  const parseIndex = resolve.indexOf('await response.clone().json()');
  const loadIndex = resolve.indexOf('const actions = loadPending()');
  const findIndex = resolve.indexOf('actions.findIndex', loadIndex);
  assert.ok(parseIndex >= 0 && loadIndex > parseIndex, 'queue must be loaded after response parsing');
  assert.ok(findIndex > loadIndex, 'sent action must be found in the fresh queue snapshot');
  assert.match(resolve, /reconcilePendingSuccess\(actions, sent, created\)/);
});

test('pending recovery is serialized and retries with capped backoff', () => {
  const flush = methodSource('flushPending', 'runPendingFlush');
  const retry = methodSource('schedulePendingRetry', 'scheduleRealtimeRetry');
  const realtime = methodSource('refreshRealtimeData', 'handleRealtimeChanged');
  assert.match(flush, /pendingFlushes\.get\(identity\.epoch\)/);
  assert.match(flush, /if \(active\) return active/);
  assert.match(retry, /Math\.min\(delay \* 2, 30000\)/);
  assert.match(retry, /flushPending\(true, identity\)/);
  assert.match(realtime, /await this\.flushPending\(true, identity\)/);
  assert.match(appSource, /pending\.some[\s\S]{0,180}this\.flushPending\(true, identity\)/);
});

test('identity exits clear active breastfeeding state and storage', () => {
  const setup = methodSource('enterSetupMode', 'bootstrapRemote');
  const logout = methodSource('logoutCurrent', 'captureInstallPrompt');
  for (const source of [setup, logout]) {
    assert.match(source, /storageRemove\(BREASTFEEDING_SESSION_KEY\)/);
    assert.match(source, /directSession: null/);
  }
  const activate = methodSource('activateIdentity', 'enterSetupMode');
  assert.match(activate, /directSession\?\.familyId === me\.familyId/);
  assert.match(activate, /directSession\?\.babyId === me\.babyId/);
});

test('sleep end rows prefer the real ending operator', () => {
  assert.match(modelSource, /endOperatorName\?: string \| null/);
  assert.match(eventsSource, /operatorName: e\.endOperatorName \|\| e\.operatorName/);
  assert.match(methodSource('resolveSuccessfulAction', 'sendAction'), /event\.type === 'sleep_end' \? created\.endOperatorName : created\.operatorName/);
});

test('Docker web entry rate limits anonymous creation and device claims', () => {
  assert.match(nginxSource, /limit_req_zone\s+\$binary_remote_addr\s+zone=anonymous_auth:10m\s+rate=12r\/m;/);
  for (const endpoint of ['/api/v1/auth/family/create', '/api/v1/auth/device/claim']) {
    const escaped = endpoint.replaceAll('/', '\\/');
    assert.match(nginxSource, new RegExp(`location = ${escaped} \\{[\\s\\S]*?limit_req zone=anonymous_auth burst=20 nodelay;[\\s\\S]*?limit_req_status 429;`));
  }
});

test('PWA entry files bypass browser and CDN caches in every Nginx deployment', () => {
  for (const source of [nginxSource, productionNginxSource]) {
    const location = source.match(/location ~ \^\/(?:[^\n]+index\\\.html[^\n]+)\$ \{[\s\S]*?\n\s*\}/)?.[0];
    assert.ok(location, 'PWA cache-control location must cover index.html');
    assert.match(location, /sw\\\.js/);
    assert.match(location, /registerSW\\\.js/);
    assert.match(location, /manifest\\\.webmanifest/);
    assert.match(location, /expires off;/);
    assert.match(location, /Cache-Control "no-store, no-cache, must-revalidate, max-age=0" always;/);
    assert.match(location, /CDN-Cache-Control "no-store" always;/);
    assert.match(location, /Cloudflare-CDN-Cache-Control "no-store" always;/);
  }
});

test('public production example uses only placeholder domains and no private key material', () => {
  const serverNames = [...productionNginxSource.matchAll(/server_name\s+([^;]+);/g)]
    .map(([, names]) => names.trim());
  assert.ok(serverNames.length > 0);
  assert.deepEqual([...new Set(serverNames)], ['example.com']);
  assert.match(productionNginxSource, /return 301 https:\/\/example\.com\$request_uri;/);
  assert.doesNotMatch(productionNginxSource, /https:\/\/\$host/);
  for (const [, certificatePath] of productionNginxSource.matchAll(/ssl_certificate(?:_key)?\s+([^;]+);/g)) {
    assert.match(certificatePath, /\/example\.com\//);
  }
  assert.doesNotMatch(productionNginxSource, /BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY/);
});

test('runtime endpoints and server credentials come from deployment configuration', () => {
  assert.match(apiClientSource, /VITE_API_BASE/);
  assert.doesNotMatch(apiClientSource, /location\.hostname[\s\S]{0,80}:8080/);
  assert.match(applicationConfigSource, /\$\{DB_URL\}/);
  assert.match(applicationConfigSource, /\$\{DB_USERNAME\}/);
  assert.match(applicationConfigSource, /\$\{DB_PASSWORD\}/);
  assert.doesNotMatch(applicationConfigSource, /baby_dev_password|APP_FEED_DEFAULT_AMOUNTS/);
});

test('database initialization contains schema only and no business seed', () => {
  assert.doesNotMatch(initSqlSource, /RANDOM_BYTES|BABY-2026/i);
  assert.doesNotMatch(initSqlSource, /INSERT\s+INTO\s+(?:app_user|family|family_member|baby)\s*\(/i);
  assert.doesNotMatch(initSqlSource, /CREATE\s+DATABASE|USE\s+baby_record/i);
  assert.match(initSqlSource, /CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+family/i);
  assert.match(initSqlSource, /baby_event_amount_before_insert/i);
});

test('local E2E creates isolated families through the public API and fails on incomplete cleanup', () => {
  assert.doesNotMatch(e2eScriptSource, /\/babies\/1(?:\/|\b)/i);
  assert.doesNotMatch(e2eScriptSource, /babyId\s+-ne\s+1\b/i);
  assert.doesNotMatch(e2eScriptSource, /INSERT\s+INTO\s+(?:family|baby)\s*\(/i);
  assert.ok((e2eScriptSource.match(/\/api\/v1\/auth\/family\/create/g) || []).length >= 2);
  assert.match(e2eScriptSource, /familyName\s*=\s*\$familyName[\s\S]*babyNickname\s*=\s*\$babyName[\s\S]*birthDate\s*=\s*\$birthDate/);
  assert.match(e2eScriptSource, /\$first\.role -eq 'ADMIN'/);
  assert.match(e2eScriptSource, /\$member\.role -eq 'MEMBER'/);
  assert.match(e2eScriptSource, /inviteCode -match '\^\[0-9A-F\]\{32\}\$'/);
  assert.match(e2eScriptSource, /baselineDashboard\.baby\.birthday -eq \$birthDate/);
  assert.match(e2eScriptSource, /Invoke-WebRequest[^\n]*\$\(\$protected\.babyId\)\/dashboard/);
  assert.match(e2eScriptSource, /function Remove-E2EFixture/);
  assert.match(e2eScriptSource, /\$fixtureMayExist\s*=\s*\$true[\s\S]*\/api\/v1\/auth\/family\/create/);
  assert.match(e2eScriptSource, /finally\s*\{[\s\S]*Remove-E2EFixture \$protectedDeviceId[\s\S]*Remove-E2EFixture \$creatorDeviceId/);
  assert.match(e2eScriptSource, /catch \{ \$cleanupErrors\.Add\("database fixture:/);
  assert.match(e2eScriptSource, /catch\s*\{\s*\$mainError\s*=\s*\$_\s*\}\s*finally/);
  assert.match(e2eScriptSource, /if \(\$cleanupErrors\.Count -gt 0\)[\s\S]*\[System\.Exception\]::new\([\s\S]*\$mainError\.Exception/);
  assert.match(e2eScriptSource, /if \(\$null -ne \$mainError\) \{ throw \$mainError \}/);
  assert.match(e2eScriptSource, /finally[\s\S]*\}\s*if \(\$cleanupErrors\.Count[\s\S]*\$result \| ConvertTo-Json -Compress\s*$/);
  assert.match(e2eScriptSource, /if \(\$exitCode -ne 0\)/);
  assert.doesNotMatch(e2eScriptSource, /BABYAPP_DEVICE_ID|\[string\]\$DeviceId/);
  assert.match(e2eScriptSource, /\[switch\]\$AllowNonLocalTarget/);
  assert.match(e2eScriptSource, /Refusing to run destructive E2E cleanup against a non-local API or database/);
  assert.match(e2eScriptSource, /\$apiUri\.IsLoopback/);
  assert.match(e2eScriptSource, /\$creatorDeviceId\s*=\s*\[guid\]::NewGuid\(\)\.ToString\(\)/);
  assert.match(e2eScriptSource, /\$memberDeviceId\s*=\s*\[guid\]::NewGuid\(\)\.ToString\(\)/);
  assert.match(e2eScriptSource, /\$protectedDeviceId\s*=\s*\[guid\]::NewGuid\(\)\.ToString\(\)/);
  assert.match(e2eScriptSource, /\$baselineCounts\s*=\s*Get-DatabaseCounts/);
  assert.match(e2eScriptSource, /DELETE FROM baby_event[\s\S]*DELETE FROM trusted_device[\s\S]*DELETE FROM family_member[\s\S]*DELETE FROM app_user[\s\S]*DELETE FROM baby[\s\S]*DELETE FROM family/);
  assert.match(e2eScriptSource, /\$cookie\s*=\s*\$memberSession\.Cookies\.GetCookies/);
  assert.match(e2eScriptSource, /events\/feed['"]?\s+-WebSession\s+\$creatorSession/);

  const sleepOwnershipCheck = e2eScriptSource.indexOf("Assert-True ($sleep.clientEventId -eq $sleepClientId)");
  const sleepTracking = e2eScriptSource.indexOf('$createdEvents.Add($sleep)');
  assert.notEqual(sleepOwnershipCheck, -1);
  assert.ok(sleepOwnershipCheck < sleepTracking, 'sleep must be owned by this request before it is tracked for mutation or cleanup');
});

const e2eEnvironmentNames = [
  'BABYAPP_API_BASE',
  'BABYAPP_DB_HOST',
  'BABYAPP_DB_PORT',
  'BABYAPP_DB_NAME',
  'BABYAPP_DB_USER',
  'BABYAPP_DB_PASSWORD',
];
const canRunLocalE2E = process.platform === 'win32' && e2eEnvironmentNames.every(name => process.env[name]);

test('local E2E failpoints preserve errors and clean database fixtures', { skip: !canRunLocalE2E }, async () => {
  const scriptPath = fileURLToPath(new URL('../../e2e-local.ps1', import.meta.url));
  const dbArgs = [
    '-h', process.env.BABYAPP_DB_HOST,
    '-P', process.env.BABYAPP_DB_PORT,
    '-u', process.env.BABYAPP_DB_USER,
    '--batch', '--raw', '--skip-column-names',
    process.env.BABYAPP_DB_NAME,
    '-e', "SELECT CONCAT_WS(',', (SELECT COUNT(*) FROM family), (SELECT COUNT(*) FROM baby), (SELECT COUNT(*) FROM app_user), (SELECT COUNT(*) FROM family_member), (SELECT COUNT(*) FROM trusted_device), (SELECT COUNT(*) FROM baby_event));",
  ];
  const childEnvironment = { ...process.env, MYSQL_PWD: process.env.BABYAPP_DB_PASSWORD };
  const databaseCounts = async () => (await execFileAsync('mysql', dbArgs, { env: childEnvironment })).stdout.trim();

  const failpointCases = [
    { value: 'AfterFixture', mainError: 'AfterFixture' },
    { value: 'AfterClaim', mainError: 'AfterClaim' },
    { value: 'AfterClaim,SyntheticCleanupFailure', mainError: 'AfterClaim', cleanupAlsoFailed: true },
    { value: 'SyntheticCleanupFailure', cleanupOnly: true },
  ];

  for (const failpointCase of failpointCases) {
    const failpoint = failpointCase.value;
    const before = await databaseCounts();
    let failure;
    try {
      await execFileAsync('pwsh.exe', [
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', scriptPath, '-TestFailPoint', failpoint,
      ], { env: childEnvironment });
    } catch (error) {
      failure = error;
    }

    assert.ok(failure, `${failpoint} must fail`);
    assert.doesNotMatch(failure.stdout, /"FamilyId"\s*:/, `${failpoint} emitted success JSON before cleanup completed`);
    if (failpointCase.mainError) {
      assert.match(failure.stderr, new RegExp(`E2E failpoint: ${failpointCase.mainError}`));
    }
    if (failpointCase.cleanupAlsoFailed) {
      assert.match(failure.stderr, /cleanup also failed: synthetic cleanup failure/);
    }
    if (failpointCase.cleanupOnly) {
      assert.match(failure.stderr, /E2E cleanup failed: synthetic cleanup failure/);
      assert.doesNotMatch(failure.stderr, /cleanup also failed:/);
    }
    assert.equal(await databaseCounts(), before, `${failpoint} changed database counts`);
  }
});

test('obsolete preview application is not kept as a second hardcoded code path', async () => {
  for (const relativePath of ['../.preview-src', '../dist-preview', '../preview', '../build-preview.sh']) {
    await assert.rejects(access(new URL(relativePath, import.meta.url)), { code: 'ENOENT' });
  }
});

test('existing production bundles contain no retired seed identity', async () => {
  const assetDirectory = new URL('../dist/assets/', import.meta.url);
  const assets = await readdir(assetDirectory).catch(() => []);
  for (const asset of assets.filter(name => name.endsWith('.js'))) {
    const source = await readFile(new URL(asset, assetDirectory), 'utf8');
    assert.doesNotMatch(source, /BABY-2026|__BABY_DEMO__|const\s+BABY_ID\s*=/);
  }
});
