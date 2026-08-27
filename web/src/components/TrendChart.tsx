import type { TrendDay } from '../domain/model';
import { compactDuration } from '../domain/format';

export type TrendMetric = 'direct' | 'breastBottle' | 'formula' | 'pumping' | 'sleep';

function metricValue(day: TrendDay, metric: TrendMetric) {
  switch (metric) {
    case 'direct': return day.directBreastfeedMinutes || 0;
    case 'breastBottle': return day.bottleBreastMilkMl || 0;
    case 'formula': return day.formulaFeedMl || 0;
    case 'pumping': return day.pumpingMl || 0;
    default: return day.sleepMinutes;
  }
}

export function TrendChart({ days, metric }: { days: TrendDay[]; metric: TrendMetric }) {
  const values = days.map(day => metricValue(day, metric));
  const max = Math.max(1, ...values);
  const asDuration = metric === 'sleep' || metric === 'direct';
  return <div className="trend-chart">
    <div className="trend-bars">{days.map((d, i) => {
      const value = values[i];
      const pct = Math.max(value ? 8 : 2, Math.round((value / max) * 100));
      return <div className="trend-column" key={`${metric}-${d.date}`}>
        <div className="trend-value">{asDuration ? (value ? compactDuration(value) : '—') : (value ? `${value}` : '—')}</div>
        <div className="trend-track"><i style={{height:`${pct}%`}} /></div>
        <small>{d.label}</small>
      </div>;
    })}</div>
  </div>;
}

export function TrendSection({ title, unit, days, metric, note }: {
  title: string;
  unit: string;
  days: TrendDay[];
  metric: TrendMetric;
  note?: React.ReactNode;
}) {
  return <div className="trend-section">
    <div className="trend-head"><div><strong>{title}</strong><span>{unit}</span></div>{note}</div>
    <TrendChart days={days} metric={metric}/>
  </div>;
}
