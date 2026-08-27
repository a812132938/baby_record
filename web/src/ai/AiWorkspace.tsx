import { useCallback, useEffect, useRef, useState } from 'react';
import { Icon } from '../components/Icon';
import { findPendingAssistant, parseEventStream, revealStepSize } from './sse';
import type {
  AiConversationDetail,
  AiConversationStatus,
  AiConversationListItem,
  AiConversationListResponse,
  AiDashboard,
  AiBottleFeedingRecord,
  AiDirectBreastfeedRecord,
  AiFeedingEventWindows,
  AiFeedingWindowStats,
  AiIntakeFeedingRecord,
  AiIntakeType,
  AiMessage,
  AiPumpingRecord,
  AiSnapshot,
  CountMap,
} from './types';

type AiWorkspaceProps = {
  babyId: number | null;
  request: (path: string, init?: RequestInit) => Promise<Response>;
  onBack: () => void;
};

const POLL_INTERVAL_MS = 1800;
const DETAIL_RECONCILE_MAX_DELAY_MS = 15_000;
const REVEAL_TICK_MS = 24;
const REVEAL_COMPLETE_MAX_MS = 720;
const ANALYSIS_STEPS = ['整理喂养记录', '梳理睡眠节律', '结合便便情况生成分析'] as const;
const FOCUSABLE_SELECTOR = 'button:not([disabled]), [href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

const INTAKE_FILTERS: Array<{ value: 'ALL' | AiIntakeType; label: string }> = [
  { value: 'ALL', label: '全部' },
  { value: 'DIRECT_BREASTFEED', label: '亲喂' },
  { value: 'BOTTLE_BREAST_MILK', label: '母乳瓶喂' },
  { value: 'FORMULA_FEED', label: '配方奶' },
  { value: 'FEED', label: '未分类' },
];

type DetailLoadResult = {
  detail: AiConversationDetail | null;
  retryable: boolean;
};

type TransientAssistant = {
  conversationId: string;
  generation: number;
  messageId: string;
  seq: number;
  content: string;
};

type StreamPayload = {
  messageId?: string | number;
  seq?: number;
  content?: string;
  text?: string;
  errorCode?: string;
  conversationStatus?: string;
};

type RevealHandle = { cancel: () => void };

