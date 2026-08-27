import type { BabyEvent } from '../domain/model';
import { clockDuration, compactDuration, formatTime } from '../domain/format';
import { Icon } from './Icon';

function rowInfo(e: BabyEvent) {
  const left = Number(e.meta?.leftSeconds || 0);
  const right = Number(e.meta?.rightSeconds || 0);
  switch (e.type) {
    case 'feed':
      return { icon: 'milk', title: `瓶喂 · 类型未设置${e.amount ? ` · ${e.amount}ml` : ''}`, sub: '历史喝奶记录' };
    case 'direct_breastfeed':
      return { icon: 'milk', title: `母乳亲喂 · ${clockDuration(left + right)}`, sub: `左 ${clockDuration(left)} · 右 ${clockDuration(right)}` };
    case 'bottle_breast_milk':
      return { icon: 'milk', title: `母乳瓶喂 · ${e.amount || 0}ml`, sub: '实际喝下量' };
    case 'formula_feed':
      return { icon: 'milk', title: `配方奶 · ${e.amount || 0}ml`, sub: '实际喝下量' };
    case 'pumping':
      return { icon: 'milk', title: `泵奶 · ${e.amount || 0}ml`, sub: `左 ${Number(e.meta?.leftMl || 0)}ml · 右 ${Number(e.meta?.rightMl || 0)}ml${e.meta?.durationSeconds ? ` · ${compactDuration(Math.round(Number(e.meta.durationSeconds) / 60))}` : ''}` };
    case 'poop':
      return { icon: 'poop', title: '便便', sub: [e.meta?.color, e.meta?.texture, e.meta?.amount].filter(Boolean).join(' · ') || '已记录' };
    case 'pee':
      return { icon: 'drop', title: '尿尿', sub: '已记录' };
    case 'sleep_start':
      return { icon: 'moon', title: '开始睡觉', sub: '睡眠' };
    default:
      return { icon: 'moon', title: '宝宝醒了', sub: '睡眠结束' };
  }
}

export function TimelineRows({ items, onSelect }: { items: BabyEvent[]; onSelect: (event: BabyEvent) => void }) {
  if (!items.length) {
    return <div className="empty-state"><strong>这一天没有记录</strong><span>可以使用补录添加历史记录</span></div>;
  }
  return <>{items.map((e, idx) => {
    const info = rowInfo(e);
    return <button className={`timeline-row ${e.pending ? 'pending' : ''}`} key={e.id} onClick={() => onSelect(e)}>
      <time>{formatTime(e.at)}</time>
      <div className="rail"><span className="dot"></span>{idx < items.length - 1 && <i/>}</div>
      <div className="event-body">
        <div className="event-icon"><Icon type={info.icon} size={18}/></div>
        <div><strong>{info.title}</strong><span>{info.sub} · {e.operatorName ? `${e.operatorName}记录` : '记录人未提供'}{e.pending ? ' · 等待同步' : ''}</span></div>
        <Icon type="chevron" size={16}/>
      </div>
    </button>;
  })}</>;
}
