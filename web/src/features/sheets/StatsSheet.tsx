import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { TrendSection } from '../../components/TrendChart';
import type { BabyEvent, TrendDay } from '../../domain/model';
import { compactDuration, duration } from '../../domain/format';
import { averageFeedInterval, sleepTotal } from '../../domain/stats';

type StatsSheetProps = {
  open: boolean;
  onClose: () => void;
  events: BabyEvent[];
  todayStart: number;
  now: number;
  trendDays: TrendDay[];
  loadingStats: boolean;
  today: { directCount: number; directMinutes: number; breastBottleMl: number; formulaMl: number; pumpingMl: number };
  onOpenDay: (date: string) => void;
};

export function StatsSheet(p: StatsSheetProps) {
  const countToday = (type: BabyEvent['type']) => p.events.filter(e => e.type === type && e.at >= p.todayStart).length;
  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="数据趋势" title="最近 7 天" onClose={p.onClose}/>
    <div className="stats-grid">
      <div><span>母乳亲喂</span><strong>{p.today.directCount}<small> 次 · {p.today.directMinutes}分</small></strong></div>
      <div><span>母乳瓶喂</span><strong>{p.today.breastBottleMl}<small> ml</small></strong></div>
      <div><span>配方奶</span><strong>{p.today.formulaMl}<small> ml</small></strong></div>
      <div><span>泵奶产量</span><strong>{p.today.pumpingMl}<small> ml</small></strong></div>
      <div><span>平均间隔</span><strong className="small-value">{averageFeedInterval(p.events, p.todayStart)}</strong></div>
      <div><span>今日睡眠</span><strong className="small-value">{duration(sleepTotal(p.events, p.todayStart, p.now))}</strong></div>
      <div><span>便便</span><strong>{countToday('poop')}<small> 次</small></strong></div>
      <div><span>尿尿</span><strong>{countToday('pee')}<small> 次</small></strong></div>
    </div>

    <TrendSection title="母乳亲喂趋势" unit="分钟 / 天" days={p.trendDays} metric="direct" note={p.loadingStats ? <small>同步中…</small> : null}/>
    <TrendSection title="母乳瓶喂趋势" unit="ml / 天" days={p.trendDays} metric="breastBottle"/>
    <TrendSection title="配方奶趋势" unit="ml / 天" days={p.trendDays} metric="formula"/>
    <TrendSection title="泵奶产量趋势" unit="ml / 天" days={p.trendDays} metric="pumping"/>
    <TrendSection title="睡眠趋势" unit="每天累计" days={p.trendDays} metric="sleep"/>

    <div className="seven-day-list feeding-day-list">{p.trendDays.slice().reverse().map(day =>
      <button key={day.date} onClick={() => p.onOpenDay(day.date)}>
        <strong>{day.label}</strong>
        <span>亲喂 {day.directBreastfeedCount || 0}次 / {day.directBreastfeedMinutes || 0}分</span>
        <span>母乳瓶喂 {day.bottleBreastMilkMl || 0}ml · 配方 {day.formulaFeedMl || 0}ml · 泵奶 {day.pumpingMl || 0}ml</span>
        <small>{compactDuration(day.sleepMinutes)} 睡眠 · 便 {day.poopCount} · 尿 {day.peeCount}</small>
        <Icon type="chevron" size={13}/>
      </button>)}
    </div>
    <p className="sheet-note">联网时使用 MySQL 的 7 天完整聚合；离线时直接使用本机历史记录，新增记录会立即进入趋势。</p>
  </BottomSheet>;
}
