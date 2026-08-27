import React from 'react';
import { Icon } from '../components/Icon';
import { TimelineRows } from '../components/TimelineRows';
import type { BabyEvent, LocalBabyProfile, SyncMode } from '../domain/model';
import { birthWeightLabel, daysOld, duration, formatTime, genderLabel, relative } from '../domain/format';

export type TodayTotals = {
  directCount: number;
  directMinutes: number;
  breastBottleMl: number;
  formulaMl: number;
  pumpingMl: number;
};

type HomeScreenProps = {
  baby: LocalBabyProfile;
  syncBadge: React.ReactNode;
  tick: number;
  lastFeed?: BabyEvent;
  lastPoop?: BabyEvent;
  activeSleep: BabyEvent | null;
  todayEvents: BabyEvent[];
  today: TodayTotals;
  aiEnabled: boolean;
  onOpenFamily: () => void;
  onToggleSleep: () => void;
  onOpenQuick: () => void;
  onOpenPoop: () => void;
  onOpenAi: () => void;
  onOpenHistory: () => void;
  onOpenStats: () => void;
  onSelectEvent: (event: BabyEvent) => void;
};

export function SyncBadge({ syncMode, realtime, pendingCount }: { syncMode: SyncMode; realtime: boolean; pendingCount: number }) {
  const text = syncMode === 'cloud'
    ? pendingCount ? `待同步 ${pendingCount}` : realtime ? '实时同步' : '已连接'
    : syncMode === 'setup' ? '未加入家庭' : syncMode === 'checking' ? '连接中' : '本机记录';
  return <span className={`sync-badge ${syncMode}`}><i />{text}</span>;
}

function lastFeedLabel(lastFeed?: BabyEvent) {
  if (!lastFeed) return '暂无记录';
  switch (lastFeed.type) {
    case 'direct_breastfeed': return '母乳亲喂';
    case 'bottle_breast_milk': return `母乳瓶喂 ${lastFeed.amount}ml`;
    case 'formula_feed': return `配方奶 ${lastFeed.amount}ml`;
    default: return lastFeed.amount ? `瓶喂 ${lastFeed.amount}ml` : '暂无记录';
  }
}

function TopBar({ baby, syncBadge, onOpenFamily }: Pick<HomeScreenProps, 'baby' | 'syncBadge' | 'onOpenFamily'>) {
  const babyDays = daysOld(baby.birthday);
  return <header className="topbar">
    <div>
      <div className="eyebrow">BABY RECORD</div>
      <h1>{baby.nickname} {babyDays !== null && <span className="day-chip">出生第 {babyDays} 天</span>}</h1>
      <div className="baby-meta">{genderLabel(baby.gender)} · {birthWeightLabel(baby.birthWeightGrams)}</div>
      {syncBadge}
    </div>
    <button className="avatar" aria-label="家庭" onClick={onOpenFamily}><Icon type="family" size={19}/></button>
  </header>;
}

function SleepHero({ activeSleep, tick, onToggleSleep }: Pick<HomeScreenProps, 'activeSleep' | 'tick' | 'onToggleSleep'>) {
  return <section className={`sleep-hero ${activeSleep ? 'sleeping' : ''}`} onClick={onToggleSleep}>
    <div className="hero-icon"><Icon type="moon" size={24}/></div>
    <div className="hero-copy">
      <span className="hero-kicker">{activeSleep ? '正在睡觉' : '当前醒着'}</span>
      <strong>{activeSleep ? duration(tick - activeSleep.at) : '点一下开始睡眠'}</strong>
      <small>{activeSleep ? `${formatTime(activeSleep.at)} 入睡 · 点一下记醒来` : '记录睡眠不需要打开表单'}</small>
    </div>
    <span className="hero-arrow"><Icon type="chevron" size={20}/></span>
  </section>;
}

function StatusGrid({ lastFeed, lastPoop, onOpenQuick, onOpenPoop }: Pick<HomeScreenProps, 'lastFeed' | 'lastPoop' | 'onOpenQuick' | 'onOpenPoop'>) {
  return <section className="status-grid">
    <article className="status-card feed-card" onClick={onOpenQuick}>
      <div className="status-head"><span className="soft-icon"><Icon type="milk" size={20}/></span><span>上次喝奶</span></div>
      <strong>{relative(lastFeed?.at)}</strong>
      <div className="status-foot"><span>{lastFeedLabel(lastFeed)}</span><span>{lastFeed ? formatTime(lastFeed.at) : '--:--'}</span></div>
    </article>
    <article className="status-card" onClick={onOpenPoop}>
      <div className="status-head"><span className="soft-icon"><Icon type="poop" size={20}/></span><span>上次便便</span></div>
      <strong>{relative(lastPoop?.at)}</strong>
      <div className="status-foot"><span>{String(lastPoop?.meta?.texture || '暂无记录')}</span><span>{lastPoop ? formatTime(lastPoop.at) : '--:--'}</span></div>
    </article>
  </section>;
}

function FeedingSummary({ today }: { today: TodayTotals }) {
  return <section className="today-strip feeding-summary">
    <div><span>母乳亲喂</span><strong>{today.directCount}<small> 次 · {today.directMinutes}分</small></strong></div>
    <div><span>母乳瓶喂</span><strong>{today.breastBottleMl}<small> ml</small></strong></div>
    <div><span>配方奶</span><strong>{today.formulaMl}<small> ml</small></strong></div>
    <div><span>泵奶产量</span><strong>{today.pumpingMl}<small> ml</small></strong></div>
  </section>;
}

export function HomeScreen(p: HomeScreenProps) {
  const today = new Date();
  return <>
    <TopBar baby={p.baby} syncBadge={p.syncBadge} onOpenFamily={p.onOpenFamily}/>
    <SleepHero activeSleep={p.activeSleep} tick={p.tick} onToggleSleep={p.onToggleSleep}/>
    <StatusGrid lastFeed={p.lastFeed} lastPoop={p.lastPoop} onOpenQuick={p.onOpenQuick} onOpenPoop={p.onOpenPoop}/>
    <FeedingSummary today={p.today}/>

    {p.aiEnabled && <button className="ai-home-entry" onClick={p.onOpenAi}>
      <span className="ai-home-mark"><Icon type="chart" size={20}/></span>
      <span className="ai-home-copy"><strong>宝宝成长分析</strong><small>基于睡眠、喂养和便便记录</small></span>
      <Icon type="chevron" size={18}/>
    </button>}

    <section className="timeline-section">
      <div className="section-title">
        <div><span>今天</span><small>{today.toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' })}</small></div>
        <div className="section-actions">
          <button onClick={p.onOpenHistory}><Icon type="clock" size={16}/> 历史</button>
          <button onClick={p.onOpenStats}><Icon type="chart" size={16}/> 统计</button>
        </div>
      </div>
      <div className="timeline"><TimelineRows items={p.todayEvents.slice(0, 10)} onSelect={p.onSelectEvent}/></div>
    </section>

    <button className="fab" onClick={p.onOpenQuick}><Icon type="plus" size={25}/><span>记录</span></button>
  </>;
}