function formatDateTime(value?: string | null) {
  if (!value) return '暂无';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '暂无';
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

function formatEvidenceTime(value?: string | null) {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatMinutes(value?: number | null) {
  const minutes = Math.max(0, Math.round(value || 0));
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`;
}

function genderLabel(value?: string | null) {
  return value === 'BOY' ? '男孩' : value === 'GIRL' ? '女孩' : '未设置';
}

function mapSummary(value?: CountMap | null) {
  const entries = Object.entries(value || {}).filter(([, count]) => Number(count) > 0);
  return entries.length ? entries.map(([key, count]) => `${key} ${count}次`).join(' · ') : '暂无记录';
}

function recordTime(value?: string | null) {
  return value?.trim() || '时间未记录';
}

function recordedInterval(value?: number | null) {
  if (value === null || value === undefined) return '首条已记录喂养';
  return `距上次已记录喂养 ${formatMinutes(value)}`;
}

function optionalMinutes(value?: number | null) {
  return value === null || value === undefined ? '—' : formatMinutes(value);
}

function intakeTypeLabel(value?: AiIntakeType | null) {
  if (value === 'DIRECT_BREASTFEED') return '母乳亲喂';
  if (value === 'BOTTLE_BREAST_MILK') return '母乳瓶喂';
  if (value === 'FORMULA_FEED') return '配方奶';
  if (value === 'FEED') return '未分类瓶喂';
  return '其他喂养';
}

function intakeDetail(record: AiIntakeFeedingRecord) {
  if (record.type === 'DIRECT_BREASTFEED') {
    return `左侧 ${formatMinutes(record.leftMinutes)} · 右侧 ${formatMinutes(record.rightMinutes)} · 共 ${formatMinutes(record.durationMinutes)}`;
  }
  return `${record.amountMl ?? 0} ml`;
}

function isV4Snapshot(snapshot: AiSnapshot) {
  return snapshot.dashboard?.schemaVersion === 'baby-ai-snapshot-v4'
    || snapshot.promptVersion === 'baby-analysis-v4'
    || Array.isArray(snapshot.dashboard?.feeding?.intakeTimeline);
}

function buildQuickQuestions(snapshot?: AiSnapshot | null) {
  const feeding = snapshot?.dashboard?.feeding;
  const sleep = snapshot?.dashboard?.sleep;
  const stool = snapshot?.dashboard?.stool;
  const intakeCount = feeding?.intakeTimeline?.length
    ?? (feeding?.directBreastfeedCount || 0) + (feeding?.bottleBreastMilkCount || 0)
      + (feeding?.formulaFeedCount || 0) + (feeding?.unclassifiedBottleCount || 0);
  const questions: string[] = [];

  if (feeding?.eventWindows?.comparable) {
    const size = feeding.eventWindows.windowSize || 12;
    questions.push(`最近${size}次喂养间隔和此前相比有什么变化？`);
  } else if (intakeCount > 0) {
    questions.push('从逐条记录看，最近的喂养间隔有什么特点？');
  }
  if ((sleep?.completedSessions || 0) + (sleep?.ongoingSessions || 0) > 0) {
    questions.push('从已有睡眠记录看，现在最值得关注什么？');
  }
  if ((stool?.count || 0) > 0) {
    questions.push('从已有便便记录看，现在最值得关注什么？');
  }
  if (questions.length < 3 && (feeding?.pumpingTimeline?.length || feeding?.pumpingCount || 0) > 0) {
    questions.push('泵奶记录和宝宝实际摄入应当怎样一起观察？');
  }
  if (!questions.length) questions.push('目前的记录足够分析哪些情况？');
  return questions.slice(0, 3);
}

type FeedingRecordListProps = {
  title: string;
  records?: Array<AiDirectBreastfeedRecord | AiBottleFeedingRecord | AiPumpingRecord> | null;
  detail: (record: AiDirectBreastfeedRecord | AiBottleFeedingRecord | AiPumpingRecord) => string;
  intake?: boolean;
};

function FeedingRecordList({ title, records, detail, intake = false }: FeedingRecordListProps) {
  if (!records?.length) return null;
  return <details className="ai-feeding-record-group">
    <summary><span>{title}</span><small>{records.length} 条</small></summary>
    <div className="ai-feeding-record-list">
      {records.map((record, index) => <div className="ai-feeding-record" key={`${record.recordedAt || 'unknown'}-${index}`}>
        <time>{recordTime(record.recordedAt)}</time>
        <strong>{detail(record)}</strong>
        {intake && <small>{recordedInterval((record as AiDirectBreastfeedRecord | AiBottleFeedingRecord).minutesSincePreviousFeed)}</small>}
      </div>)}
    </div>
  </details>;
}

function friendlyError(status?: number) {
  if (status === 401) return '登录状态已失效，请返回首页重新进入';
  if (status === 403) return '当前设备没有查看这个宝宝分析的权限';
  if (status === 404) return '这个分析会话已不存在或已从列表移除';
  if (status === 409) return '当前会话状态已更新，请稍后重试';
  if (status === 429) return '分析请求较多，请稍等一会再试';
  if (status === 502 || status === 503) return '智能分析服务暂时不可用，请稍后重试';
  return '暂时无法完成操作，请检查网络后重试';
}

async function readJson<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text) return null;
  try { return JSON.parse(text) as T; } catch { return null; }
}

function idFromResponse(value: unknown): string | null {
  if (!value || typeof value !== 'object') return null;
  const object = value as Record<string, unknown>;
  const raw = object.id ?? object.conversationId;
  return typeof raw === 'string' || typeof raw === 'number' ? String(raw) : null;
}

function isConversationStatus(value: unknown): value is AiConversationStatus {
  return value === 'ANALYZING' || value === 'RESPONDING' || value === 'READY' || value === 'FAILED';
}

function FeedingWindow({ label, stats }: { label: string; stats?: AiFeedingWindowStats | null }) {
  return <div className="ai-window-row">
    <div><strong>{label}</strong><small>{stats?.eventCount ?? 0} 次摄入 · {stats?.sampleCount ?? 0} 个间隔</small></div>
    <div><span>中位 {optionalMinutes(stats?.median)}</span><small>常见 {optionalMinutes(stats?.p25)} - {optionalMinutes(stats?.p75)}</small></div>
  </div>;
}

function FeedingWindows({ windows, baseline }: { windows: AiFeedingEventWindows; baseline?: AiFeedingWindowStats | null }) {
  const windowSize = windows.windowSize || 12;
  return <div className="ai-feeding-windows">
    <div className="ai-feeding-windows-title">
      <div><strong>等量记录对比</strong><small>每组 {windowSize} 次摄入</small></div>
      <span className={windows.comparable ? 'ready' : ''}>{windows.comparable ? '可比较' : '积累中'}</span>
    </div>
    <FeedingWindow label={`最近 ${windowSize} 次`} stats={windows.recent}/>
    <FeedingWindow label={`此前 ${windowSize} 次`} stats={windows.prior}/>
    {!windows.comparable && windows.notComparableReason && <p>{windows.notComparableReason}</p>}
    {baseline && <div className="ai-window-baseline"><span>全程已记录间隔中位数</span><strong>{optionalMinutes(baseline.median)}</strong></div>}
    <small>间隔按相邻已记录喂养的开始时间计算，不等于真实空腹时长。</small>
  </div>;
}

function IntakeTimeline({ records }: { records: AiIntakeFeedingRecord[] }) {
  const [filter, setFilter] = useState<'ALL' | AiIntakeType>('ALL');
  const availableFilters = INTAKE_FILTERS.filter(option => option.value === 'ALL' || records.some(record => record.type === option.value));
  const filtered = filter === 'ALL' ? records : records.filter(record => record.type === filter);

  useEffect(() => {
    if (filter !== 'ALL' && !records.some(record => record.type === filter)) setFilter('ALL');
  }, [filter, records]);

  return <div className="ai-feeding-records ai-intake-timeline">
    <div className="ai-feeding-records-title">
      <strong>摄入时间线</strong>
      <span>{records.length} 条 · 按时间升序</span>
    </div>
    {availableFilters.length > 2 && <div className="ai-intake-filters" aria-label="筛选喂养类型">
      {availableFilters.map(option => <button
        type="button"
        key={option.value}
        className={filter === option.value ? 'active' : ''}
        aria-pressed={filter === option.value}
        onClick={() => setFilter(option.value)}
      >{option.label}</button>)}
    </div>}
    <div className="ai-feeding-record-list">
      {filtered.map((record, index) => <div className="ai-feeding-record ai-intake-record" key={`${record.type}-${record.recordedAt || 'unknown'}-${index}`}>
        <time>{recordTime(record.recordedAt)}</time>
        <span>{intakeTypeLabel(record.type)}</span>
        <strong>{intakeDetail(record)}</strong>
        <small>{recordedInterval(record.minutesSincePreviousFeed)}</small>
      </div>)}
      {!filtered.length && <div className="ai-feeding-records-empty">这个类型暂无记录</div>}
    </div>
  </div>;
}

function SnapshotDashboard({ snapshot }: { snapshot: AiSnapshot }) {
  const dashboard: AiDashboard = snapshot.dashboard || {};
  const feeding = dashboard.feeding || {};
  const sleep = dashboard.sleep || {};
  const stool = dashboard.stool || {};
  const baby = dashboard.baby || {};
  const rangeStart = snapshot.rangeStart || dashboard.rangeStart;
  const rangeEnd = snapshot.rangeEnd || dashboard.rangeEnd;
  const sourceCount = snapshot.sourceEventCount ?? dashboard.sourceEventCount ?? 0;
  const coverage = feeding.recordCoverage;
  const rhythm = feeding.rhythm;
  const v4 = isV4Snapshot(snapshot);
  const cutoff = snapshot.snapshotAt || dashboard.snapshotAt;

  return <div className="ai-dashboard">
    <div className="ai-dashboard-intro">
      <span>{v4 ? 'V4 · 统一记录时间线' : 'V3 · 历史数据快照'}</span>
      <strong>数据截止 {formatDateTime(cutoff)}</strong>
      <small>记录范围 {formatDateTime(rangeStart)} 至 {formatDateTime(rangeEnd)}</small>
      <small>{dashboard.coverageDays ?? 0} 天 · {sourceCount} 条有效记录 · 提示词 {snapshot.promptVersion || '历史版本'}</small>
    </div>

    <section className="ai-dashboard-section">
      <h3>宝宝资料</h3>
      <div className="ai-metric-grid compact">
        <div><span>日龄</span><strong>{baby.ageDays ?? '—'}<small> 天</small></strong></div>
        <div><span>性别</span><strong>{genderLabel(baby.gender)}</strong></div>
        <div><span>出生体重</span><strong>{baby.birthWeightGrams ? `${baby.birthWeightGrams} g` : '未设置'}</strong></div>
      </div>
    </section>

    <section className="ai-dashboard-section">
      <h3>喂养</h3>
      <div className="ai-dashboard-rows">
        <div><span>母乳亲喂</span><strong>{feeding.directBreastfeedCount ?? 0} 次 · {formatMinutes(feeding.directBreastfeedMinutes)}</strong></div>
        <div><span>母乳瓶喂</span><strong>{feeding.bottleBreastMilkCount ?? 0} 次 · {feeding.bottleBreastMilkMl ?? 0} ml</strong></div>
        <div><span>配方奶</span><strong>{feeding.formulaFeedCount ?? 0} 次 · {feeding.formulaFeedMl ?? 0} ml</strong></div>
        <div><span>泵奶</span><strong>{feeding.pumpingCount ?? 0} 次 · {feeding.pumpingMl ?? 0} ml · {formatMinutes(feeding.pumpingMinutes)}</strong></div>
        {(feeding.unclassifiedBottleCount || 0) > 0 && <div><span>未分类瓶喂</span><strong>{feeding.unclassifiedBottleCount} 次 · {feeding.unclassifiedBottleMl ?? 0} ml</strong></div>}
      </div>
      <p>泵奶是母乳产量记录，不计入宝宝实际摄入量。</p>
      {!v4 && rhythm && (rhythm.intervalCount || 0) > 0 && <div className="ai-feeding-rhythm">
        <span>相邻已记录喂养间隔</span>
        <strong>平均 {formatMinutes(rhythm.averageIntervalMinutes)} · 最短 {formatMinutes(rhythm.shortestIntervalMinutes)} · 最长 {formatMinutes(rhythm.longestIntervalMinutes)}</strong>
        <small>按亲喂和各类瓶喂的开始时间计算，泵奶不参与。</small>
      </div>}
      {v4 && feeding.eventWindows && <FeedingWindows windows={feeding.eventWindows} baseline={feeding.longTermBaseline}/>} 
      {v4 && <IntakeTimeline records={feeding.intakeTimeline || []}/>} 
      {v4 && <div className="ai-pumping-timeline">
        <div className="ai-feeding-records-title"><strong>泵奶记录</strong><span>不计入宝宝摄入</span></div>
        <FeedingRecordList title="泵奶产出" records={feeding.pumpingTimeline} detail={record => {
          const value = record as AiPumpingRecord;
          return `左侧 ${value.leftMl ?? 0} ml · 右侧 ${value.rightMl ?? 0} ml · 共 ${value.amountMl ?? 0} ml · ${formatMinutes(value.durationMinutes)}`;
        }}/>
        {!feeding.pumpingTimeline?.length && <div className="ai-feeding-records-empty">暂无泵奶记录</div>}
      </div>}
      {!v4 && <div className="ai-feeding-records">
        <div className="ai-feeding-records-title">
          <strong>逐条喂养时间</strong>
          <span>{coverage ? `${coverage.included ?? 0} / ${coverage.total ?? 0} 条` : '按记录时间排列'}</span>
        </div>
        {coverage?.truncated && <p>记录较多，本次保留最近 {coverage.included ?? 0} 条逐条明细{coverage.omittedBefore ? `，${coverage.omittedBefore} 之前的明细已省略` : ''}；上方汇总仍覆盖全部记录。</p>}
        <FeedingRecordList title="母乳亲喂" records={feeding.directBreastfeeds} intake detail={record => {
          const value = record as AiDirectBreastfeedRecord;
          return `左侧 ${formatMinutes(value.leftMinutes)} · 右侧 ${formatMinutes(value.rightMinutes)} · 共 ${formatMinutes(value.durationMinutes)}`;
        }}/>
        <FeedingRecordList title="母乳瓶喂" records={feeding.bottleBreastMilkFeeds} intake detail={record => `${(record as AiBottleFeedingRecord).amountMl ?? 0} ml`}/>
        <FeedingRecordList title="配方奶" records={feeding.formulaFeeds} intake detail={record => `${(record as AiBottleFeedingRecord).amountMl ?? 0} ml`}/>
        <FeedingRecordList title="未分类瓶喂" records={feeding.unclassifiedBottleFeeds} intake detail={record => `${(record as AiBottleFeedingRecord).amountMl ?? 0} ml`}/>
        <FeedingRecordList title="泵奶" records={feeding.pumpingRecords} detail={record => {
          const value = record as AiPumpingRecord;
          return `左侧 ${value.leftMl ?? 0} ml · 右侧 ${value.rightMl ?? 0} ml · 共 ${value.amountMl ?? 0} ml · ${formatMinutes(value.durationMinutes)}`;
        }}/>
        {!feeding.directBreastfeeds?.length && !feeding.bottleBreastMilkFeeds?.length && !feeding.formulaFeeds?.length
          && !feeding.unclassifiedBottleFeeds?.length && !feeding.pumpingRecords?.length
          && <div className="ai-feeding-records-empty">暂无逐条喂养记录</div>}
      </div>}
    </section>

    <section className="ai-dashboard-section">
      <h3>睡眠</h3>
      <div className="ai-metric-grid">
        <div><span>已完成</span><strong>{sleep.completedSessions ?? 0}<small> 次</small></strong></div>
        <div><span>累计时长</span><strong>{formatMinutes(sleep.totalMinutes)}</strong></div>
        <div><span>平均每次</span><strong>{formatMinutes(sleep.averageMinutes)}</strong></div>
        <div><span>最长一次</span><strong>{formatMinutes(sleep.longestMinutes)}</strong></div>
      </div>
      {(sleep.ongoingSessions || 0) > 0 && <p>当前有 {sleep.ongoingSessions} 次睡眠仍在进行，已记录约 {formatMinutes(sleep.currentSleepMinutes)}。</p>}
    </section>

    <section className="ai-dashboard-section">
      <h3>便便</h3>
      <div className="ai-dashboard-rows">
        <div><span>记录次数</span><strong>{stool.count ?? 0} 次</strong></div>
        <div><span>颜色</span><strong>{mapSummary(stool.byColor)}</strong></div>
        <div><span>性状</span><strong>{mapSummary(stool.byTexture)}</strong></div>
        <div><span>量</span><strong>{mapSummary(stool.byAmount)}</strong></div>
      </div>
    </section>

    <section className="ai-dashboard-section muted">
      <h3>数据说明</h3>
      <p>尿尿记录未纳入本次分析，因为换尿不湿时的观察无法准确反映每次排尿。</p>
      {(dashboard.qualityNotes || []).map((note, index) => <p key={`${note}-${index}`}>{note}</p>)}
      <small>快照结构 {v4 ? dashboard.schemaVersion || 'baby-ai-snapshot-v4' : dashboard.schemaVersion || 'baby-ai-snapshot-v3'} · 提示词 {snapshot.promptVersion || '历史版本'}</small>
    </section>
  </div>;
}

function LoadingAnalysis({ responding }: { responding: boolean }) {
  const [step, setStep] = useState(0);
  useEffect(() => {
    if (responding) return;
    const timer = window.setInterval(() => setStep(current => Math.min(current + 1, ANALYSIS_STEPS.length - 1)), 2400);
    return () => window.clearInterval(timer);
  }, [responding]);

  if (responding) return <div className="ai-answering" role="status" aria-live="polite"><i/><span>正在结合最新记录回答…</span></div>;
  return <div className="ai-analysis-loading" role="status">
    <div className="ai-loading-mark"><i/><i/><i/></div>
    <strong>正在智能梳理宝宝近期情况</strong>
    <span>{ANALYSIS_STEPS[step]}</span>
    <div className="ai-loading-steps">{ANALYSIS_STEPS.map((label, index) => <i key={label} className={index <= step ? 'active' : ''}/>)}</div>
  </div>;
}

function ConversationList({
  items,
  selectedId,
  loading,
  onSelect,
  onDelete,
}: {
  items: AiConversationListItem[];
  selectedId: string | null;
  loading: boolean;
  onSelect: (id: string) => void;
  onDelete: (item: AiConversationListItem) => void;
}) {
  if (loading) return <div className="ai-list-loading">正在读取会话…</div>;
  if (!items.length) return <div className="ai-list-empty"><strong>还没有分析会话</strong><span>新建后，每次分析都会保留当时的数据依据。</span></div>;
  return <div className="ai-conversation-list">{items.map(item => {
    const snapshotAt = item.latestSnapshot?.snapshotAt || item.snapshotAt;
    return <div className={`ai-conversation-row ${String(item.id) === selectedId ? 'active' : ''}`} key={item.id}>
    <button onClick={() => onSelect(String(item.id))}>
      <strong>{item.title || '宝宝成长分析'}</strong>
      <span>{item.summary || (item.status === 'ANALYZING' ? '正在生成首次分析' : item.status === 'RESPONDING' ? '正在回答' : item.status === 'FAILED' ? '分析未完成' : '查看本次分析')}</span>
      <small>数据截止 {formatDateTime(snapshotAt)} · 更新 {formatDateTime(item.updatedAt)}</small>
    </button>
    <button className="ai-row-delete" aria-label="删除会话" onClick={() => onDelete(item)}><Icon type="trash" size={17}/></button>
  </div>})}</div>;
}

export default function AiWorkspace({ babyId, request, onBack }: AiWorkspaceProps) {
  const [items, setItems] = useState<AiConversationListItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<AiConversationDetail | null>(null);
  const [listOpen, setListOpen] = useState(false);
  const [listLoading, setListLoading] = useState(true);
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [composer, setComposer] = useState('');
  const [snapshot, setSnapshot] = useState<AiSnapshot | null>(null);
  const [snapshotOpen, setSnapshotOpen] = useState(false);
  const [streamActive, setStreamActive] = useState(false);
  const [transientAssistant, setTransientAssistant] = useState<TransientAssistant | null>(null);
  const pollTimer = useRef<number | null>(null);
  const terminalPollError = useRef(false);
  const controllers = useRef(new Set<AbortController>());
  const messagesEnd = useRef<HTMLDivElement>(null);
  const contentScroll = useRef<HTMLElement>(null);
  const selectedIdRef = useRef<string | null>(null);
  const selectionGenerationRef = useRef(0);
  const listDialogRef = useRef<HTMLDivElement>(null);
  const listDefaultTriggerRef = useRef<HTMLButtonElement>(null);
  const listReturnFocusRef = useRef<HTMLElement | null>(null);
  const snapshotDialogRef = useRef<HTMLElement>(null);
  const snapshotReturnFocusRef = useRef<HTMLElement | null>(null);
  const snapshotOpenRef = useRef(false);
  const streamControllerRef = useRef<AbortController | null>(null);
  const streamTokenRef = useRef(0);
  const revealHandleRef = useRef<RevealHandle | null>(null);

  const isCurrentSelection = useCallback((id: string | null, generation: number) => (
    selectedIdRef.current === id && selectionGenerationRef.current === generation
  ), []);

  const cancelActiveStream = useCallback(() => {
    streamTokenRef.current += 1;
    revealHandleRef.current?.cancel();
    revealHandleRef.current = null;
    streamControllerRef.current?.abort();
    streamControllerRef.current = null;
    setStreamActive(false);
    setTransientAssistant(null);
  }, []);

  const api = useCallback(async <T,>(path: string, init: RequestInit = {}) => {
    const controller = new AbortController();
    controllers.current.add(controller);
    try {
      const response = await request(path, { ...init, signal: controller.signal });
      if (!response.ok) throw Object.assign(new Error('request failed'), { status: response.status });
      return await readJson<T>(response);
    } finally {
      controllers.current.delete(controller);
    }
  }, [request]);

  const basePath = babyId ? `/api/v1/babies/${babyId}/ai/conversations` : '';

  const loadList = useCallback(async (quiet = false) => {
    if (!basePath) {
      setListLoading(false);
      return;
    }
    if (!quiet) setListLoading(true);
    try {
      const data = await api<AiConversationListResponse>(basePath);
      setItems(data?.items || []);
    } catch (reason) {
      if (!quiet && (reason as Error).name !== 'AbortError') setError(friendlyError((reason as { status?: number }).status));
    } finally {
      if (!quiet) setListLoading(false);
    }
  }, [api, basePath]);

  const loadDetail = useCallback(async (id: string, quiet = false, generation = selectionGenerationRef.current): Promise<DetailLoadResult> => {
    if (!basePath) return { detail: null, retryable: false };
    if (!quiet && isCurrentSelection(id, generation)) setBusy(true);
    try {
      const data = await api<AiConversationDetail>(`${basePath}/${encodeURIComponent(id)}`);
      if (data && isCurrentSelection(id, generation)) {
        setDetail(data);
        setError('');
      }
      return { detail: data, retryable: false };
    } catch (reason) {
      const requestError = reason as Error & { status?: number };
      const status = requestError.status;
      const terminal = [401, 403, 404].includes(status || 0);
      const retryable = requestError.name !== 'AbortError' && (status === undefined || status >= 500 || status === 408 || status === 409 || status === 425 || status === 429);
      if (isCurrentSelection(id, generation) && requestError.name !== 'AbortError') {
        if (quiet && terminal) {
          terminalPollError.current = true;
          setError(friendlyError(status));
        } else if (!quiet) {
          setError(friendlyError(status));
        } else if (!retryable) {
          terminalPollError.current = true;
          setError(friendlyError(status));
        }
      }
      return { detail: null, retryable };
    } finally {
      if (!quiet && isCurrentSelection(id, generation)) setBusy(false);
    }
  }, [api, basePath, isCurrentSelection]);

  const startAssistantStream = useCallback((conversation: AiConversationDetail, conversationId: string, generation: number) => {
    const pending = findPendingAssistant(conversation.messages);
    if (!pending || !basePath || !isCurrentSelection(conversationId, generation)) return;

    cancelActiveStream();
    if (pollTimer.current) {
      window.clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
    const messageId = String(pending.id);
    const controller = new AbortController();
    const token = streamTokenRef.current + 1;
    streamTokenRef.current = token;
    streamControllerRef.current = controller;
    setTransientAssistant({ conversationId, generation, messageId, seq: -1, content: pending.content || '' });
    setStreamActive(true);

    const isCurrentStream = () => (
      streamTokenRef.current === token && isCurrentSelection(conversationId, generation)
    );
    const updateTransient = (update: (current: TransientAssistant) => TransientAssistant) => {
      if (!isCurrentStream()) return;
      setTransientAssistant(current => {
        if (!current || current.conversationId !== conversationId || current.generation !== generation || current.messageId !== messageId) return current;
        return update(current);
      });
    };
    let targetContent = pending.content || '';
    let revealedContent = targetContent;
    let lastSeq = -1;
    let revealTimer: number | null = null;
    let revealDeadline: number | null = null;
    let revealDone: (() => void) | null = null;

    const clearRevealTimer = () => {
      if (revealTimer !== null) window.clearTimeout(revealTimer);
      revealTimer = null;
    };
    const finishReveal = () => {
      clearRevealTimer();
      const done = revealDone;
      revealDone = null;
      revealDeadline = null;
      done?.();
    };
    const publishRevealed = () => {
      updateTransient(current => ({ ...current, seq: Math.max(current.seq, lastSeq), content: revealedContent }));
    };
    const revealNext = () => {
      revealTimer = null;
      if (!isCurrentStream()) {
        finishReveal();
        return;
      }
      const remaining = Array.from(targetContent.slice(revealedContent.length));
      if (!remaining.length) {
        finishReveal();
        return;
      }
      const ticksRemaining = revealDeadline === null
        ? undefined
        : Math.max(1, Math.floor((revealDeadline - performance.now()) / REVEAL_TICK_MS));
      const step = revealDeadline !== null && performance.now() >= revealDeadline
        ? remaining.length
        : revealStepSize(remaining.length, ticksRemaining);
      revealedContent += remaining.slice(0, step).join('');
      publishRevealed();
      if (revealedContent.length >= targetContent.length) finishReveal();
      else revealTimer = window.setTimeout(revealNext, REVEAL_TICK_MS);
    };
    const scheduleReveal = () => {
      if (revealTimer === null && revealedContent.length < targetContent.length) {
        revealTimer = window.setTimeout(revealNext, REVEAL_TICK_MS);
      }
    };
    const syncReveal = (content: string, seq: number) => {
      clearRevealTimer();
      targetContent = content;
      revealedContent = content;
      lastSeq = seq;
      publishRevealed();
    };
    const revealFinalContent = (content: string, seq?: number) => {
      clearRevealTimer();
      if (!content.startsWith(revealedContent)) {
        const finalCharacters = Array.from(content);
        const revealedCharacters = Array.from(revealedContent);
        let commonLength = 0;
        while (commonLength < finalCharacters.length && commonLength < revealedCharacters.length && finalCharacters[commonLength] === revealedCharacters[commonLength]) commonLength += 1;
        revealedContent = finalCharacters.slice(0, commonLength).join('');
        publishRevealed();
      }
      targetContent = content;
      if (seq !== undefined) lastSeq = Math.max(lastSeq, seq);
      revealDeadline = performance.now() + REVEAL_COMPLETE_MAX_MS;
      return new Promise<void>(resolve => {
        revealDone = resolve;
        if (revealedContent.length >= targetContent.length) finishReveal();
        else scheduleReveal();
      });
    };
    const cancelReveal = () => {
      clearRevealTimer();
      const done = revealDone;
      revealDone = null;
      revealDeadline = null;
      done?.();
    };
    revealHandleRef.current = { cancel: cancelReveal };

    const reconcileCompletedDetail = async () => {
      let retryDelay = POLL_INTERVAL_MS;
      while (isCurrentStream()) {
        const result = await loadDetail(conversationId, true, generation);
        if (!isCurrentStream()) return false;
        const authoritative = result.detail?.messages.find(message => String(message.id) === messageId);
        if (authoritative && authoritative.status.toString().toUpperCase() !== 'PENDING') {
          setTransientAssistant(null);
          void loadList(true);
          return true;
        }
        if (!result.detail && !result.retryable) return false;
        await new Promise(resolve => window.setTimeout(resolve, retryDelay));
        retryDelay = Math.min(retryDelay * 2, DETAIL_RECONCILE_MAX_DELAY_MS);
      }
      return false;
    };

    void (async () => {
      let terminalEvent = false;
      try {
        const response = await request(
          `${basePath}/${encodeURIComponent(conversationId)}/messages/${encodeURIComponent(messageId)}/stream`,
          { headers: { Accept: 'text/event-stream' }, signal: controller.signal },
        );
        if (!response.ok) throw Object.assign(new Error('stream request failed'), { status: response.status });
        if (!response.body) throw new Error('stream response body missing');

        for await (const frame of parseEventStream(response.body)) {
          if (!isCurrentStream()) return;
          if (frame.event === 'started') continue;

          let payload: StreamPayload;
          try {
            payload = JSON.parse(frame.data) as StreamPayload;
          } catch {
            throw new Error('invalid stream payload');
          }
          if (payload.messageId !== undefined && String(payload.messageId) !== messageId) continue;

          if (frame.event === 'sync' && typeof payload.seq === 'number' && typeof payload.content === 'string') {
            if (payload.seq >= lastSeq) syncReveal(payload.content, payload.seq);
          } else if (frame.event === 'delta' && typeof payload.seq === 'number' && typeof payload.text === 'string') {
            if (payload.seq <= lastSeq) continue;
            lastSeq = payload.seq;
            targetContent += payload.text;
            updateTransient(current => ({ ...current, seq: lastSeq }));
            scheduleReveal();
          } else if (frame.event === 'completed' && typeof payload.content === 'string') {
            terminalEvent = true;
            if (isConversationStatus(payload.conversationStatus) && isCurrentStream()) {
              setDetail(current => current ? { ...current, status: payload.conversationStatus as AiConversationStatus } : current);
            }
            await revealFinalContent(payload.content, payload.seq);
            if (!isCurrentStream()) return;
            await reconcileCompletedDetail();
            break;
          } else if (frame.event === 'failed') {
            terminalEvent = true;
            const result = await loadDetail(conversationId, true, generation);
            if (result.detail && isCurrentStream()) setTransientAssistant(null);
            else if (isCurrentStream()) setError('这次分析没有完成，可以重新分析。');
            void loadList(true);
            break;
          }
        }
      } catch {
        // The final detail refresh below is the single recovery path for invalid frames and disconnects.
      } finally {
        if (!isCurrentStream()) return;
        if (!terminalEvent) {
          await loadDetail(conversationId, true, generation);
          void loadList(true);
        }
        if (!isCurrentStream()) return;
        streamControllerRef.current = null;
        revealHandleRef.current = null;
        cancelReveal();
        setStreamActive(false);
      }
    })();
  }, [basePath, cancelActiveStream, isCurrentSelection, loadDetail, loadList, request]);

  const selectConversation = useCallback((id: string | null) => {
    if (selectedIdRef.current === id) return false;
    cancelActiveStream();
    selectionGenerationRef.current += 1;
    terminalPollError.current = false;
    selectedIdRef.current = id;
    setSelectedId(id);
    setDetail(null);
    setSnapshot(null);
    snapshotOpenRef.current = false;
    setSnapshotOpen(false);
    setBusy(false);
    setError('');
    return true;
  }, [cancelActiveStream]);

  useEffect(() => {
    void loadList();
    return () => {
      if (pollTimer.current) window.clearTimeout(pollTimer.current);
      cancelActiveStream();
      controllers.current.forEach(controller => controller.abort());
      controllers.current.clear();
    };
  }, [cancelActiveStream, loadList]);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    void loadDetail(selectedId, false, selectionGenerationRef.current);
  }, [loadDetail, selectedId]);

  useEffect(() => {
    if (pollTimer.current) window.clearTimeout(pollTimer.current);
    if (streamActive || !selectedId || !detail || !['ANALYZING', 'RESPONDING'].includes(detail.status)) return;
    let cancelled = false;
    let retryDelay = POLL_INTERVAL_MS;
    const generation = selectionGenerationRef.current;
    const poll = async () => {
      const result = await loadDetail(selectedId, true, generation);
      if (cancelled || !isCurrentSelection(selectedId, generation) || terminalPollError.current) return;
      void loadList(true);
      if (!result.detail && !result.retryable) return;
      if (!result.detail || ['ANALYZING', 'RESPONDING'].includes(result.detail.status)) {
        retryDelay = result.detail ? POLL_INTERVAL_MS : Math.min(retryDelay * 2, 15_000);
        pollTimer.current = window.setTimeout(poll, retryDelay);
      }
    };
    pollTimer.current = window.setTimeout(poll, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      if (pollTimer.current) window.clearTimeout(pollTimer.current);
    };
  }, [detail?.status, isCurrentSelection, loadDetail, loadList, selectedId, streamActive]);

  useEffect(() => {
    if (!selectedId || !detail || streamActive || streamControllerRef.current || transientAssistant) return;
    if (!['ANALYZING', 'RESPONDING'].includes(detail.status)) return;
    const generation = selectionGenerationRef.current;
    startAssistantStream(detail, selectedId, generation);
  }, [detail, selectedId, startAssistantStream, streamActive, transientAssistant]);

  useEffect(() => {
    const visibleMessages = detail?.messages.filter(message => message.content?.trim()) || [];
    if (visibleMessages.length === 1 && visibleMessages[0].role.toString().toUpperCase() === 'ASSISTANT') {
      contentScroll.current?.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }
    messagesEnd.current?.scrollIntoView({ behavior: transientAssistant?.content ? 'auto' : 'smooth', block: 'end' });
  }, [detail?.messages.length, detail?.status, transientAssistant?.content.length]);

  useEffect(() => {
    if (!transientAssistant || streamActive) return;
    const authoritative = detail?.messages.find(message => String(message.id) === transientAssistant.messageId);
    if (authoritative && authoritative.status.toString().toUpperCase() !== 'PENDING') {
      setTransientAssistant(null);
    }
  }, [detail?.messages, streamActive, transientAssistant]);

  const closeConversationList = useCallback(() => setListOpen(false), []);

  const openConversationList = useCallback(() => {
    listReturnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setListOpen(true);
  }, []);

  useEffect(() => {
    if (!listOpen) return;
    const dialog = listDialogRef.current;
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) || []);
    (focusable()[0] || dialog)?.focus();
    const trapFocus = (event: KeyboardEvent) => {
      if (snapshotOpenRef.current) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        closeConversationList();
        return;
      }
      if (event.key !== 'Tab') return;
      const elements = focusable();
      if (!elements.length) {
        event.preventDefault();
        dialog?.focus();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (dialog && !dialog.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', trapFocus);
    return () => {
      document.removeEventListener('keydown', trapFocus);
      const returnTarget = listReturnFocusRef.current;
      window.requestAnimationFrame(() => {
        if (returnTarget?.isConnected) returnTarget.focus();
        else listDefaultTriggerRef.current?.focus();
      });
    };
  }, [closeConversationList, listOpen]);

  const closeSnapshot = useCallback(() => {
    snapshotOpenRef.current = false;
    setSnapshotOpen(false);
  }, []);

  const openSnapshot = useCallback((data: AiSnapshot, returnTarget: HTMLElement | null) => {
    snapshotReturnFocusRef.current = returnTarget;
    setSnapshot(data);
    snapshotOpenRef.current = true;
    setSnapshotOpen(true);
  }, []);

  useEffect(() => {
    if (!snapshotOpen) return;
    const dialog = snapshotDialogRef.current;
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) || []);
    (focusable()[0] || dialog)?.focus();
    const trapFocus = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        closeSnapshot();
        return;
      }
      if (event.key !== 'Tab') return;
      const elements = focusable();
      if (!elements.length) {
        event.preventDefault();
        dialog?.focus();
        return;
      }
      const first = elements[0];
      const last = elements[elements.length - 1];
      if (dialog && !dialog.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', trapFocus);
    return () => {
      document.removeEventListener('keydown', trapFocus);
      const returnTarget = snapshotReturnFocusRef.current;
      window.requestAnimationFrame(() => {
        if (returnTarget?.isConnected) returnTarget.focus();
        else listDefaultTriggerRef.current?.focus();
      });
    };
  }, [closeSnapshot, snapshotOpen]);

  const createConversation = async () => {
    if (!basePath || !consent || busy) return;
    const selectionAtStart = selectedIdRef.current;
    const generationAtStart = selectionGenerationRef.current;
    let createdId: string | null = null;
    let createdGeneration: number | null = null;
    setBusy(true);
    setError('');
    try {
      const data = await api<AiConversationDetail | { id?: string | number; conversationId?: string | number }>(basePath, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientRequestId: crypto.randomUUID(), dataProcessingAccepted: true }),
      });
      const id = idFromResponse(data);
      if (!id) throw new Error('missing conversation id');
      createdId = id;
      if (!isCurrentSelection(selectionAtStart, generationAtStart)) {
        await loadList(true);
        return;
      }
      selectionGenerationRef.current += 1;
      createdGeneration = selectionGenerationRef.current;
      selectedIdRef.current = id;
      setSelectedId(id);
      let conversation: AiConversationDetail | null = null;
      if (data && 'status' in data && 'messages' in data) {
        conversation = data as AiConversationDetail;
        setDetail(conversation);
      } else {
        conversation = (await loadDetail(id, true, createdGeneration)).detail;
      }
      if (conversation && isCurrentSelection(id, createdGeneration)) {
        startAssistantStream(conversation, id, createdGeneration);
      }
      setConsent(false);
      await loadList(true);
    } catch (reason) {
      if (isCurrentSelection(selectionAtStart, generationAtStart) && (reason as Error).name !== 'AbortError') {
        setError(friendlyError((reason as { status?: number }).status));
      }
    } finally {
      if (isCurrentSelection(selectionAtStart, generationAtStart) || (createdGeneration !== null && isCurrentSelection(createdId, createdGeneration))) setBusy(false);
    }
  };

  const retry = async () => {
    if (!selectedId || !basePath || busy) return;
    const conversationId = selectedId;
    const generation = selectionGenerationRef.current;
    setBusy(true);
    setError('');
    try {
      const data = await api<AiConversationDetail>(`${basePath}/${encodeURIComponent(conversationId)}/retry`, { method: 'POST' });
      if (!isCurrentSelection(conversationId, generation)) return;
      const conversation = data || (await loadDetail(conversationId, true, generation)).detail;
      if (conversation && isCurrentSelection(conversationId, generation)) {
        setDetail(conversation);
        startAssistantStream(conversation, conversationId, generation);
      }
    } catch (reason) {
      if (isCurrentSelection(conversationId, generation) && (reason as Error).name !== 'AbortError') {
        setError(friendlyError((reason as { status?: number }).status));
      }
    } finally {
      if (isCurrentSelection(conversationId, generation)) setBusy(false);
    }
  };

  const sendMessage = async (contentOverride?: string) => {
    const content = (contentOverride ?? composer).trim();
    if (!selectedId || !basePath || !detail || detail.status !== 'READY' || !content || busy) return;
    const conversationId = selectedId;
    const generation = selectionGenerationRef.current;
    const clientMessageId = crypto.randomUUID();
    const optimistic: AiMessage = { id: clientMessageId, role: 'USER', status: 'PENDING', content, createdAt: new Date().toISOString() };
    setComposer('');
    setDetail(current => current ? { ...current, status: 'RESPONDING', messages: [...current.messages, optimistic] } : current);
    setBusy(true);
    setError('');
    try {
      const data = await api<AiConversationDetail>(`${basePath}/${encodeURIComponent(conversationId)}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientMessageId, content }),
      });
      if (!isCurrentSelection(conversationId, generation)) return;
      const conversation = data && 'messages' in data ? data : (await loadDetail(conversationId, true, generation)).detail;
      if (conversation && isCurrentSelection(conversationId, generation)) {
        setDetail(conversation);
        startAssistantStream(conversation, conversationId, generation);
      }
      void loadList(true);
    } catch (reason) {
      if (isCurrentSelection(conversationId, generation) && (reason as Error).name !== 'AbortError') {
        setError(friendlyError((reason as { status?: number }).status));
        await loadDetail(conversationId, true, generation);
      }
    } finally {
      if (isCurrentSelection(conversationId, generation)) setBusy(false);
    }
  };

  const deleteConversation = async (item: AiConversationListItem) => {
    if (!basePath || !window.confirm(`删除“${item.title || '宝宝成长分析'}”？会话将从列表中移除。`)) return;
    const selectionAtStart = selectedIdRef.current;
    const generationAtStart = selectionGenerationRef.current;
    try {
      await api<null>(`${basePath}/${encodeURIComponent(String(item.id))}`, { method: 'DELETE' });
      const deletedId = String(item.id);
      setItems(current => current.filter(row => String(row.id) !== deletedId));
      if (isCurrentSelection(deletedId, generationAtStart)) {
        selectConversation(null);
      }
    } catch (reason) {
      if (isCurrentSelection(selectionAtStart, generationAtStart) && (reason as Error).name !== 'AbortError') {
        setError(friendlyError((reason as { status?: number }).status));
      }
    }
  };

  const showSnapshot = async (target?: AiSnapshot | null, snapshotId?: string | number | null) => {
    const returnTarget = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (target) {
      openSnapshot(target, returnTarget);
      return;
    }
    if (!basePath || !selectedId || !snapshotId) return;
    const conversationId = selectedId;
    const generation = selectionGenerationRef.current;
    try {
      const data = await api<AiSnapshot>(`${basePath}/${encodeURIComponent(conversationId)}/snapshots/${encodeURIComponent(String(snapshotId))}`);
      if (data && isCurrentSelection(conversationId, generation)) {
        openSnapshot(data, returnTarget);
      }
    } catch (reason) {
      if (isCurrentSelection(conversationId, generation) && (reason as Error).name !== 'AbortError') {
        setError(friendlyError((reason as { status?: number }).status));
      }
    }
  };

  const startNew = () => {
    if (!selectConversation(null)) {
      cancelActiveStream();
      selectionGenerationRef.current += 1;
      terminalPollError.current = false;
      setDetail(null);
      setSnapshot(null);
      snapshotOpenRef.current = false;
      setSnapshotOpen(false);
      setBusy(false);
      setError('');
    }
    setConsent(false);
    closeConversationList();
  };

  const isAnalyzing = detail?.status === 'ANALYZING';
  const isResponding = detail?.status === 'RESPONDING';
  const canAsk = detail?.status === 'READY';
  const hasAssistantReply = detail?.messages.some(message => message.role.toString().toUpperCase() === 'ASSISTANT' && message.content?.trim()) || false;
  const currentTransient = transientAssistant && selectedId === transientAssistant.conversationId && selectionGenerationRef.current === transientAssistant.generation
    ? transientAssistant
    : null;
  let transientIncluded = false;
  const visibleMessages = (detail?.messages || []).map(message => {
    if (currentTransient && String(message.id) === currentTransient.messageId && message.role.toString().toUpperCase() === 'ASSISTANT') {
      transientIncluded = true;
      return { ...message, content: currentTransient.content };
    }
    return message;
  }).filter(message => message.content?.trim());
  const showAnalysisLoading = (isAnalyzing || isResponding) && !currentTransient?.content.length;
  const quickQuestions = buildQuickQuestions(detail?.latestSnapshot);

  return <main className="ai-workspace">
    <header className="ai-topbar">
      <button className="ai-icon-button" aria-label="返回首页" onClick={() => { cancelActiveStream(); onBack(); }}><Icon type="back" size={21}/></button>
      <div><strong>{detail?.title || '宝宝成长分析'}</strong><span>{detail ? `由 ${detail.model || 'DeepSeek'} 分析` : '睡眠 · 喂养 · 便便'}</span></div>
       <button ref={listDefaultTriggerRef} className="ai-icon-button" aria-label="会话列表" onClick={openConversationList}><Icon type="history" size={20}/></button>
    </header>

    {detail?.latestSnapshot && <button className="ai-snapshot-bar" onClick={() => void showSnapshot(detail.latestSnapshot)}>
      <div><span>会话最新数据</span><strong>截止 {formatDateTime(detail.latestSnapshot.snapshotAt)}</strong></div>
      <small>{detail.latestSnapshot.sourceEventCount ?? detail.latestSnapshot.dashboard?.sourceEventCount ?? 0} 条记录</small>
      <Icon type="chevron" size={17}/>
    </button>}

    <section ref={contentScroll} className={`ai-content ${detail ? 'conversation' : 'empty'}`}>
      {!detail && <div className="ai-empty-state">
        <div className="ai-empty-mark"><Icon type="chart" size={26}/></div>
        <span>从第一条记录开始回顾</span>
        <h1>了解宝宝到目前为止的节律</h1>
        <p>分析睡眠、喂养和便便记录，尿尿暂不纳入。结果用于日常观察，不替代医生诊断。</p>
        {!babyId && <div className="ai-inline-error">当前未连接宝宝家庭，请返回首页完成连接。</div>}
        <label className={`ai-consent ${consent ? 'checked' : ''}`}>
          <input type="checkbox" checked={consent} onChange={event => setConsent(event.target.checked)}/>
          <i><Icon type="check" size={15}/></i>
          <span>同意将去标识化的喂养记录明细（含日期和时间）以及睡眠、便便摘要发送给 DeepSeek，仅用于本次智能分析</span>
        </label>
        <button className="ai-primary" disabled={!babyId || !consent || busy} onClick={() => void createConversation()}>{busy ? '正在创建分析…' : '生成首次分析'}</button>
        {!!items.length && <button className="ai-secondary" onClick={openConversationList}>查看已有会话（{items.length}）</button>}
      </div>}

      {detail && <div className="ai-message-stream">
        {visibleMessages.map(message => {
          const assistant = message.role.toString().toUpperCase() === 'ASSISTANT';
          const assistantCompleted = assistant && message.status.toString().toUpperCase() === 'COMPLETED';
          const streaming = assistant && currentTransient !== null && String(message.id) === currentTransient.messageId && transientIncluded;
          const evidenceTime = formatEvidenceTime(message.snapshotAt);
          return <article className={`ai-message ${assistant ? 'assistant' : 'user'} ${streaming ? 'streaming' : ''}`} key={message.id} aria-live={streaming ? 'polite' : undefined} aria-atomic={streaming ? 'false' : undefined}>
            <div className="ai-message-meta">
              <span>{assistant ? '成长分析' : message.authorName || '我'}</span>
              <time>{formatDateTime(message.createdAt)}</time>
              {assistantCompleted && message.searchUsed !== undefined && message.searchUsed !== null
                && <span className="ai-search-status">{message.searchUsed ? '已联网核对' : '仅记录分析'}</span>}
            </div>
            <div className="ai-message-content">{message.content}{streaming && <span className="ai-stream-cursor" aria-hidden="true"/>}</div>
            {assistant && message.snapshotId && <button className="ai-message-snapshot" onClick={() => void showSnapshot(null, message.snapshotId)}>
              <Icon type="chart" size={14}/> 本条回答依据{evidenceTime ? ` · 截止 ${evidenceTime}` : ''}
            </button>}
          </article>;
        })}
        {showAnalysisLoading && <LoadingAnalysis responding={isResponding}/>} 
        {detail.status === 'FAILED' && <div className="ai-failed-state"><strong>这次分析没有完成</strong><span>记录仍然安全保留，可以重新生成。</span><button disabled={busy} onClick={() => void retry()}>重新分析</button></div>}
        {canAsk && hasAssistantReply && <div className="ai-quick-questions">{quickQuestions.map(question => <button key={question} onClick={() => void sendMessage(question)}>{question}</button>)}</div>}
        <div ref={messagesEnd}/>
      </div>}
    </section>

    {error && <div className="ai-error-banner" role="alert"><span>{error}</span><button aria-label="关闭提示" onClick={() => setError('')}><Icon type="close" size={15}/></button></div>}

    {detail && (canAsk || (isResponding && hasAssistantReply)) && <form className="ai-composer" onSubmit={event => { event.preventDefault(); void sendMessage(); }}>
      <textarea
        rows={1}
        maxLength={500}
        value={composer}
        disabled={!canAsk}
        placeholder={isResponding ? '正在结合最新记录回答…' : '继续问宝宝的喂养、睡眠或便便情况'}
        onChange={event => setComposer(event.target.value)}
        onKeyDown={event => {
          if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            void sendMessage();
          }
        }}
      />
      <button type="submit" aria-label="发送" disabled={!canAsk || !composer.trim() || busy}><Icon type="send" size={19}/></button>
    </form>}

    {listOpen && <div ref={listDialogRef} className="ai-list-layer" role="dialog" aria-modal="true" aria-labelledby="ai-conversation-list-title" tabIndex={-1}>
      <header><div><strong id="ai-conversation-list-title">分析会话</strong><span>每个会话保留独立的数据快照</span></div><button className="ai-icon-button" aria-label="关闭会话列表" onClick={closeConversationList}><Icon type="close" size={20}/></button></header>
      <button className="ai-new-conversation" onClick={startNew}><Icon type="plus" size={18}/> 新建分析会话</button>
      <ConversationList items={items} selectedId={selectedId} loading={listLoading} onSelect={id => { if (id !== selectedId) selectConversation(id); closeConversationList(); }} onDelete={item => void deleteConversation(item)}/>
    </div>}

    {snapshotOpen && snapshot && <div className="ai-snapshot-overlay" onMouseDown={closeSnapshot}>
      <section ref={snapshotDialogRef} className="ai-snapshot-sheet" role="dialog" aria-modal="true" aria-labelledby="ai-snapshot-title" tabIndex={-1} onMouseDown={event => event.stopPropagation()}>
        <div className="ai-snapshot-title"><div><span>回答生成时使用的记录</span><strong id="ai-snapshot-title">本条回答依据</strong></div><button aria-label="关闭数据看板" onClick={closeSnapshot}><Icon type="close" size={19}/></button></div>
        <SnapshotDashboard snapshot={snapshot}/>
      </section>
    </div>}
  </main>;
}
