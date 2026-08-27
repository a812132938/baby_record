import { BottomSheet, SheetHeader } from '../../components/BottomSheet';
import { toInputDateTime } from '../../domain/format';

const SHORTCUTS: Array<{ label: string; minutesAgo: number }> = [
  { label: '15分钟前', minutesAgo: 15 },
  { label: '30分钟前', minutesAgo: 30 },
  { label: '1小时前', minutesAgo: 60 },
];

type RecordTimeSheetProps = {
  open: boolean;
  onClose: () => void;
  value: string;
  onChange: (value: string) => void;
  onApply: () => void;
};

export function RecordTimeSheet(p: RecordTimeSheetProps) {
  return <BottomSheet open={p.open} onClose={p.onClose}>
    <SheetHeader eyebrow="补录 / 校正" title="选择记录时间" onClose={p.onClose}/>
    <label className="field"><span>发生时间</span><input autoFocus type="datetime-local" value={p.value} max={toInputDateTime(Date.now())} onChange={e => p.onChange(e.target.value)}/></label>
    <div className="time-shortcuts">{SHORTCUTS.map(s => <button key={s.minutesAgo} onClick={() => p.onChange(toInputDateTime(Date.now() - s.minutesAgo * 60000))}>{s.label}</button>)}</div>
    <button className="primary" onClick={p.onApply}>使用这个时间</button>
  </BottomSheet>;
}
