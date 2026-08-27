export type AiConversationStatus = 'ANALYZING' | 'RESPONDING' | 'READY' | 'FAILED';
export type AiMessageRole = 'USER' | 'ASSISTANT' | 'user' | 'assistant';

export type AiConversationListItem = {
  id: number | string;
  title: string;
  status: AiConversationStatus;
  model?: string | null;
  lastErrorCode?: string | null;
  summary?: string | null;
  snapshotAt?: string | null;
  createdAt: string;
  updatedAt: string;
  latestSnapshot?: AiSnapshot | null;
};

export type AiMessage = {
  id: number | string;
  role: AiMessageRole;
  status: string;
  content: string | null;
  authorName?: string | null;
  snapshotId?: number | string | null;
  snapshotAt?: string | null;
  createdAt: string;
  errorCode?: string | null;
  searchUsed?: boolean | null;
  sources?: AiReferenceSource[] | null;
};

export type CountMap = Record<string, number>;

export type AiFeedingRecordBase = {
  recordedAt?: string | null;
  previousRecordedAt?: string | null;
  minutesSincePreviousFeed?: number | null;
};

export type AiIntakeType = 'DIRECT_BREASTFEED' | 'BOTTLE_BREAST_MILK' | 'FORMULA_FEED' | 'FEED' | string;

export type AiIntakeFeedingRecord = AiFeedingRecordBase & {
  type: AiIntakeType;
  amountMl?: number | null;
  durationMinutes?: number | null;
  leftMinutes?: number | null;
  rightMinutes?: number | null;
};

export type AiDirectBreastfeedRecord = AiFeedingRecordBase & {
  durationMinutes?: number | null;
  leftMinutes?: number | null;
  rightMinutes?: number | null;
};

export type AiBottleFeedingRecord = AiFeedingRecordBase & {
  amountMl?: number | null;
};

export type AiPumpingRecord = {
  type?: 'PUMPING' | string;
  recordedAt?: string | null;
  leftMl?: number | null;
  rightMl?: number | null;
  amountMl?: number | null;
  durationMinutes?: number | null;
  notBabyIntake?: boolean;
};

export type AiFeedingWindowStats = {
  eventCount?: number | null;
  sampleCount?: number | null;
  median?: number | null;
  p25?: number | null;
  p75?: number | null;
  shortest?: number | null;
  shortestFrom?: string | null;
  shortestTo?: string | null;
  longest?: number | null;
  longestFrom?: string | null;
  longestTo?: string | null;
  comparable?: boolean | null;
  notComparableReason?: string | null;
};

export type AiFeedingEventWindows = {
  windowSize?: number | null;
  comparable?: boolean | null;
  notComparableReason?: string | null;
  recent?: AiFeedingWindowStats | null;
  prior?: AiFeedingWindowStats | null;
};

export type AiReferenceSource = {
  title?: string | null;
  url?: string | null;
  organization?: string | null;
  publishedAt?: string | null;
};

export type AiDashboard = {
  schemaVersion?: string | null;
  timezone?: string | null;
  snapshotAt?: string | null;
  rangeStart?: string | null;
  rangeEnd?: string | null;
  coverageDays?: number | null;
  sourceEventCount?: number | null;
  baby?: {
    ageDays?: number | null;
    gender?: string | null;
    birthWeightGrams?: number | null;
  } | null;
  feeding?: {
    totalRecords?: number | null;
    directBreastfeedCount?: number | null;
    directBreastfeedMinutes?: number | null;
    bottleBreastMilkCount?: number | null;
    bottleBreastMilkMl?: number | null;
    formulaFeedCount?: number | null;
    formulaFeedMl?: number | null;
    unclassifiedBottleCount?: number | null;
    unclassifiedBottleMl?: number | null;
    pumpingCount?: number | null;
    pumpingMl?: number | null;
    pumpingMinutes?: number | null;
    intakeTimeline?: AiIntakeFeedingRecord[] | null;
    pumpingTimeline?: AiPumpingRecord[] | null;
    eventWindows?: AiFeedingEventWindows | null;
    longTermBaseline?: AiFeedingWindowStats | null;
    directBreastfeeds?: AiDirectBreastfeedRecord[] | null;
    bottleBreastMilkFeeds?: AiBottleFeedingRecord[] | null;
    formulaFeeds?: AiBottleFeedingRecord[] | null;
    unclassifiedBottleFeeds?: AiBottleFeedingRecord[] | null;
    pumpingRecords?: AiPumpingRecord[] | null;
    recordCoverage?: {
      total?: number | null;
      included?: number | null;
      truncated?: boolean | null;
      omittedBefore?: string | null;
    } | null;
    rhythm?: {
      intakeFeedCount?: number | null;
      intervalCount?: number | null;
      averageIntervalMinutes?: number | null;
      shortestIntervalMinutes?: number | null;
      longestIntervalMinutes?: number | null;
    } | null;
  } | null;
  sleep?: {
    completedSessions?: number | null;
    ongoingSessions?: number | null;
    totalMinutes?: number | null;
    averageMinutes?: number | null;
    longestMinutes?: number | null;
    currentSleepMinutes?: number | null;
  } | null;
  stool?: {
    count?: number | null;
    byColor?: CountMap | null;
    byTexture?: CountMap | null;
    byAmount?: CountMap | null;
  } | null;
  recentEvents?: unknown[];
  qualityNotes?: string[] | null;
  excludedEventTypes?: string[] | null;
};

export type AiSnapshot = {
  id: number | string;
  snapshotAt: string;
  rangeStart?: string | null;
  rangeEnd?: string | null;
  sourceEventCount?: number | null;
  promptVersion?: string | null;
  searchUsed?: boolean | null;
  sources?: AiReferenceSource[] | null;
  dashboard: AiDashboard;
};

export type AiConversationDetail = {
  id: number | string;
  title: string;
  status: AiConversationStatus;
  model?: string | null;
  lastErrorCode?: string | null;
  createdAt: string;
  updatedAt: string;
  latestSnapshot?: AiSnapshot | null;
  messages: AiMessage[];
};

export type AiConversationListResponse = { items: AiConversationListItem[] };
