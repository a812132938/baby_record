import type { BreastSide } from './feeding';

export type FeedingType = 'DIRECT_BREASTFEED' | 'BOTTLE_BREAST_MILK' | 'FORMULA_FEED' | 'PUMPING';
export type EventType = 'feed' | 'direct_breastfeed' | 'bottle_breast_milk' | 'formula_feed' | 'pumping' | 'sleep_start' | 'sleep_end' | 'poop' | 'pee';

export type SyncMode = 'checking' | 'cloud' | 'local' | 'setup';
export type OnboardingMode = 'create' | 'join';
export type BabyGender = 'BOY' | 'GIRL';
export type SheetType = 'quick' | 'directBreastfeed' | 'bottleBreastMilk' | 'formulaFeed' | 'pumping' | 'poop' | 'recordTime' | 'family' | 'stats' | 'history' | 'babyProfile' | 'devices' | 'editEvent' | null;

export interface LocalBabyEvent {
  id: string;
  type: EventType;
  at: number;
  amount?: number;
  meta?: Record<string, unknown>;
  pending?: boolean;
  clientEventId?: string;
  serverId?: number;
  serverUpdatedAt?: string;
  operatorName?: string;
  syncError?: string;
}

export interface LocalPendingAction {
  id: string;
  kind: 'feed' | 'simple' | 'sleep_start' | 'sleep_end' | 'update' | 'delete';
  familyId?: number;
  babyId?: number;
  localEventId?: string;
  at?: number;
  clientEventId?: string;
  serverId?: number;
  amount?: number;
  simpleType?: 'POOP' | 'PEE';
  data?: Record<string, unknown>;
  feedingType?: FeedingType;
  revision?: number;
  attempted?: boolean;
  cancelled?: boolean;
  blocked?: boolean;
  failureMessage?: string;
  rollbackEvent?: LocalBabyEvent;
  updateStartAt?: number;
  updateEndAt?: number;
  expectedUpdatedAt?: string;
}

export interface LocalBabyProfile {
  nickname: string;
  birthday: string | null;
  gender: 'BOY' | 'GIRL' | null;
  birthWeightGrams: number | null;
}

export interface LocalSnapshot {
  events: LocalBabyEvent[];
  pending: LocalPendingAction[];
  profile: LocalBabyProfile | null;
}

/** UI-facing aliases. The local record is the same shape the screens render. */
export type BabyEvent = LocalBabyEvent;
export type PendingAction = LocalPendingAction;

export type TrendDay = {
  date: string;
  label: string;
  milkMl: number;
  feedCount: number;
  directBreastfeedCount?: number;
  directBreastfeedMinutes?: number;
  bottleBreastMilkCount?: number;
  bottleBreastMilkMl?: number;
  formulaFeedCount?: number;
  formulaFeedMl?: number;
  pumpingCount?: number;
  pumpingMl?: number;
  pumpingMinutes?: number;
  sleepMinutes: number;
  poopCount: number;
  peeCount: number;
};

export type StatsResponse = { days: TrendDay[] };

export type RemoteEvent = {
  id: number;
  babyId: number;
  operatorId: number;
  clientEventId?: string | null;
  eventType: 'FEED' | FeedingType | 'SLEEP' | 'POOP' | 'PEE';
  startTime: string;
  endTime?: string | null;
  amountMl?: number | null;
  eventData?: string | null;
  updatedAt?: string | null;
  operatorName?: string | null;
  endOperatorName?: string | null;
  feedingType?: FeedingType | null;
  type?: FeedingType | null;
};

export type RemoteDashboard = {
  baby: { id: number; nickname: string; birthday?: string | null; gender?: BabyGender | null; birthWeightGrams?: number | null };
  feedQuickAmounts: number[];
  timeline: RemoteEvent[];
};

export type BreastfeedingSession = {
  familyId?: number;
  babyId?: number;
  startedAt: number;
  activeSide: BreastSide | null;
  activeSince: number | null;
  lastSide: BreastSide | null;
  leftSeconds: number;
  rightSeconds: number;
  segments: Array<{ side: BreastSide; seconds: number }>;
};

export type Me = { deviceId: number; userId: number; familyId: number; babyId: number; nickname: string; role: string };
export type IdentitySnapshot = { epoch: number; familyId: number; babyId: number };

export type FamilyDevice = {
  id: number;
  userId: number;
  nickname: string;
  role: string;
  deviceName: string;
  lastActiveAt: string;
  createdAt: string;
  revoked: boolean;
};

export type FamilyCreatePayload = {
  familyName: string;
  babyNickname: string;
  birthDate: string;
  nickname: string;
  creationKey: string;
  gender: BabyGender;
  birthWeightGrams: number;
  deviceId: string;
  deviceName: string;
};

export type PendingFamilyCreation = { deploymentKey: string; request: FamilyCreatePayload };
