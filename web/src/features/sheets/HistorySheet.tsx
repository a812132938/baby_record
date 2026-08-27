import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { Icon } from '../../components/Icon';
import { TimelineRows } from '../../components/TimelineRows';
import type { BabyEvent } from '../../domain/model';
import { compactDuration, dateKey, dayBounds } from '../../domain/format';
import { eventsOnDate } from '../../domain/events';
import { sleepTotal } from '../../domain/stats';

type HistorySheetProps = {
  open: boolean;
  onClose: () => void;
  date: string;
  events: BabyEvent[];
  loading: boolean;
  onPickDate: (date: string) => void;
  onShiftDay: (delta: number) => void;
  onSelectEvent: (event: BabyEvent) => void;
  onBackfill: (dayStart: number) => void;
};

function DaySummary({ date, events }: { date: string; events: BabyEvent[] }) {
  const { start, end } = dayBounds(date);
  const dayItems = eventsOnDate(events, date);
  const of = (type: BabyEvent['type']) => dayItems.filter(e => e.type === type);
  const sumMl = (items: BabyEvent[]) => items.reduce((n, e) => n + (e.amount || 0), 0);
  const directs = of('direct_breastfeed');
  const directMinutes = Math.round(directs.reduce((n, e) => n + Number(e.meta?.leftSeconds || 0) + Number(e.meta?.rightSeconds || 0), 0) / 60);
  return <div className="history-summary feeding-history-summary">
    <div><span>母乳亲喂</span><strong>{directs.length}<small> 次 · {directMinutes}分</small></strong></div>
    <div><span>母乳瓶喂</span><strong>{sumMl(of('bottle_breast_milk'))}<small> ml</small></strong></div>
    <div><span>配方奶</span><strong>{sumMl(of('formula_feed'))}<small> ml</small></strong></div>
    <div><span>泵奶产量</span><strong>{sumMl(of('pumping'))}<small> ml</small></strong></div>
    <div><span>睡眠</span><strong>{compactDuration(Math.round(sleepTotal(events, start, Math.min(end, Date.now())) / 60000))}</strong></div>
    <div><span>便便 / 尿尿</span><strong>{of('poop').length}<small> / {of('pee').length}</small></strong></div>
  </div>;
}

export function HistorySheet(p: HistorySheetProps) {
  const todayKey = dateKey(Date.now());
  const title = p.date === todayKey
    ? '今天'
    : new Date(`${p.date}T00:00:00`).toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'short' });

  return <BottomSheet open={p.open} onClose={p.onClose} tall>
    <SheetHeader eyebrow="历史记录" title={title} onClose={p.onClose}/>
    <div className="history-date-nav">
      <button onClick={() => p.onShiftDay(-1)}>‹</button>
      <input type="date" max={todayKey} value={p.date} onChange={e => p.onPickDate(e.target.value)}/>
      <button disabled={p.date >= todayKey} onClick={() => p.onShiftDay(1)}>›</button>
    </div>
    <DaySummary date={p.date} events={p.events}/>
    <div className="history-head"><span>当天时间轴</span>{p.loading && <small>从云端同步…</small>}</div>
    <div className="timeline history-timeline"><TimelineRows items={eventsOnDate(p.events, p.date)} onSelect={p.onSelectEvent}/></div>
    <button className="history-add" onClick={() => p.onBackfill(dayBounds(p.date).start)}><Icon type="plus" size={16}/> 补录这一天</button>
  </BottomSheet>;
}
