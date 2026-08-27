import type { BabyEvent, BreastfeedingSession, TrendDay } from './model';
import type { BreastSide } from './feeding';
import { isFeedingEvent } from './events';
import { dateKey, dayBounds, duration } from './format';

export function averageFeedInterval(events: BabyEvent[], since: number) {
  const feeds = events.filter(e => isFeedingEvent(e) && e.type !== 'pumping' && e.at >= since).sort((a,b)=>a.at-b.at);
  if (feeds.length < 2) return '—';
  let total = 0;
  for (let i=1;i<feeds.length;i++) total += feeds[i].at - feeds[i-1].at;
  return duration(total / (feeds.length - 1));
}

/** Counts sleep overlapping [since, until), so a sleep across midnight lands on both days. */
export function sleepTotal(events: BabyEvent[], since: number, until: number) {
  const sorted = events.filter(e => e.type === 'sleep_start' || e.type === 'sleep_end').sort((a,b)=>a.at-b.at);
  let activeStart: number | null = null;
  let total = 0;
  for (const e of sorted) {
    if (e.type === 'sleep_start') {
      activeStart = e.at;
      continue;
    }
    if (e.type === 'sleep_end' && activeStart != null) {
      const overlapStart = Math.max(activeStart, since);
      const overlapEnd = Math.min(e.at, until);
      if (overlapEnd > overlapStart) total += overlapEnd - overlapStart;
      activeStart = null;
    }
  }
  if (activeStart != null) {
    const overlapStart = Math.max(activeStart, since);
    const overlapEnd = Math.min(Date.now(), until);
    if (overlapEnd > overlapStart) total += overlapEnd - overlapStart;
  }
  return total;
}

export function breastSideSeconds(session: BreastfeedingSession, side: BreastSide, now = Date.now()) {
  const committed = side === 'LEFT' ? session.leftSeconds : session.rightSeconds;
  return committed + (session.activeSide === side && session.activeSince ? Math.max(0, Math.floor((now - session.activeSince) / 1000)) : 0);
}

export function buildLocalTrend(events: BabyEvent[], now = Date.now()): TrendDay[] {
  const days: TrendDay[] = [];
  for (let offset = 6; offset >= 0; offset--) {
    const d = new Date(now);
    d.setHours(0,0,0,0);
    d.setDate(d.getDate() - offset);
    const start = d.getTime();
    const end = dayBounds(dateKey(start)).end;
    const dayEvents = events.filter(e => e.at >= start && e.at < end);
    const feeds = dayEvents.filter(e => isFeedingEvent(e) && e.type !== 'pumping');
    const directFeeds = dayEvents.filter(e => e.type === 'direct_breastfeed');
    const bottleBreastMilk = dayEvents.filter(e => e.type === 'bottle_breast_milk');
    const formulaFeeds = dayEvents.filter(e => e.type === 'formula_feed');
    const pumping = dayEvents.filter(e => e.type === 'pumping');
    const sleepMinutes = Math.round(sleepTotal(events, start, Math.min(now, end)) / 60000);
    days.push({
      date: dateKey(start),
      label: offset === 0 ? '今天' : `${d.getMonth()+1}/${d.getDate()}`,
      milkMl: bottleBreastMilk.concat(formulaFeeds).reduce((sum,e)=>sum+(e.amount||0),0),
      feedCount: feeds.length,
      directBreastfeedCount: directFeeds.length,
      directBreastfeedMinutes: Math.round(directFeeds.reduce((sum, e) => sum + Number(e.meta?.leftSeconds || 0) + Number(e.meta?.rightSeconds || 0), 0) / 60),
      bottleBreastMilkMl: bottleBreastMilk.reduce((sum,e)=>sum+(e.amount||0),0),
      bottleBreastMilkCount: bottleBreastMilk.length,
      formulaFeedMl: formulaFeeds.reduce((sum,e)=>sum+(e.amount||0),0),
      formulaFeedCount: formulaFeeds.length,
      pumpingMl: pumping.reduce((sum,e)=>sum+(e.amount||0),0),
      pumpingCount: pumping.length,
      pumpingMinutes: Math.round(pumping.reduce((sum, e) => sum + Number(e.meta?.durationSeconds || 0), 0) / 60),
      sleepMinutes,
      poopCount: dayEvents.filter(e=>e.type==='poop').length,
      peeCount: dayEvents.filter(e=>e.type==='pee').length,
    });
  }
  return days;
}
